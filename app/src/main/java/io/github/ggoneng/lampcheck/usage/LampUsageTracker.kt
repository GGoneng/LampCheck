// -----------------------------------------------------------------------------------
// 파일명       : LampUsageTracker.kt
// 설명         : 차량 신호로 램프별 누적 점등 시간을 세는 계층
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 차량 신호에서 지금 켜져 있는 램프 목록 계산 (점등 그룹, 브레이크등, 후진등, 방향지시등)
// - 켜져 있는 동안의 시간을 램프별로 누적하고, 주입받은 저장 함수로 저장
// - 하루 평균 사용 시간 계산 (최대 24시간, 기록이 너무 짧으면 0), 시연용 사용 시간 더하기
// - 램프 교체: 그 램프의 누적 시간을 지우고 교체 시각부터 다시 셈
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.usage

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.vehicle.Gear
import io.github.ggoneng.lampcheck.vehicle.TurnSignalState
import io.github.ggoneng.lampcheck.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 켜져 있는 램프에 시간을 더하고 저장하는 간격
private const val TICK_MILLIS: Long = 5_000L

private const val HOUR_MILLIS: Double = 3_600_000.0

// 하루 평균 사용 시간의 상한 (시연용 100시간 빨리 감기로 하루 100시간이 나오지 않게)
private const val MAX_DAILY_HOURS: Double = 24.0

// 처음 기록 뒤 하루가 지나지 않았을 때 하루 평균을 믿을 수 있는 최소 누적 점등 시간
private const val MIN_RELIABLE_USED_HOURS: Double = 1.0

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// 하루 길이 (밀리초)
const val DAY_MILLIS: Long = 86_400_000L

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 램프별 누적 점등 시간과 처음 기록한 시각
// firstRecordedAtMillis: 기록을 시작한 시각 (아직 시작 전이면 null)
// replacedAtMillis: 램프별로 마지막으로 교체한 시각 (교체한 적 없으면 키가 없음)
data class LampUsage(
    val usedHours: Map<LampId, Double> = emptyMap(),
    val firstRecordedAtMillis: Long? = null,
    val replacedAtMillis: Map<LampId, Long> = emptyMap()
) {
    // 램프 하나의 기록 시작 시각 (교체했으면 교체 시각, 아니면 처음 기록한 시각)
    fun startedAtMillis(lamp: LampId): Long? = replacedAtMillis[lamp] ?: firstRecordedAtMillis
}

// 차량 신호를 구독해 램프별 누적 점등 시간을 세는 계층
// now: 현재 시각(밀리초), save: 누적 결과 저장 함수 (테스트에서 가상 시간과 가짜 저장을 넣을 수 있게 주입받음)
class LampUsageTracker(
    private val signals: StateFlow<VehicleSignals>,
    initial: LampUsage,
    private val save: (LampUsage) -> Unit,
    private val now: () -> Long,
    private val scope: CoroutineScope
) {
    private val _usage: MutableStateFlow<LampUsage> = MutableStateFlow(initial)

    // 램프별 누적 점등 시간
    val usage: StateFlow<LampUsage> = _usage.asStateFlow()

    // 마지막으로 시간을 더한 뒤 지금까지 켜져 있는 램프
    private var litNow: Set<LampId> = emptySet()

    // 마지막으로 시간을 더한 시각
    private var lastTickMillis: Long = 0L

    // 저장하지 않은 변경이 있는지
    private var dirty: Boolean = false

    // 신호 구독과 주기 누적 작업
    private var job: Job? = null

    // 누적 시작 (이미 시작했으면 무시)
    fun start() {
        if (job != null) return
        lastTickMillis = now()

        // 처음 시작이면 기록 시작 시각을 남기고 저장
        if (_usage.value.firstRecordedAtMillis == null) {
            _usage.update { it.copy(firstRecordedAtMillis = lastTickMillis) }
            save(_usage.value)
        }

        job = scope.launch {
            // 신호가 바뀌면 그 전까지 켜져 있던 램프에 시간을 더하고 켜진 램프를 바꿈
            launch {
                signals.collect { value ->
                    accumulate()
                    litNow = litLamps(value)
                }
            }

            // 켜진 채로 있는 동안에도 일정 간격으로 더하고 저장
            while (isActive) {
                delay(TICK_MILLIS)
                accumulate()
                flush()
            }
        }
    }

    // 누적 종료: 남은 시간을 더하고 저장
    fun stop() {
        job?.cancel()
        job = null
        accumulate()
        flush()
    }

    // 시연용: 램프 하나에 사용 시간을 더하고 바로 저장 (예: 100시간 빨리 감기)
    fun addUsage(lamp: LampId, hours: Double) {
        _usage.update { current ->
            val used: Double = (current.usedHours[lamp] ?: 0.0) + hours
            current.copy(
                usedHours = current.usedHours + (lamp to used),
                firstRecordedAtMillis = current.firstRecordedAtMillis ?: now()
            )
        }
        dirty = true
        flush()
    }

    // 램프 교체: 지금까지 켜져 있던 시간을 먼저 더해 둔 뒤 그 램프의 누적 시간을 지우고 교체 시각을 남겨 바로 저장
    fun replaceLamp(lamp: LampId) {
        // 교체 전 시간이 새 램프에 들어가지 않게 먼저 정산 (누적 중일 때만)
        if (job != null) accumulate()
        val replacedAt: Long = now()
        _usage.update { current ->
            current.copy(
                usedHours = current.usedHours - lamp,
                replacedAtMillis = current.replacedAtMillis + (lamp to replacedAt)
            )
        }
        dirty = true
        flush()
    }

    // 마지막으로 더한 시각부터 지금까지의 시간을 켜져 있던 램프에 더함
    private fun accumulate() {
        val current: Long = now()
        val elapsedHours: Double = (current - lastTickMillis) / HOUR_MILLIS
        lastTickMillis = current
        if (elapsedHours <= 0.0 || litNow.isEmpty()) return

        _usage.update { usage ->
            val next: MutableMap<LampId, Double> = usage.usedHours.toMutableMap()
            litNow.forEach { lamp -> next[lamp] = (next[lamp] ?: 0.0) + elapsedHours }
            usage.copy(usedHours = next)
        }
        dirty = true
    }

    // 저장하지 않은 변경이 있으면 저장
    private fun flush() {
        if (!dirty) return
        dirty = false
        save(_usage.value)
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 지금 켜져 있는 램프 목록
fun litLamps(signals: VehicleSignals): Set<LampId> {
    val lit: MutableSet<LampId> = mutableSetOf()

    // Light API로 읽은 점등 그룹은 그 그룹의 모든 위치
    for (group in signals.litGroups) group.sides.forEach { lit += LampId(group, it) }

    // 방향지시등 (LEFT면 앞뒤 왼쪽)
    val turnSide: LampSide? = when (signals.turnSignal) {
        TurnSignalState.LEFT -> LampSide.LEFT
        TurnSignalState.RIGHT -> LampSide.RIGHT
        TurnSignalState.OFF -> null
    }
    if (turnSide != null) {
        lit += LampId(LampGroup.FRONT_TURN, turnSide)
        lit += LampId(LampGroup.REAR_TURN, turnSide)
    }

    // 브레이크 페달을 밟으면 브레이크등 전체
    if (signals.brakePressed) LampGroup.BRAKE.sides.forEach { lit += LampId(LampGroup.BRAKE, it) }

    // 기어 R이면 후진등 전체
    if (signals.gear == Gear.REVERSE) LampGroup.REVERSE.sides.forEach { lit += LampId(LampGroup.REVERSE, it) }
    return lit
}

// 하루 평균 사용 시간 = 누적 점등 시간 / 처음 기록한 날부터 지난 날 수 (최소 1일, 최대 24시간)
// 처음 기록 뒤 하루가 지나지 않았고 누적 1시간 미만이면 평균을 믿기 어려우므로 0 (교체 날짜로 환산하지 않음)
fun averageDailyHours(usedHours: Double, firstRecordedAtMillis: Long?, nowMillis: Long): Double {
    if (firstRecordedAtMillis == null) return 0.0
    val elapsedMillis: Long = nowMillis - firstRecordedAtMillis

    // 기록이 너무 짧으면 환산하지 않음
    if (elapsedMillis < DAY_MILLIS && usedHours < MIN_RELIABLE_USED_HOURS) return 0.0

    // 지난 날 수로 나누고 하루 24시간을 넘지 않게 자름
    val days: Double = maxOf(1.0, elapsedMillis.toDouble() / DAY_MILLIS)
    return minOf(MAX_DAILY_HOURS, usedHours / days)
}
