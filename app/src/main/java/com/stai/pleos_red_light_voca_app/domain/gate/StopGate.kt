package com.stai.pleos_red_light_voca_app.domain.gate

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.common.Cancellable
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.core.common.Scheduler
import com.stai.pleos_red_light_voca_app.core.entity.Gear

/**
 * 정차 판정 상태.
 *
 * ```
 * INACTIVE ─activate→ WAITING_DRIVE ─D·≥8→ DRIVE_CONFIRMING ─3s→ DETECTING ─D·≤0.5→ STOP_CONFIRMING ─3s→ ARMED
 *                      WAITING_DRIVE ─(학습 ON 뒤 첫 정차, 기어 무관 ≤0.5)────────→ STOP_CONFIRMING (>0.5 → WAITING_DRIVE)
 *                          ▲                (≥8 이탈 → WAITING_DRIVE)          (>0.5 → DETECTING)
 *                          │ ARMED/ARMED_CREEP에서 >1 (재생 없이 정차 끝)
 * ARMED ⇄ ARMED_CREEP : (0.5, 1] 서행이면 CREEP(새 재생 금지), ≤0.5면 ARMED
 * ARMED ─consume(Play)→ USED ─D·≥8→ DRIVE_CONFIRMING (재주행 후 다음 정차)
 * 기어 D 이외/UNKNOWN, 데이터 오류 → WAITING_DRIVE (타이머 취소, 주행 이력 초기화)
 * ```
 */
enum class GatePhase {
    /** 학습 OFF. 판정하지 않음 */
    INACTIVE,
    /** 주행 확인 대기(첫 주행, 기어 이탈·오류 후, 재생 없이 지나간 정차 후) */
    WAITING_DRIVE,
    /** D·8km/h 이상 연속 시간 측정 중 */
    DRIVE_CONFIRMING,
    /** 감지 활성: 정차를 기다림 */
    DETECTING,
    /** D·0.5km/h 이하 연속 시간 측정 중 */
    STOP_CONFIRMING,
    /** 정차 확정, 이번 정차에서 아직 재생 시도 없음 → Play 명령으로 재생 가능 */
    ARMED,
    /** 정차 확정 후 (0.5, 1] 서행. 새 재생 금지, 0.5 이하로 돌아오면 ARMED */
    ARMED_CREEP,
    /** 이번 정차의 재생 시도를 썼음(완료·중단·실패 무관). 재주행 전까지 재생 불가 */
    USED,
}

/** [StopGate.onSample]이 알려 주는 "지금 재생 중이면 멈춰야 하는 이유" */
enum class HaltReason {
    /** moveSpeedKmh 초과 */
    MOVING,
    /** 기어 D 이외 또는 UNKNOWN */
    GEAR_NOT_DRIVE,
}

/**
 * 정차 판정기. Android 의존 없음. 단일 스레드(메인)에서만 호출한다.
 *
 * - 연속 시간은 이벤트 개수가 아니라 [clock]으로 잰다. 같은 값이 반복 수신되지 않아도 타이머로 확정한다.
 * - 타이머 만료 시 최신 속도·기어를 다시 확인한다.
 */
class StopGate(
    private val config: LearningConfig,
    private val clock: MonotonicClock,
    private val scheduler: Scheduler,
    private val onPhaseChanged: (old: GatePhase, new: GatePhase) -> Unit = { _, _ -> },
) {
    var phase: GatePhase = GatePhase.INACTIVE
        private set

    private var latestSpeedKmh: Float? = null
    private var latestGear: Gear? = null
    private var holdSinceMs: Long = 0L
    private var timer: Cancellable? = null

    /**
     * 학습 ON 뒤 아직 움직이지 않은 "첫 정차" 중인지(사용자 결정 2026-11-09: 앱을 처음 실행해 멈춘 상태에서 학습을 켜면
     * 바로 들을 수 있어야 함). 이 동안은 주행 확인도 기어 D도 요구하지 않고 속도(≤ stopSpeedKmh 3초)만 본다.
     * 이동(> moveSpeedKmh)하면 끝나고, 그 뒤는 매 정차 전에 D 주행 확인이 필요하다. 정차당 1회는 같다.
     */
    private var firstStopWithoutDrive = false

    val canPlay: Boolean get() = phase == GatePhase.ARMED

    /** 학습 ON. 주행 조건부터 다시 확인한다(지금 이미 주행 중이어도 지금부터 3초). */
    fun activate(speedKmh: Float?, gear: Gear?) {
        cancelTimer()
        latestSpeedKmh = speedKmh
        latestGear = gear
        firstStopWithoutDrive = true
        moveTo(GatePhase.WAITING_DRIVE)
        evaluate()
    }

    /** 학습 OFF */
    fun deactivate() {
        cancelTimer()
        moveTo(GatePhase.INACTIVE)
    }

    /**
     * 최신 차량 값. null은 "모름"(아직 못 받음 또는 오류 뒤)이다.
     * @return 재생 중이면 멈춰야 하는 이유. 없으면 null.
     */
    fun onSample(speedKmh: Float?, gear: Gear?): HaltReason? {
        latestSpeedKmh = speedKmh
        latestGear = gear
        if (phase == GatePhase.INACTIVE) return null
        return evaluate()
    }

    /** 속도·기어 조회 실패, unavailable·error, 서비스 단절. 감지 초기화. */
    fun onDataError() {
        latestSpeedKmh = null
        latestGear = null
        if (phase == GatePhase.INACTIVE) return
        // 첫 정차에서 이미 재생 시도를 썼으면, 복구 뒤 같은 정차에서 다시 ARMED가 되지 않게 한다
        if (phase == GatePhase.USED) firstStopWithoutDrive = false
        cancelTimer()
        moveTo(GatePhase.WAITING_DRIVE)
    }

    /**
     * 재생 시도 잠금. ARMED일 때만 성공하고 USED로 바뀐다(오디오 요청 전에 잠근다).
     * @return 잠금 성공 여부
     */
    fun consume(): Boolean {
        if (phase != GatePhase.ARMED) return false
        moveTo(GatePhase.USED)
        return true
    }

    private fun evaluate(): HaltReason? {
        val gear = latestGear
        val speed = latestSpeedKmh

        if (firstStopWithoutDrive && speed != null) {
            if (speed <= config.moveSpeedKmh) {
                evaluateFirstStop(speed)
                return null
            }
            // 첫 정차에서 출발: 여기부터는 일반 규칙(D 주행 확인)
            firstStopWithoutDrive = false
            cancelTimer()
            if (phase != GatePhase.USED) moveTo(GatePhase.WAITING_DRIVE)
        }

        if (gear != null && gear != Gear.DRIVE) {
            // 기어 이탈: 타이머 취소, 주행 이력 초기화
            cancelTimer()
            moveTo(GatePhase.WAITING_DRIVE)
            return HaltReason.GEAR_NOT_DRIVE
        }
        if (gear == null || speed == null) {
            // 모름: 판정 불가. 진행 중이던 연속 시간은 끊는다.
            cancelTimer()
            if (phase != GatePhase.USED) moveTo(GatePhase.WAITING_DRIVE)
            return null
        }

        val halt = if (speed > config.moveSpeedKmh) HaltReason.MOVING else null

        when (phase) {
            GatePhase.INACTIVE -> Unit
            GatePhase.WAITING_DRIVE, GatePhase.USED ->
                if (speed >= config.armSpeedKmh) startHold(GatePhase.DRIVE_CONFIRMING, config.armHoldMs)
            GatePhase.DRIVE_CONFIRMING ->
                if (speed < config.armSpeedKmh) {
                    cancelTimer()
                    moveTo(GatePhase.WAITING_DRIVE)
                }
            GatePhase.DETECTING ->
                if (speed <= config.stopSpeedKmh) startHold(GatePhase.STOP_CONFIRMING, config.stopHoldMs)
            GatePhase.STOP_CONFIRMING ->
                if (speed > config.stopSpeedKmh) {
                    cancelTimer()
                    moveTo(GatePhase.DETECTING)
                }
            GatePhase.ARMED, GatePhase.ARMED_CREEP -> when {
                // 재생 없이 정차가 끝남. "매 정차 이후" 재주행 확인
                speed > config.moveSpeedKmh -> moveTo(GatePhase.WAITING_DRIVE)
                speed > config.stopSpeedKmh -> moveTo(GatePhase.ARMED_CREEP)
                else -> moveTo(GatePhase.ARMED)
            }
        }
        return halt
    }

    /** 첫 정차(이동 ≤ moveSpeedKmh, 기어 무관). 정차 확인이 끊기면 감지가 아니라 주행 대기로 돌아가 다시 멈추면 다시 잰다. */
    private fun evaluateFirstStop(speed: Float) {
        val stopped = speed <= config.stopSpeedKmh
        when (phase) {
            GatePhase.WAITING_DRIVE, GatePhase.DRIVE_CONFIRMING, GatePhase.DETECTING ->
                if (stopped) startHold(GatePhase.STOP_CONFIRMING, config.stopHoldMs) else {
                    cancelTimer()
                    moveTo(GatePhase.WAITING_DRIVE)
                }
            GatePhase.STOP_CONFIRMING ->
                if (!stopped) {
                    cancelTimer()
                    moveTo(GatePhase.WAITING_DRIVE)
                }
            GatePhase.ARMED, GatePhase.ARMED_CREEP ->
                moveTo(if (stopped) GatePhase.ARMED else GatePhase.ARMED_CREEP)
            GatePhase.USED, GatePhase.INACTIVE -> Unit
        }
    }

    private fun startHold(target: GatePhase, holdMs: Long) {
        cancelTimer()
        holdSinceMs = clock.nowMs()
        moveTo(target)
        scheduleCheck(holdMs)
    }

    private fun scheduleCheck(delayMs: Long) {
        timer = scheduler.schedule(delayMs) { onHoldTimer() }
    }

    private fun onHoldTimer() {
        timer = null
        val gear = latestGear
        val speed = latestSpeedKmh
        when (phase) {
            GatePhase.DRIVE_CONFIRMING -> {
                val remaining = config.armHoldMs - (clock.nowMs() - holdSinceMs)
                when {
                    gear != Gear.DRIVE || speed == null || speed < config.armSpeedKmh -> moveTo(GatePhase.WAITING_DRIVE)
                    remaining > 0 -> scheduleCheck(remaining)
                    else -> {
                        moveTo(GatePhase.DETECTING)
                        // 감지 활성 직후 이미 정차 조건이면 바로 정차 후보로 (보통은 아님)
                        evaluate()
                    }
                }
            }
            GatePhase.STOP_CONFIRMING -> {
                val remaining = config.stopHoldMs - (clock.nowMs() - holdSinceMs)
                when {
                    speed == null || (!firstStopWithoutDrive && gear != Gear.DRIVE) -> moveTo(GatePhase.WAITING_DRIVE)
                    speed > config.stopSpeedKmh ->
                        moveTo(if (firstStopWithoutDrive) GatePhase.WAITING_DRIVE else GatePhase.DETECTING)
                    remaining > 0 -> scheduleCheck(remaining)
                    else -> moveTo(GatePhase.ARMED)
                }
            }
            else -> Unit // 이미 다른 상태로 바뀜(취소 경합)
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private fun moveTo(next: GatePhase) {
        val old = phase
        if (old == next) return
        phase = next
        onPhaseChanged(old, next)
    }
}
