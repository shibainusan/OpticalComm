package net.denpa.opticalcomm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProtocolTest {
    private fun decodeAllEvents(bits: List<Boolean>): List<DecodeEvent> {
        val d = BitStreamDecoder()
        return bits.mapNotNull { d.push(it) }
    }

    /** メッセージ/CRCエラーのみ(プリアンブル通知は除く)。 */
    private fun decodeAll(bits: List<Boolean>) =
        decodeAllEvents(bits).filter { it is DecodeEvent.Message || it is DecodeEvent.CrcError }

    @Test
    fun reportsPreambleOkThenCrcOk() {
        val ev = decodeAllEvents(Frame.encode("Hi"))
        assertEquals(listOf(DecodeEvent.PreambleOk, DecodeEvent.Message("Hi")), ev)
    }

    @Test
    fun reportsPreambleFailForNoise() {
        val noise = List(10) { false } + List(60) { it % 3 != 0 }
        val ev = decodeAllEvents(noise)
        assertTrue(ev.any { it is DecodeEvent.PreambleFail })
        assertTrue(ev.none { it is DecodeEvent.PreambleOk })
    }

    @Test
    fun lastByteExposesEachDecodedSymbol() {
        val d = BitStreamDecoder()
        val bytes = Frame.encode("Hi").mapNotNull { b -> d.push(b); d.lastByte.takeIf { it >= 0 } }
        val body = byteArrayOf(2, 'H'.code.toByte(), 'i'.code.toByte())
        assertEquals(listOf(2, 'H'.code, 'i'.code, Crc8.compute(body)), bytes)
    }

    @Test
    fun keepsReceivingAfterCorruptedPayload() {
        val bits = Frame.encode("hello").toMutableList()
        // 本文1文字目('h')の最下位ビット(bit 24+8+7)を反転
        bits[39] = !bits[39]
        val ev = decodeAllEvents(bits).last()
        assertEquals(DecodeEvent.CrcError(5, "iello"), ev)
    }

    @Test
    fun reportsCrcNg() {
        val ev = decodeAllEvents(Frame.encode("hello", crcCorruption = 1))
        assertEquals(DecodeEvent.PreambleOk, ev.first())
        assertTrue(ev.last() is DecodeEvent.CrcError)
    }

    @Test
    fun reportsPreambleFailOnInvalidLength() {
        val bits = Frame.encode("hello").toMutableList()
        // LEN(bit 24..31)の最上位ビットを立てて 64 超にする
        bits[24] = true
        val ev = decodeAllEvents(bits)
        assertTrue(ev.any { it is DecodeEvent.PreambleFail })
        assertTrue(ev.none { it is DecodeEvent.Message })
    }

    @Test
    fun roundTripAsciiAndJapanese() {
        for (text in listOf("Hi", "こんにちは世界", "")) {
            val ev = decodeAll(Frame.encode(text))
            assertEquals(listOf<DecodeEvent>(DecodeEvent.Message(text)), ev)
        }
    }

    @Test
    fun syncsAfterLeadingGarbage() {
        val bits = List(13) { it % 3 == 0 } + Frame.encode("abc")
        assertEquals(listOf<DecodeEvent>(DecodeEvent.Message("abc")), decodeAll(bits))
    }

    @Test
    fun detectsCorruption() {
        val bits = Frame.encode("hello").toMutableList()
        bits[40] = !bits[40]
        val ev = decodeAll(bits)
        assertTrue(ev.none { it is DecodeEvent.Message })
    }

    /** 30fps のカメラ輝度波形(ノイズ・フレーム間隔の揺らぎ・送信クロック誤差付き)を合成して復号する。 */
    private fun synth(text: String, bitScale: Double, seed: Int): List<DecodeEvent> {
        val rnd = Random(seed)
        val bits = Frame.encode(text)
        val bitNs = BIT_MS * 1_000_000L
        val events = ArrayList<DecodeEvent>()
        val dec = BitStreamDecoder()
        val slicer = SignalSlicer { b ->
            dec.push(b)?.let { if (it is DecodeEvent.Message || it is DecodeEvent.CrcError) events.add(it) }
        }
        val leadNs = 1_000_000_000L
        val totalNs = leadNs + (bits.size * bitNs * bitScale).toLong() + 1_500_000_000L
        val bitNsScaled = bitNs * bitScale
        fun on(x: Long): Boolean {
            val i = ((x - leadNs) / bitNsScaled).toLong()
            return x >= leadNs && i >= 0 && i < bits.size && bits[i.toInt()]
        }
        // 実カメラ同様、各フレームの輝度は露光時間(20ms)中のトーチ点灯割合に比例する
        val exposureNs = 20_000_000L
        val sub = 20
        var t = 0L
        while (t < totalNs) {
            val frac = (0 until sub).count { on(t + exposureNs * it / sub) }.toFloat() / sub
            val lum = 40f + 180f * frac + (rnd.nextFloat() - 0.5f) * 20f
            slicer.push(t, lum)
            t += 33_333_333L + rnd.nextLong(-4_000_000L, 4_000_000L)
        }
        return events
    }

    @Test
    fun slicerDecodesSyntheticWaveform() {
        for (seed in 1..20) {
            for (scale in listOf(1.0, 0.97, 1.03)) {
                assertEquals(
                    "seed=$seed scale=$scale",
                    listOf<DecodeEvent>(DecodeEvent.Message("Hello 光")),
                    synth("Hello 光", scale, seed),
                )
            }
        }
    }
}
