package com.example.opticalcomm

/** 1ビットあたりの時間。端末のトーチ応答とカメラ30fpsに余裕を持たせた値。 */
const val BIT_MS = 70L

/** 送信開始前の消灯時間(受信側の閾値校正用)。 */
const val LEAD_IDLE_MS = 1000L

const val MAX_PAYLOAD_BYTES = 64

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
     * フレーム: K28.5 K28.5 | LEN | PAYLOAD | CRC8(LEN+PAYLOAD)。LEN以降は全て 8b/10b のデータ符号。
     * RD− から開始し、K28.5×2 で RD は RD− に戻る。
     * [crcCorruption] はテスト用に CRC を意図的にずらす。
     */
    fun encode(text: String, crcCorruption: Int = 0): List<Boolean> {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) { "too long" }
        val body = byteArrayOf(payload.size.toByte()) + payload
        val bytes = body + byteArrayOf((Crc8.compute(body) xor crcCorruption).toByte())

        val sb = StringBuilder(Code8b10b.PREAMBLE)
        var rd = false
        for (b in bytes) {
            val sym = Code8b10b.encodeData(b.toInt() and 0xFF, rd)
            sb.append(sym.bits)
            rd = sym.rdAfter
        }
        return sb.map { it == '1' }
    }

    fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size
}

sealed interface DecodeEvent {
    /** プリアンブル(K28.5×2)を検出した。 */
    data object PreambleOk : DecodeEvent
    data class PreambleFail(val reason: String) : DecodeEvent
    /** プリアンブル後の 8b/10b 符号が不正(無効符号・ディスパリティ違反など)。 */
    data class SymbolError(val reason: String) : DecodeEvent
    /** CRC が一致した場合のみ。 */
    data class Message(val text: String) : DecodeEvent
    data class CrcError(val length: Int) : DecodeEvent
}

enum class DecoderState { HUNT, LENGTH, PAYLOAD, CRC }

/** 消灯がこの bit 数続いた後の最初の点灯を、フレーム開始候補とみなす。 */
private const val ARM_ZEROS = 8

/** 点灯を検出してからこの bit 数以内にプリアンブルが揃わなければ失敗とする。 */
private const val HUNT_LIMIT_BITS = 40

/**
 * 同期判定にはプリアンブル20bitのうち後ろ18bitを使う。先頭の "00" は直前の消灯と区別できず、
 * スライサがノイズ等で取りこぼす/誤るため要求しない(後続シンボルの8b/10b検査で誤同期は弾く)。
 */
private const val PREAMBLE_BITS = 18
private const val PREAMBLE_MASK = (1 shl PREAMBLE_BITS) - 1
private val PREAMBLE_VALUE = Code8b10b.PREAMBLE.takeLast(PREAMBLE_BITS).toInt(2)

/** ビット列を1つずつ受け取り、プリアンブル検出→LEN→PAYLOAD→CRC を 8b/10b 復号しながら処理する。 */
class BitStreamDecoder {
    var state = DecoderState.HUNT
        private set

    /** 点灯を検出してプリアンブルを探している間、または復号中は true。UI でビット表示するかの判断に使う。 */
    var active = false
        private set

    private var zeroRun = ARM_ZEROS
    private var huntBits = 0
    private var window = 0
    private var cur = 0
    private var curBits = 0
    private var rd = false
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
        val v = if (bit) 1 else 0
        if (state == DecoderState.HUNT) {
            // 点灯検出より前のビットも含めて常に窓へ入れる
            window = ((window shl 1) or v) and PREAMBLE_MASK
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
            if (window == PREAMBLE_VALUE) {
                active = true
                state = DecoderState.LENGTH
                cur = 0
                curBits = 0
                rd = false
                payload.reset()
                return DecodeEvent.PreambleOk
            }
            if (active && huntBits >= HUNT_LIMIT_BITS) {
                resetTo(rearmed = false)
                return DecodeEvent.PreambleFail("プリアンブル(K28.5×2)が見つからない")
            }
            return null
        }
        cur = (cur shl 1) or v
        if (++curBits < 10) return null
        val code = cur.toString(2).padStart(10, '0')
        cur = 0
        curBits = 0
        val sym = when (val d = Code8b10b.decode(code, rd)) {
            is Code8b10b.Decoded.Error -> {
                resetTo(rearmed = false)
                return DecodeEvent.SymbolError(d.reason)
            }
            is Code8b10b.Decoded.Data -> d
        }
        rd = sym.rdAfter
        val byte = sym.byte
        when (state) {
            DecoderState.LENGTH -> {
                if (byte > MAX_PAYLOAD_BYTES) {
                    resetTo(rearmed = false)
                    return DecodeEvent.SymbolError("長さ不正($byte)")
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
                reset()
                return if (crc == byte) DecodeEvent.Message(String(data, Charsets.UTF_8))
                else DecodeEvent.CrcError(len)
            }
            DecoderState.HUNT -> Unit
        }
        return null
    }
}
