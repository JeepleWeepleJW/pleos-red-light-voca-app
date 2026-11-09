package com.stai.pleos_red_light_voca_app.data.vehicle

import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.data.vehicle.GearLinkMonitor.Decision
import com.stai.pleos_red_light_voca_app.data.vehicle.GearLinkMonitor.FailureKind
import com.stai.pleos_red_light_voca_app.data.vehicle.GearLinkMonitor.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearLinkMonitorTest {
    private val disconnected = VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED)
    private val error = VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.ERROR)
    private val unsupported = VehicleEvent.SignalBlocked(VehicleSignal.GEAR, SignalBlock.UNSUPPORTED)
    private fun gear(g: Gear) = VehicleEvent.GearChanged(g)

    private fun GearLinkMonitor.pollOk(g: Gear): Decision = onPollResult(startPoll()!!, g)
    private fun GearLinkMonitor.pollFail(e: VehicleEvent, k: FailureKind, t: Long): Decision =
        onPollFailure(startPoll()!!, e, k, t)
    private fun GearLinkMonitor.pollTimeout(t: Long): Decision = onPollTimeout(startPoll()!!, t)

    @Test fun firstValueIsEmitted_sameValueNotRepeated_changedValueEmitted() {
        val m = GearLinkMonitor(0)
        assertEquals(gear(Gear.DRIVE), m.pollOk(Gear.DRIVE).emit)
        assertNull(m.pollOk(Gear.DRIVE).emit)                     // 같은 값 미반복
        assertNull(m.onListenerValue(Gear.DRIVE).emit)
        assertEquals(gear(Gear.OTHER), m.pollOk(Gear.OTHER).emit) // 놓친 변속 보정
        assertEquals(gear(Gear.DRIVE), m.onListenerValue(Gear.DRIVE).emit)
    }

    @Test fun consecutiveFailures_emitSignalFailedOnce() {
        val m = GearLinkMonitor(0)
        m.pollOk(Gear.DRIVE)
        assertEquals(disconnected, m.pollTimeout(3_000).emit)
        assertNull(m.pollTimeout(6_000).emit)
        assertNull(m.pollFail(error, FailureKind.OTHER, 9_000).emit)
        assertNull(m.onListenerFailure(error, FailureKind.CONNECTION, 9_500).emit)
        assertEquals(Phase.FAILED, m.phase)
    }

    @Test fun initialFailureBeforeAnyValue_emitsOnce() {
        val m = GearLinkMonitor(0)
        assertEquals(disconnected, m.pollFail(disconnected, FailureKind.CONNECTION, 0).emit)
        assertNull(m.pollFail(disconnected, FailureKind.CONNECTION, 3_000).emit)
    }

    @Test fun recovery_emitsGearChangedEvenIfSameAsBeforeFailure() {
        val m = GearLinkMonitor(0)
        m.pollOk(Gear.DRIVE)
        m.pollTimeout(3_000)
        assertEquals(gear(Gear.DRIVE), m.pollOk(Gear.DRIVE).emit)
        assertEquals(Phase.HEALTHY, m.phase)
        // 복구 뒤 다시 실패하면 다시 한 번 보낸다
        assertEquals(error, m.onListenerFailure(error, FailureKind.OTHER, 10_000).emit)
        assertEquals(gear(Gear.DRIVE), m.onListenerValue(Gear.DRIVE).emit)
    }

    @Test fun lateQueryResponse_afterListenerValue_isIgnored() {
        val m = GearLinkMonitor(0)
        m.pollOk(Gear.DRIVE)
        val t = m.startPoll()!!                                       // 조회 요청(그때 D)
        assertEquals(gear(Gear.OTHER), m.onListenerValue(Gear.OTHER).emit) // 그 뒤 P로 변속
        assertNull(m.onPollResult(t, Gear.DRIVE).emit)                // 늦은 D 응답은 버린다
        assertNull(m.pollOk(Gear.OTHER).emit)                         // 상태는 P 그대로
    }

    @Test fun olderPollResponse_afterNewerApplied_isIgnored() {
        val m = GearLinkMonitor(0)
        val old = m.startPoll()!!
        assertEquals(disconnected, m.onPollTimeout(old, 2_000).emit)
        val newer = m.startPoll()!!
        assertEquals(gear(Gear.OTHER), m.onPollResult(newer, Gear.OTHER).emit)
        assertNull(m.onPollResult(old, Gear.DRIVE).emit) // 시간 초과된 옛 요청의 늦은 응답
    }

    @Test fun pollResponse_fromBeforeReconnect_isIgnored() {
        val m = GearLinkMonitor(0)
        val t = m.startPoll()!!
        m.onLinkCreated(1_000)
        assertNull(m.onPollResult(t, Gear.DRIVE).emit)
        assertNull(m.onPollFailure(t, error, FailureKind.CONNECTION, 1_500).emit)
        assertEquals(Phase.UNKNOWN, m.phase)
    }

    @Test fun onlyOneOutstandingPoll() {
        val m = GearLinkMonitor(0)
        val t = m.startPoll()
        assertNotNull(t)
        assertNull(m.startPoll())
        m.onPollResult(t!!, Gear.DRIVE)
        assertNotNull(m.startPoll())
    }

    @Test fun reconnect_requestedOnlyWhenFailedAndConnectionErrorContinuesAfterInterval() {
        val m = GearLinkMonitor(0, reconnectIntervalMs = 10_000)
        m.pollOk(Gear.DRIVE)
        // 정상 → 실패: 이벤트만, 재연결 없음
        val first = m.pollTimeout(12_000)
        assertEquals(disconnected, first.emit)
        assertFalse(first.reconnect)
        // 실패 상태에서 연결 오류가 이어지고 시작 뒤 10초가 지남 → 재연결
        val second = m.pollTimeout(15_000)
        assertNull(second.emit)
        assertTrue(second.reconnect)
        m.onLinkCreated(15_000)
        // 재연결 직후 실패가 이어져도 간격 전에는 다시 재연결하지 않는다
        assertFalse(m.pollTimeout(18_000).reconnect)
        assertFalse(m.pollFail(disconnected, FailureKind.CONNECTION, 24_000).reconnect)
        assertTrue(m.pollFail(disconnected, FailureKind.CONNECTION, 25_000).reconnect)
    }

    @Test fun reconnect_notRequestedForNonConnectionErrors() {
        val m = GearLinkMonitor(0, reconnectIntervalMs = 10_000)
        m.pollFail(error, FailureKind.OTHER, 0)
        assertFalse(m.pollFail(error, FailureKind.OTHER, 20_000).reconnect)
        assertFalse(m.pollFail(error, FailureKind.OTHER, 40_000).reconnect)
    }

    @Test fun reconnect_notRequestedBeforeIntervalSinceStart() {
        val m = GearLinkMonitor(0, reconnectIntervalMs = 10_000)
        m.pollFail(disconnected, FailureKind.CONNECTION, 0)
        assertFalse(m.pollFail(disconnected, FailureKind.CONNECTION, 3_000).reconnect)
        assertFalse(m.pollTimeout(6_000).reconnect)
        assertTrue(m.pollTimeout(11_000).reconnect)
    }

    @Test fun reconnect_intervalHoldsEvenIfCallerNeverCallsOnLinkCreated() {
        val m = GearLinkMonitor(0, reconnectIntervalMs = 10_000)
        m.pollFail(disconnected, FailureKind.CONNECTION, 0)
        assertTrue(m.pollTimeout(10_000).reconnect)
        assertFalse(m.pollTimeout(13_000).reconnect)
    }

    @Test fun blocked_emitsOnce_stopsPollingAndReconnect() {
        val m = GearLinkMonitor(0, reconnectIntervalMs = 10_000)
        m.pollOk(Gear.DRIVE)
        assertEquals(unsupported, m.pollFail(unsupported, FailureKind.OTHER, 1_000).emit)
        assertEquals(Phase.BLOCKED, m.phase)
        assertFalse(m.pollingActive)
        assertNull(m.startPoll())
        assertNull(m.onListenerFailure(unsupported, FailureKind.OTHER, 2_000).emit)
        val d = m.onListenerFailure(disconnected, FailureKind.CONNECTION, 30_000)
        assertNull(d.emit)
        assertFalse(d.reconnect)
    }

    @Test fun blockedFromFailedState_alsoEmitted() {
        val m = GearLinkMonitor(0)
        m.pollTimeout(1_000)
        assertEquals(unsupported, m.onListenerFailure(unsupported, FailureKind.OTHER, 2_000).emit)
    }

    @Test fun listenerValueAfterBlocked_unblocksAndResumesPolling() {
        val m = GearLinkMonitor(0)
        m.onListenerFailure(unsupported, FailureKind.OTHER, 0)
        assertEquals(gear(Gear.DRIVE), m.onListenerValue(Gear.DRIVE).emit)
        assertTrue(m.pollingActive)
        assertNotNull(m.startPoll())
    }

    @Test fun timeoutAfterResponse_isIgnored() {
        val m = GearLinkMonitor(0)
        val t = m.startPoll()!!
        m.onPollResult(t, Gear.DRIVE)
        assertEquals(Decision.NONE, m.onPollTimeout(t, 2_000))
        assertEquals(Phase.HEALTHY, m.phase)
    }

    @Test fun connectionErrorClassification() {
        assertTrue(isPleosConnectionError("Error code: ERROR_SERVICE_CONNECTION_FAILURE"))
        assertTrue(isPleosConnectionError("Error code: ERROR_INTERNAL_SERVICE"))
        assertFalse(isPleosConnectionError("Error code: ERROR_EMPTY_RESULT"))
        assertFalse(isPleosConnectionError(null))
    }
}
