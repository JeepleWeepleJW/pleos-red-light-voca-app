package com.stai.pleos_red_light_voca_app.presentation.debug

import com.stai.pleos_red_light_voca_app.domain.source.VehicleSimulator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 디버그 패널(Composable)이 Hilt 그래프에서 [VehicleSimulator]를 꺼낼 때 쓴다. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VehicleSimulatorEntryPoint {
    fun vehicleSimulator(): VehicleSimulator
}
