// -----------------------------------------------------------------------------------
// 파일명       : SimulatedBrightnessSensor.kt
// 설명         : 실제 차량 밝기 센서 대신 누적 점등 시간으로 밝기를 계산해 내는 시연용 센서
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 지금 켜져 있는 램프만 측정값을 냄 (꺼진 램프는 마지막 측정값 유지)
// - 기본 밝기 = 100 × e^(−k₀·누적 시간), 정격 수명에서 수명 끝 밝기에 닿도록 k₀를 정함
// - 시연 조작: 램프별 추가 열화(밝기 %p 낮추기), 고장(0%), 초기화
// - 시연 조작 상태를 주입받은 저장 함수로 저장하고, 시작할 때 받은 상태로 복원
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.sensor

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.lifespan.lightSourceOf
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LightSource
import io.github.ggoneng.lampcheck.usage.litLamps
import io.github.ggoneng.lampcheck.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 램프별 시연 조작 상태
// extraDrop: 추가로 낮춘 밝기(%p), failed: 고장 낸 램프
data class SimulatedFaults(
    val extraDrop: Map<LampId, Double> = emptyMap(),
    val failed: Set<LampId> = emptySet()
)

// 시연용 밝기 센서
// signals: 차량 신호 (켜진 램프 판단), usedHours: 램프별 누적 점등 시간
// initialFaults: 저장해 둔 시연 조작 상태, saveFaults: 시연 조작 상태 저장 함수 (테스트에서 가짜 저장을 넣을 수 있게 주입받음)
class SimulatedBrightnessSensor(
    private val signals: Flow<VehicleSignals>,
    private val usedHours: Flow<Map<LampId, Double>>,
    initialFaults: SimulatedFaults,
    private val saveFaults: (SimulatedFaults) -> Unit,
    private val scope: CoroutineScope
) : BrightnessSensor {

    private val _readings: MutableStateFlow<Map<LampId, Double>> = MutableStateFlow(emptyMap())
    override val readings: StateFlow<Map<LampId, Double>> = _readings.asStateFlow()

    // 시연 조작 상태 (저장해 둔 상태에서 시작)
    private val faults: MutableStateFlow<SimulatedFaults> = MutableStateFlow(initialFaults)

    // 측정 작업
    private var job: Job? = null

    override fun start() {
        if (job != null) return

        // 신호, 누적 시간, 시연 조작 중 하나가 바뀌면 켜진 램프를 다시 잼
        job = scope.launch {
            combine(signals, usedHours, faults) { value, hours, demo -> measure(value, hours, demo) }
                .collect { measured -> _readings.update { it + measured } }
        }
    }

    override fun release() {
        job?.cancel()
        job = null
    }

    // 시연: 램프 밝기를 percentPoints(%p)만큼 더 낮춤
    fun degrade(lamp: LampId, percentPoints: Double) {
        changeFaults { current ->
            val drop: Double = (current.extraDrop[lamp] ?: 0.0) + percentPoints
            current.copy(extraDrop = current.extraDrop + (lamp to drop))
        }
    }

    // 시연: 램프를 고장 냄 (밝기 0%)
    fun fail(lamp: LampId) {
        changeFaults { it.copy(failed = it.failed + lamp) }
    }

    // 시연: 램프의 열화와 고장을 없앰
    fun reset(lamp: LampId) {
        changeFaults { SimulatedFaults(extraDrop = it.extraDrop - lamp, failed = it.failed - lamp) }
    }

    // 시연 조작 상태를 바꾸고 바로 저장
    private fun changeFaults(transform: (SimulatedFaults) -> SimulatedFaults) {
        faults.update(transform)
        saveFaults(faults.value)
    }

    // 켜진 램프마다 밝기 계산
    private fun measure(value: VehicleSignals, hours: Map<LampId, Double>, demo: SimulatedFaults): Map<LampId, Double> =
        litLamps(value).associateWith { lamp ->
            if (lamp in demo.failed) {
                0.0
            } else {
                val base: Double = baseBrightness(lightSourceOf(lamp), hours[lamp] ?: 0.0)
                (base - (demo.extraDrop[lamp] ?: 0.0)).coerceIn(0.0, 100.0)
            }
        }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 열화나 고장이 없을 때의 밝기(처음 대비 %): 100 × e^(−k₀·t), k₀ = ln(100 / 수명 끝 밝기) / 정격 수명
private fun baseBrightness(source: LightSource, usedHours: Double): Double {
    val k0: Double = ln(100.0 / source.endOfLifePercent) / source.ratedLifeHours
    return 100.0 * exp(-k0 * usedHours)
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
