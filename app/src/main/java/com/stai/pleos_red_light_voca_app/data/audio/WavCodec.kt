package com.stai.pleos_red_light_voca_app.data.audio

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 16비트 interleaved PCM. samples.size = frames * channels. */
class PcmAudio(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val frames: Int get() = samples.size / channels
}

/** Android 의존 없는 WAV 디코드/인코드/변환. 디코드는 8/16/24/32비트 정수와 32비트 float PCM을 16비트로 변환해 읽는다. */
object WavCodec {
    const val HEADER_SIZE = 44

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    /** @throws IllegalArgumentException RIFF/WAVE가 아니거나 청크가 잘못됐거나 지원하지 않는 형식 */
    fun decode(bytes: ByteArray): PcmAudio {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") {
            throw IllegalArgumentException("RIFF/WAVE 헤더가 아님")
        }
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var isFloat = false
        var fmtFound = false
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = u32(bytes, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                if (size < 16 || body + 16 > bytes.size) throw IllegalArgumentException("fmt 청크가 잘못됨")
                var format = u16(bytes, body)
                channels = u16(bytes, body + 2)
                sampleRate = u32(bytes, body + 4).toInt()
                bits = u16(bytes, body + 14)
                if (format == 0xFFFE) {
                    if (size < 40 || body + 26 > bytes.size) throw IllegalArgumentException("EXTENSIBLE fmt가 짧음")
                    format = u16(bytes, body + 24) // 하위 형식 GUID의 첫 2바이트
                }
                isFloat = format == 3
                if (format != 1 && format != 3) throw IllegalArgumentException("PCM/float이 아닌 형식 지원 안 함: format=$format")
                if (isFloat) {
                    if (bits != 32) throw IllegalArgumentException("지원하지 않는 float 비트 수: $bits (32만 지원)")
                } else if (bits != 8 && bits != 16 && bits != 24 && bits != 32) {
                    throw IllegalArgumentException("지원하지 않는 비트 수: $bits (8/16/24/32만 지원)")
                }
                if (channels < 1 || sampleRate < 1) throw IllegalArgumentException("채널/샘플레이트가 잘못됨")
                fmtFound = true
            } else if (id == "data") {
                if (!fmtFound) throw IllegalArgumentException("fmt 청크 전에 data 청크가 있음")
                val avail = (bytes.size - body).toLong()
                val len = if (size == 0L || size == 0xFFFFFFFFL || size > avail) avail else size
                return pcm(bytes, body, len.toInt(), sampleRate, channels, bits, isFloat)
            }
            val next = body.toLong() + size + (size and 1L)
            if (next > bytes.size) break
            pos = next.toInt()
        }
        throw IllegalArgumentException(if (fmtFound) "data 청크 없음" else "fmt 청크 없음")
    }

    private fun pcm(b: ByteArray, off: Int, len: Int, rate: Int, ch: Int, bits: Int, isFloat: Boolean): PcmAudio {
        val bytesPer = bits / 8
        val frameBytes = bytesPer * ch
        val usable = len / frameBytes * frameBytes
        val count = usable / bytesPer
        val out = ShortArray(count)
        when {
            isFloat -> for (i in 0 until count) {
                val f = java.lang.Float.intBitsToFloat(u32(b, off + i * 4).toInt())
                val c = if (f.isNaN()) 0f else f.coerceIn(-1f, 1f)
                out[i] = (c * 32767f).roundToInt().toShort()
            }
            bits == 16 -> for (i in 0 until count) out[i] = u16(b, off + i * 2).toShort()
            // 24/32비트 정수: 상위 16비트만 취한다(little-endian이라 마지막 두 바이트).
            bits == 24 -> for (i in 0 until count) out[i] = u16(b, off + i * 3 + 1).toShort()
            bits == 32 -> for (i in 0 until count) out[i] = u16(b, off + i * 4 + 2).toShort()
            else -> for (i in 0 until count) out[i] = (((b[off + i].toInt() and 0xFF) - 128) shl 8).toShort()
        }
        return PcmAudio(rate, ch, out)
    }

    fun convertChannels(a: PcmAudio, target: Int): PcmAudio {
        require(target >= 1)
        if (a.channels == target) return a
        val frames = a.frames
        val src = a.channels
        val out = ShortArray(frames * target)
        for (f in 0 until frames) {
            if (target == 1) {
                var sum = 0
                for (c in 0 until src) sum += a.samples[f * src + c]
                out[f] = (sum.toFloat() / src).roundToInt().toShort()
            } else {
                for (c in 0 until target) {
                    out[f * target + c] = a.samples[f * src + minOf(c, src - 1)]
                }
            }
        }
        return PcmAudio(a.sampleRate, target, out)
    }

    /** 선형 보간. 첫·끝 프레임 값을 보존한다. */
    fun resample(a: PcmAudio, targetRate: Int): PcmAudio {
        require(targetRate >= 1)
        if (a.sampleRate == targetRate) return a
        val n = a.frames
        if (n == 0) return PcmAudio(targetRate, a.channels, ShortArray(0))
        val m = maxOf(1, (n.toLong() * targetRate / a.sampleRate.toDouble()).roundToLong().toInt())
        val ch = a.channels
        val out = ShortArray(m * ch)
        for (i in 0 until m) {
            val pos = if (m == 1) 0.0 else i.toDouble() * (n - 1) / (m - 1)
            val i0 = pos.toInt().coerceAtMost(n - 1)
            val i1 = minOf(i0 + 1, n - 1)
            val t = pos - i0
            for (c in 0 until ch) {
                val s0 = a.samples[i0 * ch + c].toInt()
                val s1 = a.samples[i1 * ch + c].toInt()
                out[i * ch + c] = (s0 + (s1 - s0) * t).roundToInt().toShort()
            }
        }
        return PcmAudio(targetRate, ch, out)
    }

    fun convert(a: PcmAudio, rate: Int, channels: Int): PcmAudio =
        resample(convertChannels(a, channels), rate)

    fun silence(rate: Int, channels: Int, ms: Long): PcmAudio {
        val frames = (rate.toDouble() * ms / 1000.0).roundToLong().toInt()
        return PcmAudio(rate, channels, ShortArray(frames * channels))
    }

    /** 형식이 모두 같아야 한다. */
    fun concat(parts: List<PcmAudio>): PcmAudio {
        require(parts.isNotEmpty())
        val first = parts[0]
        require(parts.all { it.sampleRate == first.sampleRate && it.channels == first.channels }) { "형식 불일치" }
        val out = ShortArray(parts.sumOf { it.samples.size })
        var o = 0
        for (p in parts) { p.samples.copyInto(out, o); o += p.samples.size }
        return PcmAudio(first.sampleRate, first.channels, out)
    }

    fun encode(a: PcmAudio): ByteArray {
        val dataLen = a.samples.size * 2
        val out = ByteArrayOutputStream(HEADER_SIZE + dataLen)
        fun w32(v: Int) { for (i in 0..3) out.write((v ushr (8 * i)) and 0xFF) }
        fun w16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1)); w32(36 + dataLen)
        out.write("WAVE".toByteArray(Charsets.ISO_8859_1))
        out.write("fmt ".toByteArray(Charsets.ISO_8859_1)); w32(16)
        w16(1); w16(a.channels); w32(a.sampleRate)
        w32(a.sampleRate * a.channels * 2); w16(a.channels * 2); w16(16)
        out.write("data".toByteArray(Charsets.ISO_8859_1)); w32(dataLen)
        for (s in a.samples) w16(s.toInt())
        return out.toByteArray()
    }
}

sealed interface AudioPiece {
    class Wav(val bytes: ByteArray) : AudioPiece
    class Silence(val durationMs: Long) : AudioPiece
}

/** 음성 WAV와 무음을 순서대로 이어 붙여 WAV 1개로 만든다. 목표 형식 = 첫 음성 조각의 형식. */
object ChapterAudioAssembler {
    fun assemble(pieces: List<AudioPiece>): ByteArray {
        val decoded = pieces.map { if (it is AudioPiece.Wav) WavCodec.decode(it.bytes) else null }
        val target = decoded.firstOrNull { it != null }
            ?: throw IllegalArgumentException("음성 조각이 하나도 없음")
        val parts = pieces.mapIndexed { i, p ->
            when (p) {
                is AudioPiece.Wav -> WavCodec.convert(decoded[i]!!, target.sampleRate, target.channels)
                is AudioPiece.Silence -> WavCodec.silence(target.sampleRate, target.channels, p.durationMs)
            }
        }
        return WavCodec.encode(WavCodec.concat(parts))
    }
}
