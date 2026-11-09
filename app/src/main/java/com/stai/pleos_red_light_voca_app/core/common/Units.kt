package com.stai.pleos_red_light_voca_app.core.common

import kotlin.math.abs

/** CarPropertyManager PERF_VEHICLE_SPEED(m/s, 후진 시 음수 가능) → km/h */
fun metersPerSecondToKmh(metersPerSecond: Float): Float = abs(metersPerSecond) * 3.6f
