package com.stai.pleos_red_light_voca_app.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 단조 증가 시계. 앱 구현은 data `SystemMonotonicClock`(`SystemClock.elapsedRealtime()`),
 * 테스트는 가상 시간(`TestScope.testScheduler.currentTime`)을 쓴다.
 */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/** 예약 취소 핸들 */
fun interface Cancellable {
    fun cancel()
}

/** 지연 실행 스케줄러. 콜백은 컨트롤러와 같은 스레드(메인)에서 실행되어야 한다. */
fun interface Scheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

/**
 * 코루틴 기반 스케줄러. 앱에서는 di가 주는 앱 스코프(`SupervisorJob() + Dispatchers.Main.immediate`)를,
 * 테스트에서는 `TestScope`를 넘기면 가상 시간으로 동작한다.
 */
class CoroutineScheduler(private val scope: CoroutineScope) : Scheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val job: Job = scope.launch {
            delay(delayMs)
            action()
        }
        return Cancellable { job.cancel() }
    }
}
