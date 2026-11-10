// -----------------------------------------------------------------------------------
// 파일명       : HomeScreen.kt
// 설명         : 램프 수명 상태판 홈 화면 (왼쪽 차량 그림, 오른쪽 램프 목록과 시연 모드)
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 차량 연결 안 됨 알림 줄, 교체 권장·고장 배너와 정비소 찾기 버튼 (주행 제한 중에는 버튼 숨김)
// - VIN으로 고른 차종 이름과 램프 교체 안내, 램프별 이름, 밝기, 남은 수명, 누적 점등 시간, 상태 목록
// - 목록에서 램프를 누르면 교체 확인 창 (시연 모드에서는 시연할 램프 선택, 주행 제한 중에는 누를 수 없음)
// - 시연 모드 패널: 램프 선택, 밝기 10% 낮추기, 고장 내기, 100시간 사용, 초기화 (주행 제한 중에는 숨김)
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.ui

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ggoneng.lampcheck.DemoState
import io.github.ggoneng.lampcheck.MainUiState
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampStatus
import io.github.ggoneng.lampcheck.ui.theme.StatusFailedColor
import io.github.ggoneng.lampcheck.ui.theme.StatusSoonColor
import io.github.ggoneng.lampcheck.ui.theme.statusColor
import kotlin.math.roundToInt

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 배너에 올리는 상태 (고장을 먼저 보여 줌)
private val PROBLEM_STATUSES: List<LampStatus> = listOf(LampStatus.FAILED, LampStatus.REPLACE_NOW)

// 목록 열 너비
private val BRIGHTNESS_WIDTH = 80.dp
private val REMAINING_WIDTH = 250.dp
private val USED_WIDTH = 130.dp
private val STATUS_WIDTH = 130.dp

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

// 밝기 글 (측정값이 없으면 "-")
private fun brightnessText(condition: LampCondition): String =
    condition.brightnessPercent?.let { "${it.roundToInt()}%" } ?: "-"

// 누적 점등 시간 글 (1시간 미만은 분, 이상은 소수 한 자리 시간)
private fun usedText(hours: Double): String =
    if (hours < 1.0) "${(hours * 60.0).toInt()}분" else "%,.1f시간".format(hours)

// 화면 맨 위 알림 줄
@Composable
private fun NoticeBar(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

// 교체 권장·고장 배너 (예: "왼쪽 하향등 교체 권장", "오른쪽 브레이크등 고장 외 1개")
// showShopButton: 정비소 찾기 버튼을 보일지 (주행 제한 중에는 숨김)
@Composable
private fun ProblemBanner(problems: List<LampCondition>, showShopButton: Boolean, onOpenShops: () -> Unit) {
    // 문제가 여러 개면 첫 램프의 상태와 나머지 개수
    val first = problems.first()
    val summary = "${first.lamp.label} ${first.status.label}"
    val text = if (problems.size == 1) summary else "$summary 외 ${problems.size - 1}개"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StatusFailedColor.copy(alpha = 0.18f))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = StatusFailedColor,
            modifier = Modifier.weight(1f)
        )
        if (showShopButton) {
            Button(onClick = onOpenShops, modifier = Modifier.height(64.dp)) {
                Text(text = "정비소 찾기")
            }
        }
    }
}

// 램프 교체 확인 창
@Composable
private fun ReplaceDialog(lamp: LampId, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = lamp.label) },
        text = { Text(text = "새 램프로 교체했나요? 누적 점등 시간과 밝기 기록을 지우고 새 램프 기준으로 다시 기록해요.") },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.height(64.dp)) { Text(text = "교체 완료") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.height(64.dp)) { Text(text = "취소") }
        }
    )
}

// 목록 머리 줄
@Composable
private fun ListHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(30.dp))
        HeaderText(text = "램프", modifier = Modifier.weight(1f))
        HeaderText(text = "밝기", modifier = Modifier.width(BRIGHTNESS_WIDTH))
        HeaderText(text = "남은 수명", modifier = Modifier.width(REMAINING_WIDTH))
        HeaderText(text = "누적 점등", modifier = Modifier.width(USED_WIDTH))
        HeaderText(text = "상태", modifier = Modifier.width(STATUS_WIDTH))
    }
}

// 머리 줄 글자 하나
@Composable
private fun HeaderText(text: String, modifier: Modifier) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

// 램프 한 줄: 상태 점, 이름, 밝기, 남은 수명, 누적 점등 시간, 상태 (시연 모드에서는 눌러서 고름)
@Composable
private fun LampRow(condition: LampCondition, nowMillis: Long, selected: Boolean, onClick: (() -> Unit)?) {
    val color = statusColor(condition.status)
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surface)
    val outlined = if (selected) base.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else base
    val clickable = if (onClick != null) outlined.clickable(onClick = onClick) else outlined
    Row(modifier = clickable.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(14.dp))
        Text(text = condition.lamp.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = brightnessText(condition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(BRIGHTNESS_WIDTH))
        Text(
            text = remainingText(condition, nowMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(REMAINING_WIDTH)
        )
        Text(
            text = usedText(condition.usedHours),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(USED_WIDTH)
        )
        Text(text = condition.status.label, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.width(STATUS_WIDTH))
    }
}

// 시연 모드 패널: 고른 램프와 조작 버튼
@Composable
private fun DemoPanel(
    selected: LampId,
    onDegrade: () -> Unit,
    onFail: () -> Unit,
    onFastForward: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "시연 모드 · ${selected.label}", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "목록에서 램프를 눌러 고르세요. 밝기 센서가 없어 시뮬레이터 값을 바꿉니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDegrade, modifier = Modifier.weight(1f).height(64.dp)) { Text(text = "밝기 10% 낮추기") }
            Button(onClick = onFail, modifier = Modifier.weight(1f).height(64.dp)) { Text(text = "고장 내기") }
            Button(onClick = onFastForward, modifier = Modifier.weight(1f).height(64.dp)) { Text(text = "100시간 사용") }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).height(64.dp)) { Text(text = "초기화") }
        }
    }
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 홈 화면
@Composable
fun HomeScreen(
    state: MainUiState,
    onOpenShops: () -> Unit,
    onToggleDemo: () -> Unit,
    onSelectDemoLamp: (LampId) -> Unit,
    onDegrade: () -> Unit,
    onFail: () -> Unit,
    onFastForward: () -> Unit,
    onReset: () -> Unit,
    onReplaceLamp: (LampId) -> Unit
) {
    val nowMillis: Long = System.currentTimeMillis()
    val problems = PROBLEM_STATUSES.flatMap { status -> state.conditions.filter { it.status == status } }
    val demo: DemoState = state.demo
    val restricted: Boolean = state.signals.drivingRestricted
    val showDemo: Boolean = demo.enabled && !restricted

    // 교체 확인 창을 띄운 램프 (없으면 null)
    var replaceTarget: LampId? by remember { mutableStateOf<LampId?>(null) }

    // 주행 제한이 걸리면 교체 확인 창을 닫음
    LaunchedEffect(restricted) {
        if (restricted) replaceTarget = null
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 차량 연결이 안 되어 있으면 알림 줄
            if (!state.signals.connected) {
                NoticeBar(text = "차량에 연결되지 않았어요. 램프 점등 상태를 읽을 수 없어요.", color = StatusSoonColor)
            }

            // 교체 권장이나 고장이 있으면 배너
            if (problems.isNotEmpty()) {
                ProblemBanner(problems = problems, showShopButton = !restricted, onOpenShops = onOpenShops)
            }

            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // 왼쪽: 차량 그림
                LampBoard(
                    conditions = state.conditions,
                    signals = state.signals,
                    modifier = Modifier.weight(0.36f).fillMaxHeight()
                )

                // 오른쪽: 제목 줄, 램프 목록, 시연 모드 패널
                Column(
                    modifier = Modifier.weight(0.64f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 제목, VIN으로 고른 차종 프로필 이름, 램프 교체 안내 (눌러서 교체할 수 있을 때만)
                        val replaceHint: String = if (showDemo || restricted) "" else " · 램프를 갈았다면 목록에서 눌러 주세요"
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "램프 수명", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "차종: ${state.profileName}$replaceHint",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 주행 제한 중에는 시연 모드 버튼을 숨김
                        if (!restricted) {
                            OutlinedButton(onClick = onToggleDemo, modifier = Modifier.height(64.dp)) {
                                Text(text = if (demo.enabled) "시연 모드 닫기" else "시연 모드")
                            }
                        }
                    }
                    ListHeader()
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(state.conditions, key = { it.lamp.toString() }) { condition ->
                            // 시연 모드면 시연할 램프 고르기, 아니면 교체 확인 (주행 제한 중에는 누를 수 없음)
                            val onClick: (() -> Unit)? = when {
                                showDemo -> ({ onSelectDemoLamp(condition.lamp) })
                                !restricted -> ({ replaceTarget = condition.lamp })
                                else -> null
                            }
                            LampRow(
                                condition = condition,
                                nowMillis = nowMillis,
                                selected = showDemo && condition.lamp == demo.selected,
                                onClick = onClick
                            )
                        }
                    }
                    if (showDemo) {
                        DemoPanel(
                            selected = demo.selected,
                            onDegrade = onDegrade,
                            onFail = onFail,
                            onFastForward = onFastForward,
                            onReset = onReset
                        )
                    }
                }
            }
        }

        // 램프 교체 확인 창 (주행 제한 중에는 띄우지 않음)
        val target: LampId? = replaceTarget
        if (target != null && !restricted) {
            ReplaceDialog(
                lamp = target,
                onConfirm = {
                    onReplaceLamp(target)
                    replaceTarget = null
                },
                onDismiss = { replaceTarget = null }
            )
        }
    }
}
