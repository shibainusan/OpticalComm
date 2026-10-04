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
        val bits = Frame.encode("hello").toMutableList()
        bits[bits.size - 1] = !bits[bits.size - 1]
        val ev = decodeAllEvents(bits)
        assertEquals(DecodeEvent.PreambleOk, ev.first())
        assertTrue(ev.last() is DecodeEvent.CrcError)
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
}
