package com.stai.pleos_red_light_voca_app.data.vehicle

import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleMappingTest {
    @Test fun gearNames() {
        assertEquals(Gear.DRIVE, mapGearName("GEAR_DRIVE"))
        assertEquals(Gear.UNKNOWN, mapGearName("GEAR_UNKNOWN"))
        assertEquals(Gear.UNKNOWN, mapGearName(null))
        for (n in listOf("GEAR_PARK", "GEAR_REVERSE", "GEAR_NEUTRAL", "GEAR_1", "GEAR_9", "xyz")) {
            assertEquals(n, Gear.OTHER, mapGearName(n))
        }
    }

    private fun cls(msg: String?, sec: Boolean = false) =
        classifyPleosFailure(VehicleSignal.GEAR, msg, sec)

    @Test fun failureClassification() {
        val perm = VehicleEvent.SignalBlocked(VehicleSignal.GEAR, SignalBlock.PERMISSION_DENIED)
        assertEquals(perm, cls("Error code: ERROR_SECURITY_PERMISSION"))
        assertEquals(perm, cls(null, sec = true))
        val unsup = VehicleEvent.SignalBlocked(VehicleSignal.GEAR, SignalBlock.UNSUPPORTED)
        for (c in listOf("ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA", "ERROR_NOT_SUPPORTED_VALUE_TYPE")) {
            assertEquals(c, unsup, cls("Error code: $c"))
        }
        assertEquals(
            VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED),
            cls("Error code: ERROR_SERVICE_CONNECTION_FAILURE"),
        )
        assertEquals(
            VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.READ_FAILED),
            cls("Error code: ERROR_EMPTY_RESULT"),
        )
        val err = VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.ERROR)
        assertEquals(err, cls("Error code: ERROR_INTERNAL_SERVICE"))
        assertEquals(err, cls("Error code: ERROR_DRIVING_RESTRICTED"))
        assertEquals(err, cls(null))
    }

    @Test fun speedStatus() {
        assertEquals(VehicleEvent.SpeedChanged(36f), mapSpeedStatus(0, 10f))
        assertEquals(VehicleEvent.SpeedChanged(0f), mapSpeedStatus(0, 0f))
        assertEquals(VehicleEvent.SpeedChanged(18f), mapSpeedStatus(0, -5f))
        val rf = VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.READ_FAILED)
        assertEquals(rf, mapSpeedStatus(0, null))
        assertEquals(rf, mapSpeedStatus(0, Float.NaN))
        assertEquals(rf, mapSpeedStatus(0, Float.POSITIVE_INFINITY))
        assertEquals(
            VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.UNAVAILABLE),
            mapSpeedStatus(1, 5f),
        )
        assertEquals(
            VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR),
            mapSpeedStatus(2, 5f),
        )
        assertEquals(
            VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR),
            mapSpeedStatus(99, 5f),
        )
    }
}
