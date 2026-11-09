package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult

/** 재생을 허가받은 챕터의 음성(WAV)을 준비한다(없으면 음원을 병합). */
class PrepareChapterAudioUseCase(private val session: AppSession) {
    suspend operator fun invoke(contentId: String, chapter: Chapter): ChapterAudioResult =
        session.chapterAudio(contentId, chapter)
}
