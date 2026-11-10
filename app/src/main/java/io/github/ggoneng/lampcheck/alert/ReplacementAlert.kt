// -----------------------------------------------------------------------------------
// 파일명       : ReplacementAlert.kt
// 설명         : 램프가 교체 권장이나 고장 상태가 되는 순간 음성으로 한 번 알리는 계층
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 램프 상태가 교체 권장·고장으로 바뀌는 순간 한 번만 TTS로 알림
// - 상태가 좋아졌다가 다시 나빠지면 다시 알림
// - 교체 권장은 밝기(처음 대비 %)를, 고장이나 최근 밝기 10% 미만은 켜지지 않는다는 내용을 말함
// - 켜지지 않는 고장은 바로 알리고, 교체 권장은 차가 움직이는 동안 미뤘다가 멈추면 알림
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.alert

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import android.util.Log
import io.github.ggoneng.lampcheck.lifespan.FAILED_BRIGHTNESS_PERCENT
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val TAG: String = "LampCheck"

// 알리는 상태
private val ALERT_STATUSES: Set<LampStatus> = setOf(LampStatus.REPLACE_NOW, LampStatus.FAILED)

// 한글 음절 범위 (받침 판단)
private const val HANGUL_FIRST: Char = '가'
private const val HANGUL_LAST: Char = '힣'
private const val HANGUL_FINAL_COUNT: Int = 28

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 교체 알림
// conditions: 램프별 수명 추정 결과, moving: 차가 움직이는 중인지, speak: 문장을 말하고 끝날 때까지 기다리는 함수
class ReplacementAlert(
    private val conditions: Flow<List<LampCondition>>,
    private val moving: Flow<Boolean>,
    private val speak: suspend (String) -> Unit,
    private val scope: CoroutineScope
) {
    // 이미 알린 뒤 아직 나쁜 상태로 남아 있는 램프
    private val alerted: MutableSet<LampId> = mutableSetOf()

    // 상태 구독 작업
    private var job: Job? = null

    // 상태와 움직임 구독 시작 (이미 시작했으면 무시)
    fun start() {
        if (job != null) return
        job = scope.launch {
            // 상태나 움직임이 바뀔 때마다 다시 판단 (멈추면 미뤄 둔 교체 권장을 이때 알림)
            combine(conditions, moving) { list, isMoving -> list to isMoving }.collect { (list, isMoving) ->
                for (condition in list) {
                    val bad: Boolean = condition.status in ALERT_STATUSES
                    when {
                        // 좋아지면 다음에 다시 알릴 수 있게 지움
                        !bad -> alerted -= condition.lamp

                        // 이미 알린 램프는 다시 알리지 않음
                        condition.lamp in alerted -> Unit

                        // 교체 권장은 움직이는 동안 미룸 (알린 목록에 넣지 않아 멈출 때 다시 판단)
                        isMoving && !isNotLighting(condition) -> Unit

                        // 나쁜 상태로 바뀐 순간(또는 멈춘 순간) 한 번 알림
                        else -> {
                            alerted += condition.lamp
                            val text: String = alertText(condition)
                            Log.i(TAG, "교체 알림: $text")
                            speak(text)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 이름 끝 글자에 받침이 있으면 "이", 없으면 "가"
private fun subjectParticle(name: String): String {
    val last: Char = name.lastOrNull() ?: return "이"
    if (last !in HANGUL_FIRST..HANGUL_LAST) return "이"
    return if ((last - HANGUL_FIRST) % HANGUL_FINAL_COUNT == 0) "가" else "이"
}

// 켜지지 않는 고장인지: 고장 상태이거나 최근 밝기가 고장 밝기 미만 (지금 꺼져 있어도)
private fun isNotLighting(condition: LampCondition): Boolean {
    val brightness: Double? = condition.brightnessPercent
    return condition.status == LampStatus.FAILED || (brightness != null && brightness < FAILED_BRIGHTNESS_PERCENT)
}

// 상태별 알림 문장
private fun alertText(condition: LampCondition): String {
    val name: String = condition.lamp.label
    val brightness: Double? = condition.brightnessPercent

    // 켜지지 않는 고장이면 켜지지 않는다고 말함
    if (isNotLighting(condition)) {
        return "$name${subjectParticle(name)} 켜지지 않아요. 바로 점검해 주세요."
    }

    // 밝기가 수명 끝 밝기에 닿았으면 밝기를, 아니면 예상 수명이 다 됐다고 말함
    return if (brightness != null && brightness <= condition.source.endOfLifePercent) {
        "$name 밝기가 처음의 ${brightness.roundToInt()}%예요. 교체를 권해요."
    } else {
        "$name 예상 수명이 다 됐어요. 교체를 권해요."
    }
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
