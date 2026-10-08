package io.github.panuwattegif.readyproofclean.core

/**
 * Minimal JSON reader/writer with no dependencies, used for the settings and the capture log.
 *
 * Writing: Map, Iterable/Array, String, Number, Boolean and null. Map entries whose value is
 * null are omitted, so readers must treat a missing key as "null / default".
 * Reading: objects become LinkedHashMap<String, Any?>, arrays List<Any?>, whole numbers Long,
 * other numbers Double.
 */
object Json {
    class ParseException(message: String) : RuntimeException(message)

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("trailing data at ${p.pos}")
        return v
    }

    /** Parses and requires a top-level object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ParseException("not a JSON object")

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v.toString()) else sb.append("null")
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (value == null) continue
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, e)
                }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, v.asList())
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    val h = Integer.toHexString(c.code)
                    sb.append("\\u").append("0000".substring(h.length)).append(h)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]

        private fun next(): Char = if (atEnd()) throw ParseException("unexpected end") else s[pos++]

        private fun expect(c: Char) {
            if (next() != c) throw ParseException("expected '$c' at ${pos - 1}")
        }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw ParseException("unexpected end")
            val c = s[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> readLiteral("true", true)
                c == 'f' -> readLiteral("false", false)
                c == 'n' -> readLiteral("null", null)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> throw ParseException("unexpected '$c' at $pos")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            expect('{')
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("expected key at $pos")
                val key = readString()
                skipWs()
                expect(':')
                out[key] = readValue()
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw ParseException("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            expect('[')
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(readValue())
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw ParseException("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) throw ParseException("bad unicode escape at $pos")
                            val code = s.substring(pos, pos + 4).toIntOrNull(16)
                                ?: throw ParseException("bad unicode escape at $pos")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw ParseException("bad escape '\\$e' at ${pos - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' ||
                    s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            val t = s.substring(start, pos)
            val isInt = t.none { it == '.' || it == 'e' || it == 'E' }
            if (isInt) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw ParseException("bad number '$t' at $start")
        }
    }
}

/** Typed accessors for maps produced by [Json.parse]; wrong or missing types give null. */
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
fun Map<String, Any?>.strList(key: String): List<String>? =
    (this[key] as? List<*>)?.mapNotNull { it as? String }

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
