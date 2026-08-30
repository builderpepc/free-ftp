package com.freeftp.core.transfer

import com.freeftp.core.CancellationSignal
import com.freeftp.core.ProgressListener
import com.freeftp.core.RemoteClient
import com.freeftp.core.RemotePath
import com.freeftp.core.TransferCancelledException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferState {
    QUEUED,
    RUNNING,
    /** Stopped by the user, with the bytes so far kept so it can continue later. */
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = this == QUEUED || this == RUNNING
}

/** One queued file transfer. */
data class TransferRequest(
    val id: String,
    val direction: TransferDirection,
    val remotePath: String,
    val target: LocalTarget,
    /** Continue from the bytes already present rather than starting over. */
    val resume: Boolean = false,
) {
    val displayName: String get() = RemotePath.name(remotePath)
}

data class TransferStatus(
    val request: TransferRequest,
    val state: TransferState,
    val transferred: Long = 0L,
    val total: Long = -1L,
    val error: String? = null,
)

/**
 * A sequential queue of transfers over a single connection.
 *
 * One connection means one transfer at a time: FTP's control channel cannot carry two
 * transfers at once, and hammering a server with parallel SFTP channels is a good way
 * to get throttled. Items therefore run strictly in order, and a failure in one never
 * stops the rest of the queue.
 */
class TransferManager(
    private val scope: CoroutineScope,
    private val clientProvider: suspend () -> RemoteClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _transfers = MutableStateFlow<List<TransferStatus>>(emptyList())

    /** Every transfer this session has seen, in the order it was enqueued. */
    val transfers: StateFlow<List<TransferStatus>> = _transfers.asStateFlow()

    private val _isPaused = MutableStateFlow(false)

    /** True while the queue is held; observable so the UI can offer Resume. */
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val lock = Mutex()
    private val cancellations = mutableMapOf<String, CancellationSignal>()
    private val pausing = mutableSetOf<String>()
    private var worker: Job? = null

    fun enqueue(request: TransferRequest) = enqueueAll(listOf(request))

    fun enqueueAll(requests: List<TransferRequest>) {
        if (requests.isEmpty()) return
        // Something added to a held queue joins it held, rather than sitting "queued"
        // forever behind a pause that will never start it.
        val state = if (_isPaused.value) TransferState.PAUSED else TransferState.QUEUED
        _transfers.update { current -> current + requests.map { TransferStatus(it, state) } }
        ensureWorker()
    }

    /** Cancels a running transfer, or removes a queued one before it ever starts. */
    fun cancel(id: String) {
        pausing.remove(id)
        cancellations[id]?.cancel()
        _transfers.update { current ->
            current.map {
                // A queued or paused item has no running transfer to interrupt, so it is
                // marked here; a running one is marked when its transfer unwinds.
                if (it.request.id == id && it.state != TransferState.RUNNING && !it.state.isTerminal) {
                    it.copy(state = TransferState.CANCELLED)
                } else {
                    it
                }
            }
        }
    }

    /**
     * Holds the queue: the running transfer stops where it is and everything else waits.
     *
     * The in-flight transfer is stopped through the ordinary cancellation path, but
     * recorded as [TransferState.PAUSED] rather than cancelled, and the bytes already
     * written are kept so [resume] can pick up from that offset instead of starting over.
     */
    fun pause() {
        if (_isPaused.value) return
        _isPaused.value = true
        val running = _transfers.value.filter { it.state == TransferState.RUNNING }
        running.forEach { status ->
            pausing.add(status.request.id)
            cancellations[status.request.id]?.cancel()
        }
        _transfers.update { current ->
            current.map { if (it.state == TransferState.QUEUED) it.copy(state = TransferState.PAUSED) else it }
        }
    }

    /** Releases the queue, continuing paused transfers from where they stopped. */
    fun resume() {
        if (!_isPaused.value) return
        _isPaused.value = false
        _transfers.update { current ->
            current.map {
                if (it.state == TransferState.PAUSED) {
                    // resume = true makes the worker restart from the bytes already there.
                    it.copy(
                        state = TransferState.QUEUED,
                        request = it.request.copy(resume = true),
                    )
                } else {
                    it
                }
            }
        }
        ensureWorker()
    }

    /** Puts a finished-but-unsuccessful transfer back on the queue. */
    fun retry(id: String) {
        cancellations.remove(id)
        pausing.remove(id)
        _transfers.update { current ->
            current.map {
                if (it.request.id == id && it.state.isTerminal && it.state != TransferState.COMPLETED) {
                    it.copy(state = TransferState.QUEUED, transferred = 0L, error = null)
                } else {
                    it
                }
            }
        }
        ensureWorker()
    }

    fun clearCompleted() {
        _transfers.update { current -> current.filterNot { it.state == TransferState.COMPLETED } }
    }

    /** Total bytes transferred and total bytes expected across every non-cancelled item. */
    fun aggregateProgress(): Pair<Long, Long> {
        val active = _transfers.value.filter { it.state != TransferState.CANCELLED }
        return active.sumOf { it.transferred } to active.sumOf { maxOf(it.total, 0L) }
    }

    /** Suspends until nothing is queued or running. A paused queue is idle. */
    suspend fun awaitIdle() {
        transfers.first { list -> list.none { it.state.isActive } }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            if (_isPaused.value) return
            val next = lock.withLock {
                _transfers.value.firstOrNull { it.state == TransferState.QUEUED }
            } ?: return
            run(next.request)
        }
    }

    private suspend fun run(request: TransferRequest) {
        val cancellation = CancellationSignal()
        cancellations[request.id] = cancellation
        update(request.id) { it.copy(state = TransferState.RUNNING, error = null) }

        val listener = ProgressListener { transferred, total ->
            update(request.id) { it.copy(transferred = transferred, total = total) }
        }

        try {
            withContext(ioDispatcher) {
                val client = clientProvider()
                if (!client.isConnected) client.connect()
                when (request.direction) {
                    TransferDirection.DOWNLOAD -> download(client, request, listener, cancellation)
                    TransferDirection.UPLOAD -> upload(client, request, listener, cancellation)
                }
            }
            update(request.id) { it.copy(state = TransferState.COMPLETED) }
        } catch (_: TransferCancelledException) {
            // A pause and a cancel both arrive here as a cancelled transfer; only the
            // caller's intent distinguishes them, and only a pause keeps its progress.
            val paused = pausing.remove(request.id)
            update(request.id) {
                if (paused) it.copy(state = TransferState.PAUSED) else it.copy(state = TransferState.CANCELLED)
            }
        } catch (e: Exception) {
            update(request.id) {
                it.copy(state = TransferState.FAILED, error = e.message ?: e::class.simpleName)
            }
        } finally {
            cancellations.remove(request.id)
        }
    }

    private fun download(
        client: RemoteClient,
        request: TransferRequest,
        listener: ProgressListener,
        cancellation: CancellationSignal,
    ) {
        // Resuming keeps what is already there and continues at that offset.
        val offset = if (request.resume && request.target.exists()) request.target.size() else 0L
        request.target.openWrite(offset).use { sink ->
            client.download(request.remotePath, sink, offset, listener, cancellation)
        }
    }

    private fun upload(
        client: RemoteClient,
        request: TransferRequest,
        listener: ProgressListener,
        cancellation: CancellationSignal,
    ) {
        val size = request.target.size()
        val offset = if (request.resume && client.exists(request.remotePath)) {
            client.stat(request.remotePath).size.coerceAtMost(size)
        } else {
            0L
        }
        request.target.openRead().use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            client.upload(input, request.remotePath, size, offset, listener, cancellation)
        }
    }

    private fun update(id: String, transform: (TransferStatus) -> TransferStatus) {
        _transfers.update { current ->
            current.map { if (it.request.id == id) transform(it) else it }
        }
    }
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val current = value
        if (compareAndSet(current, transform(current))) return
    }
}
