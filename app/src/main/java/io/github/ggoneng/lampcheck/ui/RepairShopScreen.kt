// -----------------------------------------------------------------------------------
// 파일명       : RepairShopScreen.kt
// 설명         : 주변 정비소 목록과 길 안내 화면
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 카카오 API 키가 없으면 local.properties 설정 안내
// - 정비소 이름, 주소, 거리 목록. 누르면 길 안내
// - 검색·길 안내 결과 문장 표시
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.ui

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.ggoneng.lampcheck.ShopState
import io.github.ggoneng.lampcheck.navi.RepairShop

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

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 거리 글 (1km 미만은 m, 이상은 소수 한 자리 km)
private fun distanceText(meters: Int): String =
    if (meters < 1000) "${meters}m" else "%.1fkm".format(meters / 1000.0)

// 정비소 한 줄: 이름, 주소, 거리. 누르면 길 안내
@Composable
private fun ShopRow(shop: RepairShop, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = shop.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = shop.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = distanceText(shop.distanceMeters), style = MaterialTheme.typography.titleMedium)
    }
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 정비소 화면
@Composable
fun RepairShopScreen(
    state: ShopState,
    canSearchShops: Boolean,
    onRoute: (RepairShop) -> Unit,
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 제목 줄
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack, modifier = Modifier.height(64.dp)) {
                    Text(text = "돌아가기")
                }
                Spacer(modifier = Modifier.width(20.dp))
                Text(text = "가까운 정비소", style = MaterialTheme.typography.titleLarge)
            }

            // 검색 결과 문장 (검색 실패, 길 안내 결과)
            state.message?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }

            // 키 없음 안내, 검색 중 표시, 정비소 목록
            when {
                !canSearchShops -> Text(
                    text = "local.properties에 KAKAO_REST_API_KEY를 넣어 주세요",
                    style = MaterialTheme.typography.bodyLarge
                )

                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "정비소를 찾는 중이에요", style = MaterialTheme.typography.bodyLarge)
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.shops) { shop -> ShopRow(shop = shop, onClick = { onRoute(shop) }) }
                }
            }
        }
    }
}
