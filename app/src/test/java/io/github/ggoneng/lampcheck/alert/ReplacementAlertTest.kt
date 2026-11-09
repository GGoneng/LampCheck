// -----------------------------------------------------------------------------------
// 파일명       : ReplacementAlertTest.kt
// 설명         : 교체 알림 회귀 테스트
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 교체 권장·고장으로 바뀌는 순간 한 번만 알리는지 확인
// - 상태가 좋아졌다가 다시 나빠지면 다시 알리는지 확인
// - 교체 권장(밝기), 수명 만료, 고장 문장 확인
// - 꺼져 있어도 최근 밝기가 10% 미만이면 켜지지 않는다는 문장을 쓰는지 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.alert

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.model.LampStatus
import io.github.ggoneng.lampcheck.model.LightSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private val LOW_LEFT: LampId = LampId(LampGroup.LOW_BEAM, LampSide.LEFT)
private val BRAKE_RIGHT: LampId = LampId(LampGroup.BRAKE, LampSide.RIGHT)

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// 알림과 가짜 상태 흐름, 말한 문장 기록을 묶은 테스트 환경
private class AlertFixture(scope: TestScope) {
    val conditions: MutableStateFlow<List<LampCondition>> = MutableStateFlow(emptyList())
    val spoken: MutableList<String> = mutableListOf()
    val alert: ReplacementAlert = ReplacementAlert(conditions, { spoken += it }, scope.backgroundScope)
}

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class ReplacementAlertTest {

    @Test
    fun replaceNow_isSpokenOnce() = runTest {
        val fixture = AlertFixture(this)
        fixture.alert.start()

        // 양호 → 교체 권장(68%) → 교체 권장(65%) 순서로 바뀜
        fixture.conditions.value = listOf(condition(LOW_LEFT, LampStatus.GOOD, 95.0))
        runCurrent()
        fixture.conditions.value = listOf(condition(LOW_LEFT, LampStatus.REPLACE_NOW, 68.0))
        runCurrent()
        fixture.conditions.value = listOf(condition(LOW_LEFT, LampStatus.REPLACE_NOW, 65.0))
        runCurrent()

        assertEquals(listOf("왼쪽 하향등 밝기가 처음의 68%예요. 교체를 권해요."), fixture.spoken)
    }

    @Test
    fun failed_isSpoken_andNotRepeatedWhenTurnedOff() = runTest {
        val fixture = AlertFixture(this)
        fixture.alert.start()

        // 브레이크를 밟은 동안 고장 → 뗀 뒤 교체 권장 → 다시 밟아 고장
        fixture.conditions.value = listOf(condition(BRAKE_RIGHT, LampStatus.FAILED, 0.0))
        runCurrent()
        fixture.conditions.value = listOf(condition(BRAKE_RIGHT, LampStatus.REPLACE_NOW, 0.0))
        runCurrent()
        fixture.conditions.value = listOf(condition(BRAKE_RIGHT, LampStatus.FAILED, 0.0))
        runCurrent()

        assertEquals(listOf("오른쪽 브레이크등이 켜지지 않아요. 바로 점검해 주세요."), fixture.spoken)
    }

    @Test
    fun recoveredLamp_isAlertedAgain() = runTest {
        val fixture = AlertFixture(this)
        fixture.alert.start()

        // 교체 권장 → 양호(초기화) → 교체 권장
        fixture.conditions.value = listOf(condition(LOW_LEFT, LampStatus.REPLACE_NOW, 70.0))
        runCurrent()
        fixture.conditions.value = listOf(condition(LOW_LEFT, LampStatus.GOOD, 100.0))
        runCurrent()
        fixture.conditions.value = listOf(condition(LOW_LEFT, LampStatus.REPLACE_NOW, 60.0))
        runCurrent()

        assertEquals(
            listOf(
                "왼쪽 하향등 밝기가 처음의 70%예요. 교체를 권해요.",
                "왼쪽 하향등 밝기가 처음의 60%예요. 교체를 권해요."
            ),
            fixture.spoken
        )
    }

    @Test
    fun eachLampIsAlertedSeparately_andRatedLifeEndHasItsOwnText() = runTest {
        val fixture = AlertFixture(this)
        fixture.alert.start()

        // 하향등은 밝기는 괜찮지만 수명 만료, 브레이크등은 고장
        fixture.conditions.value = listOf(
            condition(LOW_LEFT, LampStatus.REPLACE_NOW, 90.0),
            condition(BRAKE_RIGHT, LampStatus.GOOD, 100.0)
        )
        runCurrent()
        fixture.conditions.value = listOf(
            condition(LOW_LEFT, LampStatus.REPLACE_NOW, 90.0),
            condition(BRAKE_RIGHT, LampStatus.FAILED, 0.0)
        )
        runCurrent()

        assertEquals(
            listOf(
                "왼쪽 하향등 예상 수명이 다 됐어요. 교체를 권해요.",
                "오른쪽 브레이크등이 켜지지 않아요. 바로 점검해 주세요."
            ),
            fixture.spoken
        )
    }

    @Test
    fun veryDimLamp_isSpokenAsNotLighting_evenWhenOff() = runTest {
        val fixture = AlertFixture(this)
        fixture.alert.start()

        // 앱을 다시 켰을 때 꺼져 있는 브레이크등의 최근 밝기가 0% (상태는 교체 권장)
        fixture.conditions.value = listOf(condition(BRAKE_RIGHT, LampStatus.REPLACE_NOW, 0.0))
        runCurrent()

        // 경계: 10%는 밝기 문장
        fixture.conditions.value = listOf(
            condition(BRAKE_RIGHT, LampStatus.REPLACE_NOW, 0.0),
            condition(LOW_LEFT, LampStatus.REPLACE_NOW, 10.0)
        )
        runCurrent()

        assertEquals(
            listOf(
                "오른쪽 브레이크등이 켜지지 않아요. 바로 점검해 주세요.",
                "왼쪽 하향등 밝기가 처음의 10%예요. 교체를 권해요."
            ),
            fixture.spoken
        )
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 테스트용 LED 램프 상태
private fun condition(lamp: LampId, status: LampStatus, brightness: Double?): LampCondition =
    LampCondition(
        lamp = lamp,
        source = LightSource.LED,
        usedHours = 100.0,
        brightnessPercent = brightness,
        remainingHours = if (status == LampStatus.REPLACE_NOW || status == LampStatus.FAILED) 0.0 else 1_000.0,
        estimatedReplaceAtMillis = null,
        status = status
    )

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
