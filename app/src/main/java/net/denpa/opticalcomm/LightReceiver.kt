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

    fun start(
        owner: LifecycleOwner,
        previewView: PreviewView,
        front: Boolean,
        onSample: (Long, Float) -> Unit,
    ) {
        stopped = false
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
                onSample(image.imageInfo.timestamp, brightness(image))
                image.close()
            }

            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, preview, analysis)
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
     * 画面全体の平均輝度。トーチが映るか周囲を照らす分だけ平均が動く。
     * 上位1%などのピーク指標は、白飛びした照明が常時最大値になり変化を拾えない。
     */
    private fun brightness(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        var sum = 0L
        var n = 0
        for (y in 0 until h step 2) {
            val base = y * rowStride
            for (x in 0 until w step 2) {
                sum += buf.get(base + x).toInt() and 0xFF
                n++
            }
        }
        return sum.toFloat() / n
    }
}
