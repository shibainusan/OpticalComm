package com.example.opticalcomm

/**
 * 輝度サンプル列(タイムスタンプ付き)を2値化し、エッジに再同期しながら
 * 各ビット中央でサンプリングしてビット列を出力する。
 */
class SignalSlicer(
    private val bitNanos: Long = BIT_MS * 1_000_000L,
    private val minContrast: Float = 6f,
    private val onBit: (Boolean) -> Unit,
) {
    var hi = 0f
        private set
    var lo = 0f
        private set
    val threshold get() = (hi + lo) / 2f
    var level = false
        private set

    private var initialized = false
    private var prevT = 0L
    private var prevLum = 0f
    private var synced = false
    private var nextCenter = 0L

    fun reset() {
        initialized = false
        synced = false
        level = false
    }

    fun push(t: Long, lum: Float) {
        if (!initialized) {
            hi = lum; lo = lum; prevT = t; prevLum = lum; initialized = true
            return
        }
        // ピーク追従(ゆっくり減衰)
        val k = 0.002f
        hi = if (lum > hi) lum else hi - (hi - lo) * k
        lo = if (lum < lo) lum else lo + (hi - lo) * k

        val range = hi - lo
        val newLevel = if (range < minContrast) false else {
            val margin = range * 0.15f
            when {
                lum > threshold + margin -> true
                lum < threshold - margin -> false
                else -> level
            }
        }

        if (newLevel != level) {
            // 露光中のトーチ点灯割合が輝度に現れるので、しきい値を横切る位置を線形補間して
            // フレーム間隔より細かくエッジ時刻を推定する(単純な中点だとフレーム間隔の半分の誤差が出る)
            val frac = if (lum != prevLum) ((threshold - prevLum) / (lum - prevLum)).coerceIn(0f, 1f) else 0.5f
            val edge = prevT + ((t - prevT) * frac).toLong()
            if (synced) emitUntil(edge) else if (newLevel) synced = true
            level = newLevel
            if (synced) nextCenter = edge + bitNanos / 2
        }
        if (synced) emitUntil(t)
        prevT = t
        prevLum = lum
    }

    private fun emitUntil(limit: Long) {
        while (nextCenter <= limit) {
            onBit(level)
            nextCenter += bitNanos
        }
    }
}
