package com.stai.pleos_red_light_voca_app.data.catalog.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {
    @Test fun duplicateKeyInSameObjectFails() {
        assertFails("""{"a":1,"a":2}""")
        MiniJson.parse("""{"a":{"a":1}}""") // 다른 객체의 같은 키는 허용
    }

    @Test fun literals() {
        assertEquals(true, MiniJson.parse("true"))
        assertEquals(false, MiniJson.parse(" false "))
        assertNull(MiniJson.parse("null"))
    }

    @Test fun integersAreLong() {
        assertEquals(2L, MiniJson.parse("2"))
        assertEquals(-17L, MiniJson.parse("-17"))
        assertEquals(0L, MiniJson.parse("0"))
    }

    @Test fun fractionsAndExponentsAreDouble() {
        assertEquals(1.5, MiniJson.parse("1.5"))
        assertEquals(1000.0, MiniJson.parse("1e3"))
        assertEquals(0.025, MiniJson.parse("2.5E-2"))
        assertTrue(MiniJson.parse("2.0") is Double)
    }

    @Test fun simpleEscapes() {
        assertEquals("a\"b\\c/d\b\u000C\n\r\t", MiniJson.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""))
    }

    @Test fun unicodeEscapesAndRawHangul() {
        assertEquals("가", MiniJson.parse("\"\\uAC00\""))
        assertEquals("신호 가", MiniJson.parse("\"신호 \\uac00\""))
        assertEquals("A", MiniJson.parse("\"\\u0041\""))
    }

    @Test fun surrogatePair() {
        val v = MiniJson.parse("\"\\uD83D\\uDE00\"") as String
        assertEquals("\uD83D\uDE00", v)
        assertEquals(1, v.codePointCount(0, v.length))
        assertEquals("😀", MiniJson.parse("\"😀\""))
    }

    @Test fun nestedStructuresKeepOrder() {
        val v = MiniJson.parse("""{"z":1,"a":[1,{"k":[]},null],"m":{}}""") as Map<*, *>
        assertEquals(listOf("z", "a", "m"), v.keys.toList())
        val a = v["a"] as List<*>
        assertEquals(1L, a[0])
        assertEquals(emptyList<Any?>(), (a[1] as Map<*, *>)["k"])
        assertNull(a[2])
        assertEquals(emptyMap<String, Any?>(), v["m"])
    }

    @Test fun whitespaceAround() {
        assertEquals(listOf(1L, 2L), MiniJson.parse(" \n\t[ 1 ,\r\n 2 ] \n"))
    }

    /** MiniJson의 fail()에서 나온 예외인지(정확히 IllegalArgumentException, 메시지 끝 "(offset N)") 확인한다. */
    private fun assertFails(text: String): IllegalArgumentException {
        val e = assertThrows(IllegalArgumentException::class.java) { MiniJson.parse(text) }
        assertEquals(IllegalArgumentException::class.java, e.javaClass)
        assertTrue(e.message, Regex("""\(offset \d+\)$""").containsMatchIn(e.message ?: ""))
        return e
    }

    @Test fun trailingCommaFails() {
        assertFails("[1,2,]")
        assertFails("""{"a":1,}""")
    }

    @Test fun missingQuotesFail() {
        assertFails("""{a:1}""")
        assertFails("\"abc")
    }

    @Test fun trailingGarbageFailsWithOffset() {
        val e = assertFails("{} x")
        assertTrue(e.message!!.endsWith("(offset 3)"))
        assertFails("1 2")
    }

    @Test fun emptyInputFails() {
        assertFails("")
        assertFails("   ")
    }

    @Test fun otherMalformedFail() {
        assertFails("[1 2]")
        assertFails("""{"a" 1}""")
        assertFails("tru")
        assertFails("01x")
        assertFails("-")
        assertFails("1.")
        assertFails("\"\\x\"")
        assertFails("\"\\u12\"")
        assertFails("\"a\nb\"")
        assertFails("[")
    }
}
