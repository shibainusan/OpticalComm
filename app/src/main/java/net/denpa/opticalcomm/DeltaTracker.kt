package net.denpa.opticalcomm

import kotlin.math.abs

/**
 * 1フレーム前との輝度差(ΔY)の絶対値が大きいセルを追従する。
 * フレームごとの最大では、点滅の合間(差がほぼ 0)に別のセルへ飛ぶので、各セルの |ΔY| をピーク保持
 * (1フレームごとに [scoreDecay] 倍へ減衰)したスコアで選ぶ。さらに、現在の選択のスコアの [switchRatio] 倍を
 * 超えたときだけ乗り換える。
 *
 * 各セルの輝度の最大/最小(hi/lo、[valueDecay] で減衰)も追い、乗り換え時にスライサへ渡して基準を合わせる。
 */
class DeltaTracker(
    private val count: Int,
    private val valueDecay: Float = 0.01f,
    private val scoreDecay: Float = 0.97f,
    private val switchRatio: Float = 1.25f,
) {
    private val hi = FloatArray(count)
    private val lo = FloatArray(count)
    private val score = FloatArray(count)
    private var initialized = false

    var selected = 0
        private set

    /** 直近の [update] で選択が変わったら true。 */
    var switched = false
        private set

    val selectedHi get() = hi[selected]
    val selectedLo get() = lo[selected]

    fun reset() {
        initialized = false
        score.fill(0f)
        selected = 0
        switched = false
    }

    /** [values] は各セルの輝度、[diffs] は1フレーム前との差(今回 - 前回)。選択中セルの輝度を返す。 */
    fun update(values: FloatArray, diffs: FloatArray): Float {
        require(values.size == count && diffs.size == count)
        if (!initialized) {
            for (i in 0 until count) { hi[i] = values[i]; lo[i] = values[i] }
            initialized = true
        } else {
            for (i in 0 until count) {
                val v = values[i]
                hi[i] = if (v > hi[i]) v else hi[i] - (hi[i] - lo[i]) * valueDecay
                lo[i] = if (v < lo[i]) v else lo[i] + (hi[i] - lo[i]) * valueDecay
            }
        }
        var best = selected
        for (i in 0 until count) {
            score[i] = maxOf(abs(diffs[i]), score[i] * scoreDecay)
            if (score[i] > score[best]) best = i
        }
        switched = false
        if (best != selected && score[best] > score[selected] * switchRatio) {
            selected = best
            switched = true
        }
        return values[selected]
    }
}
