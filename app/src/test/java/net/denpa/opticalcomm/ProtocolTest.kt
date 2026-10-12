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
    fun noiseNeverSyncsOrReportsEvents() {
        val noise = List(10) { false } + List(60) { it % 3 != 0 }
        assertEquals(emptyList<DecodeEvent>(), decodeAllEvents(noise))
    }

    @Test
    fun lastByteExposesEachDecodedSymbol() {
        val d = BitStreamDecoder()
        val bytes = Frame.encode("Hi").mapNotNull { b -> d.push(b); d.lastByte.takeIf { it >= 0 } }
        val body = byteArrayOf(2, 'H'.code.toByte(), 'i'.code.toByte())
        assertEquals(listOf(2, 'H'.code, 'i'.code, Crc4.compute(body, 0)), bytes)
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

    // ---- ROI(トーチが映るブロックの選択) ----

    @Test
    fun roiTrackerPicksBlinkingBlockAndIgnoresStaticNoise() {
        val rnd = Random(3)
        val blocks = FloatArray(48)
        // 点滅なし: ノイズだけなら全体平均のまま切り替わらない
        val quiet = RoiTracker(48)
        repeat(200) {
            for (i in blocks.indices) blocks[i] = 200f + (rnd.nextFloat() - 0.5f) * 2f
            quiet.update(blocks, blocks.average().toFloat())
        }
        assertEquals(48, quiet.selected)

        // 明るい背景(200)で、ブロック10だけ +30 の点滅(全体平均の振れは約0.6しかない)
        val t = RoiTracker(48)
        var switchedOnce = false
        var last = 0f
        for (f in 0 until 120) {
            for (i in blocks.indices) blocks[i] = 200f + (rnd.nextFloat() - 0.5f) * 2f
            val on = (f / 2) % 2 == 0
            if (on) blocks[10] += 30f
            last = t.update(blocks, blocks.average().toFloat())
            if (t.switched) switchedOnce = true
        }
        assertTrue(switchedOnce)
        assertEquals(10, t.selected)
        assertTrue("hi=${t.selectedHi} lo=${t.selectedLo}", t.selectedHi - t.selectedLo > 25f)
        assertTrue(last > 150f)
    }

    @Test
    fun slicerRebaseKeepsDecoding() {
        // 途中で参照領域が変わり、輝度の基準が飛んでも復号できる
        val bits = Frame.encode("Hi")
        val bitNs = BIT_MS * 1_000_000L
        val events = ArrayList<DecodeEvent>()
        val dec = BitStreamDecoder()
        val slicer = SignalSlicer { b -> dec.push(b)?.let { if (it is DecodeEvent.Message) events.add(it) } }
        val leadNs = 1_000_000_000L
        var t = 0L
        var rebased = false
        while (t < leadNs + bits.size * bitNs + 1_500_000_000L) {
            val i = ((t - leadNs) / bitNs).toInt()
            val on = t >= leadNs && i in bits.indices && bits[i]
            // 前半は背景 200 / 点灯 230、後半(rebase 後)は背景 40 / 点灯 220
            val after = t > leadNs / 2 + 300_000_000L
            val lum = if (!after) (if (on) 230f else 200f) else (if (on) 220f else 40f)
            if (after && !rebased) { slicer.rebase(220f, 40f); rebased = true }
            slicer.push(t, lum)
            t += 33_333_333L
        }
        assertEquals(listOf<DecodeEvent>(DecodeEvent.Message("Hi")), events)
    }

    @Test
    fun displayCellRotatesAndMirrors() {
        // 4x4 の左上(0,0)を時計回りに回すと、90度で右上、180度で右下、270度で左下
        assertEquals(3 to 0, toDisplayCell(0, 0, 4, 90, false))
        assertEquals(3 to 3, toDisplayCell(0, 0, 4, 180, false))
        assertEquals(0 to 3, toDisplayCell(0, 0, 4, 270, false))
        assertEquals(0 to 0, toDisplayCell(0, 0, 4, 0, false))
        // 前面カメラは左右反転
        assertEquals(0 to 0, toDisplayCell(0, 0, 4, 90, true))
        assertEquals(3 to 0, toDisplayCell(0, 0, 4, 0, true))
    }

    @Test
    fun deltaTrackerLocksMaxDeltaAbove200ForEightFramesThenReselects() {
        val n = 6
        val values = FloatArray(n) { 100f }
        val diffs = FloatArray(n)
        val t = DeltaTracker(n)
        // 条件(|ΔY|>=200)を満たすセルが無い間は、全体平均のまま
        diffs[1] = 150f
        t.update(values, diffs, 100f)
        assertEquals(n, t.selected)
        // 200 以上で最大のセル(符号は問わない)を選ぶ
        diffs[1] = 210f
        diffs[3] = -230f
        t.update(values, diffs, 100f)
        assertEquals(3, t.selected)
        assertTrue(t.switched)
        // 次の8フレームは、より大きな差が現れても動かさない
        diffs[3] = 0f
        diffs[4] = 255f
        repeat(8) {
            t.update(values, diffs, 100f)
            assertEquals(3, t.selected)
        }
        // 9フレーム目で選び直す
        t.update(values, diffs, 100f)
        assertEquals(4, t.selected)
        // 条件を満たすセルが無くなっても、いまのセルのまま
        diffs.fill(0f)
        repeat(20) { t.update(values, diffs, 100f) }
        assertEquals(4, t.selected)
    }

    @Test
    fun frozenSlicerKeepsThreshold() {
        val slicer = SignalSlicer { }
        var t = 0L
        fun feed(lum: Float, frames: Int) { repeat(frames) { slicer.push(t, lum); t += 33_333_333L } }
        repeat(6) { feed(40f, 2); feed(200f, 2) }
        val before = slicer.threshold
        slicer.frozen = true
        // 凍結中に、明るさの基準が大きく変わっても、しきい値は動かない
        repeat(6) { feed(100f, 2); feed(250f, 2) }
        assertEquals(before, slicer.threshold, 0f)
        slicer.frozen = false
        feed(250f, 5)
        assertTrue(slicer.threshold != before)
    }

    @Test
    fun sequenceNumberRoundTripsAndIsCoveredByCrc() {
        for (sn in 0..15) {
            val ev = decodeAll(Frame.encode("a", sn))
            assertEquals(listOf<DecodeEvent>(DecodeEvent.Message("a", sn)), ev)
        }
        // SN のビット(最後のバイトの上位4bit)が化けると、CRC 不一致になり、化けた SN が見える
        val bits = Frame.encode("a", 5).toMutableList()
        val lastByteStart = bits.size - 8
        bits[lastByteStart] = !bits[lastByteStart]
        val ev = decodeAll(bits).single()
        assertEquals(DecodeEvent.CrcError(1, "a", 5 xor 8), ev)
    }
}
