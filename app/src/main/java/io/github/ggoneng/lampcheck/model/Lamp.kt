// -----------------------------------------------------------------------------------
// 파일명       : Lamp.kt
// 설명         : 램프 종류, 위치, 밝기와 수명 추정 모델
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 램프 그룹(하향등, 브레이크등 등)과 좌우 위치 정의
// - 광원 종류별 정격 수명, 밝기 측정값, 램프별 수명 추정 결과 정의
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.model

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// 상태판과 점검에 쓰는 전체 램프 목록
val ALL_LAMPS: List<LampId> = LampGroup.entries.flatMap { group ->
    group.sides.map { side -> LampId(group, side) }
}

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 램프가 차의 앞쪽에 있는지 뒤쪽에 있는지
enum class LampPosition { FRONT, REAR }

// 램프의 좌우 위치
enum class LampSide(val label: String) {
    LEFT("왼쪽"),
    RIGHT("오른쪽"),
    CENTER("가운데")
}

// 램프 그룹: 이름, 앞뒤 위치, 달려 있는 위치 목록
// 파일 최상위 값을 참조하면 ALL_LAMPS 초기화와 순환이 생기므로 목록을 직접 씀
enum class LampGroup(val label: String, val position: LampPosition, val sides: List<LampSide>) {
    LOW_BEAM("하향등", LampPosition.FRONT, listOf(LampSide.LEFT, LampSide.RIGHT)),
    HIGH_BEAM("상향등", LampPosition.FRONT, listOf(LampSide.LEFT, LampSide.RIGHT)),
    FRONT_FOG("전면 안개등", LampPosition.FRONT, listOf(LampSide.LEFT, LampSide.RIGHT)),
    FRONT_TURN("앞 방향지시등", LampPosition.FRONT, listOf(LampSide.LEFT, LampSide.RIGHT)),
    TAIL("미등", LampPosition.REAR, listOf(LampSide.LEFT, LampSide.RIGHT)),
    BRAKE("브레이크등", LampPosition.REAR, listOf(LampSide.LEFT, LampSide.RIGHT, LampSide.CENTER)),
    REVERSE("후진등", LampPosition.REAR, listOf(LampSide.LEFT, LampSide.RIGHT)),
    REAR_FOG("후면 안개등", LampPosition.REAR, listOf(LampSide.CENTER)),
    REAR_TURN("뒤 방향지시등", LampPosition.REAR, listOf(LampSide.LEFT, LampSide.RIGHT))
}

// 램프 하나를 가리키는 키 (예: 왼쪽 하향등)
data class LampId(val group: LampGroup, val side: LampSide) {
    val label: String
        get() = if (side == LampSide.CENTER) group.label else "${side.label} ${group.label}"
}

// 광원 종류: 정격 수명(시간)과 수명이 끝났다고 보는 밝기(처음 대비 %)
// 값은 차종별 사양을 받기 전까지 쓰는 기본값 (LED는 L70 기준)
enum class LightSource(val label: String, val ratedLifeHours: Double, val endOfLifePercent: Double) {
    LED("LED", 10_000.0, 70.0),
    HALOGEN("할로겐", 500.0, 80.0)
}

// 수명 상태
enum class LampStatus(val label: String) {
    GOOD("양호"),
    REPLACE_SOON("교체 준비"),
    REPLACE_NOW("교체 권장"),
    FAILED("고장"),
    NO_DATA("데이터 없음")
}

// 밝기 측정값 하나: 그때까지의 누적 점등 시간과 처음 대비 밝기(%)
data class BrightnessSample(
    val usedHours: Double,
    val brightnessPercent: Double,
    val measuredAtMillis: Long
)

// 램프 하나의 현재 상태와 수명 추정 결과
// brightnessPercent: 최근 측정 밝기(처음 대비 %), 측정값이 없으면 null
// remainingHours: 추정 남은 수명(점등 시간 기준), 추정할 수 없으면 null
// estimatedReplaceAtMillis: 지금 사용 패턴으로 환산한 교체 예상 시각, 환산할 수 없으면 null
data class LampCondition(
    val lamp: LampId,
    val source: LightSource,
    val usedHours: Double,
    val brightnessPercent: Double?,
    val remainingHours: Double?,
    val estimatedReplaceAtMillis: Long?,
    val status: LampStatus
)

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
