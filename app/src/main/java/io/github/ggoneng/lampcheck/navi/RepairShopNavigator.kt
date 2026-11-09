// -----------------------------------------------------------------------------------
// 파일명       : RepairShopNavigator.kt
// 설명         : 현재 위치, 주변 정비소 검색, 내비게이션 길 안내 계층의 공통 인터페이스
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 현재 위치 조회 (일몰 계산, 정비소 검색 기준점)
// - 주변 정비소 검색과 내비게이션 목적지 설정 함수 정의
// - 구현체: PleosRepairShopNavigator(context: Context, kakaoRestApiKey: String)
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.navi

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

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

// 위도와 경도
data class GeoPoint(val latitude: Double, val longitude: Double)

// 정비소 한 곳
data class RepairShop(
    val name: String,
    val address: String,
    val location: GeoPoint,
    val distanceMeters: Int
)

// 위치와 정비소 길 안내 계층
interface RepairShopNavigator {
    // 정비소 검색 API 키가 설정되어 있는지
    val canSearchShops: Boolean

    // SDK 초기화
    fun start()

    // SDK 해제
    fun release()

    // 현재 위치. 얻지 못하면 null
    suspend fun currentLocation(): GeoPoint?

    // 현재 위치 주변 정비소를 가까운 순으로 검색. 실패하면 빈 목록
    suspend fun findNearbyShops(): List<RepairShop>

    // 정비소를 내비게이션 목적지로 설정. 성공하면 true
    suspend fun routeTo(shop: RepairShop): Boolean
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
