package com.stai.pleos_red_light_voca_app.di

import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** release: 실제 차량 입력만. 수집을 (다시) 시작할 때마다 새 소스를 만든다. */
@Module
@InstallIn(SingletonComponent::class)
object VariantModule {
    @Provides
    fun provideVehicleSourceFactory(@RealVehicleSource real: VehicleStateSourceFactory): VehicleStateSourceFactory = real
}
