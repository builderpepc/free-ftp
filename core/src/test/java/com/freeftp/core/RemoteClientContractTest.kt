package com.freeftp.core

import com.freeftp.core.bulk.scanForDownload
import com.freeftp.core.preview.FilePreview
import com.freeftp.core.preview.previewText
import com.freeftp.core.testing.ProgressRecorder
import com.freeftp.core.testing.RecordingOutputStream
import com.freeftp.core.testing.randomBytes
import com.freeftp.core.testing.sha256
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The behaviour every protocol must deliver identically, run against a real server.
 *
 * FTP and SFTP differ enormously underneath, and the whole point of [RemoteClient] is
 * that the rest of the app cannot tell. Rather than duplicating fifty near-identical
 * tests per protocol, the contract lives here once and each protocol supplies a server
 * and a client (test plan sections 5, 6, 6b, 7).
 */
abstract class RemoteClientContractTest {

    @TempDir
    lateinit var workspace: Path

    /** The directory the server exposes as `/`. */
    protected lateinit var serverRoot: Path
        private set

    protected lateinit var client: RemoteClient
        private set

    /** Starts a server rooted at [root]; the fixture is closed automatically. */
    protected abstract fun startServer(root: Path): AutoCloseable

    protected abstract fun createClient(): RemoteClient

    /** True when the protocol can report and set Unix permission bits. */
    protected open val supportsPermissions: Boolean get() = true

    /** True when this configuration is expected to surface dot-prefixed entries. */
    protected open val listsHiddenFiles: Boolean get() = true

    protected var server: AutoCloseable? = null

    @BeforeEach
    fun setUpContract() {
        serverRoot = workspace.resolve("served").also { it.createDirectories() }
        server = startServer(serverRoot)
        client = createClient().also { it.connect() }
    }

    @AfterEach
    fun tearDownContract() {
        runCatching { client.disconnect() }
        runCatching { server?.close() }
    }

    // ------------------------------------------------------------------ helpers

    protected fun seedFile(relative: String, content: ByteArray): Path {
        val path = serverRoot.resolve(relative)
        path.parent?.createDirectories()
        path.writeBytes(content)
        return path
    }

    protected fun seedFile(relative: String, content: String = "hello"): Path =
        seedFile(relative, content.toByteArray())

    protected fun seedDir(relative: String): Path =
        serverRoot.resolve(relative).also { it.createDirectories() }

    private fun download(path: String): ByteArray =
        RecordingOutputStream().also { client.download(path, it) }.bytes

    private fun upload(path: String, bytes: ByteArray) =
        client.upload(ByteArrayInputStream(bytes), path, bytes.size.toLong())

    private fun reconnect() {
        runCatching { client.disconnect() }
        client = createClient().also { it.connect() }
    }

    // ------------------------------------------------------------------ 5. listing

    @Test // 5.1
    fun `listing an empty directory yields an empty list`() {
        seedDir("empty")
        assertEquals(emptyList<RemoteFile>(), client.list("/empty"))
    }

    @Test // 5.2, 5.3, 5.8
    fun `listing reports type and size and omits dot entries`() {
        seedDir("mixed/subdir")
        seedFile("mixed/file.bin", randomBytes(1234))
        val entries = client.list("/mixed")
        assertEquals(listOf("subdir", "file.bin"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertFalse(entries[1].isDirectory)
        assertEquals(1234L, entries[1].size)
        assertEquals("/mixed/file.bin", entries[1].path)
    }

    @Test // 5.4
    fun `listing reports a plausible modification time`() {
        seedFile("stamped.txt")
        val entry = client.list("/").single { it.name == "stamped.txt" }
        val modified = entry.modifiedEpochMillis
        assertNotNull(modified)
        val skew = Math.abs(System.currentTimeMillis() - modified!!)
        assertTrue(skew < 5 * 60_000, "timestamp was $skew ms away from now")
    }

    @Test // 5.5
    fun `names with punctuation round trip`() {
        val names = listOf("a b.txt", "a&b.txt", "a#b.txt", "a'b.txt", "a+b.txt", "a%b.txt", "a(1).txt")
        names.forEach { seedFile("punct/$it") }
        assertEquals(names.sorted(), client.list("/punct").map { it.name }.sorted())
    }

    @Test // 5.6
    fun `non-ASCII names round trip`() {
        val names = listOf("привет.txt", "日本語.txt", "naïve.txt", "emoji 🚀.txt")
        names.forEach { seedFile("intl/$it") }
        assertEquals(names.sorted(), client.list("/intl").map { it.name }.sorted())
    }

    @Test // 5.7
    fun `hidden dotfiles are listed`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(listsHiddenFiles)
        seedFile("hidden/.config")
        seedFile("hidden/visible.txt")
        assertEquals(listOf(".config", "visible.txt"), client.list("/hidden").map { it.name }.sorted())
    }

    @Test // 5.9
    fun `listing a missing directory reports not found`() {
        assertThrows<RemoteFileNotFoundException> { client.list("/does/not/exist") }
    }

    @Test // 5.10
    fun `listing a file reports that it is not a directory`() {
        seedFile("plain.txt")
        assertThrows<NotADirectoryException> { client.list("/plain.txt") }
    }

    @Test // 5.11
    @Timeout(120)
    fun `a large directory is returned in full`() {
        seedDir("many")
        repeat(500) { seedFile("many/file-%03d.txt".format(it), "x") }
        assertEquals(500, client.list("/many").size)
    }

    @Test // 5.13
    fun `listings are sorted directories first then case-insensitively`() {
        seedDir("sorted/Zeta")
        seedDir("sorted/alpha")
        seedFile("sorted/Beta.txt")
        seedFile("sorted/apple.txt")
        assertEquals(
            listOf("alpha", "Zeta", "apple.txt", "Beta.txt"),
            client.list("/sorted").map { it.name },
        )
    }

    // ------------------------------------------------------------------ 6. file operations

    @Test // 6.1
    fun `makeDirectory creates a directory`() {
        client.makeDirectory("/fresh")
        assertTrue(Files.isDirectory(serverRoot.resolve("fresh")))
        assertTrue(client.list("/").any { it.name == "fresh" && it.isDirectory })
    }

    @Test // 6.2
    fun `makeDirectories creates the whole chain`() {
        client.makeDirectories("/a/b/c")
        assertTrue(Files.isDirectory(serverRoot.resolve("a/b/c")))
    }

    @Test // 6.3
    fun `makeDirectory over an existing name fails`() {
        seedDir("taken")
        assertThrows<RemoteFileAlreadyExistsException> { client.makeDirectory("/taken") }
    }

    @Test // 6.4
    fun `deleteFile removes the file`() {
        seedFile("doomed.txt")
        client.deleteFile("/doomed.txt")
        assertFalse(Files.exists(serverRoot.resolve("doomed.txt")))
    }

    @Test // 6.5
    fun `deleting a missing file reports not found`() {
        assertThrows<RemoteFileNotFoundException> { client.deleteFile("/ghost.txt") }
    }

    @Test // 6.6
    fun `removeDirectory removes an empty directory`() {
        seedDir("gone")
        client.removeDirectory("/gone")
        assertFalse(Files.exists(serverRoot.resolve("gone")))
    }

    @Test // 6.7
    fun `removeDirectory refuses a non-empty directory and leaves it intact`() {
        seedFile("full/child.txt")
        assertThrows<RemoteException> { client.removeDirectory("/full") }
        assertTrue(Files.exists(serverRoot.resolve("full/child.txt")))
    }

    @Test // 6.8
    fun `deleteRecursively removes a whole tree`() {
        seedFile("tree/one/two/deep.txt")
        seedFile("tree/one/sibling.txt")
        seedDir("tree/empty")
        client.deleteRecursively("/tree")
        assertFalse(Files.exists(serverRoot.resolve("tree")))
    }

    @Test // 6.9
    fun `rename within a directory preserves content`() {
        seedFile("dir/old.txt", "payload")
        client.rename("/dir/old.txt", "/dir/new.txt")
        assertFalse(Files.exists(serverRoot.resolve("dir/old.txt")))
        assertEquals("payload", String(serverRoot.resolve("dir/new.txt").readBytes()))
    }

    @Test // 6.10
    fun `rename across directories moves the file`() {
        seedFile("from/file.txt", "payload")
        seedDir("to")
        client.rename("/from/file.txt", "/to/file.txt")
        assertFalse(Files.exists(serverRoot.resolve("from/file.txt")))
        assertEquals("payload", String(serverRoot.resolve("to/file.txt").readBytes()))
    }

    @Test // 6.11
    fun `rename onto an existing target never silently loses both files`() {
        seedFile("a.txt", "aaa")
        seedFile("b.txt", "bbb")
        runCatching { client.rename("/a.txt", "/b.txt") }
        // Either the server refused, or it replaced b.txt with a.txt's content.
        // What must never happen is both files vanishing.
        val remaining = client.list("/").map { it.name }
        assertTrue("b.txt" in remaining, "b.txt disappeared: $remaining")
        val b = String(serverRoot.resolve("b.txt").readBytes())
        assertTrue(b == "aaa" || b == "bbb", "b.txt held unexpected content: $b")
    }

    @Test // 6.12
    fun `exists distinguishes present from absent without throwing`() {
        seedFile("here.txt")
        seedDir("heretoo")
        assertTrue(client.exists("/here.txt"))
        assertTrue(client.exists("/heretoo"))
        assertFalse(client.exists("/nowhere.txt"))
        assertFalse(client.exists("/no/such/dir"))
    }

    @Test // 6.13
    fun `stat reports size and modification time`() {
        val bytes = randomBytes(4096)
        seedFile("stat.bin", bytes)
        val entry = client.stat("/stat.bin")
        assertFalse(entry.isDirectory)
        assertEquals(4096L, entry.size)
        assertEquals("/stat.bin", entry.path)
        assertNotNull(entry.modifiedEpochMillis)
    }

    @Test // 6.14
    fun `operations work on names needing quoting`() {
        val name = "an awkward & name (v2).txt"
        upload("/$name", "payload".toByteArray())
        assertTrue(client.exists("/$name"))
        assertEquals("payload", String(download("/$name")))
        client.rename("/$name", "/renamed $name")
        assertTrue(client.exists("/renamed $name"))
        client.deleteFile("/renamed $name")
        assertFalse(client.exists("/renamed $name"))
    }

    // ------------------------------------------------------------------ 6b. metadata

    @Test // 6b.1
    fun `touch creates an empty file`() {
        client.touch("/touched.txt")
        assertTrue(client.exists("/touched.txt"))
        assertEquals(0L, client.stat("/touched.txt").size)
    }

    @Test // 6b.2, 6b.3
    fun `setModificationTime is reflected in stat`() {
        seedFile("dated.txt")
        val target = 1_700_000_000_000L // 2023-11-14T22:13:20Z
        client.setModificationTime("/dated.txt", target)
        val reported = client.stat("/dated.txt").modifiedEpochMillis
        assertNotNull(reported)
        assertTrue(
            Math.abs(reported!! - target) <= 1000L,
            "expected ~$target but the server reported $reported",
        )
    }

    @Test // 6b.5, 6b.6
    fun `setPermissions is reflected in the listing`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(supportsPermissions)
        seedFile("perms.txt")
        client.setPermissions("/perms.txt", 0b110_100_000) // 0640
        val entry = client.list("/").single { it.name == "perms.txt" }
        val mode = entry.permissions
        assertNotNull(mode)
        assertEquals("rw-r-----", permissionsToString(mode!!))
    }

    @Test // 6b.9, 6b.10
    fun `workingDirectory is an absolute path`() {
        val cwd = client.workingDirectory()
        assertTrue(cwd.startsWith("/"), "expected an absolute path, got $cwd")
    }

    // ------------------------------------------------------------------ 7. transfers

    @Test // 7.1
    fun `zero byte files round trip`() {
        upload("/empty.bin", ByteArray(0))
        assertEquals(0L, client.stat("/empty.bin").size)
        assertArrayEquals(ByteArray(0), download("/empty.bin"))
    }

    @Test // 7.2
    fun `small text round trips`() {
        val bytes = "the quick brown fox\n".repeat(50).toByteArray()
        upload("/text.txt", bytes)
        assertEquals(sha256(bytes), sha256(download("/text.txt")))
    }

    @Test // 7.3
    @Timeout(120)
    fun `five megabytes of binary round trips byte for byte`() {
        val bytes = randomBytes(5 * 1024 * 1024)
        upload("/big.bin", bytes)
        assertEquals(bytes.size.toLong(), client.stat("/big.bin").size)
        assertEquals(sha256(bytes), sha256(download("/big.bin")))
    }

    @Test // 7.4
    fun `CRLF byte sequences are not translated`() {
        // The classic FTP data-corruption bug: transferring in ASCII mode rewrites
        // line endings and silently destroys binary payloads.
        val bytes = byteArrayOf(0x00, 0x0D, 0x0A, 0x1A, 0x0D, 0x0D, 0x0A, 0x0A, 0x7F, 0x00)
        upload("/crlf.bin", bytes)
        assertArrayEquals(bytes, serverRoot.resolve("crlf.bin").readBytes())
        assertArrayEquals(bytes, download("/crlf.bin"))
    }

    @Test // 7.5, 7.6
    @Timeout(120)
    fun `progress is monotonic reaches the total and fires repeatedly`() {
        val bytes = randomBytes(5 * 1024 * 1024, seed = 7)
        seedFile("progress.bin", bytes)
        val recorder = ProgressRecorder()
        client.download("/progress.bin", RecordingOutputStream(), listener = recorder)

        val seen = recorder.transferred
        assertTrue(seen.size > 1, "expected repeated progress callbacks, saw ${seen.size}")
        assertEquals(seen.sorted(), seen, "progress went backwards: $seen")
        assertEquals(bytes.size.toLong(), seen.last())
        assertTrue(recorder.events.all { it.second == bytes.size.toLong() })
    }

    @Test // 7.7
    @Timeout(60)
    fun `a download can be cancelled mid-flight`() {
        val bytes = randomBytes(8 * 1024 * 1024, seed = 11)
        seedFile("cancel-down.bin", bytes)
        val cancellation = CancellationSignal()
        val sink = RecordingOutputStream()
        assertThrows<TransferCancelledException> {
            client.download(
                "/cancel-down.bin",
                sink,
                listener = { transferred, _ -> if (transferred > 256 * 1024) cancellation.cancel() },
                cancellation = cancellation,
            )
        }
        assertTrue(sink.bytes.size < bytes.size, "the whole file was transferred despite cancelling")
        assertArrayEquals(bytes.copyOf(sink.bytes.size), sink.bytes, "partial data was corrupted")
    }

    @Test // 7.8
    @Timeout(60)
    fun `an upload can be cancelled promptly`() {
        val bytes = randomBytes(16 * 1024 * 1024, seed = 13)
        val cancellation = CancellationSignal()
        val started = System.nanoTime()
        assertThrows<TransferCancelledException> {
            client.upload(
                ByteArrayInputStream(bytes),
                "/cancel-up.bin",
                bytes.size.toLong(),
                listener = { transferred, _ -> if (transferred > 256 * 1024) cancellation.cancel() },
                cancellation = cancellation,
            )
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 20_000, "cancellation took ${elapsedMillis}ms")
    }

    @Test // 7.9
    @Timeout(120)
    fun `a download resumes from an offset into a whole correct file`() {
        val bytes = randomBytes(2 * 1024 * 1024, seed = 17)
        seedFile("resume-down.bin", bytes)
        val offset = 700_000L
        val sink = RecordingOutputStream()
        sink.write(bytes, 0, offset.toInt()) // what an interrupted attempt already saved
        client.download("/resume-down.bin", sink, offset = offset)
        assertEquals(sha256(bytes), sha256(sink.bytes))
    }

    @Test // 7.10
    @Timeout(120)
    fun `an upload resumes from an offset into a whole correct file`() {
        val bytes = randomBytes(2 * 1024 * 1024, seed = 19)
        val offset = 700_000
        seedFile("resume-up.bin", bytes.copyOf(offset)) // the partial upload
        client.upload(
            ByteArrayInputStream(bytes, offset, bytes.size - offset),
            "/resume-up.bin",
            bytes.size.toLong(),
            offset = offset.toLong(),
        )
        assertEquals(sha256(bytes), sha256(serverRoot.resolve("resume-up.bin").readBytes()))
    }

    @Test // 7.11
    fun `resuming past the end of the file is rejected`() {
        seedFile("short.bin", randomBytes(1024))
        assertThrows<RemoteException> {
            client.download("/short.bin", RecordingOutputStream(), offset = 99_999L)
        }
        assertThrows<RemoteException> {
            client.upload(ByteArrayInputStream(ByteArray(10)), "/short.bin", 10L, offset = 99_999L)
        }
    }

    @Test // 7.13
    fun `uploading over an existing file replaces it entirely`() {
        seedFile("clobber.bin", randomBytes(50_000, seed = 23))
        val replacement = "short".toByteArray()
        upload("/clobber.bin", replacement)
        assertArrayEquals(replacement, serverRoot.resolve("clobber.bin").readBytes())
        assertEquals(replacement.size.toLong(), client.stat("/clobber.bin").size)
    }

    @Test // 7.14
    fun `sequential transfers on one connection all succeed`() {
        val payloads = (1..4).map { randomBytes(64 * 1024, seed = it) }
        payloads.forEachIndexed { index, bytes -> upload("/seq-$index.bin", bytes) }
        payloads.forEachIndexed { index, bytes ->
            assertEquals(sha256(bytes), sha256(download("/seq-$index.bin")))
        }
    }

    @Test // 7.15
    @Timeout(90)
    fun `losing the server mid-transfer fails cleanly and the client recovers`() {
        val bytes = randomBytes(16 * 1024 * 1024, seed = 29)
        seedFile("interrupted.bin", bytes)
        val stopper = Executors.newSingleThreadExecutor()
        try {
            assertThrows<RemoteException> {
                client.download(
                    "/interrupted.bin",
                    RecordingOutputStream(),
                    listener = { transferred, _ ->
                        if (transferred > 512 * 1024) stopper.submit { server?.close() }
                    },
                )
            }
        } finally {
            stopper.shutdown()
            stopper.awaitTermination(30, TimeUnit.SECONDS)
        }
        // The client must recover once a server is reachable again.
        server = startServer(serverRoot)
        reconnect()
        assertTrue(client.list("/").any { it.name == "interrupted.bin" })
    }

    // ------------------------------------------------------------------ 12. text preview

    @Test // 12.13
    fun `a text file can be previewed without touching local storage`() {
        val content = "# config\nhost = example.com\nport = 21\n\n# note: caf\u00e9 \u65e5\u672c\u8a9e \ud83d\ude80\n"
        seedFile("notes.conf", content.toByteArray())
        val preview = client.previewText("/notes.conf")
        assertTrue(preview is FilePreview.Text, "expected text, got $preview")
        preview as FilePreview.Text
        assertEquals(content, preview.content)
        assertEquals("UTF-8", preview.charsetName)
        assertFalse(preview.truncated)
    }

    @Test // 12.14
    @Timeout(120)
    fun `a file larger than the limit is previewed as its opening bytes`() {
        val line = "every line is exactly forty-eight bytes long...\n"
        val whole = line.repeat(20_000) // ~940 KB
        seedFile("huge.log", whole.toByteArray())

        val limit = 64L * 1024
        val preview = client.previewText("/huge.log", limitBytes = limit)
        assertTrue(preview is FilePreview.Text, "expected text, got $preview")
        preview as FilePreview.Text
        assertTrue(preview.truncated, "a partial read must say so")
        assertEquals(limit, preview.bytesShown)
        assertTrue(whole.startsWith(preview.content), "the preview is not a prefix of the file")
    }

    @Test // 12.15
    @Timeout(120)
    fun `the session still works after a truncated preview`() {
        seedFile("huge.log", "x".repeat(500_000).toByteArray())
        seedFile("after.txt", "still here")

        client.previewText("/huge.log", limitBytes = 32L * 1024)

        // Cutting a transfer short must not leave the caller holding a dead client.
        assertTrue(client.list("/").any { it.name == "after.txt" })
        assertEquals("still here", (client.previewText("/after.txt") as FilePreview.Text).content)
    }

    @Test // 12.16
    @Timeout(120)
    fun `a binary file is recognised without transferring all of it`() {
        seedFile("blob.bin", randomBytes(4 * 1024 * 1024, seed = 71))
        val limit = 32L * 1024
        val preview = client.previewText("/blob.bin", limitBytes = limit)
        assertTrue(preview is FilePreview.Binary, "expected binary, got $preview")
        assertTrue(
            (preview as FilePreview.Binary).bytesInspected <= limit,
            "read ${preview.bytesInspected} bytes for a preview limited to $limit",
        )
    }

    @Test // 12.17
    fun `previewing a missing file reports it as missing`() {
        assertThrows<RemoteFileNotFoundException> { client.previewText("/no-such-file.txt") }
    }

    // ------------------------------------------------------------------ 13. tree scanning

    /** Everything in the current directory, as "download all" would gather it. */
    private fun scanEverything(base: String = "/", maxFiles: Int = 5_000, maxDepth: Int = 24) =
        client.scanForDownload(base, client.list(base), maxFiles = maxFiles, maxDepth = maxDepth)

    @Test // 13.1, 13.9
    fun `scanning a flat directory finds every file and sums their sizes`() {
        seedFile("flat/a.txt", ByteArray(100))
        seedFile("flat/b.txt", ByteArray(250))
        seedFile("flat/c.txt", ByteArray(650))

        val scan = scanEverything("/flat")
        assertEquals(setOf("a.txt", "b.txt", "c.txt"), scan.files.map { it.relativePath }.toSet())
        assertEquals(1000L, scan.totalBytes)
        assertEquals(3, scan.fileCount)
        assertFalse(scan.truncated)
    }

    @Test // 13.2
    fun `scanning a nested tree keeps the structure in the relative paths`() {
        seedFile("docs/top.txt", "1")
        seedFile("docs/reports/mid.txt", "2")
        seedFile("docs/reports/2026/q3.txt", "3")
        seedDir("docs/empty")

        val scan = client.scanForDownload("/docs", client.list("/docs"))
        assertEquals(
            setOf("top.txt", "reports/mid.txt", "reports/2026/q3.txt"),
            scan.files.map { it.relativePath }.toSet(),
        )
        // reports, reports/2026 and empty
        assertEquals(3, scan.directoryCount)
    }

    @Test // 13.3, 13.5
    fun `a mixed selection is relative to the directory being browsed`() {
        seedFile("loose.txt", "x")
        seedFile("bundle/inner.txt", "y")
        seedFile("ignored.txt", "z")

        val entries = client.list("/").filter { it.name == "loose.txt" || it.name == "bundle" }
        val scan = client.scanForDownload("/", entries)
        assertEquals(
            setOf("loose.txt", "bundle/inner.txt"),
            scan.files.map { it.relativePath }.toSet(),
        )
        assertTrue(scan.files.none { it.relativePath.contains("ignored") })
    }

    @Test // 13.4
    fun `empty directories are counted but add no files`() {
        seedDir("hollow/one")
        seedDir("hollow/two")
        val scan = client.scanForDownload("/hollow", client.list("/hollow"))
        assertEquals(0, scan.fileCount)
        assertEquals(2, scan.directoryCount)
        assertEquals(0L, scan.totalBytes)
    }

    @Test // 13.7
    fun `the file limit stops the walk and says so`() {
        repeat(30) { seedFile("many/file-%02d.txt".format(it), "x") }
        val scan = scanEverything("/many", maxFiles = 10)
        assertEquals(10, scan.fileCount)
        assertTrue(scan.truncated, "hitting the file limit must be reported")
    }

    @Test // 13.8
    fun `the depth limit stops the walk and says so`() {
        seedFile("deep/one/two/three/four/bottom.txt", "x")
        val shallow = client.scanForDownload("/deep", client.list("/deep"), maxDepth = 2)
        assertTrue(shallow.truncated, "hitting the depth limit must be reported")
        assertTrue(shallow.files.none { it.relativePath.endsWith("bottom.txt") })

        val full = client.scanForDownload("/deep", client.list("/deep"), maxDepth = 24)
        assertFalse(full.truncated)
        assertEquals(listOf("one/two/three/four/bottom.txt"), full.files.map { it.relativePath })
    }

    @Test // 13.10
    fun `scanning a missing directory reports it as missing`() {
        assertThrows<RemoteFileNotFoundException> {
            client.scanForDownload("/", listOf(RemoteFile("/gone", isDirectory = true)))
        }
    }

    @Test // 2.16
    fun `operations before connecting are rejected`() {
        val fresh = createClient()
        assertThrows<NotConnectedException> { fresh.list("/") }
    }

    @Test // 2.7, 2.8
    fun `disconnect is idempotent and the client can reconnect`() {
        val fresh = createClient()
        fresh.disconnect() // never connected
        fresh.connect()
        assertTrue(fresh.isConnected)
        fresh.disconnect()
        assertFalse(fresh.isConnected)
        fresh.connect()
        assertNotNull(fresh.list("/"))
        fresh.disconnect()
    }

    @Test // 10.5
    fun `no error message leaks the password`() {
        val failures = buildList {
            add(runCatching { client.list("/nope") }.exceptionOrNull())
            add(runCatching { client.deleteFile("/nope.txt") }.exceptionOrNull())
            add(runCatching { client.stat("/nope.txt") }.exceptionOrNull())
        }.filterNotNull()
        assertTrue(failures.isNotEmpty())
        failures.forEach { failure ->
            assertTrue(failure.message!!.isNotBlank(), "empty message on ${failure::class.simpleName}")
            assertFalse(
                failure.message!!.contains(secret),
                "message leaked the password: ${failure.message}",
            )
        }
    }

    /** The password both fixtures authenticate with, so leak checks can look for it. */
    protected abstract val secret: String
}
