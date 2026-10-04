package net.denpa.opticalcomm

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

    /** プリアンブル受信後は 8bit ごとのバイトを HEX トークンにして表示する。 */
    private var inFrame = false
    private val hexTokens = ArrayList<String>()
    private val partial = StringBuilder()

    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state
    private val window = ArrayDeque<Float>()

    private val slicer = SignalSlicer { bit ->
        val ev = decoder.push(bit)
        synchronized(log) {
            if (decoder.active || ev != null) {
                if (inFrame) {
                    partial.append(if (bit) '1' else '0')
                    if (decoder.lastByte >= 0) {
                        hexTokens.add("0x%02X".format(decoder.lastByte))
                        partial.clear()
                    }
                } else {
                    if (curCount > 0 && curCount % 8 == 0) curBits.append(' ')
                    curBits.append(if (bit) '1' else '0')
                    curCount++
                }
            }
            when (ev) {
                DecodeEvent.PreambleOk -> {
                    regroupFromEnd()
                    endLine("←プリアンブル受信成功")
                    inFrame = true
                }
                is DecodeEvent.PreambleFail -> endLine("←プリアンブル失敗(${ev.reason})")
                is DecodeEvent.Message -> {
                    endLine("←CRC OK")
                    log.add("受信: ${ev.text}")
                }
                is DecodeEvent.CrcError -> {
                    endLine("←NG(len=${ev.length}, CRC不一致)")
                    log.add("受信(エラー): ${ev.text}")
                }
                null -> Unit
            }
        }
    }

    private fun currentLine(): String =
        if (inFrame) (hexTokens + listOfNotNull(partial.takeIf { it.isNotEmpty() }?.toString())).joinToString(" ")
        else curBits.toString()

    /** 点灯検出前の先頭ビットが欠けるため、8bit区切りを末尾(=プリアンブル終端)基準で付け直す。 */
    private fun regroupFromEnd() {
        val raw = curBits.toString().replace(" ", "")
        curBits.clear()
        val head = raw.length % 8
        if (head > 0) curBits.append(raw, 0, head)
        for (i in head until raw.length step 8) {
            if (curBits.isNotEmpty()) curBits.append(' ')
            curBits.append(raw, i, i + 8)
        }
    }

    private fun endLine(note: String) {
        bitLines.add("${currentLine()} $note")
        if (bitLines.size > 40) bitLines.removeAt(0)
        clearCurrent()
    }

    private fun clearCurrent() {
        curBits.clear()
        curCount = 0
        inFrame = false
        hexTokens.clear()
        partial.clear()
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
                currentBits = currentLine(),
            )
        }
    }

    fun resetSignal() {
        slicer.reset()
        decoder.reset()
        synchronized(log) { clearCurrent() }
    }

    fun clearLog() {
        synchronized(log) {
            log.clear()
            bitLines.clear()
            clearCurrent()
        }
        publish()
    }
}
