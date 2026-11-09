package com.stai.pleos_red_light_voca_app.core.common

/**
 * 로그 출력 경계. domain·core는 이 인터페이스만 본다.
 * 앱에서는 Timber로 연결하고, Timber 트리가 태그 [LearningConfig.LOG_TAG](`VocaApp`)로 출력한다.
 */
interface Logger {
    fun i(message: String)

    fun w(message: String, t: Throwable? = null)

    companion object {
        /** 아무것도 남기지 않는 로거(기본값·테스트용). */
        val NONE: Logger = object : Logger {
            override fun i(message: String) = Unit
            override fun w(message: String, t: Throwable?) = Unit
        }
    }
}
