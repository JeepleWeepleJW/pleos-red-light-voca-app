package com.stai.pleos_red_light_voca_app.domain.source

import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult

/** 음원 확인 결과. 학습 시작 시 그 콘텐츠에 빠진 음원이 있으면 화면·로그에 알리고 시작하지 않는다. */
sealed interface AudioAvailability {
    /** [missing]: 콘텐츠 ID → 빠진 음원 파일(assets 기준 경로). 빠진 것이 없는 콘텐츠는 넣지 않는다. */
    data class Checked(val missing: Map<String, List<String>>) : AudioAvailability
    /** 음원 폴더를 읽지 못함(모든 콘텐츠의 학습을 막는다). */
    data class Error(val message: String) : AudioAvailability
}

/**
 * 챕터 음원 경계. 지금 구현은 data `AssetChapterAudioSource`: 앱에 넣은 단어 음원(영어·한국어 WAV)을
 * 챕터 읽기 순서([com.stai.pleos_red_light_voca_app.domain.speech.ChapterScript])대로 WAV 1개로 병합·캐시한다.
 * 결과가 "챕터 1개 = 로컬 WAV 파일 1개"이기만 하면 음원 출처(다른 음원 세트·서버 다운로드 등)는 이 경계 뒤에서 바꿀 수 있다.
 */
interface ChapterAudioSource {
    /** [contents]의 모든 챕터 음원이 있는지 본다. */
    suspend fun checkAvailability(contents: List<Content>): AudioAvailability

    /** 캐시에 있으면 그 파일, 없으면 음원을 병합해서 돌려준다. 음원이 없거나 읽지 못하면 [ChapterAudioResult.Failed]. */
    suspend fun chapterAudio(contentId: String, chapter: Chapter): ChapterAudioResult

    /**
     * 학습 시작 시 콘텐츠 분량을 미리 병합한다. 실패한 챕터는 재생 시점에 다시 시도한다.
     * 순서는 호출자가 넘긴 [chapters] 순서 그대로이므로, 호출자는 현재 챕터부터 넘겨야 한다.
     */
    suspend fun prefetch(contentId: String, chapters: List<Chapter>)
}
