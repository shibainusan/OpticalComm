package com.example.opticalcomm

/** 1ビットあたりの時間。端末のトーチ応答とカメラ30fpsに余裕を持たせた値。 */
const val BIT_MS = 200L

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
    /** フレーム: AA AA 7E LEN PAYLOAD CRC8(LEN+PAYLOAD)。MSB first。 */
    fun encode(text: String): List<Boolean> {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) { "too long" }
        val body = byteArrayOf(payload.size.toByte()) + payload
        val bytes = byteArrayOf(PREAMBLE_BYTE.toByte(), PREAMBLE_BYTE.toByte(), SYNC_BYTE.toByte()) +
            body + byteArrayOf(Crc8.compute(body).toByte())
        val bits = ArrayList<Boolean>(bytes.size * 8)
        for (b in bytes) for (i in 7 downTo 0) bits.add((b.toInt() shr i) and 1 == 1)
        return bits
    }

    fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size
}

sealed interface DecodeEvent {
    data class Message(val text: String) : DecodeEvent
    data class CrcError(val length: Int) : DecodeEvent
}

enum class DecoderState { HUNT, LENGTH, PAYLOAD, CRC }

/** ビット列を1つずつ受け取り、プリアンブル+SYNC検出→LEN→PAYLOAD→CRCの順に復号する。 */
class BitStreamDecoder {
    var state = DecoderState.HUNT
        private set

    private var window = 0
    private var cur = 0
    private var curBits = 0
    private var length = 0
    private val payload = java.io.ByteArrayOutputStream()

    fun reset() {
        state = DecoderState.HUNT
        window = 0
        cur = 0
        curBits = 0
        payload.reset()
    }

    fun push(bit: Boolean): DecodeEvent? {
        val v = if (bit) 1 else 0
        if (state == DecoderState.HUNT) {
            window = ((window shl 1) or v) and 0xFFFF
            if (window == (PREAMBLE_BYTE shl 8 or SYNC_BYTE)) {
                state = DecoderState.LENGTH
                cur = 0
                curBits = 0
                payload.reset()
            }
            return null
        }
        cur = (cur shl 1) or v
        if (++curBits < 8) return null
        val byte = cur
        cur = 0
        curBits = 0
        when (state) {
            DecoderState.LENGTH -> {
                if (byte > MAX_PAYLOAD_BYTES) {
                    reset()
                } else {
                    length = byte
                    state = if (length == 0) DecoderState.CRC else DecoderState.PAYLOAD
                }
            }
            DecoderState.PAYLOAD -> {
                payload.write(byte)
                if (payload.size() == length) state = DecoderState.CRC
            }
            DecoderState.CRC -> {
                val data = payload.toByteArray()
                val crc = Crc8.compute(byteArrayOf(length.toByte()) + data)
                reset()
                return if (crc == byte) DecodeEvent.Message(String(data, Charsets.UTF_8))
                else DecodeEvent.CrcError(length)
            }
            DecoderState.HUNT -> Unit
        }
        return null
    }
}
