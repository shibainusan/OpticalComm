package net.denpa.opticalcomm

import kotlin.math.abs

/**
 * 1フレーム前との輝度差(ΔY)が [minDelta] 以上で、かつ最大のセルを選び、[lockFrames] フレームのあいだ動かさない。
 * その後、また同じ条件でセルを選び直す(条件を満たすセルが無ければ、いまのセルのまま)。
 * 起動直後は全体平均(最後の候補)を使う。
 *
 * 各セル(と全体平均)の輝度の最大/最小(hi/lo、[valueDecay] で減衰)も追い、乗り換え時にスライサへ渡して基準を合わせる。
 */
class DeltaTracker(
    private val count: Int,
    private val minDelta: Float = 200f,
    private val lockFrames: Int = 8,
    private val valueDecay: Float = 0.01f,
) {
    private val n = count + 1
    private val hi = FloatArray(n)
    private val lo = FloatArray(n)
    private var initialized = false
    private var lockLeft = 0

    /** 現在選択中の候補。[count] なら全体平均。 */
    var selected = count
        private set

    /** 直近の [update] で選択が変わったら true。 */
    var switched = false
        private set

    val selectedHi get() = hi[selected]
    val selectedLo get() = lo[selected]

    fun reset() {
        initialized = false
        lockLeft = 0
        selected = count
        switched = false
    }

    /** [values] は各セルの輝度、[diffs] は1フレーム前との差(今回 - 前回)、[mean] は全体平均。選択中の輝度を返す。 */
    fun update(values: FloatArray, diffs: FloatArray, mean: Float): Float {
        require(values.size == count && diffs.size == count)
        fun value(i: Int) = if (i < count) values[i] else mean
        if (!initialized) {
            for (i in 0 until n) { hi[i] = value(i); lo[i] = hi[i] }
            initialized = true
        } else {
            for (i in 0 until n) {
                val v = value(i)
                hi[i] = if (v > hi[i]) v else hi[i] - (hi[i] - lo[i]) * valueDecay
                lo[i] = if (v < lo[i]) v else lo[i] + (hi[i] - lo[i]) * valueDecay
            }
        }
        switched = false
        if (lockLeft > 0) {
            lockLeft--
        } else {
            var best = -1
            var bestDelta = minDelta
            for (i in 0 until count) {
                val d = abs(diffs[i])
                if (d >= bestDelta) { best = i; bestDelta = d }
            }
            if (best >= 0) {
                if (best != selected) { selected = best; switched = true }
                lockLeft = lockFrames
            }
        }
        return value(selected)
    }
}
