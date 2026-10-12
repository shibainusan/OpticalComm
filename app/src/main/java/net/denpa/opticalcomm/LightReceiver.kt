package net.denpa.opticalcomm

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** カメラ映像から明るい点(トーチ)の輝度を取り出し、(timestampNs, 0..255) を通知する。 */
class LightReceiver(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var executor: ExecutorService? = null
    private var camera: Camera? = null
    private var stopped = false
    private val handler = Handler(Looper.getMainLooper())
    private var autoLock: Runnable? = null
    private var onAeLock: (Boolean) -> Unit = {}
    /** ΔY の大きいセルを追従する(トラッキング対象)。 */
    private val tracker = DeltaTracker(GRID * GRID)
    private val cellMean = FloatArray(GRID * GRID)
    private val sums = IntArray(GRID * GRID)

    /** 1フレーム前のセル平均と、そこからの差分(今回 - 前回)。最初のフレームは差分 0。 */
    private val prevMean = FloatArray(GRID * GRID)
    private val diffMean = FloatArray(GRID * GRID)
    private var hasPrev = false

    // 解析画像のサイズが変わったときだけ作り直す下ごしらえ
    private var lastW = 0
    private var lastH = 0
    private var colIdx = IntArray(0)
    private var colCount = IntArray(GRID)
    private var rowCount = IntArray(GRID)
    private var rowBytes = ByteArray(0)

    /**
     * プレビューは出さず、解析画像だけをバインドする。解析画像は [GRID]x[GRID] のセルに縮小して受信輝度を取り、
     * 同じ縮小画像を [onFrame] でデバッグ表示用に渡す。
     */
    fun start(
        owner: LifecycleOwner,
        front: Boolean,
        onSample: (Long, Float) -> Unit,
        onRebase: (hi: Float, lo: Float) -> Unit = { _, _ -> },
        onFrame: (DebugFrame) -> Unit = {},
        /** AE/AWB のロック状態が変わったとき(メインスレッドから)。起動時は false で通知する。 */
        onAeLock: (Boolean) -> Unit = {},
    ) {
        stopped = false
        hasPrev = false
        tracker.reset()
        this.onAeLock = onAeLock
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            val p = future.get()
            provider = p
            val exec = Executors.newSingleThreadExecutor().also { executor = it }

            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(exec) { image ->
                val lum = brightness(image)
                if (tracker.switched) onRebase(tracker.selectedHi, tracker.selectedLo)
                onSample(image.imageInfo.timestamp, lum)
                onFrame(debugFrame(image, front))
                image.close()
            }

            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, analysis)
            setExposureCompensation(EXPOSURE_COMP_EV)
            // AE が点滅を打ち消さないよう、起動から AE_LOCK_DELAY_MS 後(ライトを向ける時間を見込む)に自動でロックする。
            // それまでに手動で操作した場合は、自動ロックはしない。
            onAeLock(false)
            cancelAutoLock()
            autoLock = Runnable {
                autoLock = null
                if (!stopped) setAeLocked(true)
            }.also { handler.postDelayed(it, AE_LOCK_DELAY_MS) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
        cancelAutoLock()
        provider?.unbindAll()
        provider = null
        camera = null
        executor?.shutdown()
        executor = null
    }

    /** 環境光を暗くして、トーチの明るい点を際立たせる(明るい部屋で全体が白っぽくなるのを避ける)。 */
    private fun setExposureCompensation(ev: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val step = state.exposureCompensationStep.toFloat()
        val range = state.exposureCompensationRange
        val index = Math.round(ev / step).coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    private fun cancelAutoLock() {
        autoLock?.let { handler.removeCallbacks(it) }
        autoLock = null
    }

    /** AE/AWB をロック/解除する(メインスレッドから)。自動ロックの予定があれば取り消す。 */
    fun setAeLocked(lock: Boolean) {
        cancelAutoLock()
        if (camera == null) return
        setAeLock(lock)
        onAeLock(lock)
    }

    private fun setAeLock(lock: Boolean) {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
                .build()
        )
    }

    /**
     * 解析画像を [GRID]x[GRID] のセルに縮小(各セルは平均輝度)し、1フレーム前との差(ΔY)が大きいセル([DeltaTracker])の輝度を返す。
     * 遠くのトーチは数ピクセルにしか映らないため、全体平均では点滅が埋もれる。
     */
    private fun brightness(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            colIdx = IntArray(w) { it * GRID / w }
            rowBytes = ByteArray(w)
            colCount = IntArray(GRID).also { for (x in 0 until w) it[colIdx[x]]++ }
            rowCount = IntArray(GRID).also { for (y in 0 until h) it[y * GRID / h]++ }
        }
        sums.fill(0)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(rowBytes, 0, w)
            val base = (y * GRID / h) * GRID
            for (x in 0 until w) sums[base + colIdx[x]] += rowBytes[x].toInt() and 0xFF
        }
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val i = r * GRID + c
                cellMean[i] = sums[i].toFloat() / (colCount[c] * rowCount[r])
            }
        }
        for (i in cellMean.indices) diffMean[i] = if (hasPrev) cellMean[i] - prevMean[i] else 0f
        cellMean.copyInto(prevMean)
        hasPrev = true
        return tracker.update(cellMean, diffMean)
    }

    /** セル [cell] に対応する範囲の U/V の平均(U/V プレーンは輝度の半分の解像度)。プレーンが無ければ無彩色(128)。 */
    private fun cellChroma(image: ImageProxy, cell: Int): Pair<Float, Float> {
        if (image.planes.size < 3) return 128f to 128f
        val cw = image.width / 2
        val ch = image.height / 2
        val c = cell % GRID
        val r = cell / GRID
        val x0 = c * cw / GRID
        val x1 = maxOf(x0 + 1, (c + 1) * cw / GRID)
        val y0 = r * ch / GRID
        val y1 = maxOf(y0 + 1, (r + 1) * ch / GRID)
        fun mean(i: Int): Float {
            val pl = image.planes[i]
            val buf = pl.buffer
            var sum = 0
            var n = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val pos = y * pl.rowStride + x * pl.pixelStride
                    if (pos < buf.limit()) { sum += buf.get(pos).toInt() and 0xFF; n++ }
                }
            }
            return if (n > 0) sum.toFloat() / n else 128f
        }
        return mean(1) to mean(2)
    }

    /** 縮小画像を表示向きのグレースケールにし、トラッキング中のセル位置を添えて返す(表示は最小〜最大に引き伸ばす)。 */
    private fun debugFrame(image: ImageProxy, mirror: Boolean): DebugFrame {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in cellMean) { if (v < lo) lo = v; if (v > hi) hi = v }
        val scale = 255f / maxOf(hi - lo, 1f)
        val rot = image.imageInfo.rotationDegrees
        val pixels = IntArray(GRID * GRID)
        val diffPixels = IntArray(GRID * GRID)
        var maxAbs = 0f
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val g = ((cellMean[r * GRID + c] - lo) * scale).toInt().coerceIn(0, 255)
                val (dc, dr) = toDisplayCell(c, r, GRID, rot, mirror)
                pixels[dr * GRID + dc] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                val d = diffMean[r * GRID + c]
                val dg = (128f + d * DIFF_GAIN).toInt().coerceIn(0, 255)
                diffPixels[dr * GRID + dc] = (0xFF shl 24) or (dg shl 16) or (dg shl 8) or dg
                if (kotlin.math.abs(d) > maxAbs) maxAbs = kotlin.math.abs(d)
            }
        }
        val sel = tracker.selected
        val (rc, rr) = toDisplayCell(sel % GRID, sel / GRID, GRID, rot, mirror)
        val (u, v) = cellChroma(image, sel)
        return DebugFrame(pixels, GRID, rc, rr, image.width, image.height, cellMean[sel], u, v, diffPixels, diffMean[sel], maxAbs)
    }

    private companion object {
        /** 解析画像の縮小サイズ(セル数の一辺)。 */
        const val GRID = 32

        /** 差分表示の強調倍率(Y の差を [DIFF_GAIN] 倍して 128 を中心に描く)。 */
        const val DIFF_GAIN = 4f

        /** 露出補正(EV)。環境光を暗くしてトーチ点との差を広げる。 */
        const val EXPOSURE_COMP_EV = -2f

        /** 起動からこの時間後に AE/AWB を自動ロックする。 */
        const val AE_LOCK_DELAY_MS = 3000L
    }
}
