package com.stai.pleos_red_light_voca_app.data.log

import com.stai.pleos_red_light_voca_app.core.common.Logger
import timber.log.Timber

/** domain의 [Logger]를 Timber로 연결한다. */
class TimberLogger : Logger {
    override fun i(message: String) {
        Timber.i(message)
    }

    override fun w(message: String, t: Throwable?) {
        if (t != null) Timber.w(t, message) else Timber.w(message)
    }
}
