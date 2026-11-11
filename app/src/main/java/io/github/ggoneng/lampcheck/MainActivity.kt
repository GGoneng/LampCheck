// -----------------------------------------------------------------------------------
// 파일명       : MainActivity.kt
// 설명         : 램프체크 앱 진입점
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 위치 런타임 권한 요청 뒤 차량, 음성, 정비소 연결과 점등 시간 누적, 밝기 측정 시작
// - 화면 상태를 collectAsStateWithLifecycle로 읽어 홈 화면 또는 정비소 화면 표시
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ggoneng.lampcheck.permission.RUNTIME_PERMISSIONS
import io.github.ggoneng.lampcheck.ui.HomeScreen
import io.github.ggoneng.lampcheck.ui.RepairShopScreen
import io.github.ggoneng.lampcheck.ui.theme.LampCheckTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    // 권한 요청 결과와 관계없이 연결 시작 (거부된 기능은 구현체가 쓸 수 없음으로 알림)
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 위치 권한 요청 (이미 허용됐으면 바로 결과가 옴)
        permissionLauncher.launch(RUNTIME_PERMISSIONS)

        setContent {
            LampCheckTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                // 정비소 화면에서 뒤로 가기를 누르면 홈으로
                BackHandler(enabled = state.screen == Screen.SHOPS) { viewModel.closeShops() }

                // 보이는 화면 그리기
                when (state.screen) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        onOpenShops = viewModel::openShops,
                        onToggleDemo = viewModel::toggleDemo,
                        onSelectDemoLamp = viewModel::selectDemoLamp,
                        onDegrade = viewModel::degradeSelected,
                        onFail = viewModel::failSelected,
                        onFastForward = viewModel::fastForwardSelected,
                        onReset = viewModel::resetSelected,
                        onReplaceLamp = viewModel::replaceLamp
                    )

                    Screen.SHOPS -> RepairShopScreen(
                        state = state.shop,
                        canSearchShops = state.canSearchShops,
                        drivingRestricted = state.signals.drivingRestricted,
                        onRoute = viewModel::routeTo,
                        onBack = viewModel::closeShops
                    )
                }
            }
        }
    }
}
