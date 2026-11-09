package com.stai.pleos_red_light_voca_app.presentation.debug


import android.content.Context
import android.content.Intent
import timber.log.Timber
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.SourceMode
import com.stai.pleos_red_light_voca_app.domain.source.VehicleSimulator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * adb 주입 수신기.
 * adb shell am broadcast -a com.stai.pleos_red_light_voca_app.debug.VEHICLE --ef speedKmh 30 --es gear DRIVE
 * extras: speedKmh, gear, source(REAL|FAKE), fail(SPEED|GEAR[:사유]), block(SPEED|GEAR[:사유]).
 * 처리 순서: source → fail/block → speed/gear. FAKE가 아닐 때 주입은 무시한다.
 * 액션 ...debug.MEDIA, extra cmd(play|pause|stop|playpause)는 DebugMedia로 보낸다.
 * 차량 주입은 [VehicleSimulator](Hilt 주입)로만 한다.
 */
@AndroidEntryPoint
class DebugVehicleReceiver : HiltBroadcastReceiver() {
    @Inject lateinit var simulator: VehicleSimulator

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Hilt 주입
        when (intent.action) {
            ACTION_VEHICLE -> handleVehicle(intent)
            ACTION_MEDIA -> {
                val cmd = intent.getStringExtra("cmd")?.lowercase()
                if (cmd in DebugMedia.COMMANDS) {
                    // 세션 연결이 비동기라 goAsync()로 수신을 연장한다
                    val pending = goAsync()
                    DebugMedia.send(context, cmd!!) { pending.finish() }
                } else {
                    w("debug media invalid cmd=$cmd")
                }
            }
        }
    }

    private fun handleVehicle(intent: Intent) {
        val extras = intent.extras
        intent.getStringExtra("source")?.let { s ->
            val m = SourceMode.entries.firstOrNull { it.name == s.trim().uppercase() }
            if (m == null) w("debug invalid source=$s") else simulator.setMode(m)
        }

        val failExtra = intent.getStringExtra("fail")
        val blockExtra = intent.getStringExtra("block")
        val hasInjection = failExtra != null || blockExtra != null ||
            extras?.containsKey("speedKmh") == true || intent.hasExtra("gear")
        if (hasInjection && simulator.mode.value != SourceMode.FAKE) {
            w("debug inject ignored: mode=${simulator.mode.value} (set source=FAKE)")
            return
        }

        failExtra?.let { v ->
            val (sig, rest) = splitSignal(v)
            val failure = if (rest == null) SignalFailure.ERROR
            else SignalFailure.entries.firstOrNull { it.name == rest }
            if (sig == null || failure == null) w("debug invalid fail=$v")
            else {
                simulator.fail(sig, failure)
                i("debug fail signal=$sig failure=$failure")
            }
        }
        blockExtra?.let { v ->
            val (sig, rest) = splitSignal(v)
            val block = if (rest == null) SignalBlock.PERMISSION_DENIED
            else SignalBlock.entries.firstOrNull { it.name == rest }
            if (sig == null || block == null) w("debug invalid block=$v")
            else {
                simulator.block(sig, block)
                i("debug block signal=$sig block=$block")
            }
        }
        if (extras?.containsKey("speedKmh") == true) {
            val speed = intent.getFloatExtra("speedKmh", Float.NaN) // --ef 로 보낸다. 다른 타입이면 NaN → 거부
            if (!speed.isFinite()) w("debug invalid speedKmh=(not a number)")
            else {
                simulator.setSpeed(speed)
                i("debug speedKmh=$speed")
            }
        }
        intent.getStringExtra("gear")?.let { g ->
            if (simulator.setGear(g)) i("debug gear=$g") else w("debug invalid gear=$g")
        }
    }

    /** "SPEED" 또는 "SPEED:REASON" → (신호, 사유 문자열). 신호가 잘못되면 null. */
    private fun splitSignal(v: String): Pair<VehicleSignal?, String?> {
        val parts = v.trim().uppercase().split(":", limit = 2)
        val sig = VehicleSignal.entries.firstOrNull { it.name == parts[0] }
        return sig to parts.getOrNull(1)
    }

    private fun i(m: String) { Timber.i(m) }
    private fun w(m: String) { Timber.w(m) }

    companion object {
        const val ACTION_VEHICLE = "com.stai.pleos_red_light_voca_app.debug.VEHICLE"
        const val ACTION_MEDIA = "com.stai.pleos_red_light_voca_app.debug.MEDIA"
    }
}
