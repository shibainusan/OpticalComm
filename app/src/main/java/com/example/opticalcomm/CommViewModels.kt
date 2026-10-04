package com.example.opticalcomm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SenderUiState(
    val sending: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

class SenderViewModel(app: Application) : AndroidViewModel(app) {
    private val sender = TorchSender(app)
    private var job: Job? = null
    private val _state = MutableStateFlow(
        SenderUiState(error = if (sender.available) null else "フラッシュ付きカメラがありません")
    )
    val state: StateFlow<SenderUiState> = _state

    fun send(text: String) {
        if (job?.isActive == true || text.isEmpty()) return
        if (Frame.utf8Size(text) > MAX_PAYLOAD_BYTES) {
            _state.value = SenderUiState(error = "長すぎます(UTF-8で最大${MAX_PAYLOAD_BYTES}バイト)")
            return
        }
        val bits = Frame.encode(text)
        _state.value = SenderUiState(sending = true, total = bits.size)
        job = viewModelScope.launch {
            try {
                sender.send(bits) { p -> _state.update { it.copy(progress = p) } }
                _state.value = SenderUiState()
            } catch (e: CancellationException) {
                _state.value = SenderUiState()
                throw e
            } catch (e: Exception) {
                _state.value = SenderUiState(error = e.message)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

data class ReceiverUiState(
    val samples: List<Float> = emptyList(),
    val threshold: Float = 0f,
    val level: Boolean = false,
    val decoderState: DecoderState = DecoderState.HUNT,
    val log: List<String> = emptyList(),
    /** 受信ビット(8bit区切り)と判定結果の注釈。確定済みの行。 */
    val bitLines: List<String> = emptyList(),
    /** 受信中でまだ注釈が付いていない行。 */
    val currentBits: String = "",
)

class ReceiverViewModel : ViewModel() {
    private val decoder = BitStreamDecoder()
    private val log = ArrayList<String>()
    private val bitLines = ArrayList<String>()
    private val curBits = StringBuilder()
    private var curCount = 0
    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state
    private val window = ArrayDeque<Float>()

    private val slicer = SignalSlicer { bit ->
        val ev = decoder.push(bit)
        synchronized(log) {
            if (decoder.active || ev != null) {
                if (curCount > 0 && curCount % 8 == 0) curBits.append(' ')
                curBits.append(if (bit) '1' else '0')
                curCount++
            }
            when (ev) {
                DecodeEvent.PreambleOk -> endLine("←プリアンブル受信成功")
                is DecodeEvent.PreambleFail -> endLine("←プリアンブル失敗(${ev.reason})")
                is DecodeEvent.Message -> {
                    endLine("←CRC OK")
                    log.add("受信: ${ev.text}")
                }
                is DecodeEvent.CrcError -> {
                    endLine("←CRC NG(len=${ev.length})")
                    log.add("CRCエラー(len=${ev.length})")
                }
                null -> Unit
            }
        }
    }

    private fun endLine(note: String) {
        bitLines.add("$curBits $note")
        if (bitLines.size > 40) bitLines.removeAt(0)
        curBits.clear()
        curCount = 0
    }

    /** 解析スレッドから呼ばれる。 */
    fun onSample(t: Long, lum: Float) {
        slicer.push(t, lum)
        window.addLast(lum)
        if (window.size > 150) window.removeFirst()
        publish()
    }

    private fun publish() {
        synchronized(log) {
            _state.value = ReceiverUiState(
                samples = window.toList(),
                threshold = slicer.threshold,
                level = slicer.level,
                decoderState = decoder.state,
                log = log.toList(),
                bitLines = bitLines.toList(),
                currentBits = curBits.toString(),
            )
        }
    }

    fun resetSignal() {
        slicer.reset()
        decoder.reset()
        synchronized(log) {
            curBits.clear()
            curCount = 0
        }
    }

    fun clearLog() {
        synchronized(log) {
            log.clear()
            bitLines.clear()
            curBits.clear()
            curCount = 0
        }
        publish()
    }
}
