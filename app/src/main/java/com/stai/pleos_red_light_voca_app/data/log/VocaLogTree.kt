package com.stai.pleos_red_light_voca_app.data.log

import android.util.Log
import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import timber.log.Timber
import kotlin.math.min

/**
 * 앱 전체 단일 로그 출력. Timber 태그와 상관없이 항상 [LearningConfig.LOG_TAG]로 쓴다.
 * `adb logcat -s VocaApp`
 *
 * Timber는 throwable이 있으면 message 뒤에 스택트레이스를 붙여 넘기므로 `Log.w(tag, msg, t)`와 같은 출력이 된다.
 * logcat 한 항목 길이 제한에 잘리지 않도록, 긴 메시지는 Timber `DebugTree`와 같은 방식(줄바꿈 기준,
 * [MAX_LOG_LENGTH]자 단위)으로 나눠 남긴다. 짧은 메시지는 한 항목이다.
 */
class VocaLogTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (message.length < MAX_LOG_LENGTH) {
            Log.println(priority, LearningConfig.LOG_TAG, message)
            return
        }
        var i = 0
        val length = message.length
        while (i < length) {
            var newline = message.indexOf('\n', i)
            newline = if (newline != -1) newline else length
            do {
                val end = min(newline, i + MAX_LOG_LENGTH)
                Log.println(priority, LearningConfig.LOG_TAG, message.substring(i, end))
                i = end
            } while (i < newline)
            i++
        }
    }

    private companion object {
        const val MAX_LOG_LENGTH = 4000
    }
}
