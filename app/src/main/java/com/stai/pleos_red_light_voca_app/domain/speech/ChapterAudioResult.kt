package com.stai.pleos_red_light_voca_app.domain.speech

import java.io.File

/** 챕터 음성 준비 결과(ChapterAudioSource·PrepareChapterAudioUseCase의 출력). */
sealed interface ChapterAudioResult {
    /** 챕터 전체(영어 → 무음 → 한국어 → 무음 → 영어)를 이어 붙인 WAV 1개. 미디어 아이템 1개 = 챕터 1개. */
    data class Ready(val wavFile: File) : ChapterAudioResult
    data class Failed(val message: String) : ChapterAudioResult
}
