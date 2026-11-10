// -----------------------------------------------------------------------------------
// 파일명       : VehicleProfileTest.kt
// 설명         : VIN으로 차종 프로필을 고르는 함수 단위 테스트
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - VIN 앞자리가 맞는 프로필을 고르는지 확인 (대소문자, 앞뒤 공백 무시)
// - VIN이 없거나, 표에 없거나, 프로필 표가 비어 있으면 기본 프로필을 쓰는지 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.lifespan

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LightSource
import org.junit.Assert.assertEquals
import org.junit.Test

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 테스트용 차종 프로필 (실제 차종 사양이 아닌 예시 값)
private val SAMPLE_A: VehicleProfile =
    VehicleProfile("예시 차종 A", listOf("KMHAA"), mapOf(LampGroup.LOW_BEAM to LightSource.HALOGEN))
private val SAMPLE_B: VehicleProfile =
    VehicleProfile("예시 차종 B", listOf("KMHBB", "KMHBC"), emptyMap())

private val PROFILES: List<VehicleProfile> = listOf(SAMPLE_A, SAMPLE_B)

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

class VehicleProfileTest {

    @Test
    fun matchingVinPrefix_selectsThatProfile() {
        // 앞자리가 맞는 프로필 (두 번째 앞자리로도 고름)
        assertEquals(SAMPLE_A, profileForVin("KMHAA81CDNU000001", PROFILES))
        assertEquals(SAMPLE_B, profileForVin("KMHBC81CDNU000001", PROFILES))

        // 소문자와 앞뒤 공백은 무시
        assertEquals(SAMPLE_A, profileForVin("  kmhaa81cdnu000001 ", PROFILES))
    }

    @Test
    fun unknownOrMissingVin_usesDefaultProfile() {
        // 표에 없는 VIN, 에뮬레이터의 invalid 값, 읽지 못한 VIN(null), 빈 표
        assertEquals(DEFAULT_PROFILE, profileForVin("KNAZZ81CDNU000001", PROFILES))
        assertEquals(DEFAULT_PROFILE, profileForVin("invalid", PROFILES))
        assertEquals(DEFAULT_PROFILE, profileForVin(null, PROFILES))
        assertEquals(DEFAULT_PROFILE, profileForVin("KMHAA81CDNU000001", emptyList()))
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
