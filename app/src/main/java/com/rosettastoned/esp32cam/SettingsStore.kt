package com.rosettastoned.esp32cam

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(value) = prefs.edit().putString("apiKey", value).apply()

    var port: Int
        get() = prefs.getInt("port", 8080)
        set(value) = prefs.edit().putInt("port", value).apply()

    var prompt: String
        get() = prefs.getString(
            "prompt",
            "시각장애인용 보조 앱입니다. 사진 속 사물, 사람, 위험 요소를 한국어로 2~3문장, 100자 이내로 설명해주세요. 안전 위험이 있으면 가장 먼저 언급해주세요."
        ) ?: ""
        set(value) = prefs.edit().putString("prompt", value).apply()

    var autoAnalyze: Boolean
        get() = prefs.getBoolean("autoAnalyze", true)
        set(value) = prefs.edit().putBoolean("autoAnalyze", value).apply()

    var bleSsid: String
        get() = prefs.getString("bleSsid", "") ?: ""
        set(value) = prefs.edit().putString("bleSsid", value).apply()

    var blePass: String
        get() = prefs.getString("blePass", "") ?: ""
        set(value) = prefs.edit().putString("blePass", value).apply()

    var phoneIp: String
        get() = prefs.getString("phoneIp", "192.168.43.1") ?: ""
        set(value) = prefs.edit().putString("phoneIp", value).apply()

    var phonePort: String
        get() = prefs.getString("phonePort", "8080") ?: "8080"
        set(value) = prefs.edit().putString("phonePort", value).apply()
