package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 앱(프로세스) 시작: 카탈로그 로드 → 진도 복원(학습 OFF) → 차량 수집 → 음원 확인. 재생하지 않는다. 중복 호출은 무시된다. */
class StartAppUseCase(private val session: AppSession) {
    operator fun invoke() = session.start()
}
