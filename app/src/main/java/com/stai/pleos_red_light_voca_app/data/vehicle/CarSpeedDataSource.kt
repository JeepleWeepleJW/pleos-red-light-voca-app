package com.stai.pleos_red_light_voca_app.data.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val CAR_SPEED_PERMISSION = "android.car.permission.CAR_SPEED"

/**
 * android.car CarPropertyManager의 PERF_VEHICLE_SPEED(m/s)를 SPEED 신호로 내보낸다.
 * 콜백은 메인 Looper 핸들러에서 오며, 같은 속도값이 연속이면 다시 내보내지 않는다.
 * 권한이 없으면 PERMISSION_DENIED를 내보내고 끝난다(재수집은 앱이 한다).
 * 속성 설정이 없거나 registerCallback이 false면 UNSUPPORTED(차단)를 내보낸다.
 */
// getStatus/unregisterCallback는 37 SDK에서 deprecated이나 API 34 기기에서는 이것뿐이라 유지한다.
@Suppress("DEPRECATION")
class CarSpeedDataSource(
    context: Context,
    private val log: (String) -> Unit = { Timber.i(it) },
) : VehicleStateSource {
    private val appContext = context.applicationContext

    override fun events(): Flow<VehicleEvent> = callbackFlow {
        fun blocked(b: SignalBlock) = trySend(VehicleEvent.SignalBlocked(VehicleSignal.SPEED, b))
        fun failed(f: SignalFailure) = trySend(VehicleEvent.SignalFailed(VehicleSignal.SPEED, f))

        // android.car는 공개 SDK optional 라이브러리(uses-library). AAOS가 아닌 기기에는 없어 존재만 확인한다.
        @Suppress("PrivateApi")
        val carMissing = runCatching { Class.forName("android.car.Car") }.isFailure
        if (carMissing) {
            log("speed: android.car unavailable")
            blocked(SignalBlock.UNSUPPORTED)
            awaitClose { }
            return@callbackFlow
        }
        if (appContext.checkSelfPermission(CAR_SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            log("speed: CAR_SPEED permission denied")
            blocked(SignalBlock.PERMISSION_DENIED)
            awaitClose { }
            return@callbackFlow
        }

        // 아래 상태는 모두 메인 스레드(핸들러·수집 취소 시 awaitClose는 호출 스레드)에서 다룬다.
        var manager: CarPropertyManager? = null
        var lastSpeed: Float? = null
        var car: Car? = null

        fun emitSpeed(e: VehicleEvent) {
            if (e is VehicleEvent.SpeedChanged) {
                if (e.speedKmh == lastSpeed) return
                lastSpeed = e.speedKmh
            } else {
                lastSpeed = null
            }
            trySend(e)
        }

        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val v = (value.value as? Number)?.toFloat()
                emitSpeed(
                    mapSpeedStatus(
                        value.status, v,
                        CarPropertyValue.STATUS_AVAILABLE,
                        CarPropertyValue.STATUS_UNAVAILABLE,
                        CarPropertyValue.STATUS_ERROR,
                    ),
                )
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                log("speed: onErrorEvent property=$propertyId zone=$zone")
                emitSpeed(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR))
            }
        }

        fun unsubscribe() {
            manager?.let { m ->
                runCatching { m.unregisterCallback(callback) }
                    .onFailure { log("speed: unregister failed ${it.javaClass.simpleName}") }
            }
            manager = null
        }

        fun subscribe(m: CarPropertyManager) {
            try {
                if (m.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) == null) {
                    log("speed: PERF_VEHICLE_SPEED not supported")
                    blocked(SignalBlock.UNSUPPORTED)
                    return
                }
                manager = m
                // 현재값 먼저
                try {
                    val current = m.getProperty(
                        java.lang.Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0,
                    )
                    val v = current?.value as? Number
                    emitSpeed(
                        if (current == null) {
                            VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.READ_FAILED)
                        } else {
                            mapSpeedStatus(
                                current.status, v?.toFloat(),
                                CarPropertyValue.STATUS_AVAILABLE,
                                CarPropertyValue.STATUS_UNAVAILABLE,
                                CarPropertyValue.STATUS_ERROR,
                            )
                        },
                    )
                } catch (e: SecurityException) {
                    log("speed: getProperty permission ${e.message}")
                    blocked(SignalBlock.PERMISSION_DENIED)
                    unsubscribe()
                    return
                } catch (e: IllegalArgumentException) {
                    log("speed: getProperty unsupported ${e.message}")
                    blocked(SignalBlock.UNSUPPORTED)
                    unsubscribe()
                    return
                } catch (e: Exception) {
                    // PropertyNotAvailable 계열 포함. 값 없음/일시 불가 → READ_FAILED (구독은 계속 시도)
                    log("speed: getProperty failed ${e.javaClass.simpleName} ${e.message}")
                    emitSpeed(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.READ_FAILED))
                }
                // API 35의 subscribePropertyEvents는 API 34에 없어, 34에서도 동작하는 registerCallback을 쓴다.
                @Suppress("DEPRECATION")
                val ok = m.registerCallback(
                    callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI,
                )
                if (!ok) {
                    // false는 이 속성을 구독할 수 없다는 뜻(일시 오류가 아님) → 차단으로 학습을 끈다.
                    log("speed: registerCallback returned false")
                    blocked(SignalBlock.UNSUPPORTED)
                }
            } catch (e: SecurityException) {
                log("speed: subscribe permission ${e.message}")
                blocked(SignalBlock.PERMISSION_DENIED)
            } catch (e: Exception) {
                log("speed: subscribe failed ${e.javaClass.simpleName} ${e.message}")
                failed(SignalFailure.ERROR)
            }
        }

        val handler = Handler(Looper.getMainLooper())
        try {
            car = Car.createCar(
                appContext, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
            ) { c, ready ->
                if (ready) {
                    log("speed: car service ready")
                    val m = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }
                        .getOrNull()
                    if (m == null) {
                        log("speed: CarPropertyManager unavailable")
                        blocked(SignalBlock.UNSUPPORTED)
                    } else {
                        unsubscribe()
                        subscribe(m)
                    }
                } else {
                    log("speed: car service disconnected")
                    manager = null
                    emitSpeed(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.DISCONNECTED))
                }
            }
        } catch (e: SecurityException) {
            log("speed: createCar permission ${e.message}")
            blocked(SignalBlock.PERMISSION_DENIED)
        } catch (e: Exception) {
            log("speed: createCar failed ${e.javaClass.simpleName} ${e.message}")
            failed(SignalFailure.DISCONNECTED)
        }

        awaitClose {
            unsubscribe()
            runCatching { car?.disconnect() }
        }
    }
}
