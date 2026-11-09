// -----------------------------------------------------------------------------------
// 파일명       : PleosVoiceAssistant.kt
// 설명         : Pleos TextToSpeech SDK로 음성 안내를 하는 구현체
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 앱 등록이 필요 없는 ON_DEVICE 모드, 한국어(ko-KR)로 TTS 초기화
// - 문장을 말하고 재생이 끝날 때까지 대기
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.voice

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

private const val TAG: String = "LampCheck"

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// Pleos TTS 구현체
class PleosVoiceAssistant(context: Context) : VoiceAssistant {

    private val appContext: Context = context.applicationContext

    // SDK 객체 (release 뒤에는 새로 만들어야 하므로 start에서 생성)
    private var ttsEngine: TextToSpeech? = null

    private val _canSpeak: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val canSpeak: StateFlow<Boolean> = _canSpeak.asStateFlow()

    // 진행 중인 말하기의 결과 (SDK 콜백 스레드에서 완료됨)
    @Volatile
    private var pendingSpeak: CompletableDeferred<Unit>? = null

    // TTS 재생 이벤트: 끝남, 멈춤, 오류 중 하나가 오면 말하기 대기를 끝냄
    private val ttsListener: EventListener = object : EventListener {
        override fun onReady() = Unit

        override fun onStart() = Unit

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            pendingSpeak?.complete(Unit)
        }

        override fun onStop() {
            pendingSpeak?.complete(Unit)
        }

        override fun onError(errorType: TtsErrorType) {
            Log.e(TAG, "TTS 오류: $errorType")
            pendingSpeak?.complete(Unit)
        }
    }

    override fun start() {
        startTts()
    }

    override fun release() {
        // 진행 중인 말하기 중단
        stop()

        // 리스너 제거 후 SDK 해제
        runSafely("TTS 해제") {
            ttsEngine?.let { engine ->
                engine.removeEventListener(ttsListener)
                engine.release()
            }
        }
        ttsEngine = null
        _canSpeak.value = false
    }

    override suspend fun speak(text: String) {
        val engine: TextToSpeech = ttsEngine ?: return
        if (!_canSpeak.value) return

        // 이번 말하기가 끝났다는 신호를 받을 자리 준비
        val done: CompletableDeferred<Unit> = CompletableDeferred()
        pendingSpeak = done

        // 문장을 하나의 완성된 요청으로 합성해 차량 오디오로 재생
        try {
            engine.speak(text, true)
        } catch (e: Exception) {
            Log.e(TAG, "TTS 말하기 실패", e)
            pendingSpeak = null
            return
        }

        // 재생이 끝나거나 멈추거나 오류가 날 때까지 대기
        try {
            done.await()
        } finally {
            if (pendingSpeak === done) pendingSpeak = null
        }
    }

    override fun stop() {
        // 진행 중인 것만 멈추고, 기다리던 호출을 바로 끝냄
        pendingSpeak?.let { done ->
            runSafely("TTS 중지") { ttsEngine?.stop() }
            done.complete(Unit)
        }
    }

    // TTS 생성과 초기화. 성공하면 리스너 등록, 한국어 설정 후 canSpeak = true
    private fun startTts() {
        runSafely("TTS 초기화") {
            // 앱 등록이 필요 없는 차량 내장 엔진 모드
            val engine: TextToSpeech = TextToSpeech(appContext, TtsMode.ON_DEVICE)
            ttsEngine = engine
            engine.initialize(object : TtsInitializeListener {
                override fun onSuccess() {
                    runSafely("TTS 설정") {
                        engine.addEventListener(ttsListener)
                        engine.setLocale(Locale.KOREA)
                        _canSpeak.value = true
                    }
                }

                override fun onFailure(errorType: TtsErrorType) {
                    Log.e(TAG, "TTS 초기화 실패: $errorType")
                    _canSpeak.value = false
                }
            })
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

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------
