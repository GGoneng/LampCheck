// -----------------------------------------------------------------------------------
// 파일명       : SimulatedBrightnessSensorTest.kt
// 설명         : 시연용 밝기 센서 회귀 테스트
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 켜진 램프만 측정하고, 꺼진 램프는 마지막 측정값을 유지하는지 확인
// - 기본 밝기가 정격 수명에서 수명 끝 밝기에 닿는지 확인 (LED, 할로겐)
// - 시연 조작(열화, 고장, 초기화)이 측정값에 반영되는지 확인
// - 시연 조작마다 상태가 저장되고, 저장한 상태로 다시 만들면 열화·고장이 그대로인지 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.sensor

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.model.LightSource
import io.github.ggoneng.lampcheck.vehicle.Gear
import io.github.ggoneng.lampcheck.vehicle.VehicleSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val EPSILON: Double = 1e-9

private val LOW_LEFT: LampId = LampId(LampGroup.LOW_BEAM, LampSide.LEFT)
private val LOW_RIGHT: LampId = LampId(LampGroup.LOW_BEAM, LampSide.RIGHT)
private val REVERSE_LEFT: LampId = LampId(LampGroup.REVERSE, LampSide.LEFT)
private val BRAKE_RIGHT: LampId = LampId(LampGroup.BRAKE, LampSide.RIGHT)

// 하향등만 켜진 신호
private val LOW_BEAM_ON: VehicleSignals = VehicleSignals(connected = true, litGroups = setOf(LampGroup.LOW_BEAM))

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// 센서와 가짜 신호, 누적 시간, 저장 기록을 묶은 테스트 환경
private class SensorFixture(scope: TestScope, initialFaults: SimulatedFaults = SimulatedFaults()) {
    val signals: MutableStateFlow<VehicleSignals> = MutableStateFlow(VehicleSignals(connected = true))
    val hours: MutableStateFlow<Map<LampId, Double>> = MutableStateFlow(emptyMap())
    val saved: MutableList<SimulatedFaults> = mutableListOf()
    val sensor: SimulatedBrightnessSensor =
        SimulatedBrightnessSensor(signals, hours, initialFaults, { saved += it }, scope.backgroundScope)

    // 램프 하나의 측정값
    fun reading(lamp: LampId): Double = sensor.readings.value.getValue(lamp)
}

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedBrightnessSensorTest {

    @Test
    fun measuresOnlyLitLamps_andKeepsLastReadingAfterOff() = runTest {
        val fixture = SensorFixture(this)
        fixture.sensor.start()
        runCurrent()

        // 아무것도 안 켜졌으면 측정값 없음
        assertTrue(fixture.sensor.readings.value.isEmpty())

        // 하향등을 켜면 좌우 하향등만 측정 (새 램프는 100%)
        fixture.signals.value = LOW_BEAM_ON
        runCurrent()
        assertEquals(setOf(LOW_LEFT, LOW_RIGHT), fixture.sensor.readings.value.keys)
        assertEquals(100.0, fixture.reading(LOW_LEFT), EPSILON)

        // 끈 뒤에 누적 시간이 바뀌어도 마지막 측정값을 유지
        fixture.signals.value = VehicleSignals(connected = true)
        fixture.hours.value = mapOf(LOW_LEFT to 5_000.0)
        runCurrent()
        assertEquals(100.0, fixture.reading(LOW_LEFT), EPSILON)
    }

    @Test
    fun baseBrightness_reachesEndOfLifeAtRatedLife() = runTest {
        val fixture = SensorFixture(this)
        fixture.sensor.start()

        // LED 하향등 정격 수명(10,000시간), 할로겐 후진등 정격 수명(500시간)
        fixture.hours.value = mapOf(
            LOW_LEFT to LightSource.LED.ratedLifeHours,
            REVERSE_LEFT to LightSource.HALOGEN.ratedLifeHours
        )
        fixture.signals.value = VehicleSignals(connected = true, litGroups = setOf(LampGroup.LOW_BEAM), gear = Gear.REVERSE)
        runCurrent()

        assertEquals(LightSource.LED.endOfLifePercent, fixture.reading(LOW_LEFT), EPSILON)
        assertEquals(LightSource.HALOGEN.endOfLifePercent, fixture.reading(REVERSE_LEFT), EPSILON)
    }

    @Test
    fun degrade_lowersOnlyThatLamp_andAccumulates() = runTest {
        val fixture = SensorFixture(this)
        fixture.sensor.start()
        fixture.signals.value = LOW_BEAM_ON
        runCurrent()

        // 왼쪽 하향등만 10%p씩 세 번 낮춤
        repeat(3) { fixture.sensor.degrade(LOW_LEFT, 10.0) }
        runCurrent()

        assertEquals(70.0, fixture.reading(LOW_LEFT), EPSILON)
        assertEquals(100.0, fixture.reading(LOW_RIGHT), EPSILON)
    }

    @Test
    fun degrade_isMeasuredWhenLampTurnsOn() = runTest {
        val fixture = SensorFixture(this)
        fixture.sensor.start()
        runCurrent()

        // 꺼진 램프는 낮춰도 측정값이 없다가, 켜면 낮춘 값으로 측정
        fixture.sensor.degrade(LOW_LEFT, 25.0)
        runCurrent()
        assertTrue(LOW_LEFT !in fixture.sensor.readings.value)
        fixture.signals.value = LOW_BEAM_ON
        runCurrent()
        assertEquals(75.0, fixture.reading(LOW_LEFT), EPSILON)
    }

    @Test
    fun fail_readsZero_andResetRestoresBaseBrightness() = runTest {
        val fixture = SensorFixture(this)
        fixture.sensor.start()
        fixture.signals.value = VehicleSignals(connected = true, brakePressed = true)
        runCurrent()

        // 오른쪽 브레이크등 고장 → 0%, 열화도 함께 걸어 둠
        fixture.sensor.degrade(BRAKE_RIGHT, 10.0)
        fixture.sensor.fail(BRAKE_RIGHT)
        runCurrent()
        assertEquals(0.0, fixture.reading(BRAKE_RIGHT), EPSILON)
        assertEquals(100.0, fixture.reading(LampId(LampGroup.BRAKE, LampSide.LEFT)), EPSILON)

        // 초기화하면 열화와 고장이 모두 없어짐
        fixture.sensor.reset(BRAKE_RIGHT)
        runCurrent()
        assertEquals(100.0, fixture.reading(BRAKE_RIGHT), EPSILON)
    }

    @Test
    fun degrade_neverGoesBelowZero() = runTest {
        val fixture = SensorFixture(this)
        fixture.sensor.start()
        fixture.signals.value = LOW_BEAM_ON
        runCurrent()

        // 100%p 넘게 낮춰도 0%에서 멈춤
        repeat(12) { fixture.sensor.degrade(LOW_LEFT, 10.0) }
        runCurrent()
        assertEquals(0.0, fixture.reading(LOW_LEFT), EPSILON)
    }

    @Test
    fun faults_areSavedOnEachChange() = runTest {
        val fixture = SensorFixture(this)

        // 열화 두 번, 고장, 초기화마다 저장
        fixture.sensor.degrade(LOW_LEFT, 10.0)
        fixture.sensor.degrade(LOW_LEFT, 10.0)
        fixture.sensor.fail(BRAKE_RIGHT)
        assertEquals(SimulatedFaults(extraDrop = mapOf(LOW_LEFT to 20.0), failed = setOf(BRAKE_RIGHT)), fixture.saved.last())

        // 초기화하면 그 램프 상태만 지워 저장
        fixture.sensor.reset(BRAKE_RIGHT)
        assertEquals(SimulatedFaults(extraDrop = mapOf(LOW_LEFT to 20.0)), fixture.saved.last())
        assertEquals(4, fixture.saved.size)
    }

    @Test
    fun savedFaults_areRestoredAfterRestart() = runTest {
        // 첫 실행: 왼쪽 하향등 30%p 열화, 오른쪽 브레이크등 고장
        val first = SensorFixture(this)
        repeat(3) { first.sensor.degrade(LOW_LEFT, 10.0) }
        first.sensor.fail(BRAKE_RIGHT)

        // 다시 켬: 저장한 상태로 새 센서를 만들고 하향등과 브레이크를 켬
        val restarted = SensorFixture(this, first.saved.last())
        restarted.sensor.start()
        restarted.signals.value = VehicleSignals(connected = true, litGroups = setOf(LampGroup.LOW_BEAM), brakePressed = true)
        runCurrent()

        // 회복되지 않고 열화·고장이 그대로 측정됨
        assertEquals(70.0, restarted.reading(LOW_LEFT), EPSILON)
        assertEquals(0.0, restarted.reading(BRAKE_RIGHT), EPSILON)
        assertEquals(100.0, restarted.reading(LOW_RIGHT), EPSILON)
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
