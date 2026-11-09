package com.stai.pleos_red_light_voca_app.presentation.playback

import com.stai.pleos_red_light_voca_app.domain.playback.PlaySource

/**
 * transport play 명령을 보낸 컨트롤러(패키지 이름·연결 힌트)에서 Play 출처를 가려낸다(순수 Kotlin).
 * 출처별 허용 규칙은 domain(`PlaySourcePolicy.gate`, `HandlePlayCommandUseCase`)에 있다.
 */
object PlaySourceClassifier {
    /** debug MediaController가 connectionHints에 넣는 키. debuggable 빌드에서만 인정한다. */
    const val DEBUG_CONTROLLER_HINT: String = "com.stai.pleos_red_light_voca_app.DEBUG_CONTROLLER"

    /**
     * 화면 "듣기" 버튼의 MediaController(`AppMediaController`)가 connectionHints에 넣는 키. 모든 빌드에서,
     * 요청 패키지가 이 앱일 때만 인정한다. APP_UI는 OTHER_CONTROLLER보다 권한이 넓지 않으므로(학습 OFF에서 학습을 켜지 않음)
     * 다른 앱이 흉내 내도 얻는 것이 없다.
     */
    const val APP_UI_CONTROLLER_HINT: String = "com.stai.pleos_red_light_voca_app.APP_UI_CONTROLLER"

    /**
     * AAOS CarMediaService가 부팅·소스 변경 시 자동 재생을 보내는 패키지(미검증: 에뮬레이터 로그의
     * `media PLAY from=`으로 확인 후 조정).
     */
    val SYSTEM_AUTOPLAY_PACKAGES: Set<String> = setOf("com.android.car", "com.android.car.updatable")

    /**
     * transport play 명령(세션 플레이어의 play())의 출처. 미디어 키는 이 경로가 아니라 MEDIA_KEY로 직접 들어온다.
     * 미디어 알림 컨트롤러(Media3가 System UI 명령을 바꿔 넘기는 대상, 패키지가 이 앱)는 힌트가 없어 OTHER다.
     */
    /**
     * 컨트롤러 연결 힌트 조회 함수([hint]: 키 → 값이 true인지)로 출처를 가린다. `GatedPlayer`가
     * `ControllerInfo.connectionHints`를 이 함수로 넘긴다(Bundle 없이 단위 테스트하려고 조회를 함수로 받는다).
     */
    fun classify(packageName: String?, hint: (String) -> Boolean, debuggableBuild: Boolean, ownPackage: String?): PlaySource =
        classifyController(
            packageName = packageName,
            hasDebugHint = hint(DEBUG_CONTROLLER_HINT),
            debuggableBuild = debuggableBuild,
            hasAppUiHint = hint(APP_UI_CONTROLLER_HINT),
            ownPackage = ownPackage,
        )

    fun classifyController(
        packageName: String?,
        hasDebugHint: Boolean,
        debuggableBuild: Boolean,
        hasAppUiHint: Boolean = false,
        ownPackage: String? = null,
    ): PlaySource = when {
        hasDebugHint && debuggableBuild -> PlaySource.SELF
        hasAppUiHint && packageName != null && packageName == ownPackage -> PlaySource.APP_UI
        packageName != null && packageName in SYSTEM_AUTOPLAY_PACKAGES -> PlaySource.SYSTEM_AUTOPLAY
        else -> PlaySource.OTHER_CONTROLLER
    }
}
