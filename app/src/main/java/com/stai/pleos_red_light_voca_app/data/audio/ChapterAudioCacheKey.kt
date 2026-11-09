package com.stai.pleos_red_light_voca_app.data.audio

import java.io.File
import java.security.MessageDigest

/**
 * 병합한 챕터 WAV의 캐시 경로. 음원 바이트·조각 순서·무음 길이·병합 방식이 바뀌면 다른 파일이 된다.
 *
 * 이름: `<safe(contentId)>/<safe(chapterId)>-<ID 해시8>-<내용 해시16>.wav`. ID 해시는 원래 contentId·chapterId로 만들어,
 * [safe]로 바꾼 이름이 같은 다른 챕터(예: 한글 ID)끼리 서로의 캐시를 지우지 않게 한다.
 */
object ChapterAudioCacheKey {
    /** 병합 방식을 바꾸면 올린다. */
    const val FORMAT_VERSION = "asset-v1"
    private const val MAX_SAFE_LENGTH = 64

    fun safe(s: String): String {
        val r = s.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
        return r.take(MAX_SAFE_LENGTH).ifEmpty { "_" }
    }

    fun hash16(pieces: List<AudioPiece>): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(FORMAT_VERSION.toByteArray(Charsets.UTF_8))
        for (p in pieces) {
            val part = when (p) {
                is AudioPiece.Wav -> "W" + hex(MessageDigest.getInstance("SHA-256").digest(p.bytes))
                is AudioPiece.Silence -> "S" + p.durationMs
            }
            d.update(0)
            d.update(part.toByteArray(Charsets.UTF_8))
        }
        return hex(d.digest()).take(16)
    }

    /** 챕터 캐시 이름의 앞부분(내용 해시 앞까지). 같은 챕터의 캐시·임시 파일은 모두 이것으로 시작한다. */
    fun chapterPrefix(contentId: String, chapterId: String): String {
        val id = hex(MessageDigest.getInstance("SHA-256").digest("$contentId\u0000$chapterId".toByteArray(Charsets.UTF_8))).take(8)
        return "${safe(chapterId)}-$id-"
    }

    fun file(cacheRoot: File, contentId: String, chapterId: String, pieces: List<AudioPiece>): File =
        File(File(cacheRoot, safe(contentId)), "${chapterPrefix(contentId, chapterId)}${hash16(pieces)}.wav")

    /** 같은 챕터의 캐시 파일인가([file]과 같은 이름 규칙, 내용 해시만 다름). */
    fun isCacheOf(name: String, contentId: String, chapterId: String): Boolean =
        Regex("""^${Regex.escape(chapterPrefix(contentId, chapterId))}[0-9a-f]{16}\.wav$""").matches(name)

    /** 같은 챕터 캐시를 쓰다 남은 임시 파일인가(`<캐시 이름>.<uuid>.tmp`). */
    fun isTempOf(name: String, contentId: String, chapterId: String): Boolean =
        Regex("""^${Regex.escape(chapterPrefix(contentId, chapterId))}[0-9a-f]{16}\.wav\..+\.tmp$""").matches(name)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
