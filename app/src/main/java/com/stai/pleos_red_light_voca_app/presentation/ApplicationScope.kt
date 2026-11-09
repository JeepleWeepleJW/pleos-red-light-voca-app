package com.stai.pleos_red_light_voca_app.presentation

import javax.inject.Qualifier

/**
 * 앱(프로세스) 단위 메인 스레드 CoroutineScope(`SupervisorJob() + Dispatchers.Main.immediate`) 한정자.
 * presentation이 di를 보지 않도록 여기에 두고, di가 이것으로 스코프를 제공한다.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
