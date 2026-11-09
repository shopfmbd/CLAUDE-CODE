package dev.claudelinux

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Minimal tar reader (ustar + GNU long names + pax path/linkpath/size). Enough for Alpine minirootfs. */
class TarReader(private val ins: InputStream) {
    class Entry(val name: String, val linkName: String, val mode: Int, val size: Long, val type: Char) {
        val isDirectory get() = type == '5'
        val isSymbolicLink get() = type == '2'
        val isLink get() = type == '1'
        val isFile get() = type == '0' || type == '\u0000' || type == '7'
    }

    private var remaining = 0L
    private var pad = 0L

    private fun readFully(b: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = ins.read(b, off, len - off)
            if (n < 0) { if (off == 0) return false; throw EOFException() }
            off += n
        }
        return true
    }

    private fun skipFully(n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException()
            left -= r
        }
    }

    private fun str(h: ByteArray, off: Int, len: Int): String {
        var e = off
        while (e < off + len && h[e].toInt() != 0) e++
        return String(h, off, e - off, Charsets.UTF_8)
    }

    private fun num(h: ByteArray, off: Int, len: Int): Long {
        if (h[off].toInt() and 0x80 != 0) { // base-256
            var v = (h[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) v = (v shl 8) or (h[off + i].toLong() and 0xFF)
            return v
        }
        val s = str(h, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readData(size: Long): ByteArray {
        val b = ByteArray(size.toInt())
        var off = 0
        while (off < b.size) { val n = ins.read(b, off, b.size - off); if (n < 0) throw EOFException(); off += n }
        skipFully((512 - size % 512) % 512)
        return b
    }

    fun next(): Entry? {
        skipFully(remaining + pad); remaining = 0; pad = 0
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize = -1L
        val h = ByteArray(512)
        while (true) {
            if (!readFully(h, 512)) return null
            if (h.all { it.toInt() == 0 }) return null
            val type = h[156].toInt().toChar()
            var size = num(h, 124, 12)
            when (type) {
                'L' -> { longName = str(readData(size), 0, size.toInt()); continue }
                'K' -> { longLink = str(readData(size), 0, size.toInt()); continue }
                'x' -> {
                    val d = String(readData(size), Charsets.UTF_8)
                    for (rec in d.split('\n')) {
                        val sp = rec.indexOf(' '); if (sp < 0) continue
                        val kv = rec.substring(sp + 1); val eq = kv.indexOf('='); if (eq < 0) continue
                        when (kv.substring(0, eq)) {
                            "path" -> paxPath = kv.substring(eq + 1)
                            "linkpath" -> paxLink = kv.substring(eq + 1)
                            "size" -> paxSize = kv.substring(eq + 1).toLong()
                        }
                    }
                    continue
                }
                'g' -> { readData(size); continue }
            }
            if (paxSize >= 0) size = paxSize
            var name = str(h, 0, 100)
            if (str(h, 257, 5) == "ustar") { val p = str(h, 345, 155); if (p.isNotEmpty()) name = "$p/$name" }
            name = paxPath ?: longName ?: name
            val link = paxLink ?: longLink ?: str(h, 157, 100)
            val isData = type == '0' || type == '\u0000' || type == '7'
            remaining = if (isData) size else 0
            pad = if (isData) (512 - size % 512) % 512 else 0
            return Entry(name, link, num(h, 100, 8).toInt(), size, type)
        }
    }

    fun copyTo(out: OutputStream) {
        val buf = ByteArray(65536)
        while (remaining > 0) {
            val n = ins.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n < 0) throw EOFException()
            out.write(buf, 0, n); remaining -= n
        }
    }
}
