// -----------------------------------------------------------------------------------
// 파일명       : Color.kt
// 설명         : 램프체크 화면 색 (차량 디스플레이용 어두운 배경)
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 배경, 패널, 글자, 강조 색 정의
// - 수명 상태 색(양호 초록, 교체 준비 노랑, 교체 권장 주황, 고장 빨강, 데이터 없음 회색)과 차량 그림 색 정의
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.ui.theme

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import androidx.compose.ui.graphics.Color
import io.github.ggoneng.lampcheck.model.LampStatus

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// 화면 바탕과 패널
val BackgroundColor: Color = Color(0xFF0E1116)
val PanelColor: Color = Color(0xFF1A1F27)
val PanelHighColor: Color = Color(0xFF262C36)

// 글자
val TextPrimaryColor: Color = Color(0xFFE8EAED)
val TextSecondaryColor: Color = Color(0xFF9AA0A9)

// 강조 (버튼, 제목)
val AccentColor: Color = Color(0xFF4FA3FF)

// 수명 상태
val StatusGoodColor: Color = Color(0xFF3DD68C)
val StatusSoonColor: Color = Color(0xFFFFC53D)
val StatusNowColor: Color = Color(0xFFFF8A3D)
val StatusFailedColor: Color = Color(0xFFFF5A5A)
val StatusNoDataColor: Color = Color(0xFF7D838C)

// 차량 그림
val CarBodyColor: Color = Color(0xFF3A414C)
val CarGlassColor: Color = Color(0xFF20262F)

// 켜진 램프 빛 표시
val GlowWhiteColor: Color = Color(0xFFFFF4D6)
val GlowAmberColor: Color = Color(0xFFFFB020)
val GlowRedColor: Color = Color(0xFFFF3B3B)

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 수명 상태별 색
fun statusColor(status: LampStatus): Color = when (status) {
    LampStatus.GOOD -> StatusGoodColor
    LampStatus.REPLACE_SOON -> StatusSoonColor
    LampStatus.REPLACE_NOW -> StatusNowColor
    LampStatus.FAILED -> StatusFailedColor
    LampStatus.NO_DATA -> StatusNoDataColor
}
