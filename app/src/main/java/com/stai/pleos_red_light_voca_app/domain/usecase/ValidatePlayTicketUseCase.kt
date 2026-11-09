package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.learning.PlayTicket
import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 준비 중인 재생 허가가 아직 유효한지(그 사이 출발·기어 이탈·학습 종료 등이 없었는지). 앱 준비 전이면 false. */
class ValidatePlayTicketUseCase(private val session: AppSession) {
    operator fun invoke(ticket: PlayTicket): Boolean = session.controllerOrNull?.isTicketValid(ticket) == true
}
