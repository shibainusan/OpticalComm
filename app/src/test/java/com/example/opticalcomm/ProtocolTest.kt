package com.example.opticalcomm

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
    fun reportsCrcNg() {
        val ev = decodeAllEvents(Frame.encode("hello", crcCorruption = 1))
        assertEquals(DecodeEvent.PreambleOk, ev.first())
        assertTrue(ev.last() is DecodeEvent.CrcError)
    }

    @Test
    fun reportsSymbolErrorOnInvalidCode() {
        val bits = Frame.encode("hello").toMutableList()
        // LEN シンボル(bit 20..29)を全て 1 にして無効符号にする
        for (i in 20..29) bits[i] = true
        val ev = decodeAllEvents(bits)
        assertTrue(ev.any { it is DecodeEvent.SymbolError })
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
        bits[30] = !bits[30]
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
        var t = 0L
        while (t < totalNs) {
            val i = ((t - leadNs) / (bitNs * bitScale)).toLong()
            val on = t >= leadNs && i < bits.size && bits[i.toInt()]
            val lum = (if (on) 220f else 40f) + (rnd.nextFloat() - 0.5f) * 20f
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

    // ---- 8b/10b ----

    @Test
    fun knownCodes() {
        assertEquals("1001110100", Code8b10b.encodeData(0x00, false).bits)   // D0.0 RD-
        assertEquals("0110001011", Code8b10b.encodeData(0x00, true).bits)    // D0.0 RD+
        assertEquals("1010101010", Code8b10b.encodeData(0xB5, false).bits)   // D21.5
        assertEquals("0011111010", Code8b10b.encodeComma(false).bits)        // K28.5 RD-
        assertEquals("1100000101", Code8b10b.encodeComma(true).bits)         // K28.5 RD+
        assertEquals("00111110101100000101", Code8b10b.PREAMBLE)
    }

    @Test
    fun allCodesAreBalancedAndRunLimited() {
        for (rd0 in listOf(false, true)) {
            for (b in 0..255) {
                val sym = Code8b10b.encodeData(b, rd0)
                assertEquals(10, sym.bits.length)
                val disparity = sym.bits.count { it == '1' } * 2 - 10
                assertTrue("byte=$b disparity=$disparity", disparity == 0 || disparity == 2 || disparity == -2)
                // RD- からは + 側 or 0、RD+ からは - 側 or 0 の符号のみ
                if (disparity != 0) assertEquals(disparity > 0, sym.rdAfter)
                if (disparity == 0) assertEquals(rd0, sym.rdAfter)
                if (disparity > 0) assertTrue(!rd0)
                if (disparity < 0) assertTrue(rd0)
            }
        }
    }

    @Test
    fun runLengthAtMostFiveAndDcBalanced() {
        val rnd = Random(7)
        repeat(200) {
            val text = String(CharArray(rnd.nextInt(0, 20)) { ('a'.code + rnd.nextInt(26)).toChar() })
            val bits = Frame.encode(text)
            var run = 1
            var maxRun = 1
            for (i in 1 until bits.size) {
                run = if (bits[i] == bits[i - 1]) run + 1 else 1
                maxRun = maxOf(maxRun, run)
            }
            assertTrue("maxRun=$maxRun text=$text", maxRun <= 5)
            // 全シンボル後の累積ディスパリティは 0 または ±2
            val total = bits.count { it } * 2 - bits.size
            assertTrue("total=$total", total in -2..2)
        }
    }

    @Test
    fun everyByteRoundTripsThroughDecoder() {
        var rd = false
        for (round in 0..1) {
            for (b in 0..255) {
                val sym = Code8b10b.encodeData(b, rd)
                val d = Code8b10b.decode(sym.bits, rd)
                assertEquals(Code8b10b.Decoded.Data(b, sym.rdAfter), d)
                rd = sym.rdAfter
            }
        }
    }

    @Test
    fun wrongRunningDisparityIsRejected() {
        // D0.0 RD- 用の符号を RD+ で受けると違反
        val code = Code8b10b.encodeData(0, false).bits
        assertTrue(Code8b10b.decode(code, true) is Code8b10b.Decoded.Error)
    }
}
