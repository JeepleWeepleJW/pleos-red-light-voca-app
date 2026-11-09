package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.learning.PlayerControl
import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 학습 세션의 정지 요청(출발·기어 이탈·데이터 오류·학습 종료)을 받을 플레이어를 붙인다. */
class AttachPlayerUseCase(private val session: AppSession) {
    operator fun invoke(player: PlayerControl) = session.attachPlayer(player)
}
