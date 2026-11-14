package com.stai.pleos_red_light_voca_app.domain.gate

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.common.Cancellable
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.core.common.Scheduler
import com.stai.pleos_red_light_voca_app.core.entity.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 수동 가상 시간. advance()가 만료된 예약을 시간 순서대로 실행한다. */
private class ManualTime : MonotonicClock, Scheduler {
    var now = 0L
    private data class Task(val at: Long, val seq: Int, val action: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    private var seq = 0

    override fun nowMs(): Long = now

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val t = Task(now + delayMs, seq++, action)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= target }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = next.at
            next.action()
        }
        now = target
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}

class StopGateTest {
    private val time = ManualTime()
    private val config = LearningConfig()
    private val gate = StopGate(config, time, time)

    private fun drive(speed: Float, gear: Gear = Gear.DRIVE) = gate.onSample(speed, gear)

    private fun armToDetecting() {
        gate.activate(null, null)
        drive(10f)
        time.advance(3_000)
        assertEquals(GatePhase.DETECTING, gate.phase)
    }

    private fun toArmed() {
        armToDetecting()
        drive(0f)
        time.advance(3_000)
        assertEquals(GatePhase.ARMED, gate.phase)
    }

    @Test fun inactive_ignores_samples() {
        assertNull(drive(20f))
        time.advance(10_000)
        assertEquals(GatePhase.INACTIVE, gate.phase)
        assertEquals(0, time.pending)
    }

    @Test fun drive_confirmation_needs_single_event_only() {
        gate.activate(null, null)
        drive(10f) // 같은 값이 반복되지 않아도 타이머로 확정
        time.advance(2_999)
        assertEquals(GatePhase.DRIVE_CONFIRMING, gate.phase)
        time.advance(1)
        assertEquals(GatePhase.DETECTING, gate.phase)
    }

    @Test fun arm_speed_boundary_is_inclusive() {
        gate.activate(null, null)
        drive(7.99f)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
        drive(8.0f)
        assertEquals(GatePhase.DRIVE_CONFIRMING, gate.phase)
        time.advance(3_000)
        assertEquals(GatePhase.DETECTING, gate.phase)
    }

    @Test fun drive_dip_below_arm_speed_restarts_hold() {
        gate.activate(null, null)
        drive(10f)
        time.advance(2_000)
        drive(7f)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
        drive(10f)
        time.advance(2_000)
        assertEquals(GatePhase.DRIVE_CONFIRMING, gate.phase)
        time.advance(1_000)
        assertEquals(GatePhase.DETECTING, gate.phase)
    }

    @Test fun stop_speed_boundary_is_inclusive() {
        armToDetecting()
        drive(0.51f)
        assertEquals(GatePhase.DETECTING, gate.phase)
        drive(0.5f)
        assertEquals(GatePhase.STOP_CONFIRMING, gate.phase)
        time.advance(3_000)
        assertEquals(GatePhase.ARMED, gate.phase)
    }

    @Test fun stop_shorter_than_hold_does_not_arm() {
        armToDetecting()
        drive(0f)
        time.advance(2_999)
        drive(0.6f)
        assertEquals(GatePhase.DETECTING, gate.phase)
        time.advance(10_000)
        assertEquals(GatePhase.DETECTING, gate.phase)
    }

    @Test fun move_threshold_is_exclusive() {
        toArmed()
        assertNull(drive(3.0f))
        assertEquals(GatePhase.ARMED_CREEP, gate.phase)
        assertEquals(HaltReason.MOVING, drive(3.01f))
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun armed_creep_lower_boundary_is_exclusive_and_returns_without_reconfirm() {
        toArmed()
        assertNull(drive(0.5f))
        assertEquals(GatePhase.ARMED, gate.phase)
        assertNull(drive(0.51f))
        assertEquals(GatePhase.ARMED_CREEP, gate.phase)
        assertFalse(gate.canPlay)
        assertNull(drive(0.5f))
        assertEquals(GatePhase.ARMED, gate.phase) // 3초 재확인 없이 즉시
        assertEquals(0, time.pending)
        assertTrue(gate.canPlay)
    }

    @Test fun move_boundary_three_is_creep_and_above_three_ends_stop() {
        toArmed()
        assertNull(drive(3.0f))
        assertEquals(GatePhase.ARMED_CREEP, gate.phase)
        assertEquals(HaltReason.MOVING, drive(3.01f))
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun creep_then_back_to_stop_rearms_without_new_drive() {
        toArmed()
        drive(0.8f)
        assertFalse(gate.canPlay)
        drive(0.3f)
        assertTrue(gate.canPlay)
    }

    @Test fun consume_only_when_armed_and_locks_until_redrive() {
        armToDetecting()
        assertFalse(gate.consume())
        drive(0f)
        time.advance(3_000)
        assertTrue(gate.consume())
        assertEquals(GatePhase.USED, gate.phase)
        assertFalse(gate.consume())
        // 장기 정차, 서행 후 재정차: 잠금 유지
        time.advance(600_000)
        drive(5f); drive(0f)
        time.advance(10_000)
        assertEquals(GatePhase.USED, gate.phase)
        // 재주행 → 다음 정차
        drive(9f)
        time.advance(3_000)
        assertEquals(GatePhase.DETECTING, gate.phase)
        drive(0f)
        time.advance(3_000)
        assertTrue(gate.canPlay)
    }

    @Test fun gear_leave_resets_history_and_cancels_timer() {
        gate.activate(null, null)
        drive(10f)
        time.advance(2_000)
        assertEquals(HaltReason.GEAR_NOT_DRIVE, drive(10f, Gear.OTHER))
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
        assertEquals(0, time.pending)
        drive(10f)
        time.advance(2_000)
        assertEquals(GatePhase.DRIVE_CONFIRMING, gate.phase)
    }

    @Test fun unknown_gear_is_not_drive() {
        toArmed()
        assertEquals(HaltReason.GEAR_NOT_DRIVE, drive(0f, Gear.UNKNOWN))
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun data_error_resets_to_waiting_drive() {
        armToDetecting()
        drive(0f)
        gate.onDataError()
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
        assertEquals(0, time.pending)
        drive(0f)
        time.advance(10_000)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun timer_rechecks_latest_values() {
        armToDetecting()
        drive(0f)
        // 타이머 만료 직전 기어 정보가 "모름"이 되면 확정하지 않는다
        gate.onSample(0f, null)
        time.advance(3_000)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun activate_while_already_driving_waits_full_hold_from_now() {
        time.advance(100_000)
        gate.activate(50f, Gear.DRIVE)
        assertEquals(GatePhase.DRIVE_CONFIRMING, gate.phase)
        time.advance(2_999)
        assertEquals(GatePhase.DRIVE_CONFIRMING, gate.phase)
        time.advance(1)
        assertEquals(GatePhase.DETECTING, gate.phase)
    }

    @Test fun deactivate_cancels_timers() {
        gate.activate(null, null)
        drive(10f)
        gate.deactivate()
        assertEquals(0, time.pending)
        assertEquals(GatePhase.INACTIVE, gate.phase)
    }

    @Test fun early_timer_fire_is_rescheduled_by_monotonic_clock() {
        // 스케줄러가 일찍 깨워도 단조 시계로 남은 시간을 다시 기다린다
        val early = object : Scheduler {
            var action: (() -> Unit)? = null
            override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
                this.action = action
                return Cancellable { this.action = null }
            }
        }
        val g = StopGate(config, time, early)
        g.activate(null, null)
        g.onSample(10f, Gear.DRIVE)
        time.now += 1_000
        early.action!!.invoke()
        assertEquals(GatePhase.DRIVE_CONFIRMING, g.phase)
        time.now += 2_000
        early.action!!.invoke()
        assertEquals(GatePhase.DETECTING, g.phase)
    }

    @Test fun first_stop_after_activate_needs_no_drive() {
        gate.activate(0f, Gear.DRIVE)
        assertEquals(GatePhase.STOP_CONFIRMING, gate.phase)
        time.advance(2_999)
        assertEquals(GatePhase.STOP_CONFIRMING, gate.phase)
        time.advance(1)
        assertEquals(GatePhase.ARMED, gate.phase)
        assertTrue(gate.consume())
        // 다음 정차부터는 주행 확인이 다시 필요하다
        drive(0f)
        time.advance(10_000)
        assertEquals(GatePhase.USED, gate.phase)
    }

    @Test fun first_stop_after_activate_accepts_any_gear() {
        // 앱 첫 실행: 주차(P) 상태로 멈춰 있어도 3초 뒤 들을 수 있다
        gate.activate(0f, Gear.OTHER)
        assertEquals(GatePhase.STOP_CONFIRMING, gate.phase)
        time.advance(3_000)
        assertEquals(GatePhase.ARMED, gate.phase)
        // 첫 정차 중 기어 변경은 재생을 멈추지 않는다
        assertTrue(gate.consume())
        assertNull(drive(0f, Gear.DRIVE))
        assertNull(drive(0f, Gear.OTHER))
        assertEquals(GatePhase.USED, gate.phase)
        // 출발하면 일반 규칙: 이동 정지
        assertEquals(HaltReason.MOVING, drive(5f))
        drive(0f, Gear.OTHER)
        time.advance(10_000)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun first_stop_with_unknown_gear_uses_speed_only() {
        gate.activate(0f, null)
        assertEquals(GatePhase.STOP_CONFIRMING, gate.phase)
        time.advance(3_000)
        assertEquals(GatePhase.ARMED, gate.phase)
    }

    @Test fun first_stop_used_then_data_error_does_not_rearm() {
        gate.activate(0f, Gear.OTHER)
        time.advance(3_000)
        assertTrue(gate.consume())
        gate.onDataError()
        drive(0f, Gear.OTHER)
        time.advance(10_000)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun normal_stop_still_requires_drive_gear() {
        toArmed()
        assertTrue(gate.consume())
        drive(10f); time.advance(3_000)
        assertEquals(GatePhase.DETECTING, gate.phase)
        assertEquals(HaltReason.GEAR_NOT_DRIVE, drive(0f, Gear.OTHER))
        time.advance(10_000)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun first_stop_creep_returns_to_waiting_then_restarts_hold() {
        gate.activate(0f, Gear.DRIVE)
        time.advance(2_000)
        drive(0.8f)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
        drive(0f)
        assertEquals(GatePhase.STOP_CONFIRMING, gate.phase)
        time.advance(3_000)
        assertEquals(GatePhase.ARMED, gate.phase)
    }

    @Test fun moving_before_first_stop_restores_drive_requirement() {
        gate.activate(0f, Gear.DRIVE)
        drive(5f) // 1 km/h 초과 이동, 8 km/h 미만
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
        drive(0f)
        time.advance(10_000)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }

    @Test fun armed_without_play_then_moving_needs_drive_again() {
        gate.activate(0f, Gear.DRIVE)
        time.advance(3_000)
        assertEquals(GatePhase.ARMED, gate.phase)
        drive(4f)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
        drive(0f)
        time.advance(10_000)
        assertEquals(GatePhase.WAITING_DRIVE, gate.phase)
    }
}
