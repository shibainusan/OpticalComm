package net.denpa.opticalcomm

/** 1ビットあたりの時間。端末のトーチ応答とカメラ30fpsに余裕を持たせた値。 */
const val BIT_MS = 70L

/** 送信開始前の消灯時間(受信側の閾値校正用)。 */
const val LEAD_IDLE_MS = 1000L

const val MAX_PAYLOAD_BYTES = 64

private const val PREAMBLE_BYTE = 0xAA
private const val SYNC_BYTE = 0x7E

object Crc8 {
    fun compute(data: ByteArray, init: Int = 0): Int {
        var crc = init and 0xFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }
}

object Frame {
    /**
     * フレーム: AA AA 7E LEN PAYLOAD CRC8(LEN+PAYLOAD)。MSB first。
     * [crcCorruption] はテスト用に CRC を意図的にずらす。
     */
    fun encode(text: String, crcCorruption: Int = 0): List<Boolean> {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) { "too long" }
        val body = byteArrayOf(payload.size.toByte()) + payload
        val bytes = byteArrayOf(PREAMBLE_BYTE.toByte(), PREAMBLE_BYTE.toByte(), SYNC_BYTE.toByte()) +
            body + byteArrayOf((Crc8.compute(body) xor crcCorruption).toByte())
        val bits = ArrayList<Boolean>(bytes.size * 8)
        for (b in bytes) for (i in 7 downTo 0) bits.add((b.toInt() shr i) and 1 == 1)
        return bits
    }

    fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size
}

sealed interface DecodeEvent {
    /** プリアンブル+SYNC を検出した。 */
    data object PreambleOk : DecodeEvent
    data class PreambleFail(val reason: String) : DecodeEvent
    /** CRC が一致した場合のみ。 */
    data class Message(val text: String) : DecodeEvent
    /** CRC 不一致。LEN 分は受信し続けるので、化けた [text] も得られる。 */
    data class CrcError(val length: Int, val text: String) : DecodeEvent
}

enum class DecoderState { HUNT, LENGTH, PAYLOAD, CRC }

/** 消灯がこの bit 数続いた後の最初の点灯を、フレーム開始候補とみなす。 */
private const val ARM_ZEROS = 8

/** 点灯を検出してからこの bit 数以内にプリアンブル+SYNC (24bit) が揃わなければ失敗とする。 */
private const val HUNT_LIMIT_BITS = 48

/** 同期判定には末尾2バイト(AA 7E)の16bitを使う。先頭の AA は直前の消灯との境目で欠けやすいため要求しない。 */
private const val SYNC_BITS = 16
private const val SYNC_MASK = (1 shl SYNC_BITS) - 1
private const val SYNC_VALUE = (PREAMBLE_BYTE shl 8) or SYNC_BYTE

/** ビット列を1つずつ受け取り、プリアンブル+SYNC検出→LEN→PAYLOAD→CRCの順に復号する。 */
class BitStreamDecoder {
    var state = DecoderState.HUNT
        private set

    /** 点灯を検出してプリアンブルを探している間、または復号中は true。UI でビット表示するかの判断に使う。 */
    var active = false
        private set

    /** 直近の push で8bitが揃ったらそのバイト値、そうでなければ -1。 */
    var lastByte = -1
        private set

    private var zeroRun = ARM_ZEROS
    private var huntBits = 0
    private var window = 0
    private var cur = 0
    private var curBits = 0
    private var length = 0
    private val payload = java.io.ByteArrayOutputStream()

    /** フレーム終了後など。次の点灯をすぐ候補にできる。 */
    fun reset() = resetTo(rearmed = true)

    /**
     * 失敗時(rearmed=false)もビット窓は捨てない。ノイズで早く点灯検出して失敗扱いになっても、
     * 直後に本物のプリアンブルが来れば、そのビットを取りこぼさず同期できるようにする。
     */
    private fun resetTo(rearmed: Boolean) {
        state = DecoderState.HUNT
        active = false
        zeroRun = if (rearmed) ARM_ZEROS else 0
        huntBits = 0
        if (rearmed) window = 0
        cur = 0
        curBits = 0
        payload.reset()
    }

    fun push(bit: Boolean): DecodeEvent? {
        lastByte = -1
        val v = if (bit) 1 else 0
        if (state == DecoderState.HUNT) {
            // 点灯検出より前のビットも含めて常に窓へ入れる
            window = ((window shl 1) or v) and SYNC_MASK
            if (!active) {
                if (!bit) {
                    zeroRun++
                } else if (zeroRun >= ARM_ZEROS) {
                    active = true
                    huntBits = 0
                } else {
                    zeroRun = 0
                }
            }
            if (active) huntBits++
            if (window == SYNC_VALUE) {
                active = true
                state = DecoderState.LENGTH
                cur = 0
                curBits = 0
                payload.reset()
                return DecodeEvent.PreambleOk
            }
            if (active && huntBits >= HUNT_LIMIT_BITS) {
                resetTo(rearmed = false)
                return DecodeEvent.PreambleFail("SYNC(AA AA 7E)が見つからない")
            }
            return null
        }
        cur = (cur shl 1) or v
        if (++curBits < 8) return null
        val byte = cur
        cur = 0
        curBits = 0
        lastByte = byte
        when (state) {
            DecoderState.LENGTH -> {
                if (byte > MAX_PAYLOAD_BYTES) {
                    resetTo(rearmed = false)
                    return DecodeEvent.PreambleFail("長さ不正($byte)")
                }
                length = byte
                state = if (length == 0) DecoderState.CRC else DecoderState.PAYLOAD
            }
            DecoderState.PAYLOAD -> {
                payload.write(byte)
                if (payload.size() == length) state = DecoderState.CRC
            }
            DecoderState.CRC -> {
                val data = payload.toByteArray()
                val crc = Crc8.compute(byteArrayOf(length.toByte()) + data)
                val len = length
                val text = String(data, Charsets.UTF_8)
                reset()
                return if (crc == byte) DecodeEvent.Message(text) else DecodeEvent.CrcError(len, text)
            }
            DecoderState.HUNT -> Unit
        }
        return null
    }
}
