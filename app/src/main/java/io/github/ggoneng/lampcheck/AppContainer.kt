// -----------------------------------------------------------------------------------
// 파일명       : AppContainer.kt
// 설명         : 차량·음성·정비소 구현체와 점등 시간 누적, 밝기 센서, 저장소, 수명 추정, 교체 알림을 만들고 연결
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - PleosVehicleGateway, PleosVoiceAssistant, PleosRepairShopNavigator 생성
// - 점등 시간 누적기, 시연용 밝기 센서, 저장소 연결 (켜진 램프의 측정값을 저장소에 기록)
// - 누적 시간과 측정값으로 램프별 수명 추정 결과를 StateFlow로 계산, 교체 알림 연결
// - 구현체의 시작과 해제를 한곳에서 처리
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import android.content.Context
import io.github.ggoneng.lampcheck.alert.ReplacementAlert
import io.github.ggoneng.lampcheck.data.LampDataStore
import io.github.ggoneng.lampcheck.lifespan.estimateLamp
import io.github.ggoneng.lampcheck.model.ALL_LAMPS
import io.github.ggoneng.lampcheck.model.BrightnessSample
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.navi.PleosRepairShopNavigator
import io.github.ggoneng.lampcheck.navi.RepairShopNavigator
import io.github.ggoneng.lampcheck.sensor.SimulatedBrightnessSensor
import io.github.ggoneng.lampcheck.usage.LampUsageTracker
import io.github.ggoneng.lampcheck.usage.averageDailyHours
import io.github.ggoneng.lampcheck.usage.litLamps
import io.github.ggoneng.lampcheck.vehicle.PleosVehicleGateway
import io.github.ggoneng.lampcheck.vehicle.VehicleGateway
import io.github.ggoneng.lampcheck.voice.PleosVoiceAssistant
import io.github.ggoneng.lampcheck.voice.VoiceAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 알림을 말하기 전에 TTS 초기화를 기다리는 최대 시간
private const val TTS_READY_TIMEOUT_MILLIS: Long = 10_000L

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 앱 전체에서 함께 쓰는 객체 묶음 (context는 Application context, scope는 ViewModel 범위를 넘김)
class AppContainer(context: Context, private val scope: CoroutineScope) {
    // 현재 시각
    private val now: () -> Long = System::currentTimeMillis

    // 차량 신호와 램프 제어
    val vehicle: VehicleGateway = PleosVehicleGateway(context)

    // 음성 안내
    val voice: VoiceAssistant = PleosVoiceAssistant(context)

    // 현재 위치, 정비소 검색, 길 안내 (카카오 키는 local.properties에서 BuildConfig로 들어옴)
    val navigator: RepairShopNavigator = PleosRepairShopNavigator(context, BuildConfig.KAKAO_REST_API_KEY)

    // 누적 점등 시간과 밝기 측정값 저장소
    val store: LampDataStore = LampDataStore(context)

    // 램프별 누적 점등 시간 (저장된 기록에서 이어서 셈)
    val usage: LampUsageTracker = LampUsageTracker(vehicle.signals, store.loadUsage(), store::saveUsage, now, scope)

    // 시연용 밝기 센서 (저장해 둔 열화·고장 상태에서 이어서 시작)
    val sensor: SimulatedBrightnessSensor = SimulatedBrightnessSensor(
        vehicle.signals,
        usage.usage.map { it.usedHours },
        store.loadFaults(),
        store::saveFaults,
        scope
    )

    // 램프별 수명 추정 결과 (누적 시간, 측정값, 차량 신호가 바뀔 때마다 다시 계산)
    val conditions: StateFlow<List<LampCondition>> = combine(usage.usage, store.samples, vehicle.signals) { used, samples, signals ->
        val lit: Set<LampId> = litLamps(signals)
        val current: Long = now()
        ALL_LAMPS.map { lamp ->
            val hours: Double = used.usedHours[lamp] ?: 0.0
            estimateLamp(
                lamp = lamp,
                usedHours = hours,
                samples = samples[lamp].orEmpty(),
                dailyHours = averageDailyHours(hours, used.firstRecordedAtMillis, current),
                shouldBeLit = lamp in lit,
                nowMillis = current
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // 교체 알림 (TTS가 아직 준비되지 않았으면 잠깐 기다렸다가 말함)
    private val alert: ReplacementAlert = ReplacementAlert(conditions, { text -> speakWhenReady(text) }, scope)

    // 측정값을 저장소에 옮겨 적는 작업
    private var recordJob: Job? = null

    // 구현체 연결과 누적, 측정, 기록, 알림 시작
    fun start() {
        vehicle.start()
        voice.start()
        navigator.start()
        usage.start()
        sensor.start()
        recordJob = scope.launch {
            // 센서는 켜진 램프만 새로 재므로 지금 켜진 램프의 측정값만 그때의 누적 시간과 함께 기록
            sensor.readings.collect { readings ->
                val lit: Set<LampId> = litLamps(vehicle.signals.value)
                for ((lamp, brightness) in readings) {
                    if (lamp !in lit) continue
                    val hours: Double = usage.usage.value.usedHours[lamp] ?: 0.0
                    store.addSample(lamp, BrightnessSample(hours, brightness, now()))
                }
            }
        }
        alert.start()
    }

    // 구현체 해제 (시작의 반대 순서, 누적 시간은 마지막으로 저장)
    fun release() {
        recordJob?.cancel()
        sensor.release()
        usage.stop()
        navigator.release()
        voice.release()
        vehicle.release()
    }

    // TTS 준비를 기다린 뒤 말함 (시간 안에 준비되지 않으면 말하지 않음)
    private suspend fun speakWhenReady(text: String) {
        withTimeoutOrNull(TTS_READY_TIMEOUT_MILLIS) { voice.canSpeak.first { it } } ?: return
        voice.speak(text)
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
