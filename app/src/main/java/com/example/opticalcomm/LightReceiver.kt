package com.example.opticalcomm

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
    private val hist = IntArray(256)

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

    /** 中央ROIの輝度上位1%の平均。小さなLEDでも平均に埋もれにくい。 */
    private fun brightness(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        hist.fill(0)
        var n = 0
        val x0 = w / 4
        val x1 = w * 3 / 4
        val y0 = h / 4
        val y1 = h * 3 / 4
        for (y in y0 until y1 step 2) {
            val base = y * rowStride
            for (x in x0 until x1 step 2) {
                hist[buf.get(base + x).toInt() and 0xFF]++
                n++
            }
        }
        val take = maxOf(1, n / 100)
        var left = take
        var sum = 0L
        var v = 255
        while (v >= 0 && left > 0) {
            val c = minOf(hist[v], left)
            sum += c.toLong() * v
            left -= c
            v--
        }
        return sum.toFloat() / take
    }
}
