package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus
import kotlinx.coroutines.flow.StateFlow

/** 음원 확인 상태를 본다. */
class ObserveAudioStatusUseCase(private val session: AppSession) {
    operator fun invoke(): StateFlow<AudioStatus> = session.audio
}
