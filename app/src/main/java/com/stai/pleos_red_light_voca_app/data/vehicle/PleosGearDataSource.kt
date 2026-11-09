package com.stai.pleos_red_light_voca_app.data.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import timber.log.Timber
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Gear as PleosGear
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Pleos Vehicle SDK의 기어 선택 상태를 GEAR 신호로 내보낸다. 연결 상태 판단은 [GearLinkMonitor]가 한다.
 *
 * 동작:
 * - 수집 시작 시 Vehicle을 initialize하고 리스너를 등록한 뒤 바로 현재값을 조회한다.
 * - [POLL_INTERVAL_MS]마다 getCurrentGearSelectionState로 상태를 확인한다. [POLL_TIMEOUT_MS] 안에 응답이 없거나
 *   실패(차단 제외)가 오면 SignalFailed를 상태가 바뀔 때 한 번만 보낸다.
 * - 실패 상태에서 연결 계열 오류(ERROR_SERVICE_CONNECTION_FAILURE·ERROR_INTERNAL_SERVICE·무응답)가 이어지면
 *   [GearLinkMonitor.DEFAULT_RECONNECT_INTERVAL_MS] 간격 이상으로 리스너 해제 → release → 새 Vehicle initialize →
 *   리스너 재등록을 한다. 옛 Vehicle의 release는 그 연결의 대기 중 조회 콜백이 끝난 뒤(최대 [RELEASE_TIMEOUT_MS])에 한다.
 * - 복구(조회 성공 또는 onSelected)되면 GearChanged(현재값)를 보낸다. 재주행 확인은 컨트롤러가 한다.
 * - 차단(권한·미지원)이면 주기 조회·재연결을 멈춘다. 리스너 값이 다시 오면 복구로 보고 조회를 다시 한다.
 * - onSelected·onFailed·조회 콜백은 모두 메인 Looper Handler로 post한 뒤 처리·전송한다(도착 순서 유지).
 *
 * 감지할 수 있는 것: 조회 실패·무응답, 리스너 onFailed, 연결 설정 예외.
 * 감지할 수 없는 것(지연): SDK는 서비스 단절(onServiceDisconnected) 시 binder만 null로 바꾸고 리스너에 알리지 않는다.
 * 이 경우는 다음 주기 조회의 실패·무응답으로만 알 수 있어 최대 약 [POLL_INTERVAL_MS] + [POLL_TIMEOUT_MS](5초)
 * 늦게 감지한다. 그 사이의 변속은 복구 뒤 조회값으로 보정된다. SDK 내부 재연결이 리스너를 되살리는지는 확인하지 못해
 * 앱이 직접 재연결한다.
 */
// Pleos Gear 선택 API는 deprecated(Google GEAR_SELECTION 속성 권장)이나 요구에 따라 이 API를 쓴다.
@Suppress("DEPRECATION")
class PleosGearDataSource(
    context: Context,
    private val log: (String) -> Unit = { Timber.i(it) },
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : VehicleStateSource {
    private val appContext = context.applicationContext

    /** Vehicle 한 개와 그 연결의 상태. 메인 스레드에서만 만진다. */
    private class Link(val vehicle: Vehicle) {
        var gear: PleosGear? = null
        var listener: GearSelectionListener? = null
        var pending = 0          // 끝나지 않은 조회 요청 수
        var retired = false      // 더 쓰지 않음(release 대기)
        var released = false
    }

    override fun events(): Flow<VehicleEvent> = callbackFlow {
        val main = Handler(Looper.getMainLooper())
        val monitor = GearLinkMonitor(startedAtMs = now())
        var link: Link? = null
        var closed = false

        // SDK 콜백은 스레드가 확인되지 않아 모두 post로 메인 큐에 넣는다(인라인 실행과 섞이면 순서가 바뀔 수 있다).
        fun post(block: () -> Unit) {
            main.post { if (!closed) block() }
        }

        fun apply(d: GearLinkMonitor.Decision, reconnect: () -> Unit) {
            d.emit?.let { trySend(it) }
            if (d.reconnect) reconnect()
        }

        fun releaseIfIdle(l: Link) {
            if (l.retired && l.pending == 0 && !l.released) {
                l.released = true
                runCatching { l.vehicle.release() }
                    .onFailure { log("gear: release failed ${it.javaClass.simpleName} ${it.message}") }
            }
        }

        // 문서: 비동기 콜백이 끝나기 전에 release()를 호출하지 않는다. 대기 요청이 있으면 미루고 최대 3초 뒤 강제 release.
        fun retire(l: Link) {
            if (l.retired) return
            l.retired = true
            val lst = l.listener
            if (lst != null) runCatching { l.gear?.unregisterGearSelection(lst) }
                .onFailure { log("gear: unregister failed ${it.javaClass.simpleName} ${it.message}") }
            if (l.pending > 0) {
                log("gear: release deferred, pending=${l.pending}")
                main.postDelayed({
                    if (!l.released) {
                        l.released = true
                        log("gear: release forced after timeout, pending=${l.pending}")
                        runCatching { l.vehicle.release() }
                    }
                }, RELEASE_TIMEOUT_MS)
            } else {
                releaseIfIdle(l)
            }
        }

        fun failureKindOf(e: Throwable): GearLinkMonitor.FailureKind =
            if (isPleosConnectionError(e.message)) GearLinkMonitor.FailureKind.CONNECTION
            else GearLinkMonitor.FailureKind.OTHER

        fun fromThrowable(e: Throwable): VehicleEvent =
            classifyPleosFailure(VehicleSignal.GEAR, e.message, e is SecurityException)

        lateinit var reconnect: () -> Unit

        fun poll() {
            val l = link ?: return
            val g = l.gear ?: return
            val ticket = monitor.startPoll() ?: return
            l.pending++
            var answered = false
            fun finish(): Boolean {
                if (answered) return false
                answered = true
                l.pending--
                releaseIfIdle(l)
                return true
            }
            val timeout = Runnable {
                if (closed) return@Runnable
                log("gear: poll ${ticket.id} timed out")
                apply(monitor.onPollTimeout(ticket, now()), reconnect)
            }
            try {
                g.getCurrentGearSelectionState(
                    { gear: VehicleGear? ->
                        // 닫힌 뒤에도 pending은 줄여야 release가 미뤄지지 않으므로 post 래퍼 대신 main.post를 쓴다.
                        main.post {
                            if (!finish() || closed) return@post
                            main.removeCallbacks(timeout)
                            apply(monitor.onPollResult(ticket, mapGearName(gear?.name)), reconnect)
                        }
                    },
                    { e: Exception ->
                        main.post {
                            if (!finish() || closed) return@post
                            main.removeCallbacks(timeout)
                            log("gear: poll ${ticket.id} failed ${e.message}")
                            apply(monitor.onPollFailure(ticket, fromThrowable(e), failureKindOf(e), now()), reconnect)
                        }
                    },
                )
                main.postDelayed(timeout, POLL_TIMEOUT_MS)
            } catch (e: Exception) {
                answered = true
                l.pending--
                monitor.cancelPoll(ticket)
                log("gear: poll ${ticket.id} threw ${e.javaClass.simpleName} ${e.message}")
                apply(monitor.onPollFailure(ticket, fromThrowable(e), failureKindOf(e), now()), reconnect)
            }
        }

        /** 새 Vehicle을 만들고 리스너를 등록한다. 실패하면 모니터에 실패로 알린다. */
        fun connect() {
            try {
                val v = Vehicle(appContext)
                val l = Link(v)
                link = l
                monitor.onLinkCreated(now())
                v.initialize()
                val g = v.getGear()
                l.gear = g
                val lst = object : GearSelectionListener {
                    override fun onSelected(gear: VehicleGear?) {
                        post {
                            if (link !== l) return@post // 재연결 전 연결의 늦은 콜백
                            apply(monitor.onListenerValue(mapGearName(gear?.name)), reconnect)
                        }
                    }

                    override fun onFailed(e: Exception) {
                        post {
                            if (link !== l) return@post
                            log("gear: onFailed ${e.message}")
                            apply(monitor.onListenerFailure(fromThrowable(e), failureKindOf(e), now()), reconnect)
                        }
                    }
                }
                l.listener = lst
                g.registerGearSelection(lst)
            } catch (e: Exception) {
                log("gear: setup failed ${e.javaClass.simpleName} ${e.message}")
                val event = if (e is SecurityException) {
                    VehicleEvent.SignalBlocked(VehicleSignal.GEAR, SignalBlock.PERMISSION_DENIED)
                } else {
                    VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED)
                }
                apply(monitor.onListenerFailure(event, GearLinkMonitor.FailureKind.CONNECTION, now()), reconnect)
            }
        }

        reconnect = {
            log("gear: reconnecting")
            link?.let { retire(it) }
            link = null
            connect()
            poll()
        }

        val pollTick = object : Runnable {
            override fun run() {
                if (closed) return
                poll()
                main.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        main.post {
            if (closed) return@post
            connect()
            pollTick.run() // 첫 조회는 바로, 이후 주기 조회
        }

        awaitClose {
            main.post {
                closed = true
                main.removeCallbacks(pollTick)
                link?.let { retire(it) }
                link = null
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_TIMEOUT_MS = 2_000L
        const val RELEASE_TIMEOUT_MS = 3_000L
    }
}
