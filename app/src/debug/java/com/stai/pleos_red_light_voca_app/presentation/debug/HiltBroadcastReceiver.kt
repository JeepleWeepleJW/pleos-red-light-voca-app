package com.stai.pleos_red_light_voca_app.presentation.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.CallSuper

/**
 * @AndroidEntryPoint 리시버의 바탕. Hilt는 생성한 부모의 onReceive에서 주입하므로 하위 클래스가
 * super.onReceive를 불러야 하는데, BroadcastReceiver.onReceive는 추상이라 Kotlin에서 직접 부를 수 없다.
 */
abstract class HiltBroadcastReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent) {}
}
