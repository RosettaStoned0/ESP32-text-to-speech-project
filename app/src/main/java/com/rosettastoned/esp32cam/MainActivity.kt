package com.rosettastoned.esp32cam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        viewModel.stopTts()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(vm: MainViewModel) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("ESP32 Vision TTS") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusSection(vm)
            SettingsSection(vm)
            ImageSection(vm)
            ResultSection(vm)
        }
    }
}

@Composable
private fun StatusSection(vm: MainViewModel) {
    Column {
        Text("서버 상태: ${vm.serverStatus}")
        Button(onClick = { vm.startServer() }) {
            Text("전송 대기 시작")
        }
    }
}

@Composable
private fun SettingsSection(vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vm.apiKey,
            onValueChange = { vm.apiKey = it },
            label = { Text("OpenAI API 키") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = vm.port.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { if (it in 1..65535) vm.port = it }
                },
                label = { Text("포트") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.size(width = 120.dp, height = 64.dp)
            )
            Text("받으면 자동 분석")
            Switch(
                checked = vm.autoAnalyze,
                onCheckedChange = { vm.autoAnalyze = it }
            )
        }
        OutlinedTextField(
            value = vm.prompt,
            onValueChange = { vm.prompt = it },
            label = { Text("분석 프롬프트") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
    }
}

@Composable
private fun ImageSection(vm: MainViewModel) {
    Column {
        val image = vm.latestImage
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "ESP32에서 받은 최근 사진",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )
        } else {
            Text("ESP32의 첫 촬영을 기다리는 중...")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.analyzeCurrentImage() }) {
                Text("다시 분석")
            }
            TextButton(onClick = { vm.replayTts() }) {
                Text("다시 듣기")
            }
            TextButton(onClick = { vm.stopTts() }) {
                Text("재생 중지")
            }
        }
        if (vm.isAnalyzing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("분석 중...")
            }
        }
        vm.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ResultSection(vm: MainViewModel) {
    Column {
        Text("분석 결과", style = MaterialTheme.typography.titleMedium)
        Text(vm.analysisText.ifBlank { "아직 결과가 없습니다." })
    }
}
