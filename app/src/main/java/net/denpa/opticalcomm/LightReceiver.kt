package net.denpa.opticalcomm

import android.content.Context
import android.hardware.camera2.CaptureRequest
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
    private var onAeLock: (Boolean) -> Unit = {}
    /** ΔY の大きいセルを追従する(トラッキング対象)。 */
    private val tracker = DeltaTracker(GRID * GRID, minDelta = TRACK_MIN_DELTA, lockFrames = TRACK_LOCK_FRAMES)
    private var overallMean = 0f

    // U/V をセルへ縮小したもの(画像表示用)
    private val uSums = IntArray(GRID * GRID)
    private val vSums = IntArray(GRID * GRID)
    private val cellU = FloatArray(GRID * GRID)
    private val cellV = FloatArray(GRID * GRID)
    private var chromaBytes = ByteArray(0)
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
            // AE/AWB は既定で有効(ロックしない)。点滅を AE が打ち消すときは、受信欄のボタンで無効(ロック)にする
            onAeLock(false)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
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

    /** AE/AWB をロック(= AE 無効)/解除(= AE 有効)する(メインスレッドから)。 */
    fun setAeLocked(lock: Boolean) {
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
     * 解析画像を [GRID]x[GRID] のセルに縮小(各セルは平均輝度)し、1フレーム前との差(ΔY)が一定以上で最大のセルを一定フレームだけ固定して追う([DeltaTracker])。
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
        var total = 0L
        for (s in sums) total += s
        overallMean = total.toFloat() / (w * h)
        return tracker.update(cellMean, diffMean, overallMean)
    }

    /**
     * U/V プレーン(輝度の半分の解像度)も [GRID]x[GRID] のセルに縮小して cellU/cellV に入れる。
     * プレーンが無い/形式が想定外のときは無彩色(128)にする。
     */
    private fun downscaleChroma(image: ImageProxy) {
        cellU.fill(128f)
        cellV.fill(128f)
        if (image.planes.size < 3) return
        val cw = image.width / 2
        val ch = image.height / 2
        if (cw == 0 || ch == 0) return
        val colN = IntArray(GRID).also { for (x in 0 until cw) it[x * GRID / cw]++ }
        val rowN = IntArray(GRID).also { for (y in 0 until ch) it[y * GRID / ch]++ }
        fun plane(i: Int, sums: IntArray, out: FloatArray) {
            val pl = image.planes[i]
            val buf = pl.buffer
            val ps = pl.pixelStride
            val rs = pl.rowStride
            val need = (cw - 1) * ps + 1
            if (chromaBytes.size < need) chromaBytes = ByteArray(need)
            sums.fill(0)
            for (y in 0 until ch) {
                val pos = y * rs
                if (pos + need > buf.limit()) return
                buf.position(pos)
                buf.get(chromaBytes, 0, need)
                val base = (y * GRID / ch) * GRID
                for (x in 0 until cw) sums[base + x * GRID / cw] += chromaBytes[x * ps].toInt() and 0xFF
            }
            for (r in 0 until GRID) for (cc in 0 until GRID) {
                val n = colN[cc] * rowN[r]
                if (n > 0) out[r * GRID + cc] = sums[r * GRID + cc].toFloat() / n
            }
        }
        plane(1, uSums, cellU)
        plane(2, vSums, cellV)
    }

    /** 縮小画像を表示向きのグレースケールにし、トラッキング中のセル位置を添えて返す(表示は最小〜最大に引き伸ばす)。 */
    private fun debugFrame(image: ImageProxy, mirror: Boolean): DebugFrame {
        downscaleChroma(image)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in cellMean) { if (v < lo) lo = v; if (v > hi) hi = v }
        val scale = 255f / maxOf(hi - lo, 1f)
        val rot = image.imageInfo.rotationDegrees
        val pixels = IntArray(GRID * GRID)
        val diffPixels = IntArray(GRID * GRID)
        val yuvPixels = IntArray(GRID * GRID)
        var maxAbs = 0f
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val g = ((cellMean[r * GRID + c] - lo) * scale).toInt().coerceIn(0, 255)
                val (dc, dr) = toDisplayCell(c, r, GRID, rot, mirror)
                pixels[dr * GRID + dc] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                yuvPixels[dr * GRID + dc] = yuvToArgb(cellMean[r * GRID + c], cellU[r * GRID + c], cellV[r * GRID + c])
                val d = diffMean[r * GRID + c]
                val dg = (128f + d * DIFF_GAIN).toInt().coerceIn(0, 255)
                diffPixels[dr * GRID + dc] = (0xFF shl 24) or (dg shl 16) or (dg shl 8) or dg
                if (kotlin.math.abs(d) > maxAbs) maxAbs = kotlin.math.abs(d)
            }
        }
        val sel = tracker.selected
        if (sel >= GRID * GRID) {
            // まだ条件を満たすセルが無く、全体平均を使っている
            return DebugFrame(pixels, GRID, -1, -1, image.width, image.height, overallMean, Float.NaN, Float.NaN, diffPixels, yuvPixels, Float.NaN, maxAbs)
        }
        val (rc, rr) = toDisplayCell(sel % GRID, sel / GRID, GRID, rot, mirror)
        return DebugFrame(pixels, GRID, rc, rr, image.width, image.height, cellMean[sel], cellU[sel], cellV[sel], diffPixels, yuvPixels, diffMean[sel], maxAbs)
    }

    private companion object {
        /** 解析画像の縮小サイズ(セル数の一辺)。 */
        const val GRID = 32

        /** 追従セルの選択条件: 1フレーム前との Y 差の絶対値がこの値以上で最大のセル。 */
        const val TRACK_MIN_DELTA = 50f

        /** 選んだセルを動かさないフレーム数(その後、選び直す)。 */
        const val TRACK_LOCK_FRAMES = 8

        /** 差分表示の強調倍率(Y の差を [DIFF_GAIN] 倍して 128 を中心に描く)。 */
        const val DIFF_GAIN = 4f

        /** 露出補正(EV)。環境光を暗くしてトーチ点との差を広げる。 */
        const val EXPOSURE_COMP_EV = -2f
    }
}
