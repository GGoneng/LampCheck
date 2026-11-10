// -----------------------------------------------------------------------------------
// 파일명       : LampBoard.kt
// 설명         : 차를 위에서 본 그림 위에 램프 수명 상태를 색 점과 밝기로 그리는 상태판 (TPMS형)
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 차체, 앞유리, 지붕, 뒷유리를 Canvas로 그림 (앞쪽이 위)
// - 수명 추정 결과가 있는 램프(차에서 확인한 램프)만 위치마다 수명 상태 색 점으로 표시, 측정값이 있으면 밝기(%)를 옆에 씀
// - 지금 켜져 있는 램프(점등 그룹, 방향지시등, 브레이크등, 후진등)에 빛 표시
// - 상태 색 범례
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.ui

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ggoneng.lampcheck.model.ALL_LAMPS
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampPosition
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.model.LampStatus
import io.github.ggoneng.lampcheck.ui.theme.CarBodyColor
import io.github.ggoneng.lampcheck.ui.theme.CarGlassColor
import io.github.ggoneng.lampcheck.ui.theme.GlowAmberColor
import io.github.ggoneng.lampcheck.ui.theme.GlowRedColor
import io.github.ggoneng.lampcheck.ui.theme.GlowWhiteColor
import io.github.ggoneng.lampcheck.ui.theme.TextPrimaryColor
import io.github.ggoneng.lampcheck.ui.theme.statusColor
import io.github.ggoneng.lampcheck.usage.litLamps
import io.github.ggoneng.lampcheck.vehicle.VehicleSignals
import kotlin.math.roundToInt

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 차 그림의 가로 / 세로 비율
private const val CAR_ASPECT: Float = 0.46f

// 램프 위치 (차 그림 안의 비율 좌표. x: 왼쪽 0 → 오른쪽 1, y: 앞 0 → 뒤 1)
private val LAMP_POSITIONS: Map<LampId, Offset> = mapOf(
    // 앞쪽
    LampId(LampGroup.FRONT_TURN, LampSide.LEFT) to Offset(0.10f, 0.075f),
    LampId(LampGroup.FRONT_TURN, LampSide.RIGHT) to Offset(0.90f, 0.075f),
    LampId(LampGroup.LOW_BEAM, LampSide.LEFT) to Offset(0.24f, 0.045f),
    LampId(LampGroup.LOW_BEAM, LampSide.RIGHT) to Offset(0.76f, 0.045f),
    LampId(LampGroup.HIGH_BEAM, LampSide.LEFT) to Offset(0.37f, 0.05f),
    LampId(LampGroup.HIGH_BEAM, LampSide.RIGHT) to Offset(0.63f, 0.05f),
    LampId(LampGroup.FRONT_FOG, LampSide.LEFT) to Offset(0.22f, 0.13f),
    LampId(LampGroup.FRONT_FOG, LampSide.RIGHT) to Offset(0.78f, 0.13f),
    // 뒤쪽
    LampId(LampGroup.REAR_TURN, LampSide.LEFT) to Offset(0.10f, 0.925f),
    LampId(LampGroup.REAR_TURN, LampSide.RIGHT) to Offset(0.90f, 0.925f),
    LampId(LampGroup.TAIL, LampSide.LEFT) to Offset(0.20f, 0.955f),
    LampId(LampGroup.TAIL, LampSide.RIGHT) to Offset(0.80f, 0.955f),
    LampId(LampGroup.BRAKE, LampSide.LEFT) to Offset(0.31f, 0.965f),
    LampId(LampGroup.BRAKE, LampSide.RIGHT) to Offset(0.69f, 0.965f),
    LampId(LampGroup.BRAKE, LampSide.CENTER) to Offset(0.50f, 0.71f),
    LampId(LampGroup.REVERSE, LampSide.LEFT) to Offset(0.42f, 0.965f),
    LampId(LampGroup.REVERSE, LampSide.RIGHT) to Offset(0.58f, 0.965f),
    LampId(LampGroup.REAR_FOG, LampSide.CENTER) to Offset(0.50f, 0.93f)
)

// 점 옆 밝기 글자 모양
private val BRIGHTNESS_TEXT_STYLE: TextStyle = TextStyle(color = TextPrimaryColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

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

// 램프 그룹별 빛 색
private fun glowColor(group: LampGroup): Color = when (group) {
    LampGroup.LOW_BEAM, LampGroup.HIGH_BEAM, LampGroup.FRONT_FOG, LampGroup.REVERSE -> GlowWhiteColor
    LampGroup.FRONT_TURN, LampGroup.REAR_TURN -> GlowAmberColor
    LampGroup.TAIL, LampGroup.BRAKE, LampGroup.REAR_FOG -> GlowRedColor
}

// 차 그림과 램프 점, 밝기 글자 그리기
private fun DrawScope.drawCar(conditions: Map<LampId, LampCondition>, lit: Set<LampId>, textMeasurer: TextMeasurer) {
    // 캔버스 안에 비율을 지켜 차 크기를 잡고 가운데 놓음
    val carHeight = minOf(size.height, size.width / CAR_ASPECT) * 0.94f
    val carWidth = carHeight * CAR_ASPECT
    val left = (size.width - carWidth) / 2f
    val top = (size.height - carHeight) / 2f

    // 차체
    drawRoundRect(
        color = CarBodyColor,
        topLeft = Offset(left, top),
        size = Size(carWidth, carHeight),
        cornerRadius = CornerRadius(carWidth * 0.22f)
    )

    // 앞유리, 지붕, 뒷유리
    drawRoundRect(
        color = CarGlassColor,
        topLeft = Offset(left + carWidth * 0.12f, top + carHeight * 0.24f),
        size = Size(carWidth * 0.76f, carHeight * 0.14f),
        cornerRadius = CornerRadius(carWidth * 0.08f)
    )
    drawRoundRect(
        color = CarBodyColor.copy(alpha = 0.6f),
        topLeft = Offset(left + carWidth * 0.14f, top + carHeight * 0.40f),
        size = Size(carWidth * 0.72f, carHeight * 0.28f),
        cornerRadius = CornerRadius(carWidth * 0.06f)
    )
    drawRoundRect(
        color = CarGlassColor,
        topLeft = Offset(left + carWidth * 0.16f, top + carHeight * 0.73f),
        size = Size(carWidth * 0.68f, carHeight * 0.10f),
        cornerRadius = CornerRadius(carWidth * 0.08f)
    )

    // 램프 점 (수명 추정 결과가 있는 램프만, 켜진 램프는 빛 표시를 먼저 그림)
    val dotRadius = 8.dp.toPx()
    val haloRadius = dotRadius * 2.6f
    for (lamp in ALL_LAMPS) {
        val condition: LampCondition = conditions[lamp] ?: continue
        val position = LAMP_POSITIONS.getValue(lamp)
        val center = Offset(left + carWidth * position.x, top + carHeight * position.y)
        if (lamp in lit) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor(lamp.group).copy(alpha = 0.85f), Color.Transparent),
                    center = center,
                    radius = haloRadius
                ),
                radius = haloRadius,
                center = center
            )
        }
        drawCircle(color = statusColor(condition.status), radius = dotRadius, center = center)
        drawCircle(
            color = Color.Black.copy(alpha = 0.5f),
            radius = dotRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // 측정값이 있으면 밝기 글자 (앞쪽 램프는 점 아래, 뒤쪽 램프는 점 위)
        val brightness: Double = condition.brightnessPercent ?: continue
        val layout: TextLayoutResult = textMeasurer.measure("${brightness.roundToInt()}%", BRIGHTNESS_TEXT_STYLE)
        val gap = dotRadius + 2.dp.toPx()
        val textTop = if (lamp.group.position == LampPosition.FRONT) center.y + gap else center.y - gap - layout.size.height
        drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, textTop))
    }
}

// 상태 색 범례
@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        for (status in LampStatus.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(statusColor(status)))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = status.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 램프 상태판 (차 그림 + 범례)
@Composable
fun LampBoard(conditions: List<LampCondition>, signals: VehicleSignals, modifier: Modifier = Modifier) {
    val lit = litLamps(signals)
    val byLamp = conditions.associateBy { it.lamp }
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "앞", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            drawCar(byLamp, lit, textMeasurer)
        }
        Text(text = "뒤", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.size(12.dp))
        Legend()
    }
}
