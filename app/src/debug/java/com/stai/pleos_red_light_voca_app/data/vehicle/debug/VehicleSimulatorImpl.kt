package com.stai.pleos_red_light_voca_app.data.vehicle.debug

import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.SourceMode
import com.stai.pleos_red_light_voca_app.domain.source.VehicleSimulator
import kotlinx.coroutines.flow.StateFlow

/** [VehicleSimulator] 구현. 모드는 [DebugVehicle]에, 주입은 그 가짜 소스에 넘긴다. */
class VehicleSimulatorImpl(private val debugVehicle: DebugVehicle) : VehicleSimulator {
    private val fake get() = debugVehicle.fake

    override val mode: StateFlow<SourceMode> get() = debugVehicle.mode
    override val speedKmh: StateFlow<Float> get() = fake.speedKmh
    override val gearLabel: StateFlow<String> get() = fake.gearLabel

    override fun setMode(mode: SourceMode) = debugVehicle.setMode(mode)
    override fun setSpeed(kmh: Float) = fake.setSpeed(kmh)
    override fun setGear(label: String): Boolean = fake.setGear(label)
    override fun fail(signal: VehicleSignal, failure: SignalFailure) = fake.fail(signal, failure)
    override fun block(signal: VehicleSignal, block: SignalBlock) = fake.block(signal, block)
}
