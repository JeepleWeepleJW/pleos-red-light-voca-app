package com.stai.pleos_red_light_voca_app.data.catalog.json

/**
 * 최소 JSON 파서(순수 Kotlin). 결과 타입:
 * 객체 -> Map<String, Any?>(순서 유지), 배열 -> List<Any?>, 문자열 -> String,
 * 정수 -> Long, 소수·지수 -> Double, true/false -> Boolean, null -> null.
 */
object MiniJson {
    /** @throws IllegalArgumentException 잘못된 입력. 메시지 끝에 입력 문자열 안의 0 기준 위치 `(offset N)`. */
    fun parse(text: String): Any? = Reader(text).readDocument()

    private class Reader(private val s: String) {
        private var i = 0

        fun readDocument(): Any? {
            skipWs()
            if (i >= s.length) fail("empty input")
            val v = readValue(0)
            skipWs()
            if (i < s.length) fail("unexpected trailing character '${s[i]}'")
            return v
        }

        private fun fail(msg: String, at: Int = i): Nothing = throw IllegalArgumentException("$msg (offset $at)")

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (i >= s.length) fail("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                if (i >= s.length) fail("unexpected end of input in object")
                if (s[i] != '"') fail("expected string key")
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                skipWs()
                if (map.containsKey(key)) fail("duplicate key '$key'")
                map[key] = readValue(depth + 1)
                skipWs()
                if (i >= s.length) fail("unexpected end of input in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(readValue(depth + 1))
                skipWs()
                if (i >= s.length) fail("unexpected end of input in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            val start = i
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string", start)
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c < ' ' -> fail("control character in string")
                    c == '\\' -> {
                        i++
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail("incomplete \\u escape")
                                var code = 0
                                for (k in 1..4) {
                                    val d = Character.digit(s[i + k], 16)
                                    if (d < 0) fail("invalid \\u escape", i + k)
                                    code = code * 16 + d
                                }
                                sb.append(code.toChar()) // 서로게이트 쌍은 UTF-16 그대로 이어 붙으면 된다
                                i += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("invalid number", start)
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else fail("invalid number", start)
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) fail("invalid number fraction", start)
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) fail("invalid number exponent", start)
            }
            val t = s.substring(start, i)
            if (isDouble) return t.toDouble()
            return t.toLongOrNull() ?: t.toDouble()
        }
    }

    private const val MAX_DEPTH = 200
}
