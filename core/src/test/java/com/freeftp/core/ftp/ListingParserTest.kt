package com.freeftp.core.ftp

import com.freeftp.core.permissionsToString
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.apache.commons.net.ftp.FTPClientConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test plan section 5b — LIST dialect parsing.
 *
 * Modelled on Cyberduck's `ftp/parser` package: raw lines as real servers emit them,
 * parsed with no server in the loop.
 */
class ListingParserTest {

    private fun utc(millis: Long?): ZonedDateTime? =
        millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC) }

    @Test // 5b.1
    fun `vsftpd unix listing`() {
        val files = ListingParser.parseListing(
            "/pub",
            listOf(
                "drwxr-xr-x    2 0        0            4096 Feb 14 09:11 incoming",
                "-rw-r--r--    1 1000     1000         1234 Feb 14 09:12 readme.txt",
            ),
        )
        assertEquals(2, files.size)
        val dir = files[0]
        assertEquals("/pub/incoming", dir.path)
        assertTrue(dir.isDirectory)
        val file = files[1]
        assertEquals("/pub/readme.txt", file.path)
        assertFalse(file.isDirectory)
        assertEquals(1234L, file.size)
        assertEquals("rw-r--r--", permissionsToString(file.permissions!!))
        assertEquals("1000", file.owner)
        assertEquals("1000", file.group)
    }

    @Test // 5b.2
    fun `proftpd total header line is discarded`() {
        val files = ListingParser.parseListing(
            "/",
            listOf(
                "total 12",
                "-rw-r--r--   1 ftp      ftp           123 Mar  3 09:14 a.txt",
            ),
        )
        assertEquals(listOf("a.txt"), files.map { it.name })
    }

    @Test // 5b.3
    fun `unix filename containing spaces is not truncated`() {
        val files = ListingParser.parseListing(
            "/",
            listOf("-rw-r--r--   1 ftp      ftp           123 Mar  3 09:14 my holiday photos.tar.gz"),
        )
        assertEquals("my holiday photos.tar.gz", files.single().name)
    }

    @Test // 5b.4
    fun `unix symlink exposes its target`() {
        val files = ListingParser.parseListing(
            "/",
            listOf("lrwxrwxrwx   1 root     root            7 Mar  3 09:14 current -> release"),
        )
        val link = files.single()
        assertEquals("current", link.name)
        assertTrue(link.isSymlink)
        assertEquals("release", link.symlinkTarget)
    }

    @Test // 5b.5
    fun `recent date without a year is never resolved into the future`() {
        val files = ListingParser.parseListing(
            "/",
            listOf("-rw-r--r--   1 ftp      ftp           123 Mar  3 09:14 a.txt"),
        )
        val when0 = utc(files.single().modifiedEpochMillis)
        assertNotNull(when0)
        assertEquals(3, when0!!.dayOfMonth)
        assertEquals(3, when0.monthValue)
        assertEquals(9, when0.hour)
        assertEquals(14, when0.minute)
        assertTrue(
            when0.toInstant().isBefore(Instant.now().plusSeconds(86_400)),
            "recent-date listings must not land in the future, got $when0",
        )
    }

    @Test // 5b.6
    fun `old date with an explicit year parses at midnight`() {
        val files = ListingParser.parseListing(
            "/",
            listOf("-rw-r--r--   1 ftp      ftp           123 Mar  3  2019 old.txt"),
        )
        val when0 = utc(files.single().modifiedEpochMillis)!!
        assertEquals(2019, when0.year)
        assertEquals(3, when0.monthValue)
        assertEquals(3, when0.dayOfMonth)
        assertEquals(0, when0.hour)
    }

    @Test // 5b.7
    fun `unix listing without a group column still parses`() {
        val files = ListingParser.parseListing(
            "/",
            listOf("-rw-r--r--   1 ftp                    123 Mar  3 09:14 a.txt"),
        )
        assertEquals("a.txt", files.single().name)
        assertEquals(123L, files.single().size)
    }

    @Test // 5b.8, 5b.9
    fun `windows IIS MS-DOS listing`() {
        val files = ListingParser.parseListing(
            "/wwwroot",
            listOf(
                "02-14-24  09:11AM       <DIR>          images",
                "02-14-24  09:12AM                 1234 default.htm",
            ),
            FTPClientConfig.SYST_NT,
        )
        assertEquals(2, files.size)
        assertTrue(files[0].isDirectory)
        assertEquals("/wwwroot/images", files[0].path)
        assertFalse(files[1].isDirectory)
        assertEquals(1234L, files[1].size)
        val when0 = utc(files[1].modifiedEpochMillis)!!
        assertEquals(2024, when0.year)
        assertEquals(2, when0.monthValue)
        assertEquals(14, when0.dayOfMonth)
    }

    @Test // 5b.10
    fun `netware listing`() {
        val files = ListingParser.parseListing(
            "/",
            listOf(
                "d [R----F--] jrhodes                       512 Feb 14 09:11 incoming",
                "- [R----F--] jrhodes                      1234 Feb 14 09:12 readme.txt",
            ),
            FTPClientConfig.SYST_NETWARE,
        )
        assertEquals(2, files.size)
        assertTrue(files[0].isDirectory)
        assertEquals("readme.txt", files[1].name)
        assertEquals(1234L, files[1].size)
    }

    @Test // 5b.11
    fun `EPLF listing`() {
        val files = ListingParser.parseEplf(
            "/pub",
            listOf(
                "+i8388621.48594,m825718503,r,s280,\tdjb.html",
                "+i8388621.48598,m825718503,/,\tarchive",
            ),
        )
        assertEquals(2, files.size)
        assertEquals("/pub/djb.html", files[0].path)
        assertEquals(280L, files[0].size)
        assertFalse(files[0].isDirectory)
        assertEquals(825_718_503_000L, files[0].modifiedEpochMillis)
        assertTrue(files[1].isDirectory)
        assertEquals("archive", files[1].name)
    }

    @Test // 5b.12, 5b.13
    fun `MLSD facts drive type size and timestamp`() {
        val files = ListingParser.parseMlsd(
            "/pub",
            listOf(
                "type=dir;sizd=4096;modify=20240214091100; images",
                "type=file;size=1234;modify=20240214091200; default.htm",
            ),
        )
        assertEquals(2, files.size)
        assertTrue(files[0].isDirectory)
        assertEquals("/pub/images", files[0].path)
        assertFalse(files[1].isDirectory)
        assertEquals(1234L, files[1].size)
        val when0 = utc(files[1].modifiedEpochMillis)!!
        assertEquals(2024, when0.year)
        assertEquals(2, when0.monthValue)
        assertEquals(14, when0.dayOfMonth)
        assertEquals(9, when0.hour)
        assertEquals(12, when0.minute)
    }

    @Test // 5b.14
    fun `MLSD cdir and pdir entries are filtered out`() {
        val files = ListingParser.parseMlsd(
            "/pub",
            listOf(
                "type=cdir;modify=20240214091100; /pub",
                "type=pdir;modify=20240214091100; /",
                "type=file;size=1; a.txt",
            ),
        )
        assertEquals(listOf("a.txt"), files.map { it.name })
    }

    @Test // 5b.15
    fun `unicode names survive the unix parser`() {
        val files = ListingParser.parseListing(
            "/",
            listOf(
                "-rw-r--r--   1 ftp      ftp           123 Mar  3 09:14 привет мир.txt",
                "drwxr-xr-x   2 ftp      ftp          4096 Mar  3 09:14 日本語フォルダ",
            ),
        )
        assertEquals(listOf("привет мир.txt", "日本語フォルダ"), files.map { it.name })
    }

    @Test // 5b.16, 5.8
    fun `garbage lines and dot entries are skipped without aborting the listing`() {
        val files = ListingParser.parseListing(
            "/",
            listOf(
                "drwxr-xr-x   2 ftp      ftp          4096 Mar  3 09:14 .",
                "drwxr-xr-x   2 ftp      ftp          4096 Mar  3 09:14 ..",
                "this is not a listing line at all",
                "",
                "-rw-r--r--   1 ftp      ftp           123 Mar  3 09:14 good.txt",
            ),
        )
        assertEquals(listOf("good.txt"), files.map { it.name })
    }

    @Test // 5.7
    fun `hidden dotfiles are kept`() {
        val files = ListingParser.parseListing(
            "/home/bob",
            listOf("-rw-------   1 bob      bob            42 Mar  3 09:14 .netrc"),
        )
        assertEquals("/home/bob/.netrc", files.single().path)
    }

    @Test
    fun `an entry with no permission bits reports null rather than zero`() {
        val files = ListingParser.parseMlsd("/", listOf("type=file;size=1; a.txt"))
        assertNull(files.single().permissions)
    }
}
