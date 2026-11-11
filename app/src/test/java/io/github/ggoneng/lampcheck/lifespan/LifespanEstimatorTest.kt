// -----------------------------------------------------------------------------------
// 파일명       : LifespanEstimatorTest.kt
// 설명         : 수명 추정 순수 함수 단위 테스트
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 감쇠율 맞춤이 참값을 되찾는지, 측정값이 부족하면 맞추지 않는지 확인
// - 정격 수명 대체 계산, 감쇠 모델 남은 수명 계산 확인
// - 상태 판정 임계값(고장, 교체 권장, 교체 준비, 양호, 데이터 없음)과 교체 예상 시각 환산 확인
// - 기본 프로필의 광원 표와, 차종 프로필의 광원으로 추정하는지 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.lifespan

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.ALL_LAMPS
import io.github.ggoneng.lampcheck.model.BrightnessSample
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.model.LampStatus
import io.github.ggoneng.lampcheck.model.LightSource
import io.github.ggoneng.lampcheck.usage.DAY_MILLIS
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 테스트 기준 시각 (2026-11-10 00:00 UTC)
private const val NOW_MILLIS: Long = 1_794_268_800_000L

private const val EPSILON: Double = 1e-9

// LED 램프 (정격 10,000시간, 수명 끝 70%)
private val LED_LAMP: LampId = LampId(LampGroup.LOW_BEAM, LampSide.LEFT)

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

class LifespanEstimatorTest {

    @Test
    fun lightSource_reverseAndRearTurnAreHalogen_othersLed() {
        // 기본 프로필: 후진등과 뒤 방향지시등만 할로겐
        for (lamp in ALL_LAMPS) {
            val expected: LightSource =
                if (lamp.group == LampGroup.REVERSE || lamp.group == LampGroup.REAR_TURN) LightSource.HALOGEN else LightSource.LED
            assertEquals(lamp.label, expected, DEFAULT_PROFILE.lightSourceOf(lamp))
        }
    }

    @Test
    fun estimate_usesLightSourceFromProfile() {
        // 하향등이 할로겐인 차종: 정격 500시간 - 누적 200시간 = 300시간, 광원도 할로겐
        val halogenLowBeam = VehicleProfile("할로겐 하향등 차종", listOf("TEST"), mapOf(LampGroup.LOW_BEAM to LightSource.HALOGEN))
        val condition: LampCondition = estimateLamp(LED_LAMP, 200.0, emptyList(), 0.0, false, NOW_MILLIS, halogenLowBeam)
        assertEquals(LightSource.HALOGEN, condition.source)
        assertEquals(300.0, condition.remainingHours!!, EPSILON)
    }

    @Test
    fun fitDecayRate_recoversTrueRate() {
        // 참값 k = 0.0005로 만든 측정값에서 k를 되찾음
        val trueK: Double = 0.0005
        val samples: List<BrightnessSample> = decaySamples(trueK, listOf(0.0, 50.0, 120.0, 300.0, 610.0))
        assertEquals(trueK, fitDecayRate(samples)!!, EPSILON)
    }

    @Test
    fun fitDecayRate_needsTwoSamplesOverOneHour() {
        // 측정값 1개, 범위 1시간 미만이면 맞추지 않음
        assertNull(fitDecayRate(decaySamples(0.001, listOf(10.0))))
        assertNull(fitDecayRate(decaySamples(0.001, listOf(10.0, 10.9))))

        // 범위 1시간이면 맞춤
        assertEquals(0.001, fitDecayRate(decaySamples(0.001, listOf(10.0, 11.0)))!!, EPSILON)
    }

    @Test
    fun fitDecayRate_ignoresFailedReadings() {
        // 고장 밝기(10% 미만)는 맞춤에서 빼므로 나머지로 참값을 되찾음
        val samples: List<BrightnessSample> = decaySamples(0.0005, listOf(100.0, 400.0)) + sample(450.0, 0.0)
        assertEquals(0.0005, fitDecayRate(samples)!!, EPSILON)
    }

    @Test
    fun remaining_usesRatedLife_whenSamplesAreNotEnough() {
        // 측정값 1개: 정격 10,000시간 - 누적 1,000시간
        val condition: LampCondition = estimate(usedHours = 1_000.0, samples = listOf(sample(1_000.0, 97.0)))
        assertEquals(9_000.0, condition.remainingHours!!, EPSILON)
        assertEquals(LampStatus.GOOD, condition.status)
    }

    @Test
    fun remaining_usesDecayModel_whenRateCanBeFitted() {
        // k = 0.0005, 수명 끝 70%면 t_end = ln(100/70)/k = 약 713.35시간, 누적 400시간이면 남은 수명 약 313.35시간
        val samples: List<BrightnessSample> = decaySamples(0.0005, listOf(0.0, 200.0, 400.0))
        val condition: LampCondition = estimate(usedHours = 400.0, samples = samples)
        val expected: Double = ln(100.0 / 70.0) / 0.0005 - 400.0
        assertEquals(expected, condition.remainingHours!!, 1e-6)
    }

    @Test
    fun remaining_isZero_andReplaceNow_whenBrightnessReachedEndOfLife() {
        // 최근 밝기 70% (LED 수명 끝) 이하
        val condition: LampCondition = estimate(usedHours = 50.0, samples = listOf(sample(0.0, 100.0), sample(50.0, 68.0)))
        assertEquals(0.0, condition.remainingHours!!, EPSILON)
        assertEquals(LampStatus.REPLACE_NOW, condition.status)
        assertEquals(68.0, condition.brightnessPercent!!, EPSILON)
    }

    @Test
    fun replaceNow_whenRatedLifeIsUsedUp() {
        // 밝기는 괜찮지만 정격 수명을 다 씀
        val condition: LampCondition = estimate(usedHours = 10_500.0, samples = emptyList())
        assertEquals(0.0, condition.remainingHours!!, EPSILON)
        assertEquals(LampStatus.REPLACE_NOW, condition.status)
    }

    @Test
    fun failed_onlyWhenLampShouldBeLit() {
        // 켜져 있어야 하는데 밝기 5%면 고장
        val samples: List<BrightnessSample> = listOf(sample(10.0, 100.0), sample(10.2, 5.0))
        assertEquals(LampStatus.FAILED, estimate(usedHours = 10.2, samples = samples, shouldBeLit = true).status)

        // 꺼져 있으면 고장이라 단정하지 않고 교체 권장
        assertEquals(LampStatus.REPLACE_NOW, estimate(usedHours = 10.2, samples = samples, shouldBeLit = false).status)

        // 경계: 10%는 고장이 아님
        val boundary: List<BrightnessSample> = listOf(sample(10.2, 10.0))
        assertEquals(LampStatus.REPLACE_NOW, estimate(usedHours = 10.2, samples = boundary, shouldBeLit = true).status)
    }

    @Test
    fun replaceSoon_whenReplacementIsWithin60Days() {
        // 정격 기준 남은 500시간, 하루 10시간이면 50일이라 교체 준비
        val soon: LampCondition = estimate(usedHours = 9_500.0, samples = emptyList(), dailyHours = 10.0)
        assertEquals(LampStatus.REPLACE_SOON, soon.status)

        // 남은 600시간, 하루 10시간이면 경계인 60일이라 교체 준비
        assertEquals(LampStatus.REPLACE_SOON, estimate(usedHours = 9_400.0, samples = emptyList(), dailyHours = 10.0).status)

        // 남은 700시간, 하루 10시간이면 70일이라 양호
        assertEquals(LampStatus.GOOD, estimate(usedHours = 9_300.0, samples = emptyList(), dailyHours = 10.0).status)
    }

    @Test
    fun replaceSoon_withoutDailyAverage_usesTenPercentOfRatedLife() {
        // 하루 평균 없음: 남은 1,000시간 (정격의 10%)이면 교체 준비, 1,001시간이면 양호
        val soon: LampCondition = estimate(usedHours = 9_000.0, samples = emptyList(), dailyHours = 0.0)
        assertEquals(LampStatus.REPLACE_SOON, soon.status)
        assertNull(soon.estimatedReplaceAtMillis)
        assertEquals(LampStatus.GOOD, estimate(usedHours = 8_999.0, samples = emptyList(), dailyHours = 0.0).status)
    }

    @Test
    fun noData_whenNeverUsedAndNeverMeasured() {
        val condition: LampCondition = estimate(usedHours = 0.0, samples = emptyList(), dailyHours = 3.0)
        assertEquals(LampStatus.NO_DATA, condition.status)
        assertNull(condition.brightnessPercent)
        assertNull(condition.remainingHours)
        assertNull(condition.estimatedReplaceAtMillis)
    }

    @Test
    fun replaceAt_convertsRemainingHoursWithDailyUsage() {
        // 정격 기준 남은 300시간, 하루 3시간이면 100일 뒤
        val condition: LampCondition = estimate(usedHours = 9_700.0, samples = emptyList(), dailyHours = 3.0)
        assertEquals(NOW_MILLIS + 100L * DAY_MILLIS, condition.estimatedReplaceAtMillis)

        // 할로겐 후진등: 정격 500시간, 누적 200시간, 하루 0.5시간이면 600일 뒤
        val reverse: LampCondition = estimateLamp(
            lamp = LampId(LampGroup.REVERSE, LampSide.LEFT),
            usedHours = 200.0,
            samples = emptyList(),
            dailyHours = 0.5,
            shouldBeLit = false,
            nowMillis = NOW_MILLIS,
            profile = DEFAULT_PROFILE
        )
        assertEquals(300.0, reverse.remainingHours!!, EPSILON)
        assertEquals(NOW_MILLIS + 600L * DAY_MILLIS, reverse.estimatedReplaceAtMillis)
    }

    @Test
    fun replaceAt_doesNotOverflow_whenDailyUsageIsTiny() {
        // 1ms 켜진 뒤 며칠 지난 램프처럼 하루 평균이 아주 작으면 교체 예상 시각이 Long 끝에서 멈춤
        val condition: LampCondition = estimate(usedHours = 1e-9, samples = emptyList(), dailyHours = 1e-12)
        assertEquals(Long.MAX_VALUE, condition.estimatedReplaceAtMillis)
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 측정값 하나
private fun sample(usedHours: Double, brightness: Double): BrightnessSample =
    BrightnessSample(usedHours = usedHours, brightnessPercent = brightness, measuredAtMillis = NOW_MILLIS)

// B(t) = 100 * exp(-k * t)를 따르는 측정값 목록
private fun decaySamples(k: Double, hours: List<Double>): List<BrightnessSample> =
    hours.map { t -> sample(t, 100.0 * exp(-k * t)) }

// 기본 프로필의 LED 하향등 수명 추정 (하루 평균 기본 0)
private fun estimate(
    usedHours: Double,
    samples: List<BrightnessSample>,
    dailyHours: Double = 0.0,
    shouldBeLit: Boolean = false
): LampCondition = estimateLamp(LED_LAMP, usedHours, samples, dailyHours, shouldBeLit, NOW_MILLIS, DEFAULT_PROFILE)

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
