// -----------------------------------------------------------------------------------
// 파일명       : LifespanEstimator.kt
// 설명         : 밝기 측정값과 누적 점등 시간으로 램프의 남은 수명과 상태를 추정하는 순수 함수
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 램프 광원은 차종 프로필(VehicleProfile)에서 받음
// - 밝기 감쇠 모델 B(t) = 100 * exp(-k * t)의 감쇠율 k를 원점 통과 최소제곱으로 맞춤
// - 측정값이 부족하면 정격 수명으로 남은 수명 계산
// - 하루 평균 사용 시간으로 교체 예상 시각 환산, 수명 상태(양호, 교체 준비, 교체 권장, 고장, 데이터 없음) 판정
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.lifespan

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.BrightnessSample
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampStatus
import io.github.ggoneng.lampcheck.model.LightSource
import io.github.ggoneng.lampcheck.usage.DAY_MILLIS
import kotlin.math.ln

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 아래 임계값은 실측 근거가 아닌 설계 기본값
// 감쇠율을 맞추려면 측정값이 몇 시간 범위에 걸쳐 있어야 하는지
private const val MIN_FIT_SPAN_HOURS: Double = 1.0

// 교체 예상까지 이 날 수 이하면 교체 준비
private const val REPLACE_SOON_DAYS: Double = 60.0

// 하루 평균을 모를 때 남은 수명이 정격의 이 비율 이하면 교체 준비
private const val REPLACE_SOON_RATED_RATIO: Double = 0.1

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// 켜져 있어야 하는데 이 밝기(%) 미만이면 고장 (설계 기본값, 교체 알림 문구에도 씀)
const val FAILED_BRIGHTNESS_PERCENT: Double = 10.0

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 남은 수명(시간): 감쇠율을 맞출 수 있으면 감쇠 모델, 아니면 정격 수명 기준
private fun remainingHours(source: LightSource, usedHours: Double, samples: List<BrightnessSample>): Double {
    val k: Double? = fitDecayRate(samples)
    if (k == null || k <= 0.0) return maxOf(0.0, source.ratedLifeHours - usedHours)

    // 밝기가 수명 끝 밝기에 닿는 누적 시간
    val endHours: Double = ln(100.0 / source.endOfLifePercent) / k
    return maxOf(0.0, endHours - usedHours)
}

// 수명 상태 판정
private fun statusOf(
    source: LightSource,
    latestBrightness: Double?,
    remaining: Double,
    dailyHours: Double,
    shouldBeLit: Boolean
): LampStatus {
    // 켜져 있어야 하는데 거의 빛이 나지 않으면 고장
    if (shouldBeLit && latestBrightness != null && latestBrightness < FAILED_BRIGHTNESS_PERCENT) return LampStatus.FAILED

    // 밝기가 수명 끝에 닿았거나 남은 수명이 없으면 교체 권장
    if (latestBrightness != null && latestBrightness <= source.endOfLifePercent) return LampStatus.REPLACE_NOW
    if (remaining <= 0.0) return LampStatus.REPLACE_NOW

    // 교체 예상까지 남은 날 수로 판단 (하루 평균이 없으면 정격 대비 남은 비율)
    val soon: Boolean = if (dailyHours > 0.0) {
        remaining / dailyHours <= REPLACE_SOON_DAYS
    } else {
        remaining <= source.ratedLifeHours * REPLACE_SOON_RATED_RATIO
    }
    if (soon) return LampStatus.REPLACE_SOON

    // 그 밖은 양호 (데이터 없음은 estimateLamp에서 먼저 거름)
    return LampStatus.GOOD
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 감쇠율 k (1/시간): ln(B/100) = -k * t를 원점 통과 최소제곱으로 맞춤, k = -sum(t * ln(B/100)) / sum(t^2)
// 측정값이 2개 미만이거나 누적 시간 범위가 1시간 미만이면 null
// 밝기 0 이하 값은 로그를 쓸 수 없고, 고장 밝기 미만 값은 서서히 어두워진 것이 아니라 고장이므로 맞춤에서 뺌
fun fitDecayRate(samples: List<BrightnessSample>): Double? {
    // 맞춤에 쓸 측정값 고르기
    val usable: List<BrightnessSample> = samples.filter { it.brightnessPercent >= FAILED_BRIGHTNESS_PERCENT }
    if (usable.size < 2) return null
    val span: Double = usable.maxOf { it.usedHours } - usable.minOf { it.usedHours }
    if (span < MIN_FIT_SPAN_HOURS) return null

    // 원점 통과 최소제곱
    val numerator: Double = usable.sumOf { it.usedHours * ln(it.brightnessPercent / 100.0) }
    val denominator: Double = usable.sumOf { it.usedHours * it.usedHours }
    if (denominator <= 0.0) return null
    return -numerator / denominator
}

// 램프 하나의 수명 추정
// samples: 시간순 밝기 측정값, dailyHours: 하루 평균 사용 시간, shouldBeLit: 지금 켜져 있어야 하는지
// profile: 램프 광원을 정하는 차종 프로필
fun estimateLamp(
    lamp: LampId,
    usedHours: Double,
    samples: List<BrightnessSample>,
    dailyHours: Double,
    shouldBeLit: Boolean,
    nowMillis: Long,
    profile: VehicleProfile
): LampCondition {
    val source: LightSource = profile.lightSourceOf(lamp)
    val latestBrightness: Double? = samples.lastOrNull()?.brightnessPercent

    // 사용 기록도 측정값도 없으면 추정하지 않음
    if (usedHours <= 0.0 && samples.isEmpty()) {
        return LampCondition(lamp, source, usedHours, null, null, null, LampStatus.NO_DATA)
    }

    // 남은 수명 (최근 밝기가 수명 끝 밝기 이하면 0)
    val remaining: Double = if (latestBrightness != null && latestBrightness <= source.endOfLifePercent) {
        0.0
    } else {
        remainingHours(source, usedHours, samples)
    }

    // 남은 수명을 하루 평균 사용 시간으로 나눠 교체 예상 시각으로 환산 (하루 평균이 아주 작아도 Long을 넘지 않게 자름)
    val replaceAt: Long? = if (dailyHours > 0.0) {
        val untilMillis: Double = remaining / dailyHours * DAY_MILLIS
        val maxUntilMillis: Long = Long.MAX_VALUE - nowMillis
        nowMillis + if (untilMillis >= maxUntilMillis.toDouble()) maxUntilMillis else untilMillis.toLong()
    } else {
        null
    }

    val status: LampStatus = statusOf(source, latestBrightness, remaining, dailyHours, shouldBeLit)
    return LampCondition(lamp, source, usedHours, latestBrightness, remaining, replaceAt, status)
}
