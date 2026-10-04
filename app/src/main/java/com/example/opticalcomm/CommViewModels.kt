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
)

class ReceiverViewModel : ViewModel() {
    private val decoder = BitStreamDecoder()
    private val log = ArrayList<String>()
    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state
    private val window = ArrayDeque<Float>()

    private val slicer = SignalSlicer { bit ->
        decoder.push(bit)?.let { ev ->
            synchronized(log) {
                log.add(
                    when (ev) {
                        is DecodeEvent.Message -> "受信: ${ev.text}"
                        is DecodeEvent.CrcError -> "CRCエラー(len=${ev.length})"
                    }
                )
            }
        }
    }

    /** 解析スレッドから呼ばれる。 */
    fun onSample(t: Long, lum: Float) {
        slicer.push(t, lum)
        window.addLast(lum)
        if (window.size > 150) window.removeFirst()
        _state.value = ReceiverUiState(
            samples = window.toList(),
            threshold = slicer.threshold,
            level = slicer.level,
            decoderState = decoder.state,
            log = synchronized(log) { log.toList() },
        )
    }

    fun resetSignal() {
        slicer.reset()
        decoder.reset()
    }

    fun clearLog() {
        synchronized(log) { log.clear() }
        _state.update { it.copy(log = emptyList()) }
    }
}
