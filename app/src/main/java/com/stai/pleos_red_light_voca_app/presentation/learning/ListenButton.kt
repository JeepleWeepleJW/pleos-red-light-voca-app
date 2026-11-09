package com.stai.pleos_red_light_voca_app.presentation.learning

import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackPhase
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus

/** 화면 "듣기" 버튼이 세션에 보낼 명령. 앱 내부 MediaController로 보내 미디어 키와 같은 게이트를 거친다. */
enum class ListenCommand { PLAY, PAUSE }

/**
 * 화면 "듣기" 버튼 표시(순수 Kotlin). 재생 허가는 여기서 하지 않는다: 버튼은 세션에 Play·Pause 명령만 보내고,
 * 정차 확정(ARMED)·정차당 1회·음원 준비 판정은 domain 게이트(`HandlePlayCommandUseCase` → `LearningController`)가 한다.
 * 여기서 버튼을 막는 것은 누를 필요가 없는 때를 알려 주는 표시일 뿐이다.
 *
 * 비활성 이유 문구는 화면 카드 표시 판단([LearningCard])이 상태 줄로 정한다.
 *
 * @param command 누르면 보낼 명령. null이면 버튼 비활성.
 */
data class ListenButton(val label: String, val command: ListenCommand?) {
    val enabled: Boolean get() = command != null

    companion object {
        /**
         * [audio]가 확인 중이면 세션이 APP_UI Play를 기다리지 않고 버리고(`HandlePlayCommandUseCase`: `ignored: app not ready`),
         * 그 콘텐츠의 음원이 준비되지 않았으면 재생해도 실패하므로, 두 경우 모두 화면에서 재생 시작 버튼을 먼저 막는다. 멈춤은 막지 않는다.
         */
        fun of(s: LearningState, audio: AudioStatus): ListenButton = when {
            !s.learningOn -> DISABLED
            s.playback == PlaybackPhase.PREPARING || s.playback == PlaybackPhase.PLAYING ->
                ListenButton("멈춤", ListenCommand.PAUSE)
            !audio.isReadyFor(s.contentId) -> DISABLED // Checking·Unavailable·이 콘텐츠 음원 누락
            s.currentChapterId == null -> DISABLED
            s.gatePhase == GatePhase.ARMED -> ListenButton("듣기", ListenCommand.PLAY)
            else -> DISABLED
        }

        private val DISABLED = ListenButton("듣기", null)
    }
}
