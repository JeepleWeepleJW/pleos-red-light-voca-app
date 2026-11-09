package com.stai.pleos_red_light_voca_app.domain.speech

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.entity.Chapter

/** 챕터 오디오 구성 요소 */
sealed interface ChapterSegment {
    data class Speech(val languageTag: String, val text: String) : ChapterSegment
    data class Silence(val durationMs: Long) : ChapterSegment
}

/** ADR-002 2장 읽기 순서: 영어 단어 → 무음 → 한국어 뜻 → 무음 → 영어 단어. 음성 조각은 언어별 단어 음원 하나에 대응한다. */
object ChapterScript {
    fun of(chapter: Chapter, config: LearningConfig = LearningConfig()): List<ChapterSegment> = listOf(
        ChapterSegment.Speech(config.englishLanguageTag, chapter.word),
        ChapterSegment.Silence(config.chapterSilenceMs),
        ChapterSegment.Speech(config.koreanLanguageTag, chapter.meaningKo),
        ChapterSegment.Silence(config.chapterSilenceMs),
        ChapterSegment.Speech(config.englishLanguageTag, chapter.word),
    )
}
