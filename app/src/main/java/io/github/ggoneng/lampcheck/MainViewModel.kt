// -----------------------------------------------------------------------------------
// 파일명       : MainViewModel.kt
// 설명         : 화면 상태를 하나의 StateFlow로 모으고 수명 추정·시연 조작·정비소 안내를 연결
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 차량 신호, 램프별 수명 추정 결과, 차종 프로필 이름, 시연 모드, 화면 상태를 MainUiState 하나로 묶음
// - 시연 모드: 램프 선택, 밝기 낮추기, 고장 내기, 사용 시간 빨리 감기, 초기화
// - 램프 교체 기록
// - 정비소 화면 검색과 길 안내
// - ViewModel이 사라질 때 구현체 해제
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ggoneng.lampcheck.lifespan.DEFAULT_PROFILE
import io.github.ggoneng.lampcheck.model.LampCondition
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.navi.RepairShop
import io.github.ggoneng.lampcheck.vehicle.VehicleSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 시연 조작 한 번에 낮추는 밝기(%p)와 더하는 사용 시간
private const val DEMO_DEGRADE_PERCENT_POINTS: Double = 10.0
private const val DEMO_FAST_FORWARD_HOURS: Double = 100.0

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ViewModel 안에서만 바꾸는 화면 상태 (보이는 화면, 정비소 화면 내용, 시연 모드)
private data class ScreenState(
    val screen: Screen = Screen.HOME,
    val shop: ShopState = ShopState(),
    val demo: DemoState = DemoState()
)

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 보이는 화면
enum class Screen { HOME, SHOPS }

// 정비소 화면 상태
// message: 검색·길 안내 결과 문장 (없으면 null)
data class ShopState(
    val loading: Boolean = false,
    val shops: List<RepairShop> = emptyList(),
    val message: String? = null
)

// 시연 모드 상태: 패널을 열었는지, 조작할 램프
data class DemoState(
    val enabled: Boolean = false,
    val selected: LampId = LampId(LampGroup.LOW_BEAM, LampSide.LEFT)
)

// 화면 전체 상태
// profileName: VIN으로 고른 차종 프로필 이름
data class MainUiState(
    val signals: VehicleSignals = VehicleSignals(),
    val conditions: List<LampCondition> = emptyList(),
    val profileName: String = DEFAULT_PROFILE.name,
    val screen: Screen = Screen.HOME,
    val shop: ShopState = ShopState(),
    val demo: DemoState = DemoState(),
    val canSearchShops: Boolean = false
)

// 화면과 수명 추정·시연·정비소 계층을 잇는 ViewModel
class MainViewModel(application: Application) : AndroidViewModel(application) {
    // 구현체, 저장소, 누적기, 센서, 알림
    private val container: AppContainer = AppContainer(application, viewModelScope)

    // 보이는 화면, 정비소 화면 내용, 시연 모드
    private val screenState: MutableStateFlow<ScreenState> = MutableStateFlow(ScreenState())

    // 구현체 연결을 시작했는지
    private var started: Boolean = false

    // 화면 전체 상태
    val uiState: StateFlow<MainUiState> = combine(
        container.vehicle.signals,
        container.conditions,
        container.profile,
        screenState
    ) { signals, conditions, profile, screen ->
        MainUiState(
            signals = signals,
            conditions = conditions,
            profileName = profile.name,
            screen = screen.screen,
            shop = screen.shop,
            demo = screen.demo,
            canSearchShops = container.navigator.canSearchShops
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState(canSearchShops = container.navigator.canSearchShops))

    // 권한 요청이 끝난 뒤 호출: 구현체 연결과 누적·측정·알림 시작 (화면을 다시 만들어도 한 번만)
    fun start() {
        if (started) return
        started = true
        container.start()
    }

    // 시연 모드 패널 열기·닫기
    fun toggleDemo() {
        screenState.update { it.copy(demo = it.demo.copy(enabled = !it.demo.enabled)) }
    }

    // 시연 조작할 램프 선택
    fun selectDemoLamp(lamp: LampId) {
        screenState.update { it.copy(demo = it.demo.copy(selected = lamp)) }
    }

    // 시연: 고른 램프 밝기를 10%p 낮춤
    fun degradeSelected() {
        container.sensor.degrade(screenState.value.demo.selected, DEMO_DEGRADE_PERCENT_POINTS)
    }

    // 시연: 고른 램프를 고장 냄
    fun failSelected() {
        container.sensor.fail(screenState.value.demo.selected)
    }

    // 시연: 고른 램프에 사용 시간 100시간을 더함
    fun fastForwardSelected() {
        container.usage.addUsage(screenState.value.demo.selected, DEMO_FAST_FORWARD_HOURS)
    }

    // 시연: 고른 램프의 열화와 고장을 없앰
    fun resetSelected() {
        container.sensor.reset(screenState.value.demo.selected)
    }

    // 램프 교체: 그 램프를 새 램프 기준으로 다시 기록
    fun replaceLamp(lamp: LampId) {
        container.replaceLamp(lamp)
    }

    // 정비소 화면 열기와 주변 검색
    fun openShops() {
        screenState.update { it.copy(screen = Screen.SHOPS) }

        // 검색 키가 없으면 화면에서 안내만 함
        if (!container.navigator.canSearchShops) return
        viewModelScope.launch {
            screenState.update { it.copy(shop = ShopState(loading = true)) }
            val shops = container.navigator.findNearbyShops()
            val message = if (shops.isEmpty()) "주변 정비소를 찾지 못했어요" else null
            screenState.update { it.copy(shop = ShopState(shops = shops, message = message)) }
        }
    }

    // 홈 화면으로 돌아가기
    fun closeShops() {
        screenState.update { it.copy(screen = Screen.HOME) }
    }

    // 고른 정비소로 길 안내하고 결과를 짧게 알려 줌
    fun routeTo(shop: RepairShop) {
        viewModelScope.launch {
            val message = if (container.navigator.routeTo(shop)) {
                "${shop.name}까지 길 안내를 시작했어요"
            } else {
                "길 안내를 시작하지 못했어요"
            }
            screenState.update { it.copy(shop = it.shop.copy(message = message)) }
            container.voice.speak(message)
        }
    }

    // ViewModel이 사라질 때 구현체 해제 (viewModelScope는 먼저 취소됨)
    override fun onCleared() {
        if (started) container.release()
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
