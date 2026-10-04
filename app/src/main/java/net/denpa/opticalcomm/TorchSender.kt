package net.denpa.opticalcomm

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class TorchSender(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val torchId: String? = manager.cameraIdList.firstOrNull {
        val c = manager.getCameraCharacteristics(it)
        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }

    val available get() = torchId != null

    /** ビット毎に絶対時刻へ合わせてトーチを切り替える。キャンセルされても必ず消灯する。 */
    suspend fun send(bits: List<Boolean>, onProgress: (Int) -> Unit) {
        val id = torchId ?: error("no flash")
        try {
            setTorch(id, false)
            sleepUntil(SystemClock.elapsedRealtime() + LEAD_IDLE_MS)
            val start = SystemClock.elapsedRealtime()
            bits.forEachIndexed { i, bit ->
                sleepUntil(start + i * BIT_MS)
                setTorch(id, bit)
                onProgress(i + 1)
            }
            sleepUntil(start + bits.size * BIT_MS)
        } finally {
            withContext(NonCancellable) { setTorch(id, false) }
        }
    }

    private suspend fun sleepUntil(targetMs: Long) {
        val d = targetMs - SystemClock.elapsedRealtime()
        if (d > 0) delay(d)
    }

    private fun setTorch(id: String, on: Boolean) {
        try {
            manager.setTorchMode(id, on)
        } catch (_: Exception) {
        }
    }
}
