// -----------------------------------------------------------------------------------
// 파일명       : LampUsageTrackerTest.kt
// 설명         : 점등 시간 누적기 회귀 테스트 (가상 시간)
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 하향등, 브레이크등, 후진등, 방향지시등이 켜진 동안만 해당 램프에 시간이 쌓이는지 확인
// - 주기 저장과 종료 저장, 처음 기록 시각 저장, 저장된 기록에서 이어 세기 확인
// - 시연용 사용 시간 더하기와 하루 평균 사용 시간 계산(최대 24시간, 짧은 기록은 0) 확인
// - 램프 교체가 그 램프의 누적 시간만 지우고 교체 시각부터 다시 세는지 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.usage

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.ALL_LAMPS
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.vehicle.Gear
import io.github.ggoneng.lampcheck.vehicle.TurnSignalState
import io.github.ggoneng.lampcheck.vehicle.VehicleSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val HOUR: Long = 3_600_000L
private const val EPSILON: Double = 1e-9

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// 누적기와 가짜 신호, 저장 기록을 묶은 테스트 환경 (가상 시각은 testScheduler에서 읽음)
@OptIn(ExperimentalCoroutinesApi::class)
private class TrackerFixture(scope: TestScope, initial: LampUsage = LampUsage()) {
    val signals: MutableStateFlow<VehicleSignals> = MutableStateFlow(VehicleSignals(connected = true))
    val saved: MutableList<LampUsage> = mutableListOf()
    val tracker: LampUsageTracker = LampUsageTracker(
        signals = signals,
        initial = initial,
        save = { saved += it },
        now = { scope.testScheduler.currentTime },
        scope = scope.backgroundScope
    )

    // 램프 하나의 누적 시간
    fun hours(lamp: LampId): Double = tracker.usage.value.usedHours[lamp] ?: 0.0
}

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class LampUsageTrackerTest {

    @Test
    fun lowBeam_accumulatesOnBothSides_onlyWhileLit() = runTest {
        val fixture = TrackerFixture(this)
        fixture.tracker.start()
        runCurrent()

        // 하향등 1시간 켬
        fixture.signals.value = VehicleSignals(connected = true, litGroups = setOf(LampGroup.LOW_BEAM))
        runCurrent()
        advanceTimeBy(HOUR)
        runCurrent()

        // 끈 뒤 30분은 쌓이지 않음
        fixture.signals.value = VehicleSignals(connected = true)
        runCurrent()
        advanceTimeBy(HOUR / 2)
        runCurrent()

        assertEquals(1.0, fixture.hours(LampId(LampGroup.LOW_BEAM, LampSide.LEFT)), EPSILON)
        assertEquals(1.0, fixture.hours(LampId(LampGroup.LOW_BEAM, LampSide.RIGHT)), EPSILON)
        assertOnlyLampsUsed(fixture, setOf(LampId(LampGroup.LOW_BEAM, LampSide.LEFT), LampId(LampGroup.LOW_BEAM, LampSide.RIGHT)))

        // 주기 저장에 같은 값이 들어감
        assertEquals(1.0, fixture.saved.last().usedHours[LampId(LampGroup.LOW_BEAM, LampSide.LEFT)]!!, EPSILON)
    }

    @Test
    fun brake_accumulatesOnAllThreeLamps() = runTest {
        val fixture = TrackerFixture(this)
        fixture.tracker.start()
        runCurrent()

        // 브레이크 30분 밟음
        fixture.signals.value = VehicleSignals(connected = true, brakePressed = true)
        runCurrent()
        advanceTimeBy(HOUR / 2)
        fixture.signals.value = VehicleSignals(connected = true)
        runCurrent()

        val brakes: Set<LampId> = LampGroup.BRAKE.sides.map { LampId(LampGroup.BRAKE, it) }.toSet()
        brakes.forEach { assertEquals(it.label, 0.5, fixture.hours(it), EPSILON) }
        assertOnlyLampsUsed(fixture, brakes)
    }

    @Test
    fun reverseGear_accumulatesReverseLamps() = runTest {
        val fixture = TrackerFixture(this)
        fixture.tracker.start()
        runCurrent()

        // 기어 R 15분, 그 뒤 D 15분
        fixture.signals.value = VehicleSignals(connected = true, gear = Gear.REVERSE)
        runCurrent()
        advanceTimeBy(HOUR / 4)
        fixture.signals.value = VehicleSignals(connected = true, gear = Gear.DRIVE)
        runCurrent()
        advanceTimeBy(HOUR / 4)
        runCurrent()

        val reverses: Set<LampId> = setOf(LampId(LampGroup.REVERSE, LampSide.LEFT), LampId(LampGroup.REVERSE, LampSide.RIGHT))
        reverses.forEach { assertEquals(it.label, 0.25, fixture.hours(it), EPSILON) }
        assertOnlyLampsUsed(fixture, reverses)
    }

    @Test
    fun turnSignal_accumulatesFrontAndRearOfThatSide() = runTest {
        val fixture = TrackerFixture(this)
        fixture.tracker.start()
        runCurrent()

        // 왼쪽 6분, 오른쪽 12분
        fixture.signals.value = VehicleSignals(connected = true, turnSignal = TurnSignalState.LEFT)
        runCurrent()
        advanceTimeBy(HOUR / 10)
        fixture.signals.value = VehicleSignals(connected = true, turnSignal = TurnSignalState.RIGHT)
        runCurrent()
        advanceTimeBy(HOUR / 5)
        fixture.signals.value = VehicleSignals(connected = true)
        runCurrent()

        assertEquals(0.1, fixture.hours(LampId(LampGroup.FRONT_TURN, LampSide.LEFT)), EPSILON)
        assertEquals(0.1, fixture.hours(LampId(LampGroup.REAR_TURN, LampSide.LEFT)), EPSILON)
        assertEquals(0.2, fixture.hours(LampId(LampGroup.FRONT_TURN, LampSide.RIGHT)), EPSILON)
        assertEquals(0.2, fixture.hours(LampId(LampGroup.REAR_TURN, LampSide.RIGHT)), EPSILON)
        assertOnlyLampsUsed(
            fixture,
            setOf(LampSide.LEFT, LampSide.RIGHT).flatMap { listOf(LampId(LampGroup.FRONT_TURN, it), LampId(LampGroup.REAR_TURN, it)) }.toSet()
        )
    }

    @Test
    fun start_recordsFirstTime_andStop_savesRemainingTime() = runTest {
        val fixture = TrackerFixture(this)

        // 시작하면 처음 기록 시각이 저장됨
        advanceTimeBy(1_000L)
        fixture.tracker.start()
        runCurrent()
        assertEquals(1_000L, fixture.saved.single().firstRecordedAtMillis)

        // 주기(5초)보다 짧게 켰다가 종료하면 종료할 때 저장됨
        fixture.signals.value = VehicleSignals(connected = true, litGroups = setOf(LampGroup.TAIL))
        runCurrent()
        advanceTimeBy(3_600L)
        fixture.tracker.stop()

        assertEquals(2, fixture.saved.size)
        assertEquals(0.001, fixture.saved.last().usedHours[LampId(LampGroup.TAIL, LampSide.LEFT)]!!, EPSILON)
    }

    @Test
    fun initialUsage_isContinued() = runTest {
        // 저장된 기록(하향등 왼쪽 10시간)에서 이어서 셈
        val left = LampId(LampGroup.LOW_BEAM, LampSide.LEFT)
        val fixture = TrackerFixture(this, LampUsage(usedHours = mapOf(left to 10.0), firstRecordedAtMillis = 0L))
        fixture.tracker.start()
        runCurrent()
        fixture.signals.value = VehicleSignals(connected = true, litGroups = setOf(LampGroup.LOW_BEAM))
        runCurrent()
        advanceTimeBy(HOUR)
        runCurrent()

        assertEquals(11.0, fixture.hours(left), EPSILON)
        assertEquals(1.0, fixture.hours(LampId(LampGroup.LOW_BEAM, LampSide.RIGHT)), EPSILON)

        // 처음 기록 시각은 바꾸지 않음
        assertTrue(fixture.saved.all { it.firstRecordedAtMillis == 0L })
    }

    @Test
    fun addUsage_addsHoursAndSavesImmediately() = runTest {
        val fixture = TrackerFixture(this)
        fixture.tracker.start()
        runCurrent()

        // 100시간 빨리 감기 두 번
        val lamp = LampId(LampGroup.BRAKE, LampSide.CENTER)
        fixture.tracker.addUsage(lamp, 100.0)
        fixture.tracker.addUsage(lamp, 100.0)

        assertEquals(200.0, fixture.hours(lamp), EPSILON)
        assertEquals(200.0, fixture.saved.last().usedHours[lamp]!!, EPSILON)
        assertOnlyLampsUsed(fixture, setOf(lamp))
    }

    @Test
    fun replaceLamp_clearsOnlyThatLamp_andCountsFromReplacedTime() = runTest {
        val fixture = TrackerFixture(this)
        val left = LampId(LampGroup.LOW_BEAM, LampSide.LEFT)
        val right = LampId(LampGroup.LOW_BEAM, LampSide.RIGHT)
        fixture.tracker.start()
        runCurrent()

        // 하향등 1시간 켬
        fixture.signals.value = VehicleSignals(connected = true, litGroups = setOf(LampGroup.LOW_BEAM))
        runCurrent()
        advanceTimeBy(HOUR)
        runCurrent()

        // 켜진 채로 왼쪽만 교체: 왼쪽 0시간, 교체 시각 저장, 오른쪽은 그대로
        fixture.tracker.replaceLamp(left)
        assertEquals(0.0, fixture.hours(left), EPSILON)
        assertEquals(1.0, fixture.hours(right), EPSILON)
        assertEquals(HOUR, fixture.saved.last().replacedAtMillis[left])

        // 30분 더 켜면 새 램프는 처음부터 다시 셈
        advanceTimeBy(HOUR / 2)
        runCurrent()
        assertEquals(0.5, fixture.hours(left), EPSILON)
        assertEquals(1.5, fixture.hours(right), EPSILON)

        // 기록 시작 시각: 왼쪽은 교체 시각, 오른쪽은 처음 기록 시각 (하루 평균 계산에 씀)
        assertEquals(HOUR, fixture.tracker.usage.value.startedAtMillis(left))
        assertEquals(0L, fixture.tracker.usage.value.startedAtMillis(right))
    }

    @Test
    fun averageDailyHours_dividesByDaysWithMinimumOneDay() {
        // 기록 전이면 0
        assertEquals(0.0, averageDailyHours(5.0, null, 0L), EPSILON)

        // 하루가 안 지났으면 1일로 나눔
        assertEquals(5.0, averageDailyHours(5.0, 0L, DAY_MILLIS / 2), EPSILON)

        // 5일 동안 10시간 → 하루 2시간
        assertEquals(2.0, averageDailyHours(10.0, 0L, 5 * DAY_MILLIS), EPSILON)
    }

    @Test
    fun averageDailyHours_isCappedAt24Hours() {
        // 첫날 100시간 빨리 감기 → 하루 100시간이 아니라 24시간
        assertEquals(24.0, averageDailyHours(100.0, 0L, HOUR), EPSILON)

        // 3일 동안 90시간 → 하루 30시간이 아니라 24시간
        assertEquals(24.0, averageDailyHours(90.0, 0L, 3 * DAY_MILLIS), EPSILON)
    }

    @Test
    fun averageDailyHours_isZeroForTooShortRecord() {
        // 처음 기록 뒤 하루 안 됨 + 누적 1시간 미만 → 날짜로 환산하지 않음
        assertEquals(0.0, averageDailyHours(0.9, 0L, DAY_MILLIS - 1), EPSILON)

        // 누적 1시간이면 환산 (최소 1일로 나눔)
        assertEquals(1.0, averageDailyHours(1.0, 0L, DAY_MILLIS - 1), EPSILON)

        // 하루가 지났으면 누적이 짧아도 환산
        assertEquals(0.9, averageDailyHours(0.9, 0L, DAY_MILLIS), EPSILON)
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 기대한 램프 밖에는 시간이 쌓이지 않았는지 확인
private fun assertOnlyLampsUsed(fixture: TrackerFixture, expected: Set<LampId>) {
    for (lamp in ALL_LAMPS) {
        if (lamp !in expected) assertEquals(lamp.label, 0.0, fixture.hours(lamp), 0.0)
    }
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
