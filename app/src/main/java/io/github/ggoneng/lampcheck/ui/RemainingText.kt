// -----------------------------------------------------------------------------------
// 파일명       : RemainingText.kt
// 설명         : 램프 목록에 쓰는 남은 수명 글 만들기
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 교체 예상 시각이 있으면 남은 기간(약 N일, 약 N개월, 약 N년)과 남은 점등 시간을 함께 씀
// - 교체 예상 시각이 없으면(기록이 짧거나 하루 평균을 모름) 남은 점등 시간만 씀
// - 10년을 넘으면 "10년 이상"으로 씀
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.ui

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.usage.DAY_MILLIS
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 남은 수명을 날, 달, 해 중 무엇으로 쓸지 나누는 기준
private const val DAYS_PER_MONTH: Double = 30.4
private const val MONTHS_PER_YEAR: Double = 12.0
private const val SHOW_DAYS_UNDER: Double = 60.0
private const val SHOW_MONTHS_UNDER: Double = 24.0

// 이보다 길면 "10년 이상"으로 씀
private const val MAX_SHOWN_YEARS: Double = 10.0

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 교체까지 남은 기간 글 (예: 약 14개월, 10년 이상)
private fun periodText(days: Double): String {
    val months: Double = days / DAYS_PER_MONTH
    val years: Double = months / MONTHS_PER_YEAR
    return when {
        days < 1.0 -> "1일 미만"
        days < SHOW_DAYS_UNDER -> "약 ${days.roundToInt()}일"
        months < SHOW_MONTHS_UNDER -> "약 ${months.roundToInt()}개월"
        years > MAX_SHOWN_YEARS -> "${MAX_SHOWN_YEARS.toInt()}년 이상"
        else -> "약 ${years.roundToInt()}년"
    }
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 남은 수명 글 (예: 약 14개월 (9,900시간), 10년 이상 (10,000시간). 교체 예상 시각이 없으면 시간만)
fun remainingText(condition: LampCondition, nowMillis: Long): String {
    val remaining: Double = condition.remainingHours ?: return "-"
    if (remaining <= 0.0) return "남은 수명 없음"
    val hours: String = "%,d시간".format(remaining.roundToLong())
    val replaceAt: Long = condition.estimatedReplaceAtMillis ?: return hours
    val days: Double = (replaceAt - nowMillis).toDouble() / DAY_MILLIS
    return "${periodText(days)} ($hours)"
}
