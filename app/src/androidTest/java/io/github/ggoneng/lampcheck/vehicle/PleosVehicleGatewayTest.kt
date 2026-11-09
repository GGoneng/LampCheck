// -----------------------------------------------------------------------------------
// 파일명       : PleosVehicleGatewayTest.kt
// 설명         : Pleos Connect 에뮬레이터에서 PleosVehicleGateway가 차량 신호를 받는지 확인하는 기기 테스트
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - Virtual Console HTTP API(기기 안 nc, shell 권한)로 램프·방향지시등·브레이크·기어 주입
// - 주입한 값이 몇 초 안에 signals(litGroups, turnSignal, brakePressed, gear, drivingRestricted)에 들어오는지 확인
// - supportedGroups, controllableGroups, VIN 확인 결과를 로그(태그 LampCheckTest)로 남김
// - 제어할 수 있으면 setLampSwitch(LOW_BEAM, ON) 뒤 점등 상태 확인
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.vehicle

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ggoneng.lampcheck.model.LampGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val TAG: String = "LampCheckTest"

// 신호가 바뀌기를 기다리는 최대 시간
private const val SIGNAL_TIMEOUT_MILLIS: Long = 8_000L

// SDK 연결과 capability 응답을 기다리는 최대 시간
private const val CONNECT_TIMEOUT_MILLIS: Long = 15_000L

// 방향지시등을 끈 뒤 다시 켜지지 않는지 지켜보는 시간
private const val TURN_OFF_HOLD_MILLIS: Long = 1_500L

// 테스트 앱에 줄 런타임 권한
private val RUNTIME_PERMISSIONS: List<String> = listOf(
    "pleos.car.permission.FUSED_LOCATION"
)

// 기준 상태: 방향지시등·전조등·상향등 끔, 차속·브레이크 0, 기어 P
private val BASELINE_PATHS: List<String> = listOf(
    "/vehicle/turn-signal?mode=Off",
    "/vehicle/lights?feature=headlamp_mode&value=Off",
    "/vehicle/lights?feature=high_beam&value=false",
    "/vehicle/controls?speed=0&accelerator=0&brake=0",
    "/vehicle/gear?gear=Park"
)

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// PleosVehicleGateway 기기 테스트 (Pleos Connect Emulator + Virtual Console 필요)
@RunWith(AndroidJUnit4::class)
class PleosVehicleGatewayTest {

    private val uiAutomation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var gateway: PleosVehicleGateway

    @Before
    fun setUp() {
        // 위치 런타임 권한 허용
        RUNTIME_PERMISSIONS.forEach { uiAutomation.grantRuntimePermission(targetContext.packageName, it) }

        // 차량을 기준 상태로 되돌림
        resetVehicle()

        // 게이트웨이 시작 후 차량 서비스 연결과 기준 상태 반영을 기다림
        gateway = PleosVehicleGateway(targetContext)
        gateway.start()
        awaitSignals("차량 연결", CONNECT_TIMEOUT_MILLIS) { it.connected }
        awaitSignals("기준 상태 반영") { it.gear == Gear.PARK && !it.brakePressed && it.litGroups.isEmpty() }
    }

    @After
    fun tearDown() {
        // 게이트웨이 해제 후 차량을 기준 상태로 되돌림
        if (::gateway.isInitialized) gateway.release()
        resetVehicle()
    }

    @Test
    fun capabilities_and_vin_areReported() {
        // capability 응답이 모두 올 시간을 줌
        runBlocking { delay(3_000L) }
        val supported: Set<LampGroup> = gateway.supportedGroups.value
        val controllable: Set<LampGroup> = gateway.controllableGroups.value
        val vin: String? = runBlocking { gateway.readVin() }

        // 결과를 로그로 남김
        Log.i(TAG, "supportedGroups=$supported")
        Log.i(TAG, "controllableGroups=$controllable")
        Log.i(TAG, "switches=${gateway.signals.value.switches}")
        Log.i(TAG, "vin=$vin")

        // 에뮬레이터는 하향등·상향등·미등 상태를 주입할 수 있으므로 읽을 수 있어야 함
        assertTrue("supportedGroups=$supported", supported.containsAll(listOf(LampGroup.LOW_BEAM, LampGroup.HIGH_BEAM, LampGroup.TAIL)))
        assertFalse("VIN이 비어 있음: $vin", vin.isNullOrBlank())
        assertFalse("VIN에 JSON 따옴표가 남아 있음: $vin", vin.orEmpty().contains('"'))
    }

    @Test
    fun lowBeamAndTailLamp_areReflectedInLitGroups() {
        // 하향등 모드: 하향등과 미등이 켜짐
        inject("/vehicle/lights?feature=headlamp_mode&value=LowBeam")
        awaitSignals("하향등 켜짐") { LampGroup.LOW_BEAM in it.litGroups && LampGroup.TAIL in it.litGroups }

        // 미등 모드: 하향등은 꺼지고 미등만 켜짐
        inject("/vehicle/lights?feature=headlamp_mode&value=Position")
        awaitSignals("미등만 켜짐") { LampGroup.LOW_BEAM !in it.litGroups && LampGroup.TAIL in it.litGroups }

        // 끔: 둘 다 꺼짐
        inject("/vehicle/lights?feature=headlamp_mode&value=Off")
        awaitSignals("전조등 꺼짐") { LampGroup.LOW_BEAM !in it.litGroups && LampGroup.TAIL !in it.litGroups }
    }

    @Test
    fun highBeam_isReflectedInLitGroups() {
        // 상향등 켜기
        inject("/vehicle/lights?feature=high_beam&value=true")
        awaitSignals("상향등 켜짐") { LampGroup.HIGH_BEAM in it.litGroups }

        // 상향등 끄기
        inject("/vehicle/lights?feature=high_beam&value=false")
        awaitSignals("상향등 꺼짐") { LampGroup.HIGH_BEAM !in it.litGroups }
    }

    @Test
    fun turnSignal_leftRightOff_areReflected() {
        // 깜빡이는 동안 LEFT와 꺼짐이 번갈아 올 수 있으므로 한 번이라도 LEFT를 받는지 확인
        inject("/vehicle/turn-signal?mode=BlinkLeft")
        awaitSignals("왼쪽 방향지시등") { it.turnSignal == TurnSignalState.LEFT }

        inject("/vehicle/turn-signal?mode=BlinkRight")
        awaitSignals("오른쪽 방향지시등") { it.turnSignal == TurnSignalState.RIGHT }

        // 끈 뒤에는 OFF가 들어오고 잠시 뒤에도 OFF로 남아 있어야 함
        inject("/vehicle/turn-signal?mode=Off")
        awaitSignals("방향지시등 꺼짐") { it.turnSignal == TurnSignalState.OFF }
        runBlocking { delay(TURN_OFF_HOLD_MILLIS) }
        assertEquals(TurnSignalState.OFF, gateway.signals.value.turnSignal)
    }

    @Test
    fun brakePedal_isReflected() {
        // 브레이크 밟기
        inject("/vehicle/controls?brake=60")
        awaitSignals("브레이크 밟음") { it.brakePressed }

        // 브레이크 떼기
        inject("/vehicle/controls?brake=0")
        awaitSignals("브레이크 뗌") { !it.brakePressed }
    }

    @Test
    fun gear_parkReverseDrive_areReflected() {
        // R → D → P 순서로 바꿈
        inject("/vehicle/gear?gear=Reverse")
        awaitSignals("기어 R") { it.gear == Gear.REVERSE }

        inject("/vehicle/gear?gear=Drive")
        awaitSignals("기어 D") { it.gear == Gear.DRIVE }

        inject("/vehicle/gear?gear=Park")
        awaitSignals("기어 P") { it.gear == Gear.PARK }
    }

    @Test
    fun drivingRestricted_followsGear() {
        // P가 아닌 기어(D)에서는 주행 제한
        inject("/vehicle/gear?gear=Drive")
        awaitSignals("D에서 주행 제한") { it.drivingRestricted }

        // P로 돌아오면 제한 풀림
        inject("/vehicle/gear?gear=Park")
        awaitSignals("P에서 주행 제한 풀림") { !it.drivingRestricted }
    }

    @Test
    fun setLampSwitch_lowBeamOn_turnsLampOn() {
        // 스위치 capability 응답을 기다림. 제어할 수 없는 차면 건너뜀
        val controllable: Boolean = runBlocking {
            withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { gateway.controllableGroups.first { LampGroup.LOW_BEAM in it } } != null
        }
        Log.i(TAG, "LOW_BEAM 제어 가능=$controllable, controllableGroups=${gateway.controllableGroups.value}")
        assumeTrue("이 차에서는 하향등 스위치를 제어할 수 없음", controllable)

        // 하향등 스위치 ON → 점등 상태와 스위치 위치 확인
        val turnedOn: Boolean = runBlocking { gateway.setLampSwitch(LampGroup.LOW_BEAM, LampSwitch.ON) }
        assertTrue("setLampSwitch(LOW_BEAM, ON) 실패", turnedOn)
        val lit: VehicleSignals = awaitSignals("앱이 켠 하향등") { LampGroup.LOW_BEAM in it.litGroups }
        Log.i(TAG, "setLampSwitch 뒤 switches=${lit.switches}")

        // 다시 OFF
        val turnedOff: Boolean = runBlocking { gateway.setLampSwitch(LampGroup.LOW_BEAM, LampSwitch.OFF) }
        assertTrue("setLampSwitch(LOW_BEAM, OFF) 실패", turnedOff)
        awaitSignals("앱이 끈 하향등") { LampGroup.LOW_BEAM !in it.litGroups }
    }

    // 조건에 맞는 신호가 올 때까지 대기. 시간 안에 안 오면 마지막 신호와 함께 실패
    private fun awaitSignals(
        label: String,
        timeoutMillis: Long = SIGNAL_TIMEOUT_MILLIS,
        condition: (VehicleSignals) -> Boolean
    ): VehicleSignals = runBlocking {
        withTimeoutOrNull(timeoutMillis) { gateway.signals.first(condition) }
    } ?: throw AssertionError("$label: ${timeoutMillis}ms 안에 신호가 오지 않음. 마지막 신호=${gateway.signals.value}")

    // 기준 상태 요청을 차례로 보냄
    private fun resetVehicle() {
        BASELINE_PATHS.forEach { inject(it) }
    }

    // Virtual Console에 값 쓰기: 세션 하트비트 뒤 PUT. 200이 아니면 실패
    private fun inject(path: String) {
        request("POST", "/browser/session?client=lampcheck-test")
        val response: String = request("PUT", path)
        assertTrue("주입 실패 $path → ${response.lineSequence().firstOrNull()}", response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"))
    }

    // 기기 안 nc로 HTTP 요청 하나를 보내고 응답 전체를 돌려줌 (shell 권한)
    private fun request(method: String, path: String): String {
        val pipes: Array<ParcelFileDescriptor> = uiAutomation.executeShellCommandRw("nc -w 3 127.0.0.1 8088")
        // 요청을 쓰고 입력을 닫음
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
            output.write("$method $path HTTP/1.0\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        // 서버가 연결을 닫을 때까지 응답을 읽음
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { input -> input.readBytes().decodeToString() }
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
