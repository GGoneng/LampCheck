// -----------------------------------------------------------------------------------
// 파일명       : VehicleProfile.kt
// 설명         : 차대번호(VIN)로 차종 프로필을 골라 램프별 광원 종류를 정하는 계층
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 차종 프로필(이름, 해당 VIN 앞자리, 램프 그룹별 광원) 정의
// - VIN 앞자리로 프로필 표에서 차종을 고르고, 없으면 기본 프로필 사용
// - 프로필 표는 차종별 사양을 받기 전까지 비워 둠 (기본 프로필만 동작)
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.lifespan

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LightSource

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// 차종을 모를 때 쓰는 기본 프로필 (차종별 사양을 받기 전까지 쓰는 예시: 후진등과 뒤 방향지시등은 전구형)
val DEFAULT_PROFILE: VehicleProfile = VehicleProfile(
    name = "기본 사양",
    vinPrefixes = emptyList(),
    lightSources = mapOf(
        LampGroup.REVERSE to LightSource.HALOGEN,
        LampGroup.REAR_TURN to LightSource.HALOGEN
    )
)

// 차종 프로필 표 (실제 차종 사양을 받으면 여기에 더함)
val VEHICLE_PROFILES: List<VehicleProfile> = emptyList()

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 차종 프로필
// vinPrefixes: 이 차종에 해당하는 VIN 앞자리 목록 (예: 제조사 코드 + 차종 코드)
// lightSources: 램프 그룹별 광원 (표에 없는 그룹은 LED)
data class VehicleProfile(
    val name: String,
    val vinPrefixes: List<String>,
    val lightSources: Map<LampGroup, LightSource>
) {
    // 램프의 광원 종류
    fun lightSourceOf(lamp: LampId): LightSource = lightSources[lamp.group] ?: LightSource.LED
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// VIN에 맞는 차종 프로필 (대소문자와 앞뒤 공백은 무시, VIN이 없거나 표에 없으면 기본 프로필)
fun profileForVin(vin: String?, profiles: List<VehicleProfile>): VehicleProfile {
    val normalized: String = vin?.trim()?.uppercase() ?: return DEFAULT_PROFILE
    return profiles.firstOrNull { profile ->
        profile.vinPrefixes.any { prefix -> normalized.startsWith(prefix.uppercase()) }
    } ?: DEFAULT_PROFILE
}
