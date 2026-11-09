package com.stai.pleos_red_light_voca_app.presentation.learning

/** 화면이 할 일(일회성) */
sealed interface LearningEvent {
    /** 런타임 권한 요청 창을 띄운다. */
    data class RequestPermissions(val permissions: List<String>) : LearningEvent

    /** 재생 서비스의 MediaSession에 "듣기"(Play)·"멈춤"(Pause) 명령을 보낸다(앱 내부 MediaController). */
    data class SendListenCommand(val command: ListenCommand) : LearningEvent
}
