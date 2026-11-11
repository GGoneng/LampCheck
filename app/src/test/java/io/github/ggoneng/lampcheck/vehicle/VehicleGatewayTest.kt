// -----------------------------------------------------------------------------------
// 파일명       : VehicleGatewayTest.kt
// 설명         : 상태판에 보일 램프를 고르는 함수 단위 테스트
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - capability로 확인한 Light API 그룹만 보이고, 브레이크등, 방향지시등, 후진등은 항상 보이는지 확인
// - 확인한 그룹이 없으면(차량 연결 전) 전체 램프가 보이는지 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.vehicle

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import io.github.ggoneng.lampcheck.model.ALL_LAMPS
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import org.junit.Assert.assertEquals
import org.junit.Test

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

class VehicleGatewayTest {

    @Test
    fun displayedLamps_hidesUnsupportedLightGroups_only() {
        // 에뮬레이터처럼 하향등, 상향등, 미등만 확인되면 안개등만 빠짐
        val shown: List<LampId> = displayedLamps(setOf(LampGroup.LOW_BEAM, LampGroup.HIGH_BEAM, LampGroup.TAIL))
        val hiddenGroups: Set<LampGroup> = setOf(LampGroup.FRONT_FOG, LampGroup.REAR_FOG)
        assertEquals(ALL_LAMPS.filter { it.group !in hiddenGroups }, shown)
    }

    @Test
    fun displayedLamps_showsAll_whenNothingIsConfirmed() {
        // 차량 연결 전이거나 차량 서비스가 없으면 전체
        assertEquals(ALL_LAMPS, displayedLamps(emptySet()))
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
