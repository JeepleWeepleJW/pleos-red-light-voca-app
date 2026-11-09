package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.learning.PlayerControl
import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 붙어 있는 플레이어가 [player]일 때만 뗀다(서비스 종료 시). */
class DetachPlayerUseCase(private val session: AppSession) {
    operator fun invoke(player: PlayerControl) = session.detachPlayer(player)
}
