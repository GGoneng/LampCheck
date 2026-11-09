// -----------------------------------------------------------------------------------
// 파일명       : LampDataStore.kt
// 설명         : 램프별 누적 점등 시간, 밝기 측정값, 시연 조작 상태를 SharedPreferences에 JSON으로 저장
// 작성자       : 이민하
// 작성일       : 2026-11-10
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 램프별 누적 점등 시간과 처음 기록 시각 저장과 불러오기
// - 램프별 밝기 측정값 목록(최근 50개) 저장과 불러오기, StateFlow로 내보내기
// - 누적 시간이 0.5시간 이상 늘었거나 밝기가 1%p 이상 바뀐 측정값만 기록
// - 시연용 밝기 시뮬레이터의 램프별 추가 열화·고장 상태 저장과 불러오기
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.data

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import android.content.Context
import android.content.SharedPreferences
import io.github.ggoneng.lampcheck.model.BrightnessSample
import io.github.ggoneng.lampcheck.model.LampGroup
import io.github.ggoneng.lampcheck.model.LampId
import io.github.ggoneng.lampcheck.model.LampSide
import io.github.ggoneng.lampcheck.sensor.SimulatedFaults
import io.github.ggoneng.lampcheck.usage.LampUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val PREFS_NAME: String = "lamp_data"
private const val KEY_USAGE: String = "usage"
private const val KEY_SAMPLES: String = "samples"
private const val KEY_FAULTS: String = "faults"

// 램프마다 남기는 측정값 수
private const val MAX_SAMPLES_PER_LAMP: Int = 50

// 새 측정값을 기록하는 조건 (누적 시간 증가, 밝기 변화)
private const val RECORD_MIN_HOURS: Double = 0.5
private const val RECORD_MIN_BRIGHTNESS_CHANGE: Double = 1.0

// JSON 필드 이름
private const val FIELD_FIRST_RECORDED_AT: String = "firstRecordedAtMillis"
private const val FIELD_LAMPS: String = "lamps"
private const val FIELD_GROUP: String = "group"
private const val FIELD_SIDE: String = "side"
private const val FIELD_HOURS: String = "usedHours"
private const val FIELD_SAMPLES: String = "samples"
private const val FIELD_BRIGHTNESS: String = "brightnessPercent"
private const val FIELD_MEASURED_AT: String = "measuredAtMillis"
private const val FIELD_EXTRA_DROP: String = "extraDrop"
private const val FIELD_PERCENT_POINTS: String = "percentPoints"
private const val FIELD_FAILED: String = "failed"

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// 램프별 누적 점등 시간과 밝기 측정값 저장소
class LampDataStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 앱 시작 시 저장된 측정값을 불러옴
    private val _samples: MutableStateFlow<Map<LampId, List<BrightnessSample>>> =
        MutableStateFlow(decodeSamples(prefs.getString(KEY_SAMPLES, null)))

    // 램프별 밝기 측정값 (시간순)
    val samples: StateFlow<Map<LampId, List<BrightnessSample>>> = _samples.asStateFlow()

    // 저장된 누적 점등 시간 불러오기 (없으면 빈 기록)
    fun loadUsage(): LampUsage = decodeUsage(prefs.getString(KEY_USAGE, null))

    // 누적 점등 시간 저장
    fun saveUsage(usage: LampUsage) {
        prefs.edit().putString(KEY_USAGE, encodeUsage(usage)).apply()
    }

    // 측정값 하나를 기록 조건에 맞을 때만 추가하고 저장 (램프마다 최근 50개만 남김)
    fun addSample(lamp: LampId, sample: BrightnessSample) {
        val previous: List<BrightnessSample> = _samples.value[lamp].orEmpty()
        if (!shouldRecord(previous.lastOrNull(), sample)) return

        // 화면에 바로 반영
        val next: Map<LampId, List<BrightnessSample>> = _samples.value + (lamp to (previous + sample).takeLast(MAX_SAMPLES_PER_LAMP))
        _samples.value = next

        // JSON으로 바꿔 저장
        prefs.edit().putString(KEY_SAMPLES, encodeSamples(next)).apply()
    }

    // 저장된 시연 조작 상태 불러오기 (없으면 조작 없음)
    fun loadFaults(): SimulatedFaults = decodeFaults(prefs.getString(KEY_FAULTS, null))

    // 시연 조작 상태 저장
    fun saveFaults(faults: SimulatedFaults) {
        prefs.edit().putString(KEY_FAULTS, encodeFaults(faults)).apply()
    }
}

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// 이전 기록보다 누적 시간이 0.5시간 이상 늘었거나 밝기가 1%p 이상 바뀌었으면 기록
private fun shouldRecord(previous: BrightnessSample?, next: BrightnessSample): Boolean {
    if (previous == null) return true
    return next.usedHours - previous.usedHours >= RECORD_MIN_HOURS ||
        abs(next.brightnessPercent - previous.brightnessPercent) >= RECORD_MIN_BRIGHTNESS_CHANGE
}

// 램프 키를 JSON 객체에 넣음
private fun putLamp(item: JSONObject, lamp: LampId) {
    item.put(FIELD_GROUP, lamp.group.name)
    item.put(FIELD_SIDE, lamp.side.name)
}

// JSON 객체에서 램프 키를 꺼냄
private fun readLamp(item: JSONObject): LampId =
    LampId(LampGroup.valueOf(item.getString(FIELD_GROUP)), LampSide.valueOf(item.getString(FIELD_SIDE)))

// 누적 점등 시간을 JSON 문자열로 변환
private fun encodeUsage(usage: LampUsage): String {
    val lamps = JSONArray()
    for ((lamp, hours) in usage.usedHours) {
        val item = JSONObject()
        putLamp(item, lamp)
        item.put(FIELD_HOURS, hours)
        lamps.put(item)
    }
    val root = JSONObject()
    root.put(FIELD_FIRST_RECORDED_AT, usage.firstRecordedAtMillis ?: JSONObject.NULL)
    root.put(FIELD_LAMPS, lamps)
    return root.toString()
}

// JSON 문자열을 누적 점등 시간으로 변환. 저장된 값이 없으면 빈 기록
private fun decodeUsage(json: String?): LampUsage {
    if (json == null) return LampUsage()

    val root = JSONObject(json)
    val lamps: JSONArray = root.getJSONArray(FIELD_LAMPS)
    val usedHours: Map<LampId, Double> = (0 until lamps.length()).associate { index ->
        val item: JSONObject = lamps.getJSONObject(index)
        readLamp(item) to item.getDouble(FIELD_HOURS)
    }
    val firstRecordedAt: Long? = if (root.isNull(FIELD_FIRST_RECORDED_AT)) null else root.getLong(FIELD_FIRST_RECORDED_AT)
    return LampUsage(usedHours = usedHours, firstRecordedAtMillis = firstRecordedAt)
}

// 램프별 측정값을 JSON 배열 문자열로 변환
private fun encodeSamples(samples: Map<LampId, List<BrightnessSample>>): String {
    val array = JSONArray()
    for ((lamp, list) in samples) {
        val values = JSONArray()
        for (sample in list) {
            val value = JSONObject()
            value.put(FIELD_HOURS, sample.usedHours)
            value.put(FIELD_BRIGHTNESS, sample.brightnessPercent)
            value.put(FIELD_MEASURED_AT, sample.measuredAtMillis)
            values.put(value)
        }
        val item = JSONObject()
        putLamp(item, lamp)
        item.put(FIELD_SAMPLES, values)
        array.put(item)
    }
    return array.toString()
}

// JSON 배열 문자열을 램프별 측정값으로 변환. 저장된 값이 없으면 빈 맵
private fun decodeSamples(json: String?): Map<LampId, List<BrightnessSample>> {
    if (json == null) return emptyMap()

    val array = JSONArray(json)
    return (0 until array.length()).associate { index ->
        val item: JSONObject = array.getJSONObject(index)
        val values: JSONArray = item.getJSONArray(FIELD_SAMPLES)
        val list: List<BrightnessSample> = (0 until values.length()).map { position ->
            val value: JSONObject = values.getJSONObject(position)
            BrightnessSample(
                usedHours = value.getDouble(FIELD_HOURS),
                brightnessPercent = value.getDouble(FIELD_BRIGHTNESS),
                measuredAtMillis = value.getLong(FIELD_MEASURED_AT)
            )
        }
        readLamp(item) to list
    }
}

// 시연 조작 상태를 JSON 문자열로 변환
private fun encodeFaults(faults: SimulatedFaults): String {
    val drops = JSONArray()
    for ((lamp, percentPoints) in faults.extraDrop) {
        val item = JSONObject()
        putLamp(item, lamp)
        item.put(FIELD_PERCENT_POINTS, percentPoints)
        drops.put(item)
    }
    val failed = JSONArray()
    for (lamp in faults.failed) {
        val item = JSONObject()
        putLamp(item, lamp)
        failed.put(item)
    }
    val root = JSONObject()
    root.put(FIELD_EXTRA_DROP, drops)
    root.put(FIELD_FAILED, failed)
    return root.toString()
}

// JSON 문자열을 시연 조작 상태로 변환. 저장된 값이 없으면 조작 없음
private fun decodeFaults(json: String?): SimulatedFaults {
    if (json == null) return SimulatedFaults()

    val root = JSONObject(json)
    val drops: JSONArray = root.getJSONArray(FIELD_EXTRA_DROP)
    val failed: JSONArray = root.getJSONArray(FIELD_FAILED)
    return SimulatedFaults(
        extraDrop = (0 until drops.length()).associate { index ->
            val item: JSONObject = drops.getJSONObject(index)
            readLamp(item) to item.getDouble(FIELD_PERCENT_POINTS)
        },
        failed = (0 until failed.length()).map { index -> readLamp(failed.getJSONObject(index)) }.toSet()
    )
}

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
