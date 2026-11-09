package com.stai.pleos_red_light_voca_app.domain.playback

import com.stai.pleos_red_light_voca_app.domain.learning.PlayTicket

/**
 * Play 명령의 출처와 허용 규칙(순수 Kotlin).
 *
 * 요구 1장·사용자 결정(2026-11-08): 앱 실행·서비스 연결·재시작·시스템 자동 재생으로는 학습 ON도 재생도 하지 않는다.
 * 학습 OFF에서 학습 ON은 사용자 미디어 키(또는 debug 빌드의 디버그 컨트롤러)로만 한다.
 * 화면 "듣기" 버튼(APP_UI, 사용자 결정 2026-11-09)은 학습 ON일 때의 재생 명령으로만 쓰며 학습을 켜지 않는다(OTHER_CONTROLLER와 같은 규칙).
 * 컨트롤러(패키지 이름·연결 힌트)에서 출처를 가려내는 일은 presentation(`PlaySourceClassifier`)이 한다.
 */
enum class PlaySource {
    /** 세션 콜백 onMediaButtonEvent가 직접 받은 미디어 키(시스템이 전달한 차량 키·adb keyevent) */
    MEDIA_KEY,
    /** debuggable 빌드에서 디버그 연결 힌트를 단 이 앱의 MediaController(debug 패널·adb debug.MEDIA) */
    SELF,
    /** 시스템 자동 재생으로 보는 컨트롤러 패키지 */
    SYSTEM_AUTOPLAY,
    /** 그 밖의 컨트롤러(미디어 센터 화면 버튼, 음성, 미디어 알림·System UI 버튼 등) */
    OTHER_CONTROLLER,
    /** 이 앱 화면의 "듣기" 버튼(앱 내부 MediaController, 연결 힌트 + 요청 패키지가 이 앱). 모든 빌드 */
    APP_UI,
}

enum class PlayGate {
    /** 학습 세션의 재생 허가(LearningController.onPlayCommand)로 넘긴다. */
    FORWARD,
    /** 학습 OFF에서 키가 아닌 명령(다른 컨트롤러·화면 듣기 버튼): 학습을 켜지 않는다. */
    IGNORE_NOT_USER_KEY,
    /** 시스템 자동 재생: 학습 ON·재생 모두 하지 않는다. */
    IGNORE_SYSTEM_AUTOPLAY,
    /** 학습 OFF인데 선택 콘텐츠의 음원이 준비되지 않음: 학습을 켜지 않는다. */
    IGNORE_AUDIO_NOT_READY,
}

object PlaySourcePolicy {
    fun gate(source: PlaySource, learningOn: Boolean, audioReady: Boolean = true): PlayGate = when {
        source == PlaySource.SYSTEM_AUTOPLAY -> PlayGate.IGNORE_SYSTEM_AUTOPLAY
        learningOn -> PlayGate.FORWARD
        source == PlaySource.OTHER_CONTROLLER || source == PlaySource.APP_UI -> PlayGate.IGNORE_NOT_USER_KEY
        !audioReady -> PlayGate.IGNORE_AUDIO_NOT_READY
        else -> PlayGate.FORWARD
    }
}

/** 컨트롤러 Play 명령 처리 결과(HandlePlayCommandUseCase) */
sealed interface PlayCommandResult {
    /** 재생 허가: 플레이어가 이 티켓의 챕터 음성을 준비해 처음부터 재생한다. */
    data class Play(val ticket: PlayTicket) : PlayCommandResult

    /** 학습 세션까지 넘겼지만 재생은 하지 않는다(학습 ON만 됨·정차 판정 등으로 거절). 세션 표시만 갱신한다. */
    data object Forwarded : PlayCommandResult

    /** 출처 게이트·앱 준비 상태로 학습 세션에 넘기지 않았다. */
    data object Ignored : PlayCommandResult
}
