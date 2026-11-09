package com.stai.pleos_red_light_voca_app.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class WavCodecTest {
    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun le16(v: Int) = ByteArray(2) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun chunk(id: String, body: ByteArray, sizeOverride: Long? = null): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id.toByteArray()); o.write(le32(sizeOverride ?: body.size.toLong())); o.write(body)
        if (body.size % 2 == 1 && sizeOverride == null) o.write(0)
        return o.toByteArray()
    }

    private fun fmt(rate: Int, ch: Int, bits: Int, format: Int = 1): ByteArray =
        chunk("fmt ", le16(format) + le16(ch) + le32(rate.toLong()) + le32((rate * ch * bits / 8).toLong()) +
            le16(ch * bits / 8) + le16(bits))

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WAVE".toByteArray() + chunks.fold(ByteArray(0)) { a, c -> a + c }
        return "RIFF".toByteArray() + le32(body.size.toLong()) + body
    }

    private fun s16(vararg v: Int) = v.fold(ByteArray(0)) { a, x -> a + le16(x) }

    private fun tone(rate: Int, ch: Int, frames: Int) =
        PcmAudio(rate, ch, ShortArray(frames * ch) { ((it % 100) * 100).toShort() })

    @Test fun decodeWithListChunk() {
        val wav = riff(fmt(22050, 1, 16), chunk("LIST", "INFOabc".toByteArray()), chunk("data", s16(1, -2, 3)))
        val a = WavCodec.decode(wav)
        assertEquals(22050, a.sampleRate); assertEquals(1, a.channels)
        assertArrayEquals(shortArrayOf(1, -2, 3), a.samples)
    }

    @Test fun decode8bit() {
        val a = WavCodec.decode(riff(fmt(8000, 1, 8), chunk("data", byteArrayOf(128.toByte(), 255.toByte(), 0))))
        assertArrayEquals(shortArrayOf(0, (127 shl 8).toShort(), (-128 shl 8).toShort()), a.samples)
    }

    @Test fun oddChunkPadding() {
        val wav = riff(fmt(8000, 1, 16), chunk("junk", byteArrayOf(1, 2, 3)), chunk("data", s16(5, 6)))
        assertArrayEquals(shortArrayOf(5, 6), WavCodec.decode(wav).samples)
    }

    @Test fun streamingDataSize() {
        for (size in listOf(0xFFFFFFFFL, 0L, 1_000_000L)) {
            val wav = riff(fmt(8000, 1, 16), chunk("data", s16(7, 8, 9), sizeOverride = size))
            assertArrayEquals(shortArrayOf(7, 8, 9), WavCodec.decode(wav).samples)
        }
    }

    @Test fun extensible() {
        val guid = le16(1) + ByteArray(14)
        val body = le16(0xFFFE) + le16(1) + le32(8000) + le32(16000) + le16(2) + le16(16) +
            le16(22) + le16(16) + le32(4) + guid
        val a = WavCodec.decode(riff(chunk("fmt ", body), chunk("data", s16(4))))
        assertArrayEquals(shortArrayOf(4), a.samples)
    }

    private fun le32i(v: Int) = le32(v.toLong() and 0xFFFFFFFFL)
    private fun f32(vararg v: Float) = v.fold(ByteArray(0)) { a, x -> a + le32i(java.lang.Float.floatToIntBits(x)) }

    @Test fun decode24bit() {
        // 0x7FFFFF -> 0x7FFF, -0x800000 -> -0x8000, 0x123456 -> 0x1234
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0x80.toByte(), 0x56, 0x34, 0x12)
        val a = WavCodec.decode(riff(fmt(8000, 1, 24), chunk("data", data)))
        assertArrayEquals(shortArrayOf(0x7FFF, -0x8000, 0x1234), a.samples)
    }

    @Test fun decode32bitInt() {
        val data = le32i(0x7FFFFFFF) + le32i(Int.MIN_VALUE) + le32i(0x12345678)
        val a = WavCodec.decode(riff(fmt(8000, 1, 32), chunk("data", data)))
        assertArrayEquals(shortArrayOf(0x7FFF, -0x8000, 0x1234), a.samples)
    }

    @Test fun decode32bitFloatClamps() {
        val data = f32(1.0f, -1.0f, 0.5f, 2.0f, -3.0f, 0f)
        val a = WavCodec.decode(riff(fmt(8000, 1, 32, 3), chunk("data", data)))
        assertArrayEquals(shortArrayOf(32767, -32767, 16384, 32767, -32767, 0), a.samples)
    }

    @Test fun decodeExtensibleFloat() {
        val guid = le16(3) + ByteArray(14)
        val body = le16(0xFFFE) + le16(1) + le32(8000) + le32(32000) + le16(4) + le16(32) +
            le16(22) + le16(32) + le32(4) + guid
        val a = WavCodec.decode(riff(chunk("fmt ", body), chunk("data", f32(0.5f))))
        assertArrayEquals(shortArrayOf(16384), a.samples)
    }

    @Test fun unsupportedFormats() {
        for ((bits, fmtTag) in listOf(64 to 1, 64 to 3, 16 to 3, 24 to 3, 16 to 2)) {
            try {
                WavCodec.decode(riff(fmt(8000, 1, bits, fmtTag), chunk("data", ByteArray(8))))
                fail("예외 기대")
            } catch (e: IllegalArgumentException) {
                // decode의 형식 판정에서 나온 예외인지: 정확히 IllegalArgumentException이고 미지원 문구가 있다.
                assertEquals(IllegalArgumentException::class.java, e.javaClass)
                assertTrue(e.message, e.message?.contains("지원") == true)
            }
        }
    }

    @Test fun channelConversion() {
        val st = PcmAudio(8000, 2, shortArrayOf(10, 20, 30, 41))
        assertArrayEquals(shortArrayOf(15, 36), WavCodec.convertChannels(st, 1).samples)
        val mono = PcmAudio(8000, 1, shortArrayOf(5, 6))
        assertArrayEquals(shortArrayOf(5, 5, 6, 6), WavCodec.convertChannels(mono, 2).samples)
        assertArrayEquals(shortArrayOf(5, 5, 5, 6, 6, 6), WavCodec.convertChannels(mono, 3).samples)
        assertArrayEquals(shortArrayOf(10, 20, 20, 30, 41, 41), WavCodec.convertChannels(st, 3).samples)
    }

    @Test fun resampleLengthAndEnds() {
        val src = PcmAudio(16000, 1, ShortArray(1600) { it.toShort() })
        val up = WavCodec.resample(src, 24000)
        assertEquals(2400, up.frames); assertEquals(24000, up.sampleRate)
        assertEquals(0, up.samples.first().toInt()); assertEquals(1599, up.samples.last().toInt())
        val down = WavCodec.resample(src, 8000)
        assertEquals(800, down.frames); assertEquals(1599, down.samples.last().toInt())
    }

    @Test fun silenceLength() {
        val s = WavCodec.silence(22050, 1, 600)
        assertEquals(13230, s.frames)
        assertEquals(13230 * 2, WavCodec.silence(22050, 2, 600).samples.size)
    }

    @Test fun assembleChapter() {
        val en = WavCodec.encode(tone(22050, 1, 1000))
        val ko = WavCodec.encode(tone(16000, 2, 800))
        val out = ChapterAudioAssembler.assemble(listOf(
            AudioPiece.Wav(en), AudioPiece.Silence(600), AudioPiece.Wav(ko),
            AudioPiece.Silence(600), AudioPiece.Wav(en),
        ))
        val d = WavCodec.decode(out)
        assertEquals(22050, d.sampleRate); assertEquals(1, d.channels)
        val expected = 1000 + 13230 + 1103 + 13230 + 1000
        assertEquals(expected, d.frames)
        val dataLen = out.size - 44
        assertEquals(expected * 2, dataLen)
        assertEquals(dataLen.toLong(), le(out, 40))
        assertEquals(out.size - 8L, le(out, 4))
    }

    private fun le(b: ByteArray, o: Int): Long =
        (0..3).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    @Test fun assembleWithoutSpeechFails() {
        try { ChapterAudioAssembler.assemble(listOf(AudioPiece.Silence(100))); fail() }
        catch (e: IllegalArgumentException) { /* ok */ }
    }

    @Test fun roundTrip() {
        val a = tone(44100, 2, 500)
        val d = WavCodec.decode(WavCodec.encode(a))
        assertEquals(44100, d.sampleRate); assertEquals(2, d.channels)
        assertArrayEquals(a.samples, d.samples)
    }

    @Test fun cacheKey() {
        val root = File("/cache")
        val en = WavCodec.encode(tone(22050, 1, 100))
        val ko = WavCodec.encode(tone(22050, 1, 200))
        fun pieces(e: ByteArray = en, k: ByteArray = ko, silence: Long = 600) = listOf(
            AudioPiece.Wav(e), AudioPiece.Silence(silence), AudioPiece.Wav(k), AudioPiece.Silence(silence), AudioPiece.Wav(e),
        )
        fun f(p: List<AudioPiece> = pieces()) = ChapterAudioCacheKey.file(root, "day/1", "ch 1", p)
        assertEquals(f(), f(pieces(e = en.copyOf(), k = ko.copyOf())))
        assertNotEquals(f(), f(pieces(k = WavCodec.encode(tone(22050, 1, 300)))))
        assertNotEquals(f(), f(pieces(e = ko, k = en)))
        assertNotEquals(f(), f(pieces(silence = 500)))
        assertEquals("day_1", f().parentFile!!.name)
        assertTrue(f().name.matches(Regex("ch_1-[0-9a-f]{8}-[0-9a-f]{16}\\.wav")))
        assertTrue(ChapterAudioCacheKey.isCacheOf(f().name, "day/1", "ch 1"))
        assertFalse(ChapterAudioCacheKey.isCacheOf(f().name, "day/1", "ch"))
        assertFalse(ChapterAudioCacheKey.isCacheOf(f().name, "day/2", "ch 1"))
        assertTrue(ChapterAudioCacheKey.isTempOf("${f().name}.abc.tmp", "day/1", "ch 1"))
        assertFalse(ChapterAudioCacheKey.isTempOf("${f().name}.abc.tmp", "day/1", "ch"))
        // safe()로 같은 이름이 되는 다른 ID(한글)는 ID 해시로 구분된다
        assertEquals(ChapterAudioCacheKey.safe("단어1"), ChapterAudioCacheKey.safe("낱말1"))
        val a = ChapterAudioCacheKey.file(root, "d", "단어1", pieces()).name
        assertFalse(ChapterAudioCacheKey.isCacheOf(a, "d", "낱말1"))
        assertEquals(64, ChapterAudioCacheKey.safe("a".repeat(200)).length)
        assertEquals("a_b_c.d-e", ChapterAudioCacheKey.safe("a/b가c.d-e"))
    }
}
