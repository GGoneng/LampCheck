// -----------------------------------------------------------------------------------
// 파일명       : VehicleGateway.kt
// 설명         : 차량 신호를 읽고 램프를 제어하는 계층의 공통 인터페이스
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 램프 점등 상태, 스위치 위치, 방향지시등, 브레이크 페달, 기어, 주행 제한, 움직임 상태를 하나의 신호로 묶음
// - 램프 스위치 제어와 차대번호 조회 함수 정의
// - capability로 확인한 램프만 고르는 표시 램프 목록 계산
// - 구현체: PleosVehicleGateway(context: Context)
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.vehicle

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.ALL_LAMPS
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import kotlinx.coroutines.flow.StateFlow

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// Light API로 점등 상태와 스위치를 다루는 다섯 그룹 (나머지 그룹은 브레이크, 방향지시등, 기어 신호로 판단)
val LIGHT_GROUPS: List<LampGroup> = listOf(
    LampGroup.LOW_BEAM,
    LampGroup.HIGH_BEAM,
    LampGroup.TAIL,
    LampGroup.FRONT_FOG,
    LampGroup.REAR_FOG
)

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 변속 기어
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

// 방향지시등 상태 (Vehicle SDK TurnSignalValue와 같은 범위)
enum class TurnSignalState { OFF, LEFT, RIGHT }

// 램프 스위치 위치 (Vehicle SDK LightSwitch와 같은 범위 + 알 수 없음)
enum class LampSwitch { OFF, ON, AUTO, DAYTIME_RUNNING, UNKNOWN }

// 차량에서 받은 신호 묶음
// litGroups: Light API 점등 상태가 켜짐인 그룹 (LOW_BEAM, HIGH_BEAM, TAIL, FRONT_FOG, REAR_FOG 중)
// switches: 위 다섯 그룹의 스위치 위치
// drivingRestricted: 주행 중이라 화면 조작이 제한된 상태인지 (CarUxRestrictions)
// moving: 차가 움직이는 중인지 (차속 기준, 신호 대기처럼 멈춰 있으면 false)
data class VehicleSignals(
    val connected: Boolean = false,
    val litGroups: Set<LampGroup> = emptySet(),
    val switches: Map<LampGroup, LampSwitch> = emptyMap(),
    val turnSignal: TurnSignalState = TurnSignalState.OFF,
    val brakePressed: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val drivingRestricted: Boolean = false,
    val moving: Boolean = false
)

// 차량 신호와 램프 제어 계층
interface VehicleGateway {
    // 최신 차량 신호
    val signals: StateFlow<VehicleSignals>

    // 이 차에서 점등 상태를 읽을 수 있는 그룹 (capability 확인 결과)
    val supportedGroups: StateFlow<Set<LampGroup>>

    // 이 차에서 앱이 스위치를 바꿀 수 있는 그룹 (capability가 WRITE 이상이고 권한이 있는 그룹)
    val controllableGroups: StateFlow<Set<LampGroup>>

    // SDK 연결과 신호 구독 시작
    fun start()

    // 구독 해제와 SDK 연결 종료
    fun release()

    // 램프 스위치 변경. 성공하면 true
    suspend fun setLampSwitch(group: LampGroup, switch: LampSwitch): Boolean

    // 차대번호 조회. 실패하면 null
    suspend fun readVin(): String?
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 상태판에 보일 램프: Light API 그룹은 capability로 확인한 그룹만, 나머지 그룹은 항상
// 확인한 그룹이 하나도 없으면(차량 연결 전이거나 차량 서비스가 없음) 전체 램프
fun displayedLamps(supportedGroups: Set<LampGroup>): List<LampId> {
    if (supportedGroups.isEmpty()) return ALL_LAMPS
    return ALL_LAMPS.filter { lamp -> lamp.group !in LIGHT_GROUPS || lamp.group in supportedGroups }
}
