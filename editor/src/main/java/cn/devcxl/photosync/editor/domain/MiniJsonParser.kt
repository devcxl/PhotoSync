package cn.devcxl.photosync.editor.domain

/**
 * Lightweight recursive-descent JSON parser used by [EditGraphJsonCodec] so domain tests
 * run on pure JVM without `android.jar` `org.json` stubs.
 */
internal class MiniJsonParser(private val src: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= src.length) return null
        return when (src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        pos++ // '{'
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return map
        }
        while (pos < src.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            if (pos < src.length && src[pos] == ':') pos++
            map[key] = parseValue()
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == '}') {
                pos++
                break
            } else {
                break
            }
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        val list = ArrayList<Any?>()
        pos++ // '['
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return list
        }
        while (pos < src.length) {
            list.add(parseValue())
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == ']') {
                pos++
                break
            } else {
                break
            }
        }
        return list
    }

    private fun parseString(): String {
        if (src[pos] == '"') pos++
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') break
            if (c == '\\' && pos < src.length) {
                when (val esc = src[pos++]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseBoolean(): Boolean {
        return if (src.startsWith("true", pos)) {
            pos += 4
            true
        } else {
            pos += 5
            false
        }
    }

    private fun parseNull(): Any? {
        pos += 4
        return null
    }

    private fun parseNumber(): Number {
        val start = pos
        while (pos < src.length && src[pos] in "0123456789+-.eE") {
            pos++
        }
        val token = src.substring(start, pos)
        return if ('.' in token || 'e' in token || 'E' in token) {
            token.toDoubleOrNull() ?: 0.0
        } else {
            token.toLongOrNull() ?: 0L
        }
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) {
            pos++
        }
    }
}
