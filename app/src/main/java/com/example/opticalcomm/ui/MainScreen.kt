package com.example.opticalcomm.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opticalcomm.LightReceiver
import com.example.opticalcomm.MAX_PAYLOAD_BYTES
import com.example.opticalcomm.ReceiverViewModel
import com.example.opticalcomm.SenderViewModel

@Composable
fun MainScreen(cameraGranted: Boolean, requestCamera: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("送信") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("受信") })
        }
        if (!cameraGranted) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("カメラ権限が必要です")
                Button(onClick = requestCamera) { Text("許可する") }
            }
        } else if (tab == 0) {
            SendTab()
        } else {
            ReceiveTab()
        }
    }
}

@Composable
private fun SendTab(vm: SenderViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("送信する文字(UTF-8で最大${MAX_PAYLOAD_BYTES}バイト)") },
            enabled = !s.sending,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("背面のライトを相手のカメラに向けてください")
        if (s.sending) {
            LinearProgressIndicator(
                progress = { if (s.total == 0) 0f else s.progress.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("送信中 ${s.progress}/${s.total} bit")
            Button(onClick = vm::cancel) { Text("中止") }
        } else {
            Button(onClick = { vm.send(text) }, enabled = text.isNotEmpty()) { Text("送信") }
        }
        s.error?.let { Text(it, color = Color.Red) }
    }
}

@Composable
private fun ReceiveTab(vm: ReceiverViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var front by rememberSaveable { mutableStateOf(false) }
    val receiver = remember { LightReceiver(context) }
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }

    DisposableEffect(front) {
        vm.resetSignal()
        receiver.start(owner, previewView, front, vm::onSample)
        onDispose { receiver.stop() }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(200.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { front = !front }) { Text(if (front) "前面カメラ" else "背面カメラ") }
            TextButton(onClick = vm::clearLog) { Text("履歴消去") }
        }
        Text("信号: ${if (s.level) "ON" else "OFF"} / 状態: ${s.decoderState}")
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val n = s.samples.size
            if (n > 1) {
                val vMin = minOf(s.samples.min(), s.threshold)
                val vMax = maxOf(s.samples.max(), s.threshold, vMin + 10f)
                fun y(v: Float) = size.height * (1f - (v - vMin) / (vMax - vMin))
                for (i in 1 until n) {
                    drawLine(
                        Color(0xFF1976D2),
                        Offset(size.width * (i - 1) / 149f, y(s.samples[i - 1])),
                        Offset(size.width * i / 149f, y(s.samples[i])),
                        strokeWidth = 3f,
                    )
                }
                drawLine(Color.Red, Offset(0f, y(s.threshold)), Offset(size.width, y(s.threshold)), strokeWidth = 2f)
            }
        }
        s.log.asReversed().forEach { Text(it) }
    }
}
