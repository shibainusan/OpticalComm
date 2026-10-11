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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
    private var roi = RoiTracker(0)
    private var cols = 0
    private var rows = 0
    private var cellMean = FloatArray(0)

    fun start(
        owner: LifecycleOwner,
        previewView: PreviewView,
        front: Boolean,
        onSample: (Long, Float) -> Unit,
        onRebase: (hi: Float, lo: Float) -> Unit = { _, _ -> },
        onRoi: (RoiRect) -> Unit = {},
    ) {
        stopped = false
        cols = 0 // 次のフレームでトラッカーを作り直す
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            val p = future.get()
            provider = p
            val exec = Executors.newSingleThreadExecutor().also { executor = it }

            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

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
                onRoi(roiRect(image))
                image.close()
            }

            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, preview, analysis)
            setExposureCompensation(EXPOSURE_COMP_EV)
            // AE が点滅を打ち消さないよう、露出が落ち着いた後にロックする
            previewView.postDelayed({ if (!stopped) setAeLock(true) }, 1500)
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
     * 画面を CELL x CELL ピクセルのセルに分け、点滅の振れ幅が最大のセルの平均輝度([RoiTracker])を返す。
     * 遠くのトーチは数ピクセルにしか映らないため、大きなブロックの平均では点滅が埋もれる。
     * 上位1%などのピーク指標は、白飛びした照明が常時最大値になり変化を拾えない。
     */
    private fun brightness(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        if (cols != w / CELL || rows != h / CELL) {
            cols = w / CELL
            rows = h / CELL
            roi = RoiTracker(cols * rows, decay = ROI_DECAY, minSwitchContrast = ROI_MIN_CONTRAST)
            cellMean = FloatArray(cols * rows)
        }
        var total = 0L
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0
                for (y in r * CELL until (r + 1) * CELL) {
                    val base = y * rowStride + c * CELL
                    for (x in 0 until CELL) sum += buf.get(base + x).toInt() and 0xFF
                }
                cellMean[r * cols + c] = sum.toFloat() / (CELL * CELL)
                total += sum
            }
        }
        return roi.update(cellMean, total.toFloat() / (cols * rows * CELL * CELL))
    }

    /** 現在トラッキング中の領域(全体平均なら画面全体)を、解析バッファ上のピクセル座標で返す。 */
    private fun roiRect(image: ImageProxy): RoiRect {
        val m = FloatArray(9)
        image.imageInfo.sensorToBufferTransformMatrix.getValues(m)
        val sel = roi.selected
        if (cols == 0 || sel >= cols * rows) return RoiRect(0f, 0f, image.width.toFloat(), image.height.toFloat(), m)
        val x = (sel % cols) * CELL.toFloat()
        val y = (sel / cols) * CELL.toFloat()
        return RoiRect(x, y, x + CELL, y + CELL, m)
    }

    private companion object {
        /** トラッキングのセル一辺(ピクセル)。 */
        const val CELL = 4

        /** セルの振れ幅の減衰(1フレームあたり)。AE の整定などで付いた古い振れ幅が長く残らないようにする。 */
        const val ROI_DECAY = 0.01f

        /** この振れ幅未満のセルへは乗り換えない(ノイズ対策)。 */
        const val ROI_MIN_CONTRAST = 8f

        /** 露出補正(EV)。環境光を暗くしてトーチ点との差を広げる。 */
        const val EXPOSURE_COMP_EV = -2f
    }
}
