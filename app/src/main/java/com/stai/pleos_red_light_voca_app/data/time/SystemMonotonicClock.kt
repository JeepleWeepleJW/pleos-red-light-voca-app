package com.stai.pleos_red_light_voca_app.data.time

import android.os.SystemClock
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock

/** 부팅 후 경과 시간(깊은 잠자기 포함) 기반 단조 시계 */
class SystemMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
