package com.stai.pleos_red_light_voca_app.data.vehicle.debug

import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.SourceMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleStateSourceTest {
    @Test fun snapshotFirstThenChanges() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeVehicleStateSource()
        fake.setSpeed(12f)
        assertTrue(fake.setGear("D"))
        val got = mutableListOf<VehicleEvent>()
        val job = launch { fake.events().collect { got.add(it) } }
        assertEquals(
            listOf(VehicleEvent.SpeedChanged(12f), VehicleEvent.GearChanged(Gear.DRIVE)),
            got.toList(),
        )
        fake.setSpeed(0f)
        fake.fail(VehicleSignal.SPEED, SignalFailure.ERROR)
        fake.block(VehicleSignal.GEAR, SignalBlock.UNSUPPORTED)
        assertEquals(VehicleEvent.SpeedChanged(0f), got[2])
        assertEquals(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR), got[3])
        assertEquals(VehicleEvent.SignalBlocked(VehicleSignal.GEAR, SignalBlock.UNSUPPORTED), got[4])
        job.cancel()
    }

    @Test fun defaults() {
        val fake = FakeVehicleStateSource()
        assertEquals(0f, fake.speedKmh.value)
        assertEquals(Gear.OTHER, fake.gear.value)
        assertEquals("GEAR_PARK", fake.gearLabel.value)
    }

    @Test fun gearLabelParsing() {
        for (l in listOf("DRIVE", "d", "GEAR_DRIVE")) assertEquals(l, Gear.DRIVE, FakeVehicleStateSource.parseGear(l))
        for (l in listOf("UNKNOWN", "GEAR_UNKNOWN")) assertEquals(Gear.UNKNOWN, FakeVehicleStateSource.parseGear(l))
        for (l in listOf("PARK", "P", "REVERSE", "R", "NEUTRAL", "N", "GEAR_1", "GEAR_9")) {
            assertEquals(l, Gear.OTHER, FakeVehicleStateSource.parseGear(l))
        }
        val fake = FakeVehicleStateSource()
        assertFalse(fake.setGear("banana"))
        assertEquals("GEAR_PARK", fake.gearLabel.value)
    }

    @Test fun switchingChangesSource() = runTest(UnconfinedTestDispatcher()) {
        val real = FakeVehicleStateSource().apply { setSpeed(50f) }
        val fake = FakeVehicleStateSource().apply { setSpeed(5f) }
        val mode = MutableStateFlow(SourceMode.FAKE)
        val sw = SwitchingVehicleStateSource(real, fake, mode, log = {})
        val got = mutableListOf<VehicleEvent>()
        val job: Job = launch { sw.events().collect { got.add(it) } }
        assertEquals(VehicleEvent.SpeedChanged(5f), got[0])
        assertTrue(got.none { it is VehicleEvent.SignalFailed }) // 첫 모드에서는 단절 신호 없음
        val before = got.size
        mode.value = SourceMode.REAL
        // 전환 시 새 소스 이벤트보다 먼저 두 신호 DISCONNECTED
        assertEquals(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.DISCONNECTED), got[before])
        assertEquals(VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED), got[before + 1])
        assertEquals(VehicleEvent.SpeedChanged(50f), got[before + 2])
        assertEquals(VehicleEvent.SpeedChanged(50f), got.last { it is VehicleEvent.SpeedChanged })
        fake.setSpeed(7f) // 더는 전달되지 않음
        assertFalse(got.contains(VehicleEvent.SpeedChanged(7f)))
        real.setSpeed(60f)
        assertEquals(VehicleEvent.SpeedChanged(60f), got.last())
        job.cancel()
    }
}
