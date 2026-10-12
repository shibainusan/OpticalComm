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
    /** 送信中のフレームのシーケンス番号(0..15)。 */
    val sn: Int = 0,
)

class SenderViewModel(app: Application) : AndroidViewModel(app) {
    private val sender = TorchSender(app)
    private var job: Job? = null
    private var nextSn = 0
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
        val sn = nextSn
        nextSn = (nextSn + 1) and SN_MASK
        val bits = Frame.encode(text, sn)
        _state.value = SenderUiState(sending = true, total = bits.size, sn = sn)
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
    /** 点灯を検出してプリアンブルを探している間 true(HUNT のうち、点灯検出前は false)。 */
    val preambleActive: Boolean = false,
    val log: List<String> = emptyList(),
    /** 受信ビット(8bit区切り)と判定結果の注釈。確定済みの行。 */
    val bitLines: List<String> = emptyList(),
    /** 受信中でまだ注釈が付いていない行。 */
    val currentBits: String = "",
    /** 解析画像を縮小したデバッグ画像と、トラッキング中のセル(赤枠で表示)。 */
    val frame: DebugFrame? = null,
)

private const val HUNT_RING_BITS = 24

class ReceiverViewModel : ViewModel() {
    private val decoder = BitStreamDecoder()
    private val log = ArrayList<String>()
    private val bitLines = ArrayList<String>()

    /** HUNT 中は直近 [HUNT_RING_BITS] ビットを常に流し込み、プリアンブル検出まで同じ行を更新し続ける。 */
    private val ring = StringBuilder()

    /** プリアンブル受信後は 8bit ごとのバイトを HEX トークンにして表示する。 */
    private var inFrame = false
    private val hexTokens = ArrayList<String>()
    private val partial = StringBuilder()

    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state
    private val window = ArrayDeque<Float>()

    private val slicer: SignalSlicer = SignalSlicer { bit ->
        val ev = decoder.push(bit)
        // プリアンブル受信成功(以降 HUNT 以外)からフレーム終了までは、しきい値を固定する
        slicer.frozen = decoder.state != DecoderState.HUNT
        synchronized(log) {
            if (inFrame) {
                partial.append(if (bit) '1' else '0')
                if (decoder.lastByte >= 0) {
                    hexTokens.add("0x%02X".format(decoder.lastByte))
                    partial.clear()
                }
            } else {
                ring.append(if (bit) '1' else '0')
                if (ring.length > HUNT_RING_BITS) ring.deleteCharAt(0)
            }
            when (ev) {
                DecodeEvent.PreambleOk -> {
                    endLine("←プリアンブル受信成功")
                    inFrame = true
                }
                // 同期前の失敗は行を確定させず、リングの更新を続ける。フレーム中(LEN不正)だけ行を閉じる
                is DecodeEvent.PreambleFail -> if (inFrame) endLine("←失敗(${ev.reason})")
                is DecodeEvent.Message -> {
                    endLine("←CRC OK SN=${ev.sn}")
                    log.add("受信(SN=${ev.sn}): ${ev.text}")
                }
                is DecodeEvent.CrcError -> {
                    endLine("←NG(len=${ev.length}, CRC不一致, SN=${ev.sn})")
                    log.add("受信(エラー, SN=${ev.sn}): ${ev.text}")
                }
                null -> Unit
            }
        }
    }

    /** 注釈を付ける前の、ビット/HEX だけの行。確定する行はこれに結果の注釈を付ける。 */
    private fun baseLine(): String =
        if (inFrame) (hexTokens + listOfNotNull(partial.takeIf { it.isNotEmpty() }?.toString())).joinToString(" ")
        else groupFromEnd(ring)

    /** 表示中の行。プリアンブルを探している間だけ「受信中」を添える(確定時は成功/失敗の注釈に置き換える)。 */
    private fun currentLine(): String =
        if (!inFrame && decoder.active) "${baseLine()} ←プリアンブル受信中" else baseLine()

    /** 最新ビット(末尾)を基準に8bit区切りにする。リングが流れても区切り位置が末尾に揃う。 */
    private fun groupFromEnd(raw: CharSequence): String {
        val sb = StringBuilder()
        val head = raw.length % 8
        if (head > 0) sb.append(raw, 0, head)
        for (i in head until raw.length step 8) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(raw, i, i + 8)
        }
        return sb.toString()
    }

    private fun endLine(note: String) {
        bitLines.add("${baseLine()} $note")
        if (bitLines.size > 40) bitLines.removeAt(0)
        clearCurrent()
    }

    private fun clearCurrent() {
        ring.clear()
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

    /** 参照領域が切り替わったとき(解析スレッドから、直後の onSample の前に呼ばれる)。 */
    fun onRebase(hi: Float, lo: Float) = slicer.rebase(hi, lo)

    @Volatile
    private var debugFrame: DebugFrame? = null

    /** デバッグ画像が更新されたとき(解析スレッドから)。 */
    fun onFrame(f: DebugFrame) {
        debugFrame = f
    }

    private fun publish() {
        synchronized(log) {
            _state.value = ReceiverUiState(
                samples = window.toList(),
                threshold = slicer.threshold,
                level = slicer.level,
                decoderState = decoder.state,
                preambleActive = decoder.active,
                log = log.toList(),
                bitLines = bitLines.toList(),
                currentBits = currentLine(),
                frame = debugFrame,
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
