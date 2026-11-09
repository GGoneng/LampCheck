// -----------------------------------------------------------------------------------
// 파일명       : PleosRepairShopNavigator.kt
// 설명         : FusedLocation, NaviHelper SDK와 카카오 로컬 API로 주변 정비소를 찾아 길 안내하는 구현체
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - FusedLocation SDK로 현재 위치 조회 (마지막 위치가 없으면 첫 위치를 잠깐 기다림)
// - 카카오 로컬 키워드 검색으로 반경 5km 안 자동차 정비소를 가까운 순으로 검색
// - NaviHelper SDK로 정비소를 내장 내비게이션 목적지로 설정
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.navi

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val TAG: String = "LampCheck"

// 카카오 로컬 키워드 검색 조건
private const val KAKAO_KEYWORD_URL: String = "https://dapi.kakao.com/v2/local/search/keyword.json"
private const val SHOP_QUERY: String = "자동차정비"
private const val SEARCH_RADIUS_METERS: Int = 5_000
private const val HTTP_TIMEOUT_MILLIS: Int = 5_000

// 위치 응답 대기 시간 (마지막 위치 조회, 첫 위치 수신)
private const val LAST_LOCATION_TIMEOUT_MILLIS: Long = 3_000L
private const val FIRST_FIX_TIMEOUT_MILLIS: Long = 10_000L

// 내비게이션 앱이 경로 탐색을 시작할 때까지 기다리는 시간
private const val ROUTE_TIMEOUT_MILLIS: Long = 10_000L

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// Pleos FusedLocation + NaviHelper + 카카오 로컬 API 구현체
class PleosRepairShopNavigator(
    context: Context,
    private val kakaoRestApiKey: String
) : RepairShopNavigator {

    private val appContext: Context = context.applicationContext

    // SDK 객체 (release 뒤에는 새로 만들어야 하므로 start에서 생성)
    private var fusedLocation: FusedLocation? = null
    private var naviHelper: NaviHelper? = null

    // 경로 요청 결과를 받을 자리 (내비게이션 이벤트 콜백에서 완료)
    @Volatile
    private var pendingRoute: CompletableDeferred<Boolean>? = null

    override val canSearchShops: Boolean = kakaoRestApiKey.isNotBlank()

    // 경로 탐색 시작이면 성공, 오류면 실패
    private val naviListener: NaviHelperEventListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            pendingRoute?.complete(true)
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.e(TAG, "내비게이션 경로 요청 실패: $errorCode")
            pendingRoute?.complete(false)
        }
    }

    override fun start() {
        // 위치 서비스가 있는 환경일 때만 FusedLocation 초기화
        runSafely("FusedLocation 초기화") {
            val location: FusedLocation = FusedLocation(appContext)
            if (location.isSupported()) {
                location.initialize()
                fusedLocation = location
            } else {
                Log.w(TAG, "FusedLocation을 지원하지 않는 환경")
            }
        }

        // 내장 내비게이션 서비스 연결과 이벤트 리스너 등록
        runSafely("NaviHelper 초기화") {
            val navi: NaviHelper = NaviHelper(appContext)
            navi.initialize(
                onConnected = { Log.i(TAG, "NaviHelper 연결됨") },
                onFailed = { e: Throwable -> Log.e(TAG, "NaviHelper 연결 실패", e) }
            )
            navi.addListener(naviListener)
            naviHelper = navi
        }
    }

    override fun release() {
        // 기다리던 경로 요청을 끝냄
        pendingRoute?.complete(false)

        // 리스너 제거 후 SDK 해제
        runSafely("NaviHelper 해제") {
            naviHelper?.let { navi ->
                navi.removeListener(naviListener)
                navi.release()
            }
        }
        runSafely("FusedLocation 해제") { fusedLocation?.release() }
        naviHelper = null
        fusedLocation = null
    }

    override suspend fun currentLocation(): GeoPoint? {
        val location: FusedLocation = fusedLocation ?: return null

        // 마지막으로 계산된 위치 조회 (없으면 null로 옴)
        val last: LocationData? = awaitLocation(
            timeoutMillis = LAST_LOCATION_TIMEOUT_MILLIS,
            waitForFix = false,
            subscribe = { listener -> location.getLastKnownLocation(listener) },
            unsubscribe = {}
        )

        // 계산된 위치가 없으면 위치 업데이트를 잠깐 구독해 첫 위치를 받음
        val data: LocationData = last ?: awaitLocation(
            timeoutMillis = FIRST_FIX_TIMEOUT_MILLIS,
            waitForFix = true,
            subscribe = { listener -> location.registerFusedLocationCallback(listener) },
            unsubscribe = { listener -> location.unregisterFusedLocationCallback(listener) }
        ) ?: return null

        return GeoPoint(latitude = data.latitude, longitude = data.longitude)
    }

    override suspend fun findNearbyShops(): List<RepairShop> {
        if (!canSearchShops) return emptyList()
        val center: GeoPoint = currentLocation() ?: return emptyList()

        // 네트워크 요청은 IO 스레드에서 실행
        return withContext(Dispatchers.IO) {
            try {
                searchShops(center)
            } catch (e: Exception) {
                Log.e(TAG, "정비소 검색 실패", e)
                emptyList()
            }
        }
    }

    override suspend fun routeTo(shop: RepairShop): Boolean {
        val navi: NaviHelper = naviHelper ?: return false

        // 경로 탐색 결과를 받을 자리 준비
        val result: CompletableDeferred<Boolean> = CompletableDeferred()
        pendingRoute = result

        // 정비소를 목적지로 설정
        // TODO(확인 필요): 외부 검색 결과에는 Pleos 내비 POI ID가 없음. poiId를 빈 값, poiSubId를 샘플 앱 값 "0"으로 보냄 (문서에 규칙 없음)
        val routeInfo: RouteInfo = RouteInfo(
            latitude = shop.location.latitude,
            longitude = shop.location.longitude,
            poiName = shop.name,
            poiId = "",
            address = shop.address,
            poiSubId = "0"
        )
        try {
            navi.requestRoute(routeInfo)
        } catch (e: Exception) {
            Log.e(TAG, "내비게이션 목적지 설정 실패", e)
            pendingRoute = null
            return false
        }

        // requestRoute는 요청만 보내므로 경로 탐색 시작 또는 오류 이벤트를 기다림
        return try {
            withTimeoutOrNull(ROUTE_TIMEOUT_MILLIS) { result.await() } ?: false
        } finally {
            if (pendingRoute === result) pendingRoute = null
        }
    }

    // 카카오 로컬 키워드 검색 호출 (IO 스레드에서 실행)
    private fun searchShops(center: GeoPoint): List<RepairShop> {
        // 검색어, 중심 좌표(x=경도, y=위도), 반경, 거리순 정렬
        val query: String = URLEncoder.encode(SHOP_QUERY, "UTF-8")
        val url: URL = URL(
            "$KAKAO_KEYWORD_URL?query=$query&x=${center.longitude}&y=${center.latitude}" +
                "&radius=$SEARCH_RADIUS_METERS&sort=distance"
        )
        val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
        try {
            // REST API 키 헤더와 시간 제한 설정
            connection.requestMethod = "GET"
            connection.connectTimeout = HTTP_TIMEOUT_MILLIS
            connection.readTimeout = HTTP_TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "KakaoAK $kakaoRestApiKey")

            // 실패 응답이면 빈 목록
            val code: Int = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "정비소 검색 응답 오류: HTTP $code")
                return emptyList()
            }

            // 응답 본문을 정비소 목록으로 변환
            val body: String = connection.inputStream.bufferedReader().use { it.readText() }
            return parseShops(body)
        } finally {
            connection.disconnect()
        }
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// SDK 호출에서 난 예외를 로그로 남기고 앱이 죽지 않게 함
private fun runSafely(label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.e(TAG, "$label 실패", e)
    }
}

// 위치 콜백 하나를 기다림. waitForFix면 빈 값은 건너뜀. 오류, 예외, 시간 초과면 null
private suspend fun awaitLocation(
    timeoutMillis: Long,
    waitForFix: Boolean,
    subscribe: (FusedLocationListener) -> Unit,
    unsubscribe: (FusedLocationListener) -> Unit
): LocationData? {
    val result: CompletableDeferred<LocationData?> = CompletableDeferred()
    val listener: FusedLocationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location != null || !waitForFix) result.complete(location)
        }

        override fun onError(exception: Exception) {
            Log.e(TAG, "위치 조회 실패", exception)
            result.complete(null)
        }
    }

    // 위치 요청 (권한이 없으면 SDK가 예외를 던짐)
    try {
        subscribe(listener)
    } catch (e: Exception) {
        Log.e(TAG, "위치 요청 실패", e)
        return null
    }

    // 응답을 기다린 뒤 구독 해제
    return try {
        withTimeoutOrNull(timeoutMillis) { result.await() }
    } finally {
        runSafely("위치 구독 해제") { unsubscribe(listener) }
    }
}

// 카카오 로컬 응답 JSON의 documents → 정비소 목록 (응답이 이미 거리순)
private fun parseShops(body: String): List<RepairShop> {
    val documents: JSONArray = JSONObject(body).getJSONArray("documents")
    return (0 until documents.length()).map { index ->
        val document: JSONObject = documents.getJSONObject(index)
        RepairShop(
            name = document.getString("place_name"),
            address = document.optString("road_address_name").ifBlank { document.optString("address_name") },
            location = GeoPoint(
                latitude = document.getString("y").toDouble(),
                longitude = document.getString("x").toDouble()
            ),
            distanceMeters = document.optString("distance").toIntOrNull() ?: 0
        )
    }
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
