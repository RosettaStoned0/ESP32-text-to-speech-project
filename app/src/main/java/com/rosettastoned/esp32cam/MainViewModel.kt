package com.rosettastoned.esp32cam

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val analyzer = VisionAnalyzer()
    private var server: Esp32ImageServer? = null
    private var latestBytes: ByteArray? = null

    private val tts = TtsSpeaker(application)

    var serverStatus by mutableStateOf("stopped")
        private set
    var latestImage by mutableStateOf<Bitmap?>(null)
        private set
    var analysisText by mutableStateOf("")
        private set
    var isAnalyzing by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var apiKey by mutableStateOf(settings.apiKey)
    var port by mutableStateOf(settings.port)
    var prompt by mutableStateOf(settings.prompt)
    var autoAnalyze by mutableStateOf(settings.autoAnalyze)

    fun startServer() {
        settings.apiKey = apiKey
        settings.port = port
        settings.prompt = prompt
        settings.autoAnalyze = autoAnalyze

        val newServer = Esp32ImageServer(
            onImage = ::handleImage,
            onStatus = ::handleServerStatus
        )
        server?.stop()
        newServer.start(port)
        server = newServer
    }

    private fun handleServerStatus(status: String) {
        viewModelScope.launch(Dispatchers.Main) { serverStatus = status }
    }

    private fun handleImage(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.Main) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                errorMessage = "이미지 디코딩 실패"
                return@launch
            }
            latestBytes = bytes
            latestImage = bitmap
            errorMessage = null
            if (autoAnalyze && apiKey.isNotBlank() && !isAnalyzing) {
                analyzeBytes(bytes)
            }
        }
    }

    fun analyzeCurrentImage() {
        val bytes = latestBytes ?: return
        if (apiKey.isBlank()) {
            errorMessage = "API 키를 먼저 입력하세요"
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            if (!isAnalyzing) analyzeBytes(bytes)
        }
    }

    private suspend fun analyzeBytes(bytes: ByteArray) {
        isAnalyzing = true
        analysisText = ""
        val result = withContext(Dispatchers.IO) { analyzer.analyze(apiKey, bytes, prompt) }
        isAnalyzing = false
        result.fold(
            onSuccess = { text ->
                analysisText = text
                tts.speak(text)
            },
            onFailure = { e ->
                errorMessage = e.message ?: "분석 실패"
            }
        )
    }

    fun replayTts() {
        if (analysisText.isNotBlank()) tts.speak(analysisText)
    }

    fun stopTts() {
        tts.stop()
    }

    override fun onCleared() {
        server?.stop()
        tts.shutdown()
        super.onCleared()
    }
}
