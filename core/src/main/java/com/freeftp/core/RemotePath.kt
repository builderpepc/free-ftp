package com.freeftp.core

/**
 * POSIX-style remote path arithmetic.
 *
 * Remote servers speak forward-slash paths regardless of the local platform, so this
 * deliberately does not use [java.io.File] or [java.nio.file.Path]: a backslash is an
 * ordinary filename character on a remote host, and `File` on Windows would mangle it.
 */
object RemotePath {

    const val ROOT: String = "/"
    const val SEPARATOR: Char = '/'

    /** Splits [path] into its non-empty segments, ignoring `.` and resolving `..`. */
    fun segments(path: String): List<String> {
        val out = ArrayList<String>()
        for (raw in path.split(SEPARATOR)) {
            when {
                raw.isEmpty() || raw == "." -> Unit
                raw == ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(raw)
            }
        }
        return out
    }

    /**
     * Returns an absolute, canonical form of [path]: duplicate separators collapsed,
     * `.` dropped, `..` resolved, trailing separator removed. `..` never escapes the
     * root — it clamps, matching what servers do with `CWD ..` at `/`.
     */
    fun normalize(path: String): String {
        val segs = segments(path)
        return if (segs.isEmpty()) ROOT else segs.joinToString(separator = "/", prefix = "/")
    }

    /** The directory containing [path]. The parent of the root is the root. */
    fun parent(path: String): String {
        val segs = segments(path)
        if (segs.size <= 1) return ROOT
        return segs.subList(0, segs.size - 1).joinToString(separator = "/", prefix = "/")
    }

    /** The last segment of [path]; empty for the root. */
    fun name(path: String): String = segments(path).lastOrNull() ?: ""

    /**
     * Appends [child] to [base]. A leading `/` on [child] is ignored (it is still joined
     * relative to [base]), while `..` inside [child] resolves against [base].
     */
    fun join(base: String, child: String): String = normalize("$base/$child")

    /** Resolves [relative] against [base], honouring leading `/` in [relative] as absolute. */
    fun resolve(base: String, relative: String): String =
        if (relative.startsWith(SEPARATOR)) normalize(relative) else join(base, relative)

    /** True when [descendant] lies strictly below [ancestor]. Prefix matches must land on a separator. */
    fun isAncestorOf(ancestor: String, descendant: String): Boolean {
        val a = segments(ancestor)
        val d = segments(descendant)
        if (d.size <= a.size) return false
        return a.indices.all { a[it] == d[it] }
    }

    /** The path of [descendant] relative to [ancestor], without a leading separator. */
    fun relativize(ancestor: String, descendant: String): String {
        require(isAncestorOf(ancestor, descendant) || normalize(ancestor) == normalize(descendant)) {
            "$descendant is not under $ancestor"
        }
        val a = segments(ancestor)
        val d = segments(descendant)
        return d.subList(a.size, d.size).joinToString("/")
    }

    /** File extension without the dot, or empty when there is none. */
    fun extension(path: String): String {
        val n = name(path)
        val dot = n.lastIndexOf('.')
        return if (dot <= 0 || dot == n.lastIndex) "" else n.substring(dot + 1)
    }
}
