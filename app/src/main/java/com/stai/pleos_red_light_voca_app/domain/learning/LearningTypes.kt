package com.stai.pleos_red_light_voca_app.domain.learning

import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal

/** UI·미디어 서비스가 구독하는 상태 */
data class LearningState(
    val learningOn: Boolean = false,
    /** 선택(또는 학습 중)한 콘텐츠 */
    val contentId: String? = null,
    /** 다음에 재생할 챕터. 콘텐츠를 모두 끝냈으면 null */
    val currentChapterId: String? = null,
    /** 선택한 콘텐츠에서 완료한 챕터 수 */
    val completedChapters: Int = 0,
    val totalChapters: Int = 0,
    /** 콘텐츠 ID → 완료한 챕터 수(카탈로그의 모든 콘텐츠). 화면의 콘텐츠 목록 진도 표시용 */
    val completedByContent: Map<String, Int> = emptyMap(),
    val gatePhase: GatePhase = GatePhase.INACTIVE,
    val playback: PlaybackPhase = PlaybackPhase.IDLE,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    /** null이면 학습 가능. 값이 있으면 학습 비활성(시작 불가) */
    val disabledReason: DisabledReason? = null,
    /** 마지막 챕터를 끝내 세션이 종료되었는지(다음 학습 시작 시 false) */
    val sessionCompleted: Boolean = false,
    /** 마지막 재생 종료·중단 사유(표시·로그용) */
    val lastPlaybackEnd: PlaybackEnd? = null,
)

data class VehicleSnapshot(
    val speedKmh: Float? = null,
    val gear: Gear? = null,
    val speedFailure: SignalFailure? = null,
    val gearFailure: SignalFailure? = null,
)

/** 미지원·권한 거부로 막힌 신호들 */
data class DisabledReason(val blocked: Map<VehicleSignal, SignalBlock>)

enum class PlaybackPhase {
    IDLE,
    /** Play 결정 후 재생 시작 보고 전(음원 병합·준비 중) */
    PREPARING,
    PLAYING,
}

enum class PlaybackEnd {
    /** STATE_ENDED: 챕터 완료 */
    COMPLETED,
    /** 사용자 Pause 명령 */
    USER_PAUSE,
    /** 사용자 Stop 명령 */
    USER_STOP,
    /** 오디오 포커스 상실 */
    FOCUS_LOSS,
    /** 플레이어·음원 오류 */
    ERROR,
    /** 출발(moveSpeedKmh 초과) */
    MOVING,
    /** 기어 D 이탈 */
    GEAR_NOT_DRIVE,
    /** 차량 데이터 오류·단절 */
    VEHICLE_DATA_ERROR,
    /** 학습 종료(사용자, 비활성화) */
    LEARNING_STOPPED,
}

/** 컨트롤러가 플랫폼에 요청하는 정지 이유 = [PlaybackEnd] 중 컨트롤러가 일으키는 것 */
typealias PauseReason = PlaybackEnd

/**
 * 컨트롤러 → 플레이어 경계. 재생 "시작" 메서드는 일부러 없다.
 * 재생 시작은 MediaSession 컨트롤러 명령에서만 한다(ADR-001).
 */
fun interface PlayerControl {
    /** 재생 중이거나 준비 중이면 멈춘다(준비 중이면 시작하지 않는다). 멱등. */
    fun pause(reason: PauseReason)
}

/** Play 명령을 받은 쪽(MediaSession)이 보관했다가 실제 play 직전에 [LearningController.isTicketValid]로 확인한다. */
data class PlayTicket(val id: Long, val contentId: String, val chapterId: String)

sealed interface PlayDecision {
    /** 이 챕터를 처음부터(seekTo(0)) 재생한다. */
    data class Play(val ticket: PlayTicket) : PlayDecision

    /** 학습 OFF였으므로 학습 ON으로만 바꿨다. 재생하지 않는다. */
    data object LearningEnabledOnly : PlayDecision

    /** 재생하지 않는다. */
    data class Ignored(val reason: IgnoreReason) : PlayDecision
}

enum class IgnoreReason {
    /** 미지원·권한 거부로 학습 비활성 */
    DISABLED,
    /** 학습 ON이지만 정차 확정(ARMED) 아님 */
    NOT_ARMED,
    /** 정차 확정 후 서행 (0.5, 1] km/h: 새 재생 금지 */
    CREEPING,
    /** 이번 정차의 재생 시도를 이미 씀 */
    STOP_ALREADY_USED,
    /** 이미 재생(준비) 중 */
    ALREADY_PLAYING,
    /** 재생할 콘텐츠·챕터 없음 */
    NO_CONTENT,
}

sealed interface StartResult {
    data object Started : StartResult
    data object AlreadyOn : StartResult
    data class Disabled(val reason: DisabledReason) : StartResult
    data object UnknownContent : StartResult
}

/** 플레이어가 학습 세션에 알리는 재생 경과 */
sealed interface PlaybackReport {
    val ticket: PlayTicket

    /** 실제로 소리가 나기 시작했다. */
    data class Started(override val ticket: PlayTicket) : PlaybackReport

    /** STATE_ENDED: 챕터를 끝까지 재생했다(진도 저장). */
    data class Ended(override val ticket: PlayTicket) : PlaybackReport

    /** 끝까지 가지 못했다(포커스 상실·오류·서비스 종료 등). */
    data class Interrupted(override val ticket: PlayTicket, val reason: PlaybackEnd) : PlaybackReport
}
