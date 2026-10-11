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
 * 受信欄に表示するデバッグ画像。[pixels] は [size]x[size] のグレースケール ARGB(表示向きに回転・ミラー済み)で、
 * トラッキングが実際に見ているセル画像そのもの。[roiCol]/[roiRow] はトラッキング中のセル(全体平均なら -1)。
 */
class DebugFrame(
    val pixels: IntArray,
    val size: Int,
    val roiCol: Int,
    val roiRow: Int,
    /** 元の解析画像の解像度。 */
    val imageWidth: Int,
    val imageHeight: Int,
)

/**
 * センサー向きのセル位置 ([col], [row])(一辺 [n] セル)を、表示向きの位置へ変換する。
 * [rotationDegrees] は表示で時計回りに回す角度、[mirror] は前面カメラの左右反転。
 */
fun toDisplayCell(col: Int, row: Int, n: Int, rotationDegrees: Int, mirror: Boolean): Pair<Int, Int> {
    val (x, y) = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> (n - 1 - row) to col
        180 -> (n - 1 - col) to (n - 1 - row)
        270 -> row to (n - 1 - col)
        else -> col to row
    }
    return (if (mirror) n - 1 - x else x) to y
}

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
