package com.stai.pleos_red_light_voca_app.presentation.playback

import androidx.media3.common.Player
import com.stai.pleos_red_light_voca_app.domain.learning.PlayTicket

/** 플레이어 이벤트에 대한 대응(순수 규칙). [GatedPlayer]가 쓰고 단위 테스트로 고정한다. */
enum class FocusAction {
    NONE,
    /**
     * 정지(stop: 포커스 반납, playWhenReady=false라 포커스를 되찾아도 자동 재개 없음) 후 FOCUS_LOSS 보고.
     * 일시 상실·영구 상실 모두. 영구 상실의 stop은 Pleos 가이드 요구다.
     */
    HALT_AND_REPORT,
}

/** 재생 시작 단계. 실제 구현은 ExoPlayer, 테스트는 기록용 가짜. */
interface StartTarget<T> {
    fun setMediaItem(item: T)
    fun prepare()
    fun seekTo(positionMs: Long)
    fun play()
}

object PlaybackRules {
    /** Media3 handleAudioFocus=true: 일시 상실은 playWhenReady=true인 채 재생 억제 → 포커스 회복 시 자동 재개된다. */
    fun onSuppressionReason(reason: Int): FocusAction =
        if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) FocusAction.HALT_AND_REPORT
        else FocusAction.NONE

    /** 영구 상실은 playWhenReady=false(이유 AUDIO_FOCUS_LOSS)로 온다. */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int): FocusAction =
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) FocusAction.HALT_AND_REPORT
        else FocusAction.NONE

    /** STATE_ENDED가 지금 재생 중 티켓의 아이템에서 났을 때만 챕터 완료로 본다(이전 아이템의 늦은 종료 무시). */
    fun isEndedForTicket(currentItemTag: Any?, ticket: PlayTicket?): Boolean =
        ticket != null && currentItemTag == ticket

    /** 챕터 재생 시작: 항상 처음부터(ADR-002 2.1). 순서 = 아이템 설정 → prepare → seekTo(0) → play. */
    fun <T> startFromBeginning(target: StartTarget<T>, item: T) {
        target.setMediaItem(item)
        target.prepare()
        target.seekTo(0)
        target.play()
    }
}
