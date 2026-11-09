package com.stai.pleos_red_light_voca_app.domain.session

import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.domain.learning.PauseReason
import com.stai.pleos_red_light_voca_app.domain.learning.PlayerControl

/** 앱 시작 상태 */
sealed interface SessionState {
    data object Loading : SessionState
    data class Ready(val catalog: Catalog) : SessionState
    data class Failed(val message: String) : SessionState
}

/** 챕터 음원 확인 상태(화면 표시용) */
sealed interface AudioStatus {
    data object Checking : AudioStatus

    /**
     * 확인 끝. [missing]: 콘텐츠 ID → 준비되지 않은 음원(assets 기준 경로. 파일은 있지만 문구가 다르거나 깨졌으면 `경로 (이유)`).
     * 비어 있으면 모든 콘텐츠의 음원이 준비됐다.
     */
    data class Checked(val missing: Map<String, List<String>>) : AudioStatus

    /** 음원을 확인하지 못함(모든 콘텐츠의 학습을 막는다). */
    data class Unavailable(val message: String) : AudioStatus

    /** [contentId]의 학습을 시작할 수 있는가. [contentId]가 null이면 확인이 끝나 막을 이유가 없는지만 본다. */
    fun isReadyFor(contentId: String?): Boolean = when (this) {
        Checking, is Unavailable -> false
        is Checked -> contentId == null || missing[contentId].isNullOrEmpty()
    }

    /** [isReadyFor]가 false일 때 로그에 쓰는 자세한 이유(경로 포함). 화면 문구는 `AppSession.startLearning`이 콘텐츠 제목으로 만든다. */
    fun refusalFor(contentId: String?): String = when (this) {
        Checking -> "음원 확인 중"
        is Unavailable -> message
        is Checked -> {
            val files = contentId?.let { missing[it] }.orEmpty()
            "음원 준비 안 됨: $contentId (문제 파일 ${files.size}개, 예: ${files.firstOrNull().orEmpty()})"
        }
    }
}

/** 화면 "학습 시작" 결과 */
sealed interface StartOutcome {
    data object Started : StartOutcome
    data class Refused(val message: String) : StartOutcome
}

/**
 * LearningController가 호출하는 정지 요청을 현재 살아 있는 미디어 서비스 플레이어로 넘긴다.
 * 서비스가 없으면 재생 중일 수도 없으므로 기록만 한다.
 */
internal class PlayerControlRelay(private val logger: Logger) : PlayerControl {
    @Volatile var target: PlayerControl? = null

    override fun pause(reason: PauseReason) {
        val t = target
        if (t == null) logger.i("pause($reason) requested but no player attached") else t.pause(reason)
    }
}
