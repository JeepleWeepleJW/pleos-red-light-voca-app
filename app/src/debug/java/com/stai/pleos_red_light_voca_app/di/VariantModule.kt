package com.stai.pleos_red_light_voca_app.di

import com.stai.pleos_red_light_voca_app.data.vehicle.debug.DebugVehicle
import com.stai.pleos_red_light_voca_app.data.vehicle.debug.SwitchingVehicleStateSource
import com.stai.pleos_red_light_voca_app.data.vehicle.debug.VehicleSimulatorImpl
import com.stai.pleos_red_light_voca_app.domain.source.VehicleSimulator
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * debug: 실제 입력과 디버그 시뮬레이션(FAKE)을 [DebugVehicle.mode]로 바꿔 끼운다.
 * 수집을 (다시) 시작할 때마다 새 소스를 만든다. 가짜 소스와 모드 상태는 프로세스당 하나([DebugVehicle])다.
 */
@Module
@InstallIn(SingletonComponent::class)
object VariantModule {
    @Provides
    @Singleton
    fun provideDebugVehicle(): DebugVehicle = DebugVehicle()

    @Provides
    @Singleton
    fun provideVehicleSimulator(debugVehicle: DebugVehicle): VehicleSimulator = VehicleSimulatorImpl(debugVehicle)

    @Provides
    fun provideVehicleSourceFactory(
        @RealVehicleSource real: VehicleStateSourceFactory,
        debugVehicle: DebugVehicle,
    ): VehicleStateSourceFactory = VehicleStateSourceFactory {
        SwitchingVehicleStateSource(real.create(), debugVehicle.fake, debugVehicle.mode)
    }
}
