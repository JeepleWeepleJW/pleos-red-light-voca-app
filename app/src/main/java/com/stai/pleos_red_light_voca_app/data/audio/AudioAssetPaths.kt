package com.stai.pleos_red_light_voca_app.data.audio

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterScript
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterSegment
import java.security.MessageDigest

/**
 * 단어 음원 파일 규칙(assets 기준). 생성 스크립트 `tools/generate_audio.sh`와 같아야 한다.
 *
 * - `audio/<contentId>/<chapterId>.en.wav`: 영어 단어(`word`)
 * - `audio/<contentId>/<chapterId>.ko.wav`: 한국어 뜻(`meaningKo`)
 * - `audio/manifest.tsv`: 음원별 텍스트 지문. `#`로 시작하는 줄은 주석, 나머지는
 *   `<contentId>/<chapterId>.<en|ko>.wav<TAB><텍스트 UTF-8 SHA-256 소문자 hex><TAB><음성 이름>`.
 *   catalog 문구를 고치고 음원을 다시 만들지 않으면 지문이 달라 "준비되지 않음"이 된다.
 * - WAV(PCM 정수 8/16/24/32비트 또는 32비트 float, 채널·샘플레이트 무관. 생성 스크립트는 16비트 mono 22050Hz).
 */
object AudioAssetPaths {
    const val ROOT = "audio"
    const val MANIFEST = "$ROOT/manifest.tsv"
    const val ENGLISH_SUFFIX = ".en.wav"
    const val KOREAN_SUFFIX = ".ko.wav"

    /** 음원 하나: assets 경로와 그 음원이 읽어야 하는 텍스트. */
    data class Entry(val path: String, val text: String)

    fun contentDir(contentId: String): String = "$ROOT/$contentId"

    /** 음성 조각 언어 태그 → 음원 경로. 영어·한국어 태그 외에는 음원이 없다. */
    fun speechPath(contentId: String, chapterId: String, languageTag: String, config: LearningConfig): String {
        val suffix = when (languageTag) {
            config.englishLanguageTag -> ENGLISH_SUFFIX
            config.koreanLanguageTag -> KOREAN_SUFFIX
            else -> throw IllegalArgumentException("음원이 없는 언어 태그: $languageTag")
        }
        return "${contentDir(contentId)}/$chapterId$suffix"
    }

    /** 챕터 읽기 순서에 필요한 음원(경로 중복 없음). */
    fun required(contentId: String, chapter: Chapter, config: LearningConfig): List<Entry> =
        ChapterScript.of(chapter, config)
            .filterIsInstance<ChapterSegment.Speech>()
            .map { Entry(speechPath(contentId, chapter.chapterId, it.languageTag, config), it.text) }
            .distinctBy { it.path }

    /** manifest.tsv → assets 경로(`audio/…`) → 텍스트 지문. 형식이 맞지 않는 줄은 건너뛴다. */
    fun parseManifest(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val cols = line.split('\t')
                if (cols.size < 2) null else "$ROOT/${cols[0]}" to cols[1].lowercase()
            }
            .toMap()

    fun textHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
