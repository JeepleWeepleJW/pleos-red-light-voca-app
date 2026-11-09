package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 학습할 콘텐츠를 고른다. 앱 준비 전이면 무시된다. */
class SelectContentUseCase(private val session: AppSession) {
    operator fun invoke(contentId: String) {
        session.controllerOrNull?.selectContent(contentId)
    }
}
