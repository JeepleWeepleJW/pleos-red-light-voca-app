package com.stai.pleos_red_light_voca_app.core.entity

/**
 * 학습 진도. contentId와 chapterId만 저장한다(ADR-002: positionMs 없음).
 *
 * @property lastCompletedChapterIds 콘텐츠별 마지막으로 끝까지 재생(STATE_ENDED)된 챕터 ID.
 *   없으면 그 콘텐츠는 첫 챕터부터.
 * @property activeContentId 마지막으로 학습한(선택한) 콘텐츠 ID. 재시작 시 화면 기본 선택에만 쓴다.
 */
data class Progress(
    val lastCompletedChapterIds: Map<String, String> = emptyMap(),
    val activeContentId: String? = null,
)
