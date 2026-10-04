package io.github.kkursun.openplay.phone

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Apple property lists, as AirPlay 1 sends them: binary ones written, binary or XML ones read. */
object Plist {
    /** A binary plist of a dictionary of strings, numbers and booleans (all /play takes). */
    fun write(dict: Map<String, Any>): ByteArray {
        val objects = mutableListOf<Any>(dict)
        dict.forEach { (k, v) -> objects += k; objects += v }
        val keys = dict.keys.toList()
        val body = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        val header = "bplist00".toByteArray()
        val ref = 1 // one byte per object reference: under 256 objects
        for (o in objects) {
            offsets += header.size + body.size()
            when (o) {
                is Map<*, *> -> {
                    marker(body, 0xD, o.size)
                    keys.indices.forEach { body.write(1 + 2 * it) }
                    keys.indices.forEach { body.write(2 + 2 * it) }
                }
                is String -> if (o.all { it.code < 128 }) {
                    marker(body, 0x5, o.length)
                    body.write(o.toByteArray(Charsets.US_ASCII))
                } else {
                    marker(body, 0x6, o.length)
                    body.write(o.toByteArray(Charsets.UTF_16BE))
                }
                is Boolean -> body.write(if (o) 0x09 else 0x08)
                is Double, is Float -> {
                    body.write(0x23)
                    body.write(ByteBuffer.allocate(8).putDouble((o as Number).toDouble()).array())
                }
                is Number -> {
                    body.write(0x13)
                    body.write(ByteBuffer.allocate(8).putLong(o.toLong()).array())
                }
                else -> throw IllegalArgumentException("Can't write ${o::class.simpleName} to a plist")
            }
        }
        require(objects.size < 256)
        val tableAt = header.size + body.size()
        val offsetSize = if (tableAt < 256) 1 else if (tableAt < 65536) 2 else 4
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(body.toByteArray())
        for (o in offsets) for (i in offsetSize - 1 downTo 0) out.write(o ushr (8 * i))
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(ref)
        out.write(ByteBuffer.allocate(24).putLong(objects.size.toLong()).putLong(0).putLong(tableAt.toLong()).array())
        return out.toByteArray()
    }

    private fun marker(out: ByteArrayOutputStream, kind: Int, count: Int) {
        if (count < 15) {
            out.write(kind shl 4 or count)
        } else {
            out.write(kind shl 4 or 0xF)
            out.write(0x11) // 2-byte int follows
            out.write(count ushr 8)
            out.write(count and 0xFF)
        }
    }

    /** A plist, binary or XML, as maps, lists, strings, longs, doubles, booleans and byte arrays. */
    fun read(data: ByteArray): Any? =
        if (data.size >= 8 && String(data, 0, 8, Charsets.US_ASCII) == "bplist00") Binary(data).root() else Xml(String(data, Charsets.UTF_8)).root()

    private class Binary(val data: ByteArray) {
        val b = ByteBuffer.wrap(data)
        val offsetSize = data[data.size - 26].toInt()
        val refSize = data[data.size - 25].toInt()
        val count = b.getLong(data.size - 24).toInt()
        val top = b.getLong(data.size - 16).toInt()
        val table = b.getLong(data.size - 8).toInt()

        fun uint(at: Int, size: Int): Long = (0 until size).fold(0L) { acc, i -> acc shl 8 or (data[at + i].toLong() and 0xFF) }

        fun root(): Any? = obj(top, 0)

        fun obj(index: Int, depth: Int): Any? {
            require(index in 0 until count && depth < 32) { "bad plist" }
            var at = uint(table + index * offsetSize, offsetSize).toInt()
            val m = data[at].toInt() and 0xFF
            val kind = m ushr 4
            var n = m and 0xF
            fun length() {
                if (n == 0xF) {
                    val size = 1 shl (data[at + 1].toInt() and 0xF)
                    n = uint(at + 2, size).toInt()
                    at += 1 + size
                }
                at += 1
            }
            return when (kind) {
                0x0 -> when (m) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> {
                    val size = 1 shl n
                    if (size == 8) b.getLong(at + 1) else uint(at + 1, size)
                }
                0x2 -> if (n == 2) b.getFloat(at + 1).toDouble() else b.getDouble(at + 1)
                0x3 -> b.getDouble(at + 1) // date: seconds since 2001
                0x4 -> { length(); data.copyOfRange(at, at + n) }
                0x5 -> { length(); String(data, at, n, Charsets.US_ASCII) }
                0x6 -> { length(); String(data, at, 2 * n, Charsets.UTF_16BE) }
                0x8 -> uint(at + 1, n + 1) // UID
                0xA -> { length(); (0 until n).map { obj(uint(at + it * refSize, refSize).toInt(), depth + 1) } }
                0xD -> {
                    length()
                    (0 until n).associate {
                        obj(uint(at + it * refSize, refSize).toInt(), depth + 1).toString() to
                            obj(uint(at + (n + it) * refSize, refSize).toInt(), depth + 1)
                    }
                }
                else -> null
            }
        }
    }

    private class Xml(text: String) {
        val tokens = Regex("<(/?)(\\w+)(/?)>|([^<]+)").findAll(text.substringAfter("<plist").substringAfter('>'))
            .filter { it.groupValues[2].isNotEmpty() || it.groupValues[4].isNotBlank() }.iterator() // no indentation

        fun root(): Any? = value(tokens.next())

        fun content(name: String): String {
            val sb = StringBuilder()
            while (true) {
                val t = tokens.next()
                if (t.groupValues[1] == "/" && t.groupValues[2] == name) return sb.toString()
                sb.append(t.groupValues[4])
            }
        }

        fun value(t: MatchResult): Any? {
            val (_, close, name, empty) = t.groupValues
            if (close == "/") throw IllegalArgumentException("bad plist")
            if (empty == "/") return when (name) { "true" -> true; "false" -> false; "dict" -> emptyMap<String, Any?>(); "array" -> emptyList<Any?>(); "string" -> ""; else -> null }
            return when (name) {
                "dict" -> buildMap {
                    while (true) {
                        val k = tokens.next()
                        if (k.groupValues[1] == "/") break
                        put(unescape(content("key")), value(tokens.next()))
                    }
                }
                "array" -> buildList {
                    while (true) {
                        val v = tokens.next()
                        if (v.groupValues[1] == "/") break
                        add(value(v))
                    }
                }
                "string" -> unescape(content(name))
                "integer" -> content(name).trim().toLong()
                "real" -> content(name).trim().toDouble()
                "true" -> true.also { content(name) }
                "false" -> false.also { content(name) }
                else -> content(name)
            }
        }

        fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
