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
    private val roi = RoiTracker(GRID * GRID, decay = ROI_DECAY, minSwitchContrast = ROI_MIN_CONTRAST)
    private val cellMean = FloatArray(GRID * GRID)
    private val sums = IntArray(GRID * GRID)

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
    ) {
        stopped = false
        roi.reset()
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
                if (roi.switched) onRebase(roi.selectedHi, roi.selectedLo)
                onSample(image.imageInfo.timestamp, lum)
                onFrame(debugFrame(image, front))
                image.close()
            }

            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, analysis)
            setExposureCompensation(EXPOSURE_COMP_EV)
            // AE が点滅を打ち消さないよう、露出が落ち着いた後にロックする
            Handler(Looper.getMainLooper()).postDelayed({ if (!stopped) setAeLock(true) }, 1500)
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
     * 解析画像を [GRID]x[GRID] のセルに縮小(各セルは平均輝度)し、点滅の振れ幅が最大のセルの輝度([RoiTracker])を返す。
     * 遠くのトーチは数ピクセルにしか映らないため、全体平均では点滅が埋もれる。
     * 上位1%などのピーク指標は、白飛びした照明が常時最大値になり変化を拾えない。
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
        var total = 0L
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val i = r * GRID + c
                total += sums[i]
                cellMean[i] = sums[i].toFloat() / (colCount[c] * rowCount[r])
            }
        }
        return roi.update(cellMean, total.toFloat() / (w * h))
    }

    /** 縮小画像を表示向きのグレースケールにし、トラッキング中のセル位置を添えて返す(表示は最小〜最大に引き伸ばす)。 */
    private fun debugFrame(image: ImageProxy, mirror: Boolean): DebugFrame {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in cellMean) { if (v < lo) lo = v; if (v > hi) hi = v }
        val scale = 255f / maxOf(hi - lo, 1f)
        val rot = image.imageInfo.rotationDegrees
        val pixels = IntArray(GRID * GRID)
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val g = ((cellMean[r * GRID + c] - lo) * scale).toInt().coerceIn(0, 255)
                val (dc, dr) = toDisplayCell(c, r, GRID, rot, mirror)
                pixels[dr * GRID + dc] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        val sel = roi.selected
        val (rc, rr) = if (sel >= GRID * GRID) (-1 to -1) else toDisplayCell(sel % GRID, sel / GRID, GRID, rot, mirror)
        return DebugFrame(pixels, GRID, rc, rr, image.width, image.height)
    }

    private companion object {
        /** 解析画像の縮小サイズ(セル数の一辺)。 */
        const val GRID = 32

        /** セルの振れ幅の減衰(1フレームあたり)。AE の整定などで付いた古い振れ幅が長く残らないようにする。 */
        const val ROI_DECAY = 0.01f

        /** この振れ幅未満のセルへは乗り換えない(ノイズ対策)。 */
        const val ROI_MIN_CONTRAST = 8f

        /** 露出補正(EV)。環境光を暗くしてトーチ点との差を広げる。 */
        const val EXPOSURE_COMP_EV = -2f
    }
}
