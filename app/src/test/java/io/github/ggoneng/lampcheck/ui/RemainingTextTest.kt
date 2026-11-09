// -----------------------------------------------------------------------------------
// 파일명       : RemainingTextTest.kt
// 설명         : 램프 목록의 남은 수명 글 회귀 테스트
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 교체 예상 시각이 없으면(짧은 기록) 남은 점등 시간만 쓰는지 확인
// - 10년을 넘으면 "10년 이상"으로 쓰는지 확인
// - 날, 달, 해 단위와 남은 수명 없음, 데이터 없음 표시 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.ui

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.lifespan.estimateLamp
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.model.LampStatus
import io.github.ggoneng.lampcheck.model.LightSource
import io.github.ggoneng.lampcheck.usage.DAY_MILLIS
import io.github.ggoneng.lampcheck.usage.averageDailyHours
import org.junit.Assert.assertEquals
import org.junit.Test

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 테스트 기준 시각 (2026-11-10 00:00 UTC)
private const val NOW_MILLIS: Long = 1_794_268_800_000L

private val LOW_LEFT: LampId = LampId(LampGroup.LOW_BEAM, LampSide.LEFT)

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

class RemainingTextTest {

    @Test
    fun shortRecord_showsHoursOnly() {
        // 처음 기록 2분 뒤, 누적 2분 → 하루 평균 0 → 교체 날짜 없음
        val used: Double = 2.0 / 60.0
        val daily: Double = averageDailyHours(used, NOW_MILLIS - 120_000L, NOW_MILLIS)
        val condition: LampCondition = estimateLamp(LOW_LEFT, used, emptyList(), daily, false, NOW_MILLIS)
        assertEquals("10,000시간", remainingText(condition, NOW_MILLIS))
    }

    @Test
    fun moreThanTenYears_isShownAsTenYearsOrMore() {
        // 하루 2시간, 남은 10,000시간 → 5,000일(약 13.7년)
        assertEquals("10년 이상 (10,000시간)", remainingText(condition(10_000.0, 5_000.0), NOW_MILLIS))

        // 경계 아래: 약 9.6년(3,500일)은 년 단위
        assertEquals("약 10년 (7,000시간)", remainingText(condition(7_000.0, 3_500.0), NOW_MILLIS))
    }

    @Test
    fun fastForwardedLamp_isShownInMonthsWithDailyCap() {
        // 첫날 100시간 빨리 감기 → 하루 24시간으로 제한 → 9,900 / 24 = 412.5일 → 약 14개월
        val daily: Double = averageDailyHours(100.0, NOW_MILLIS - 60_000L, NOW_MILLIS)
        val condition: LampCondition = estimateLamp(LOW_LEFT, 100.0, emptyList(), daily, false, NOW_MILLIS)
        assertEquals("약 14개월 (9,900시간)", remainingText(condition, NOW_MILLIS))
    }

    @Test
    fun daysAndSpecialCases() {
        assertEquals("약 45일 (450시간)", remainingText(condition(450.0, 45.0), NOW_MILLIS))
        assertEquals("1일 미만 (5시간)", remainingText(condition(5.0, 0.5), NOW_MILLIS))
        assertEquals("남은 수명 없음", remainingText(condition(0.0, 0.0), NOW_MILLIS))
        assertEquals("-", remainingText(condition(null, null), NOW_MILLIS))
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 남은 수명(시간)과 교체까지 남은 날 수로 만든 램프 상태
private fun condition(remainingHours: Double?, daysUntilReplace: Double?): LampCondition =
    LampCondition(
        lamp = LOW_LEFT,
        source = LightSource.LED,
        usedHours = 100.0,
        brightnessPercent = 95.0,
        remainingHours = remainingHours,
        estimatedReplaceAtMillis = daysUntilReplace?.let { NOW_MILLIS + (it * DAY_MILLIS).toLong() },
        status = LampStatus.GOOD
    )

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
