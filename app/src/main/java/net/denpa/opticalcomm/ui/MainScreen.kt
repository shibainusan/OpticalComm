package net.denpa.opticalcomm.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.denpa.opticalcomm.LightReceiver
import net.denpa.opticalcomm.MAX_PAYLOAD_BYTES
import net.denpa.opticalcomm.ReceiverViewModel
import net.denpa.opticalcomm.SenderUiState
import net.denpa.opticalcomm.SenderViewModel

@Composable
fun MainScreen(cameraGranted: Boolean, requestCamera: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!cameraGranted) {
            Text("カメラ権限が必要です")
            Button(onClick = requestCamera) { Text("許可する") }
        } else {
            val senderVm: SenderViewModel = viewModel()
            val sender by senderVm.state.collectAsStateWithLifecycle()
            SendSection(senderVm, sender)
            // トーチとカメラは同時に使えない端末があるので、送信中は受信カメラを止める
            ReceiveSection(paused = sender.sending, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun SendSection(vm: SenderViewModel, s: SenderUiState) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("送信する文字(UTF-8で最大${MAX_PAYLOAD_BYTES}バイト)") },
            enabled = !s.sending,
            modifier = Modifier.fillMaxWidth(),
        )
        if (s.sending) {
            LinearProgressIndicator(
                progress = { if (s.total == 0) 0f else s.progress.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::cancel) { Text("中止") }
                Text("送信中 SN=${s.sn} ${s.progress}/${s.total} bit")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.send(text) }, enabled = text.isNotEmpty()) { Text("送信") }
                Text("背面のライトを相手のカメラへ", style = MaterialTheme.typography.bodySmall)
            }
        }
        s.error?.let { Text(it, color = Color.Red) }
    }
}

@Composable
private fun ReceiveSection(paused: Boolean, modifier: Modifier, vm: ReceiverViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var front by rememberSaveable { mutableStateOf(false) }
    var aeLocked by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(true) }
    val receiver = remember { LightReceiver(context) }

    DisposableEffect(front, paused) {
        if (!paused) {
            vm.resetSignal()
            receiver.start(owner, front, vm::onSample, vm::onRebase, vm::onFrame, onAeLock = { aeLocked = it })
        }
        onDispose { receiver.stop() }
    }

    val bitScroll = rememberScrollState()
    // 末尾付近を見ているときだけ、新しいビットに追従して最下部へスクロールする
    LaunchedEffect(s.bitLines.size, s.currentBits.length) {
        if (bitScroll.maxValue - bitScroll.value < 200) bitScroll.scrollTo(bitScroll.maxValue)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 解析画像を 32x32 に縮小したもの(トラッキングが実際に見ている絵)を、プレビューの代わりに表示する
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            val f = s.frame
            if (f != null) {
                val bitmap = remember(f, showDiff) {
                    Bitmap.createBitmap(if (showDiff) f.diffPixels else f.pixels, f.size, f.size, Bitmap.Config.ARGB_8888)
                        .asImageBitmap()
                }
                Box(Modifier.fillMaxHeight().aspectRatio(1f)) {
                    Image(
                        bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                    )
                    // デバッグ用: トラッキング中のセルを赤枠で表示する(全体平均のときは画像全体)
                    Canvas(Modifier.fillMaxSize()) {
                        val cell = size.width / f.size
                        if (f.roiCol < 0) {
                            drawRect(Color.Red, style = Stroke(width = 3f))
                        } else {
                            // 1セルは小さいので、中心を合わせて最小 18px の枠にする
                            val b = maxOf(cell * 3f, 18f)
                            val cx = (f.roiCol + 0.5f) * cell
                            val cy = (f.roiRow + 0.5f) * cell
                            drawRect(Color.Red, topLeft = Offset(cx - b / 2f, cy - b / 2f), size = Size(b, b), style = Stroke(width = 3f))
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { front = !front }) { Text(if (front) "前面カメラ" else "背面カメラ") }
            TextButton(onClick = vm::clearLog) { Text("履歴消去") }
            TextButton(onClick = { showDiff = !showDiff }) { Text(if (showDiff) "表示:差分" else "表示:輝度") }
            TextButton(onClick = { receiver.setAeLocked(!aeLocked) }, enabled = !paused) {
                Text(if (aeLocked) "AEロック中(解除)" else "AE解除中(ロック)")
            }
        }
        Text(if (paused) "送信中のため受信停止" else "信号: ${if (s.level) "ON" else "OFF"} / 状態: ${s.decoderState}${s.frame?.let { " / 解析 ${it.imageWidth}x${it.imageHeight}" } ?: ""}")
        s.frame?.let {
            Text("追跡セル(ΔY最大) Y=%.0f U=%.1f V=%.1f ΔY=%+.1f (最大|ΔY|=%.1f)".format(it.roiY, it.roiU, it.roiV, it.roiDiff, it.maxAbsDiff), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
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
        Text("受信ビット", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(bitScroll)) {
            s.bitLines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            if (s.currentBits.isNotEmpty()) {
                Text(s.currentBits, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Text("受信メッセージ", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth().height(72.dp).verticalScroll(rememberScrollState())) {
            s.log.asReversed().forEach { Text(it) }
        }
    }
}
