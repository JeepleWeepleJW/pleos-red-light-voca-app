package com.stai.pleos_red_light_voca_app.data.vehicle

import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal

/**
 * Pleos 기어 연결 상태 기계. android·Pleos 타입 없이 시각(ms)을 인자로 받아 JVM 단위 테스트가 가능하다.
 * 한 스레드([PleosGearDataSource]에서는 메인 Looper)에서만 부른다.
 *
 * 상태:
 * - [Phase.UNKNOWN]: 시작 직후, 값도 실패도 아직 없음.
 * - [Phase.HEALTHY]: 값을 받았고 이후 실패가 없음.
 * - [Phase.FAILED]: 조회 실패·무응답·onFailed(차단 제외)를 받음. 진입할 때 한 번만 SignalFailed를 내보낸다.
 *   연속 실패마다 다시 보내지 않는다(보내면 컨트롤러가 감지를 계속 초기화한다).
 * - [Phase.BLOCKED]: 권한·미지원(SignalBlocked). 주기 조회·재연결을 멈춘다. 리스너 값이 오면 HEALTHY로 돌아간다.
 *
 * 이벤트 규칙:
 * - 값(조회 응답 또는 리스너): UNKNOWN·FAILED·BLOCKED에서는 항상 GearChanged(복구). HEALTHY에서는 이전에 보낸 값과
 *   다를 때만 보낸다(같은 값 미반복, 놓친 변속 보정).
 * - 오래된 조회 응답: 요청 이후 리스너 값이 도착했거나, 더 나중에 보낸 조회 응답이 이미 반영됐거나,
 *   요청 뒤 재연결이 일어났으면 그 응답은 버린다.
 * - 재연결: FAILED 상태에서 연결 계열 실패(서비스 연결 실패·내부 서비스 오류·무응답)가 다시 오고,
 *   마지막 연결(또는 재연결) 뒤 [reconnectIntervalMs]가 지났으면 [Decision.reconnect]를 true로 준다.
 *   호출 쪽은 재연결을 마치면 [onLinkCreated]를 부른다.
 *
 * 감지 한계: SDK가 서비스 단절 시 콜백 없이 binder만 잃는 경우는 주기 조회의 실패·무응답으로만 알 수 있어
 * 최대 (조회 주기 + 응답 제한 시간)만큼 늦게 감지한다. 그 사이의 변속은 복구 뒤 조회값으로 보정된다.
 */
class GearLinkMonitor(
    startedAtMs: Long,
    private val reconnectIntervalMs: Long = DEFAULT_RECONNECT_INTERVAL_MS,
) {
    enum class Phase { UNKNOWN, HEALTHY, FAILED, BLOCKED }

    /** 실패 원인 종류. [CONNECTION]만 재연결 대상이다. */
    enum class FailureKind { CONNECTION, OTHER }

    /** 조회 요청 표(동일성으로 비교). [onPollResult]·[onPollFailure]·[onPollTimeout]에 그대로 돌려준다. */
    class PollTicket internal constructor(
        val id: Long,
        internal val generation: Int,
        internal val valueSeqAtRequest: Long,
    )

    /** 처리 결과. [emit]이 있으면 Flow로 보내고, [reconnect]면 재연결한다. */
    data class Decision(val emit: VehicleEvent? = null, val reconnect: Boolean = false) {
        companion object { val NONE = Decision() }
    }

    var phase: Phase = Phase.UNKNOWN
        private set

    private var lastSent: Gear? = null
    private var valueSeq = 0L          // 리스너 값 도착 순번
    private var nextPollId = 1L
    private var lastAppliedPollId = 0L
    private var outstanding: PollTicket? = null
    private var generation = 0
    private var lastLinkAtMs = startedAtMs

    /** 주기 조회를 해야 하는지. 차단 상태에서는 false. */
    val pollingActive: Boolean get() = phase != Phase.BLOCKED

    /**
     * 새 조회를 시작한다. 차단 상태이거나 앞선 조회가 아직 제한 시간 안에 대기 중이면 null(조회하지 않음).
     */
    fun startPoll(): PollTicket? {
        if (!pollingActive || outstanding != null) return null
        return PollTicket(nextPollId++, generation, valueSeq).also { outstanding = it }
    }

    /** 조회 요청 자체가 동기 예외로 실패해 응답이 오지 않을 때(대기 표를 지운다). */
    fun cancelPoll(ticket: PollTicket) {
        if (outstanding == ticket) outstanding = null
    }

    fun onPollResult(ticket: PollTicket, gear: Gear): Decision {
        if (outstanding == ticket) outstanding = null
        if (ticket.generation != generation) return Decision.NONE
        if (ticket.valueSeqAtRequest != valueSeq) return Decision.NONE // 요청 뒤 리스너 값이 옴
        if (ticket.id <= lastAppliedPollId) return Decision.NONE       // 더 나중 조회가 이미 반영됨
        lastAppliedPollId = ticket.id
        return onValue(gear)
    }

    fun onPollFailure(ticket: PollTicket, event: VehicleEvent, kind: FailureKind, nowMs: Long): Decision {
        if (outstanding == ticket) outstanding = null
        if (ticket.generation != generation) return Decision.NONE // 재연결 전 연결의 실패(release 영향 포함)
        return onFailure(event, kind, nowMs)
    }

    /** 응답 제한 시간이 지남. 이미 응답했거나 재연결로 무효가 된 표면 무시한다. */
    fun onPollTimeout(ticket: PollTicket, nowMs: Long): Decision {
        if (outstanding != ticket) return Decision.NONE
        outstanding = null
        if (ticket.generation != generation) return Decision.NONE
        return onFailure(
            VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED),
            FailureKind.CONNECTION,
            nowMs,
        )
    }

    /** 리스너 onSelected. */
    fun onListenerValue(gear: Gear): Decision {
        valueSeq++
        return onValue(gear)
    }

    /** 리스너 onFailed 또는 연결 설정 실패. */
    fun onListenerFailure(event: VehicleEvent, kind: FailureKind, nowMs: Long): Decision =
        onFailure(event, kind, nowMs)

    /** 새 연결(재연결)을 만든 직후. 이전 연결의 조회 응답은 모두 무효가 된다. */
    fun onLinkCreated(nowMs: Long) {
        generation++
        outstanding = null
        lastLinkAtMs = nowMs
    }

    private fun onValue(gear: Gear): Decision {
        val wasHealthy = phase == Phase.HEALTHY
        phase = Phase.HEALTHY
        if (wasHealthy && gear == lastSent) return Decision.NONE
        lastSent = gear
        return Decision(emit = VehicleEvent.GearChanged(gear))
    }

    private fun onFailure(event: VehicleEvent, kind: FailureKind, nowMs: Long): Decision = when (event) {
        is VehicleEvent.SignalBlocked -> {
            if (phase == Phase.BLOCKED) {
                Decision.NONE
            } else {
                phase = Phase.BLOCKED
                lastSent = null
                outstanding = null
                Decision(emit = event)
            }
        }
        is VehicleEvent.SignalFailed -> when (phase) {
            Phase.BLOCKED -> Decision.NONE // 차단 중에는 재연결·실패 보고를 하지 않는다
            Phase.FAILED -> {
                val due = kind == FailureKind.CONNECTION && nowMs - lastLinkAtMs >= reconnectIntervalMs
                if (due) lastLinkAtMs = nowMs // 재연결 실패가 반복돼도 간격을 지킨다
                Decision(reconnect = due)
            }
            Phase.UNKNOWN, Phase.HEALTHY -> {
                phase = Phase.FAILED
                lastSent = null
                Decision(emit = event)
            }
        }
        else -> Decision.NONE
    }

    companion object {
        const val DEFAULT_RECONNECT_INTERVAL_MS = 10_000L
    }
}
