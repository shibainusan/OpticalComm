package net.denpa.opticalcomm

/**
 * 画面を複数ブロックに分けたときの各ブロック平均輝度(と全体平均)から、点滅の振れ幅が最も大きい
 * 領域を選んで、その輝度を返す。明るい部屋では全体平均だとトーチの寄与が埋もれるため、
 * トーチが映っているブロックだけを見て S/N を稼ぐ。
 *
 * 候補は blockCount 個のブロックと全体平均(最後の候補)。最初は全体平均を使い、
 * 他の候補の振れ幅が現在の [switchRatio] 倍を超えたときだけ乗り換える(ノイズでの頻繁な切替を避ける)。
 */
/**
 * プレビュー上に重ねる領域。[left]..[bottom] は解析バッファ上のピクセル座標、[sensorToBuffer] はそのフレームの
 * `ImageInfo.sensorToBufferTransformMatrix`(Matrix.getValues の 9 要素)。
 * 表示側で PreviewView.getSensorToViewTransform と組み合わせて画面座標へ変換する。
 */
class RoiRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val sensorToBuffer: FloatArray)

class RoiTracker(
    private val blockCount: Int,
    private val decay: Float = 0.002f,
    private val switchRatio: Float = 1.5f,
    private val minSwitchContrast: Float = 4f,
) {
    private val n = blockCount + 1
    private val hi = FloatArray(n)
    private val lo = FloatArray(n)
    private var initialized = false

    /** 現在選択中の候補。blockCount なら全体平均。 */
    var selected = blockCount
        private set

    /** 直近の [update] で選択が変わったら true。 */
    var switched = false
        private set

    val selectedHi get() = hi[selected]
    val selectedLo get() = lo[selected]

    fun reset() {
        initialized = false
        selected = blockCount
        switched = false
    }

    /** [blocks] は blockCount 個、[mean] は全体平均。選択中領域の輝度を返す。 */
    fun update(blocks: FloatArray, mean: Float): Float {
        require(blocks.size == blockCount)
        fun value(i: Int) = if (i < blockCount) blocks[i] else mean
        if (!initialized) {
            for (i in 0 until n) { hi[i] = value(i); lo[i] = hi[i] }
            initialized = true
        } else {
            for (i in 0 until n) {
                val v = value(i)
                hi[i] = if (v > hi[i]) v else hi[i] - (hi[i] - lo[i]) * decay
                lo[i] = if (v < lo[i]) v else lo[i] + (hi[i] - lo[i]) * decay
            }
        }
        switched = false
        var best = selected
        var bestContrast = hi[best] - lo[best]
        for (i in 0 until n) {
            val c = hi[i] - lo[i]
            if (c > bestContrast) { best = i; bestContrast = c }
        }
        val cur = hi[selected] - lo[selected]
        if (best != selected && bestContrast >= minSwitchContrast && bestContrast > cur * switchRatio) {
            selected = best
            switched = true
        }
        return value(selected)
    }
}
