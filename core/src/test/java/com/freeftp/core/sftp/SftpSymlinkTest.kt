package com.freeftp.core.sftp

import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.ServerProfile
import com.freeftp.core.testing.EmbeddedSftpServer
import com.freeftp.core.testing.RecordingOutputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Test plan 5.12, 6b.7 and 6b.8 — symbolic links, which only SFTP can express. */
class SftpSymlinkTest {

    @TempDir
    lateinit var workspace: Path

    private lateinit var root: Path
    private lateinit var server: EmbeddedSftpServer
    private lateinit var client: SftpRemoteClient

    @BeforeEach
    fun setUp() {
        root = workspace.resolve("served").also { it.createDirectories() }
        server = EmbeddedSftpServer(root, hostKeyFile = workspace.resolve("hostkey.ser")).start()
        client = SftpRemoteClient(
            ServerProfile(
                id = "sftp",
                name = "Test SFTP",
                protocol = Protocol.SFTP,
                host = "127.0.0.1",
                port = server.port,
                credentials = Credentials.Password(EmbeddedSftpServer.USER, EmbeddedSftpServer.PASSWORD),
            )
        )
        client.connect()
    }

    @AfterEach
    fun tearDown() {
        runCatching { client.disconnect() }
        server.close()
    }

    @Test // 6b.7, 5.12
    fun `a symlink to a file is flagged and resolvable`() {
        root.resolve("target.txt").writeText("linked content")
        client.symlink("/link.txt", "target.txt")

        val entry = client.list("/").single { it.name == "link.txt" }
        assertTrue(entry.isSymlink)
        assertFalse(entry.isDirectory)
        assertEquals("target.txt", entry.symlinkTarget)
        assertEquals("target.txt", client.readlink("/link.txt"))
    }

    @Test // 6b.8
    fun `reading through a symlink returns the target content`() {
        root.resolve("target.txt").writeText("linked content")
        client.symlink("/link.txt", "target.txt")

        val sink = RecordingOutputStream()
        client.download("/link.txt", sink)
        assertEquals("linked content", String(sink.bytes))
    }

    @Test // 5.12
    fun `a symlink to a directory is browsable as a directory`() {
        root.resolve("real").createDirectories()
        root.resolve("real/inside.txt").writeText("x")
        client.symlink("/alias", "real")

        val entry = client.list("/").single { it.name == "alias" }
        assertTrue(entry.isSymlink)
        assertTrue(entry.isDirectory, "a symlink to a directory must be navigable")
        assertEquals(listOf("inside.txt"), client.list("/alias").map { it.name })
    }

    @Test
    fun `deleting a symlink leaves its target alone`() {
        root.resolve("target.txt").writeText("still here")
        client.symlink("/link.txt", "target.txt")
        client.deleteRecursively("/link.txt")

        assertFalse(client.exists("/link.txt"))
        assertTrue(client.exists("/target.txt"))
    }
}
