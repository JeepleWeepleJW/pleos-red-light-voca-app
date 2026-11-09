package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** 앱 시작 상태(로딩·준비·실패)를 본다. */
class ObserveSessionStateUseCase(private val session: AppSession) {
    operator fun invoke(): StateFlow<SessionState> = session.state
}
