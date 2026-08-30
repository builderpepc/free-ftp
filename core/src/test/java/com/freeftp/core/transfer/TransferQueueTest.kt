package com.freeftp.core.transfer

import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemoteClient
import com.freeftp.core.ServerProfile
import com.freeftp.core.sftp.SftpRemoteClient
import com.freeftp.core.testing.EmbeddedSftpServer
import com.freeftp.core.testing.randomBytes
import org.junit.jupiter.api.Assertions.assertFalse
import com.freeftp.core.testing.sha256
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Test plan section 8 — the transfer queue, exercised over a real SFTP server so the
 * queue's behaviour is measured against real I/O rather than a stub.
 */
class TransferQueueTest {

    @TempDir
    lateinit var workspace: Path

    private lateinit var serverRoot: Path
    private lateinit var localDir: File
    private lateinit var server: EmbeddedSftpServer
    private lateinit var client: RemoteClient
    private lateinit var scope: CoroutineScope
    private lateinit var manager: TransferManager

    @BeforeEach
    fun setUp() {
        serverRoot = workspace.resolve("served").also { it.createDirectories() }
        localDir = workspace.resolve("local").toFile().also { it.mkdirs() }
        server = EmbeddedSftpServer(serverRoot, hostKeyFile = workspace.resolve("hostkey.ser")).start()
        client = SftpRemoteClient(
            ServerProfile(
                id = "sftp",
                name = "Queue test",
                protocol = Protocol.SFTP,
                host = "127.0.0.1",
                port = server.port,
                credentials = Credentials.Password(EmbeddedSftpServer.USER, EmbeddedSftpServer.PASSWORD),
            )
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = TransferManager(scope, clientProvider = { client })
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        runCatching { client.disconnect() }
        server.close()
    }

    private fun seedRemote(name: String, bytes: ByteArray) {
        serverRoot.resolve(name).writeBytes(bytes)
    }

    private fun download(id: String, name: String, resume: Boolean = false) = TransferRequest(
        id = id,
        direction = TransferDirection.DOWNLOAD,
        remotePath = "/$name",
        target = FileTarget(File(localDir, name)),
        resume = resume,
    )

    private fun upload(id: String, name: String, bytes: ByteArray) = TransferRequest(
        id = id,
        direction = TransferDirection.UPLOAD,
        remotePath = "/$name",
        target = FileTarget(File(localDir, name).apply { writeBytes(bytes) }),
    )

    private fun drain(timeoutMillis: Long = 120_000) = runBlocking {
        withTimeout(timeoutMillis) { manager.awaitIdle() }
    }

    @Test // 8.1
    @Timeout(180)
    fun `three transfers run to completion in order`() {
        val payloads = (1..3).map { randomBytes(128 * 1024, seed = it) }
        payloads.forEachIndexed { index, bytes -> seedRemote("file-$index.bin", bytes) }
        manager.enqueueAll((0..2).map { download("t$it", "file-$it.bin") })
        drain()

        val statuses = manager.transfers.value
        assertEquals(listOf("t0", "t1", "t2"), statuses.map { it.request.id })
        assertTrue(statuses.all { it.state == TransferState.COMPLETED }, statuses.toString())
        payloads.forEachIndexed { index, bytes ->
            assertEquals(sha256(bytes), sha256(File(localDir, "file-$index.bin").readBytes()))
        }
    }

    @Test // 8.2
    @Timeout(120)
    fun `a transfer passes through queued then running then completed exactly once`() {
        seedRemote("states.bin", randomBytes(64 * 1024))
        val observed = mutableListOf<TransferState>()
        val subscribed = CompletableDeferred<Unit>()
        val watcher = manager.transfers
            .onEach { list ->
                subscribed.complete(Unit)
                list.firstOrNull { it.request.id == "s1" }?.let { status ->
                    if (observed.lastOrNull() != status.state) observed.add(status.state)
                }
            }
            .launchIn(scope)
        // The collector starts asynchronously; enqueueing before it is listening would
        // let the transfer reach RUNNING unobserved.
        runBlocking { withTimeout(10_000) { subscribed.await() } }

        manager.enqueue(download("s1", "states.bin"))
        drain()
        watcher.cancel()

        assertEquals(
            listOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.COMPLETED),
            observed,
        )
    }

    @Test // 8.3
    @Timeout(180)
    fun `a failing transfer is recorded and the queue keeps going`() {
        seedRemote("good.bin", randomBytes(32 * 1024))
        manager.enqueueAll(
            listOf(
                download("bad", "missing.bin"), // nothing on the server by this name
                download("good", "good.bin"),
            )
        )
        drain()

        val statuses = manager.transfers.value.associateBy { it.request.id }
        assertEquals(TransferState.FAILED, statuses.getValue("bad").state)
        assertNotNull(statuses.getValue("bad").error)
        assertTrue(statuses.getValue("bad").error!!.isNotBlank())
        assertEquals(TransferState.COMPLETED, statuses.getValue("good").state)
    }

    @Test // 8.4
    @Timeout(180)
    fun `cancelling a queued item stops it from ever starting`() {
        seedRemote("first.bin", randomBytes(4 * 1024 * 1024, seed = 3))
        seedRemote("second.bin", randomBytes(32 * 1024))
        manager.enqueueAll(listOf(download("first", "first.bin"), download("second", "second.bin")))
        manager.cancel("second")
        drain()

        val statuses = manager.transfers.value.associateBy { it.request.id }
        assertEquals(TransferState.COMPLETED, statuses.getValue("first").state)
        assertEquals(TransferState.CANCELLED, statuses.getValue("second").state)
        assertEquals(0L, statuses.getValue("second").transferred)
        assertTrue(!File(localDir, "second.bin").exists(), "a cancelled item must not be written")
    }

    @Test // 8.5
    @Timeout(180)
    fun `cancelling the running item lets the next one start`() {
        seedRemote("huge.bin", randomBytes(16 * 1024 * 1024, seed = 5))
        seedRemote("next.bin", randomBytes(32 * 1024, seed = 6))
        manager.enqueueAll(listOf(download("huge", "huge.bin"), download("next", "next.bin")))

        val watcher = manager.transfers
            .onEach { list ->
                val running = list.firstOrNull { it.request.id == "huge" }
                if (running?.state == TransferState.RUNNING && running.transferred > 512 * 1024) {
                    manager.cancel("huge")
                }
            }
            .launchIn(scope)
        drain()
        watcher.cancel()

        val statuses = manager.transfers.value.associateBy { it.request.id }
        assertEquals(TransferState.CANCELLED, statuses.getValue("huge").state)
        assertEquals(TransferState.COMPLETED, statuses.getValue("next").state)
    }

    @Test // 8.6
    @Timeout(180)
    fun `aggregate progress adds up across the queue`() {
        val sizes = listOf(64 * 1024, 128 * 1024, 32 * 1024)
        sizes.forEachIndexed { index, size -> seedRemote("agg-$index.bin", randomBytes(size, seed = index)) }
        manager.enqueueAll(sizes.indices.map { download("a$it", "agg-$it.bin") })
        drain()

        val (transferred, total) = manager.aggregateProgress()
        assertEquals(sizes.sum().toLong(), transferred)
        assertEquals(sizes.sum().toLong(), total)
    }

    @Test // 8.7
    @Timeout(180)
    fun `a failed transfer can be retried once the cause is gone`() {
        manager.enqueue(download("late", "late.bin"))
        drain()
        assertEquals(TransferState.FAILED, manager.transfers.value.single().state)

        val bytes = randomBytes(64 * 1024, seed = 9)
        seedRemote("late.bin", bytes)
        manager.retry("late")
        drain()

        assertEquals(TransferState.COMPLETED, manager.transfers.value.single().state)
        assertEquals(sha256(bytes), sha256(File(localDir, "late.bin").readBytes()))
    }

    @Test // 8.8
    @Timeout(180)
    fun `clearCompleted removes only the finished items`() {
        seedRemote("done.bin", randomBytes(16 * 1024))
        manager.enqueueAll(listOf(download("done", "done.bin"), download("failed", "absent.bin")))
        drain()

        manager.clearCompleted()
        assertEquals(listOf("failed"), manager.transfers.value.map { it.request.id })
    }

    // ------------------------------------------------------------------ 14. pause/resume

    @Test // 14.1, 14.2, 14.9
    @Timeout(240)
    fun `pausing keeps the partial file and resuming finishes it correctly`() {
        val bytes = randomBytes(12 * 1024 * 1024, seed = 31)
        seedRemote("paused.bin", bytes)
        manager.enqueue(download("p", "paused.bin"))

        // Pause once enough has arrived that a resume genuinely has to continue.
        val watcher = manager.transfers
            .onEach { list ->
                val running = list.firstOrNull { it.request.id == "p" }
                if (running?.state == TransferState.RUNNING && running.transferred > 1_000_000) {
                    manager.pause()
                }
            }
            .launchIn(scope)
        drain()
        watcher.cancel()

        val paused = manager.transfers.value.single()
        assertEquals(TransferState.PAUSED, paused.state)
        assertTrue(manager.isPaused.value, "isPaused must be observable for the UI")
        val partial = File(localDir, "paused.bin").length()
        assertTrue(partial in 1 until bytes.size.toLong(), "expected a partial file, got $partial")

        manager.resume()
        drain()

        assertEquals(TransferState.COMPLETED, manager.transfers.value.single().state)
        assertFalse(manager.isPaused.value)
        assertEquals(
            sha256(bytes),
            sha256(File(localDir, "paused.bin").readBytes()),
            "a resumed download must reassemble the whole original file",
        )
    }

    @Test // 14.3, 14.4
    @Timeout(240)
    fun `a paused queue holds everything and resuming releases it in order`() {
        (0..2).forEach { seedRemote("q-$it.bin", randomBytes(64 * 1024, seed = it)) }
        manager.pause()
        manager.enqueueAll((0..2).map { download("q$it", "q-$it.bin") })
        drain()

        assertTrue(
            manager.transfers.value.all { it.state == TransferState.PAUSED },
            "nothing may start while the queue is held: ${manager.transfers.value.map { it.state }}",
        )
        assertTrue(localDir.listFiles().orEmpty().isEmpty(), "a held queue must not write files")

        manager.resume()
        drain()

        val statuses = manager.transfers.value
        assertEquals(listOf("q0", "q1", "q2"), statuses.map { it.request.id })
        assertTrue(statuses.all { it.state == TransferState.COMPLETED }, statuses.toString())
    }

    @Test // 14.5, 14.6
    @Timeout(120)
    fun `pausing with nothing running and resuming when not paused are both harmless`() {
        manager.pause()
        assertTrue(manager.isPaused.value)
        manager.resume()
        assertFalse(manager.isPaused.value)
        manager.resume() // already running: a no-op, not a second worker

        seedRemote("after.bin", randomBytes(32 * 1024, seed = 41))
        manager.enqueue(download("a", "after.bin"))
        drain()
        assertEquals(listOf(TransferState.COMPLETED), manager.transfers.value.map { it.state })
    }

    @Test // 14.7
    @Timeout(120)
    fun `an item cancelled while paused stays cancelled after resuming`() {
        seedRemote("keep.bin", randomBytes(32 * 1024, seed = 43))
        seedRemote("drop.bin", randomBytes(32 * 1024, seed = 47))
        manager.pause()
        manager.enqueueAll(listOf(download("keep", "keep.bin"), download("drop", "drop.bin")))
        manager.cancel("drop")
        manager.resume()
        drain()

        val statuses = manager.transfers.value.associateBy { it.request.id }
        assertEquals(TransferState.COMPLETED, statuses.getValue("keep").state)
        assertEquals(TransferState.CANCELLED, statuses.getValue("drop").state)
        assertTrue(!File(localDir, "drop.bin").exists(), "a cancelled item must not be written")
    }

    @Test // 14.8
    @Timeout(240)
    fun `an upload survives a pause and resume intact`() {
        val bytes = randomBytes(8 * 1024 * 1024, seed = 53)
        manager.enqueue(upload("up", "resumed-upload.bin", bytes))

        val watcher = manager.transfers
            .onEach { list ->
                val running = list.firstOrNull { it.request.id == "up" }
                if (running?.state == TransferState.RUNNING && running.transferred > 1_000_000) {
                    manager.pause()
                }
            }
            .launchIn(scope)
        drain()
        watcher.cancel()
        assertEquals(TransferState.PAUSED, manager.transfers.value.single().state)

        manager.resume()
        drain()

        assertEquals(TransferState.COMPLETED, manager.transfers.value.single().state)
        assertEquals(sha256(bytes), sha256(serverRoot.resolve("resumed-upload.bin").readBytes()))
    }

    @Test // 8.1 in the upload direction
    @Timeout(180)
    fun `uploads queue and complete just like downloads`() {
        val bytes = randomBytes(256 * 1024, seed = 21)
        manager.enqueue(upload("up", "uploaded.bin", bytes))
        drain()

        assertEquals(TransferState.COMPLETED, manager.transfers.value.single().state)
        assertEquals(sha256(bytes), sha256(serverRoot.resolve("uploaded.bin").readBytes()))
    }
}
