package com.freeftp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Remote path arithmetic: the arithmetic every "works on my server" bug hides in. */
class RemotePathTest {

    @Test
    fun `normalize collapses duplicate and trailing separators`() {
        assertEquals("/a/b", RemotePath.normalize("/a//b/"))
        assertEquals("/a/b", RemotePath.normalize("///a///b///"))
    }

    @Test
    fun `normalize of empty and dot is the root`() {
        assertEquals("/", RemotePath.normalize(""))
        assertEquals("/", RemotePath.normalize("."))
        assertEquals("/", RemotePath.normalize("/"))
        assertEquals("/", RemotePath.normalize("/./"))
    }

    @Test
    fun `dot dot never escapes the root`() {
        assertEquals("/", RemotePath.normalize("/.."))
        assertEquals("/", RemotePath.normalize("/../../.."))
        assertEquals("/b", RemotePath.normalize("/a/../../b"))
        assertEquals("/a/c", RemotePath.normalize("/a/b/../c"))
    }

    @Test
    fun `parent walks one level and stops at the root`() {
        assertEquals("/a", RemotePath.parent("/a/b.txt"))
        assertEquals("/a/b", RemotePath.parent("/a/b/c"))
        assertEquals("/", RemotePath.parent("/a"))
        assertEquals("/", RemotePath.parent("/"))
        assertEquals("/", RemotePath.parent(""))
    }

    @Test
    fun `name returns the last segment`() {
        assertEquals("b.txt", RemotePath.name("/a/b.txt"))
        assertEquals("b", RemotePath.name("/a/b/"))
        assertEquals("", RemotePath.name("/"))
        assertEquals("", RemotePath.name(""))
    }

    @Test
    fun `join does not escape or alter the child name`() {
        assertEquals("/a/b c.txt", RemotePath.join("/a", "b c.txt"))
        assertEquals("/a/b&c#d.txt", RemotePath.join("/a", "b&c#d.txt"))
        assertEquals("/b c.txt", RemotePath.join("/", "b c.txt"))
    }

    @Test
    fun `join treats the child as relative even with a leading separator`() {
        assertEquals("/a/b", RemotePath.join("/a/", "/b"))
        assertEquals("/a/b", RemotePath.join("/a", "b"))
        assertEquals("/a", RemotePath.join("/a", ""))
    }

    @Test
    fun `segments containing dots are not treated as parent references`() {
        assertEquals("/a/..b", RemotePath.normalize("/a/..b"))
        assertEquals("/a/...", RemotePath.normalize("/a/..."))
        assertEquals("..b", RemotePath.name("/a/..b"))
        assertEquals("/a/.hidden", RemotePath.normalize("/a/.hidden"))
    }

    @Test
    fun `unicode and emoji segments survive intact`() {
        val path = "/データ/файл/naïve/🚀 rocket.txt"
        assertEquals(path, RemotePath.normalize(path))
        assertEquals("🚀 rocket.txt", RemotePath.name(path))
        assertEquals("/データ/файл/naïve", RemotePath.parent(path))
    }

    @Test
    fun `isAncestorOf requires a separator boundary`() {
        assertTrue(RemotePath.isAncestorOf("/a", "/a/b"))
        assertTrue(RemotePath.isAncestorOf("/", "/a"))
        assertFalse(RemotePath.isAncestorOf("/a", "/ab"))
        assertFalse(RemotePath.isAncestorOf("/a", "/a"))
        assertFalse(RemotePath.isAncestorOf("/a/b", "/a"))
    }

    @Test
    fun `relativize strips the ancestor prefix`() {
        assertEquals("c/d", RemotePath.relativize("/a/b", "/a/b/c/d"))
        assertEquals("a", RemotePath.relativize("/", "/a"))
        assertEquals("", RemotePath.relativize("/a", "/a"))
    }

    @Test
    fun `backslash is an ordinary filename character`() {
        assertEquals("b\\c", RemotePath.name("/a/b\\c"))
        assertEquals("/a/b\\c", RemotePath.normalize("/a/b\\c"))
        assertEquals(listOf("a", "b\\c"), RemotePath.segments("/a/b\\c"))
    }

    @Test
    fun `segments splits on separators only`() {
        assertEquals(listOf("a", "b"), RemotePath.segments("/a/b"))
        assertEquals(emptyList<String>(), RemotePath.segments("/"))
        assertEquals(listOf("a"), RemotePath.segments("a"))
    }

    @Test
    fun `very long paths are not truncated`() {
        val deep = (1..256).joinToString(separator = "/", prefix = "/") { "segment$it" }
        assertEquals(deep, RemotePath.normalize(deep))
        assertEquals(256, RemotePath.segments(deep).size)
        val wide = "/" + "x".repeat(4096)
        assertEquals(wide, RemotePath.normalize(wide))
        assertEquals(4096, RemotePath.name(wide).length)
    }

    @Test
    fun `resolve honours absolute relatives`() {
        assertEquals("/b", RemotePath.resolve("/a", "/b"))
        assertEquals("/a/b", RemotePath.resolve("/a", "b"))
        assertEquals("/", RemotePath.resolve("/a", ".."))
    }

    @Test
    fun `extension is the text after the final dot`() {
        assertEquals("txt", RemotePath.extension("/a/b.txt"))
        assertEquals("gz", RemotePath.extension("/a/b.tar.gz"))
        assertEquals("", RemotePath.extension("/a/b"))
        assertEquals("", RemotePath.extension("/a/.hidden"))
        assertEquals("", RemotePath.extension("/a/b."))
    }
}
