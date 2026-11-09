// -----------------------------------------------------------------------------------
// 파일명       : PleosVehicleGateway.kt
// 설명         : Pleos Vehicle SDK와 AAOS Car API로 차량 신호를 읽고 램프 스위치를 바꾸는 구현체
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 하향등, 상향등, 미등, 전면·후면 안개등의 capability 확인, 점등 상태 구독, 스위치 위치 조회와 변경
// - 방향지시등 상태와 브레이크 페달 구독 (Vehicle SDK)
// - 기어 구독 (AAOS CarPropertyManager), 주행 제한 구독 (CarUxRestrictionsManager)
// - 차대번호(VIN) 조회
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.vehicle

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.CarInfo
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.TurnSignal
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.PleosPermission
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.ggoneng.lampcheck.model.LampGroup
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val TAG: String = "LampCheck"

// SDK 응답을 기다리는 최대 시간 (서비스가 연결되지 않으면 SDK가 요청을 쌓아 두기만 하므로 끝을 정함)
private const val CALL_TIMEOUT_MILLIS: Long = 5_000L

// Light API로 점등 상태와 스위치를 다루는 다섯 그룹
private val LIGHT_GROUPS: List<LampGroup> = listOf(
    LampGroup.LOW_BEAM,
    LampGroup.HIGH_BEAM,
    LampGroup.TAIL,
    LampGroup.FRONT_FOG,
    LampGroup.REAR_FOG
)

// 읽을 수 있는 접근 수준과 쓸 수 있는 접근 수준
private val READABLE: Set<AccessType> = setOf(AccessType.READ, AccessType.READ_WRITE)
private val WRITABLE: Set<AccessType> = setOf(AccessType.WRITE, AccessType.READ_WRITE)

// 앞으로 가는 기어 (D와 수동 1~9단)
private val DRIVE_GEARS: Set<Int> = setOf(
    VehicleGear.GEAR_DRIVE,
    VehicleGear.GEAR_FIRST,
    VehicleGear.GEAR_SECOND,
    VehicleGear.GEAR_THIRD,
    VehicleGear.GEAR_FOURTH,
    VehicleGear.GEAR_FIFTH,
    VehicleGear.GEAR_SIXTH,
    VehicleGear.GEAR_SEVENTH,
    VehicleGear.GEAR_EIGHTH,
    VehicleGear.GEAR_NINTH
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

// Pleos Vehicle SDK + AAOS Car API 구현체
class PleosVehicleGateway(context: Context) : VehicleGateway {

    private val appContext: Context = context.applicationContext

    // Vehicle SDK 진입점과 사용하는 도메인
    private val vehicle: Vehicle = Vehicle(appContext)
    private val light: Light = vehicle.getLight()
    private val turnSignalApi: TurnSignal = vehicle.getTurnSignal()
    private val brake: Brake = vehicle.getBrake()
    private val carInfo: CarInfo = vehicle.getCarInfo()

    // AAOS Car API 연결과 매니저 (start에서 연결, release에서 해제)
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null

    private val _signals: MutableStateFlow<VehicleSignals> = MutableStateFlow(VehicleSignals())
    override val signals: StateFlow<VehicleSignals> = _signals.asStateFlow()

    private val _supportedGroups: MutableStateFlow<Set<LampGroup>> = MutableStateFlow(emptySet())
    override val supportedGroups: StateFlow<Set<LampGroup>> = _supportedGroups.asStateFlow()

    private val _controllableGroups: MutableStateFlow<Set<LampGroup>> = MutableStateFlow(emptySet())
    override val controllableGroups: StateFlow<Set<LampGroup>> = _controllableGroups.asStateFlow()

    // 점등 상태를 구독한 그룹 (SDK 콜백 스레드에서 추가되므로 동시성 집합 사용)
    private val registeredLightGroups: MutableSet<LampGroup> = ConcurrentHashMap.newKeySet()

    // 그룹별 점등 상태 리스너 (해제할 때 등록한 인스턴스가 필요)
    private val lightStateListeners: Map<LampGroup, LightStateListener> = LIGHT_GROUPS.associateWith { group ->
        object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) {
                // 점등 상태 반영 후 스위치 위치도 다시 읽음
                applyLightState(group, state)
                readSwitch(group)
            }

            override fun onFailed(e: Exception) {
                Log.e(TAG, "${group.name} 점등 상태 구독 실패", e)
            }
        }
    }

    // 방향지시등 상태 리스너
    private val turnSignalListener: TurnSignalStateListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            applyTurnSignal(state)
        }

        override fun onFailed(e: Exception) {
            Log.e(TAG, "방향지시등 구독 실패", e)
        }
    }

    // 브레이크 페달 리스너
    private val brakeListener: BrakePedalStatusListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            applyBrake(status)
        }

        override fun onFailed(e: Exception) {
            Log.e(TAG, "브레이크 페달 구독 실패", e)
        }
    }

    // 기어 리스너 (AAOS)
    private val carPropertyCallback: CarPropertyManager.CarPropertyEventCallback =
        object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                when (value.propertyId) {
                    VehiclePropertyIds.GEAR_SELECTION -> {
                        // VehicleGear 값을 앱 기어로 변환
                        val gear: Gear = toGear(value.value as? Int ?: VehicleGear.GEAR_UNKNOWN)
                        _signals.update { it.copy(gear = gear) }
                    }
                }
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                Log.e(TAG, "차량 속성 오류: propertyId=$propertyId, areaId=$areaId")
            }
        }

    // 주행 제한 리스너 (AAOS)
    private val uxRestrictionsListener: CarUxRestrictionsManager.OnUxRestrictionsChangedListener =
        CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
            applyUxRestrictions(restrictions)
        }

    override fun start() {
        // Vehicle SDK 연결 (연결 전에 보낸 요청은 SDK가 쌓아 두었다가 연결되면 보냄)
        runSafely("Vehicle SDK 초기화") { vehicle.initialize() }

        // 다섯 램프의 capability 확인, 점등 상태 구독, 스위치 위치 조회
        LIGHT_GROUPS.forEach { group ->
            checkLightState(group)
            checkLightSwitch(group)
        }

        // 방향지시등과 브레이크 페달 구독, 현재 값 조회
        startTurnSignal()
        startBrake()

        // AAOS Car API 연결과 기어, 주행 제한 구독
        startCar()
    }

    override fun release() {
        // 점등 상태 구독 해제
        registeredLightGroups.forEach { group ->
            runSafely("${group.name} 점등 상태 구독 해제") {
                unregisterLightState(group, lightStateListeners.getValue(group))
            }
        }
        registeredLightGroups.clear()

        // 방향지시등, 브레이크 페달 구독 해제
        runSafely("방향지시등 구독 해제") { turnSignalApi.unregisterTurnSignalState(turnSignalListener) }
        runSafely("브레이크 페달 구독 해제") { brake.unregisterBrakePedalStatus(brakeListener) }

        // Vehicle SDK 연결 종료
        runSafely("Vehicle SDK 해제") { vehicle.release() }

        // Car API 구독 해제와 연결 종료
        runSafely("Car API 해제") {
            propertyManager?.unregisterCallback(carPropertyCallback)
            uxRestrictionsManager?.unregisterListener()
            car?.disconnect()
        }
        propertyManager = null
        uxRestrictionsManager = null
        car = null

        _signals.update { it.copy(connected = false) }
    }

    override suspend fun setLampSwitch(group: LampGroup, switch: LampSwitch): Boolean {
        // 앱이 바꿀 수 없는 그룹이나 SDK에 없는 위치면 실패
        if (group !in _controllableGroups.value) return false
        val value: LightSwitch = toLightSwitch(switch) ?: return false

        // set 결과를 기다림
        val success: Boolean = awaitCallback("${group.name} 스위치 변경", false) { onResult, onFailed ->
            setLightSwitch(group, value, { _: LightSwitch? -> onResult(true) }, onFailed)
        }

        // 바뀐 스위치 위치를 다시 읽음
        readSwitch(group)
        return success
    }

    override suspend fun readVin(): String? {
        val raw: String? = awaitCallback<String?>("차대번호 조회", null) { onResult, onFailed ->
            carInfo.getVINInfo(onResult, onFailed)
        }
        // SDK가 서비스의 JSON 문자열 값을 그대로 넘기므로(예: "\"invalid\"") 앞뒤 따옴표를 뗌
        return raw?.trim('"')
    }

    // 점등 상태 capability 확인 → 읽을 수 있으면 지원 그룹에 넣고 구독
    private fun checkLightState(group: LampGroup) {
        val onComplete: (CapabilityConfig, List<LightState>) -> Unit = { config, _ ->
            // capability 응답이 왔으면 차량 서비스와 연결된 것
            _signals.update { it.copy(connected = true) }
            if (config.accessType in READABLE) {
                _supportedGroups.update { it + group }
                subscribeLightState(group)
            }
        }
        val onFailed: (Exception) -> Unit = { e -> Log.e(TAG, "${group.name} 점등 상태 capability 확인 실패", e) }
        runSafely("${group.name} 점등 상태 capability 확인") { checkStateCapability(group, onComplete, onFailed) }
    }

    // 스위치 capability 확인 → 쓸 수 있고 권한이 있으면 제어 그룹에 넣고, 읽을 수 있으면 현재 위치 조회
    private fun checkLightSwitch(group: LampGroup) {
        val onComplete: (CapabilityConfig, List<LightSwitch>) -> Unit = { config, _ ->
            if (config.accessType in WRITABLE && hasControlPermission()) {
                _controllableGroups.update { it + group }
            }
            if (config.accessType in READABLE) {
                readSwitch(group)
            }
        }
        val onFailed: (Exception) -> Unit = { e -> Log.e(TAG, "${group.name} 스위치 capability 확인 실패", e) }
        runSafely("${group.name} 스위치 capability 확인") { checkSwitchCapability(group, onComplete, onFailed) }
    }

    // 점등 상태 구독과 현재 값 조회
    private fun subscribeLightState(group: LampGroup) {
        runSafely("${group.name} 점등 상태 구독") {
            registerLightState(group, lightStateListeners.getValue(group))
            registeredLightGroups.add(group)
        }

        // 구독은 값이 바뀔 때만 알려 주므로 시작할 때 현재 값을 한 번 읽음
        val onComplete: (LightState?) -> Unit = { state -> applyLightState(group, state) }
        val onFailed: (Exception) -> Unit = { e -> Log.e(TAG, "${group.name} 점등 상태 조회 실패", e) }
        runSafely("${group.name} 점등 상태 조회") { getCurrentLightState(group, onComplete, onFailed) }
    }

    // 스위치 위치를 읽어 switches에 반영
    private fun readSwitch(group: LampGroup) {
        val onComplete: (LightSwitch?) -> Unit = { value ->
            _signals.update { it.copy(switches = it.switches + (group to toLampSwitch(value))) }
        }
        val onFailed: (Exception) -> Unit = { e -> Log.e(TAG, "${group.name} 스위치 조회 실패", e) }
        runSafely("${group.name} 스위치 조회") { getLightSwitch(group, onComplete, onFailed) }
    }

    // 방향지시등 구독과 현재 값 조회
    private fun startTurnSignal() {
        runSafely("방향지시등 구독") { turnSignalApi.registerTurnSignalState(turnSignalListener) }
        val onComplete: (TurnSignalValue?) -> Unit = { state -> applyTurnSignal(state) }
        val onFailed: (Exception) -> Unit = { e -> Log.e(TAG, "방향지시등 조회 실패", e) }
        runSafely("방향지시등 조회") { turnSignalApi.getCurrentTurnSignalState(onComplete, onFailed) }
    }

    // 브레이크 페달 구독과 현재 값 조회
    private fun startBrake() {
        runSafely("브레이크 페달 구독") { brake.registerBrakePedalStatus(brakeListener) }
        val onComplete: (Boolean?) -> Unit = { status -> applyBrake(status) }
        val onFailed: (Exception) -> Unit = { e -> Log.e(TAG, "브레이크 페달 조회 실패", e) }
        runSafely("브레이크 페달 조회") { brake.getCurrentBrakePedalStatus(onComplete, onFailed) }
    }

    // AAOS Car API 연결과 기어, 주행 제한 구독
    private fun startCar() {
        // Car 서비스에 연결 (서비스가 없으면 null)
        val connected: Car = try {
            Car.createCar(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Car API 연결 실패", e)
            null
        } ?: return
        car = connected

        // 기어는 바뀔 때마다 받음 (CAR_POWERTRAIN 권한 필요)
        runSafely("기어 구독") {
            val manager: CarPropertyManager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = manager
            manager.registerCallback(
                carPropertyCallback,
                VehiclePropertyIds.GEAR_SELECTION,
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
        }

        // 주행 제한 구독과 현재 상태 반영
        runSafely("주행 제한 구독") {
            val manager: CarUxRestrictionsManager =
                connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxRestrictionsManager = manager
            manager.registerListener(uxRestrictionsListener)
            manager.currentCarUxRestrictions?.let { applyUxRestrictions(it) }
        }
    }

    // ON일 때만 켜진 것으로 litGroups에 반영
    private fun applyLightState(group: LampGroup, state: LightState?) {
        _signals.update { current ->
            val lit: Set<LampGroup> = if (state == LightState.ON) current.litGroups + group else current.litGroups - group
            current.copy(litGroups = lit)
        }
    }

    // SDK 방향지시등 값을 앱 값으로 반영
    private fun applyTurnSignal(state: TurnSignalValue?) {
        val value: TurnSignalState = when (state) {
            TurnSignalValue.LEFT -> TurnSignalState.LEFT
            TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
            else -> TurnSignalState.OFF
        }
        _signals.update { it.copy(turnSignal = value) }
    }

    // 페달이 밟혔는지 반영 (값이 없으면 안 밟힘으로 봄)
    private fun applyBrake(status: Boolean?) {
        _signals.update { it.copy(brakePressed = status == true) }
    }

    // 주의 분산 최적화가 필요한 상태(주행 중)인지 반영
    private fun applyUxRestrictions(restrictions: CarUxRestrictions) {
        _signals.update { it.copy(drivingRestricted = restrictions.isRequiresDistractionOptimization) }
    }

    // 램프 제어 권한이 허용되어 있는지
    private fun hasControlPermission(): Boolean =
        appContext.checkSelfPermission(PleosPermission.CONTROL_CAR_LIGHTS) == PackageManager.PERMISSION_GRANTED

    // 그룹별 점등 상태 capability 확인 호출
    private fun checkStateCapability(
        group: LampGroup,
        onComplete: (CapabilityConfig, List<LightState>) -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        when (group) {
            LampGroup.LOW_BEAM -> light.checkLowBeamLightStateCapabilities(onComplete, onFailed)
            LampGroup.HIGH_BEAM -> light.checkHighBeamLightStateCapabilities(onComplete, onFailed)
            LampGroup.TAIL -> light.checkTailLampLightStateCapabilities(onComplete, onFailed)
            LampGroup.FRONT_FOG -> light.checkFrontFogLightStateCapabilities(onComplete, onFailed)
            LampGroup.REAR_FOG -> light.checkRearFogLightStateCapabilities(onComplete, onFailed)
            else -> Unit
        }
    }

    // 그룹별 스위치 capability 확인 호출 (CapabilityConfig를 함께 주는 새 API)
    private fun checkSwitchCapability(
        group: LampGroup,
        onComplete: (CapabilityConfig, List<LightSwitch>) -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        when (group) {
            LampGroup.LOW_BEAM -> light.checkLowBeamLightSwitchCapabilities(onComplete, onFailed)
            LampGroup.HIGH_BEAM -> light.checkHighBeamLightSwitchCapabilities(onComplete, onFailed)
            LampGroup.TAIL -> light.checkTailLampLightSwitchCapabilities(onComplete, onFailed)
            LampGroup.FRONT_FOG -> light.checkFrontFogLightSwitchCapabilities(onComplete, onFailed)
            LampGroup.REAR_FOG -> light.checkRearFogLightSwitchCapabilities(onComplete, onFailed)
            else -> Unit
        }
    }

    // 그룹별 점등 상태 구독 호출
    private fun registerLightState(group: LampGroup, listener: LightStateListener) {
        when (group) {
            LampGroup.LOW_BEAM -> light.registerLowBeamLightState(listener)
            LampGroup.HIGH_BEAM -> light.registerHighBeamLightState(listener)
            LampGroup.TAIL -> light.registerTailLampLightState(listener)
            LampGroup.FRONT_FOG -> light.registerFrontFogLightState(listener)
            LampGroup.REAR_FOG -> light.registerRearFogLightState(listener)
            else -> Unit
        }
    }

    // 그룹별 점등 상태 구독 해제 호출
    private fun unregisterLightState(group: LampGroup, listener: LightStateListener) {
        when (group) {
            LampGroup.LOW_BEAM -> light.unregisterLowBeamLightState(listener)
            LampGroup.HIGH_BEAM -> light.unregisterHighBeamLightState(listener)
            LampGroup.TAIL -> light.unregisterTailLampLightState(listener)
            LampGroup.FRONT_FOG -> light.unregisterFrontFogLightState(listener)
            LampGroup.REAR_FOG -> light.unregisterRearFogLightState(listener)
            else -> Unit
        }
    }

    // 그룹별 현재 점등 상태 조회 호출
    private fun getCurrentLightState(
        group: LampGroup,
        onComplete: (LightState?) -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        when (group) {
            LampGroup.LOW_BEAM -> light.getCurrentLowBeamLightState(onComplete, onFailed)
            LampGroup.HIGH_BEAM -> light.getCurrentHighBeamLightState(onComplete, onFailed)
            LampGroup.TAIL -> light.getCurrentTailLampLightState(onComplete, onFailed)
            LampGroup.FRONT_FOG -> light.getCurrentFrontFogLightState(onComplete, onFailed)
            LampGroup.REAR_FOG -> light.getCurrentRearFogLightState(onComplete, onFailed)
            else -> Unit
        }
    }

    // 그룹별 스위치 위치 조회 호출
    private fun getLightSwitch(
        group: LampGroup,
        onComplete: (LightSwitch?) -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        when (group) {
            LampGroup.LOW_BEAM -> light.getLowBeamLightSwitch(onComplete, onFailed)
            LampGroup.HIGH_BEAM -> light.getHighBeamLightSwitch(onComplete, onFailed)
            LampGroup.TAIL -> light.getTailLampLightSwitch(onComplete, onFailed)
            LampGroup.FRONT_FOG -> light.getFrontFogLightSwitch(onComplete, onFailed)
            LampGroup.REAR_FOG -> light.getRearFogLightSwitch(onComplete, onFailed)
            else -> Unit
        }
    }

    // 그룹별 스위치 변경 호출
    private fun setLightSwitch(
        group: LampGroup,
        value: LightSwitch,
        onComplete: (LightSwitch?) -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        when (group) {
            LampGroup.LOW_BEAM -> light.setLowBeamLightSwitch(value, onComplete, onFailed)
            LampGroup.HIGH_BEAM -> light.setHighBeamLightSwitch(value, onComplete, onFailed)
            LampGroup.TAIL -> light.setTailLampLightSwitch(value, onComplete, onFailed)
            LampGroup.FRONT_FOG -> light.setFrontFogLightSwitch(value, onComplete, onFailed)
            LampGroup.REAR_FOG -> light.setRearFogLightSwitch(value, onComplete, onFailed)
            else -> Unit
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

// 콜백 API를 suspend로 감쌈. 실패, 예외, 시간 초과면 fallback
private suspend fun <T> awaitCallback(
    label: String,
    fallback: T,
    call: (onResult: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit
): T {
    val result: T? = withTimeoutOrNull(CALL_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine<T> { continuation ->
            // 콜백이 늦게 오거나 두 번 와도 한 번만 이어 감
            val resumeOnce: (T) -> Unit = { value -> if (continuation.isActive) continuation.resume(value) }
            val fail: (Exception) -> Unit = { e ->
                Log.e(TAG, "$label 실패", e)
                resumeOnce(fallback)
            }
            try {
                call(resumeOnce, fail)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
    // 시간 안에 응답이 없으면 fallback
    return result ?: fallback
}

// AAOS VehicleGear 값 → 앱 기어
private fun toGear(value: Int): Gear = when (value) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    in DRIVE_GEARS -> Gear.DRIVE
    else -> Gear.UNKNOWN
}

// SDK 스위치 위치 → 앱 스위치 위치
private fun toLampSwitch(value: LightSwitch?): LampSwitch = when (value) {
    LightSwitch.OFF -> LampSwitch.OFF
    LightSwitch.ON -> LampSwitch.ON
    LightSwitch.AUTOMATIC -> LampSwitch.AUTO
    LightSwitch.DAYTIME_RUNNING -> LampSwitch.DAYTIME_RUNNING
    null -> LampSwitch.UNKNOWN
}

// 앱 스위치 위치 → SDK 스위치 위치 (알 수 없음은 보낼 수 없음)
private fun toLightSwitch(value: LampSwitch): LightSwitch? = when (value) {
    LampSwitch.OFF -> LightSwitch.OFF
    LampSwitch.ON -> LightSwitch.ON
    LampSwitch.AUTO -> LightSwitch.AUTOMATIC
    LampSwitch.DAYTIME_RUNNING -> LightSwitch.DAYTIME_RUNNING
    LampSwitch.UNKNOWN -> null
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
