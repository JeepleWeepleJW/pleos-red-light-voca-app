package com.stai.pleos_red_light_voca_app.data.vehicle

import android.content.Context
import timber.log.Timber
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * 실제 차량 입력: 속도([CarSpeedDataSource])와 기어([PleosGearDataSource])를 합친다.
 *
 * 단절은 각 신호별로 DISCONNECTED를 보낸다([com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource] 계약:
 * 속도와 기어는 서로 다른 서비스라 끊긴 쪽만).
 */
class VehicleStateSourceImpl(
    context: Context,
    log: (String) -> Unit = { Timber.i(it) },
) : VehicleStateSource {
    private val speed = CarSpeedDataSource(context, log)
    private val gear = PleosGearDataSource(context, log)

    override fun events(): Flow<VehicleEvent> = merge(speed.events(), gear.events())
}
