package com.fz.yqlandroid.manager.srtdirect

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.fz.yqlandroid.manager.Camera2ParamApplier

/**
 * §134 Camera2 采集（拷自 test-android `CameraSource`，照 silu `Camera2MediaStreamer.createSession`）：
 * 录像模板，会话输出 = **编码器 Surface（必选）+ SurfaceView 预览（仅界面可见时）**，
 * 摄像头直写编码器，不经 GPU 重绘/CPU 拷贝；熄屏/切后台预览撤掉只剩编码器。
 *
 * 与 test-android 的差别：前后摄可选；PC 下发的硬件参数（AE 帧率区间=推送帧率、快门/ISO、对焦、变焦含 0.5x、
 * 白平衡、50Hz 防闪、关防抖）走 yql-android 既有算法 [Camera2ParamApplier.applyToBuilder]，直接写自己的 CaptureRequest。
 * 输出方向 = 传感器原生（横向，与 3840 版一致），不旋转。
 */
internal class SdCameraSource(
    private val context: Context,
    private val listener: Listener,
) : PreviewRegistry.Listener {

    interface Listener {
        /** 摄像头出错/被断开（已关闭设备），引擎负责延迟重开 */
        fun onCameraError(msg: String)
        /** 会话首帧到达（采集已恢复） */
        fun onCameraFirstFrame() {}
    }

    companion object {
        /** 指定朝向的第一个摄像头 id（后置一般是逻辑主摄，ZOOM_RATIO 可 <1）；没有就用第一个 */
        fun cameraId(ctx: Context, front: Boolean): String? {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            return cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
            } ?: cm.cameraIdList.firstOrNull()
        }

        /** 编码器可用的输出尺寸里与目标最接近的（优先同宽高比） */
        fun chooseSize(ctx: Context, cameraId: String, w: Int, h: Int): Size {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaCodec::class.java) ?: return Size(w, h)
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
            val ratio = w.toFloat() / h
            return sizes.sortedWith(compareBy<Size>(
                { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                { Math.abs(it.width * it.height - w * h) },
            )).first()
        }

        /**
         * 屏幕预览尺寸：同宽高比、不超过 1080p 的最大 SurfaceHolder 尺寸。
         * Camera2 规定 SurfaceView 预览最大 1080p，推 1440p/4K 时预览若用推流尺寸会话会配置失败。
         */
        fun choosePreviewSize(ctx: Context, cameraId: String, w: Int, h: Int): Size {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.view.SurfaceHolder::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1080 }
            if (sizes.isNullOrEmpty()) return Size(minOf(w, 1920), minOf(h, 1080))
            val ratio = w.toFloat() / h
            return sizes.sortedWith(compareBy<Size>(
                { Math.abs(it.width.toFloat() / it.height - ratio) > 0.01f },
                { -(it.width * it.height) },
            )).first()
        }
    }

    private val thread = HandlerThread("sd-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile var chars: CameraCharacteristics? = null
        private set
    @Volatile var cameraId: String = ""
        private set
    @Volatile var isFront = false
        private set
    private var encoderSurface: Surface? = null
    @Volatile private var closed = false
    @Volatile private var previewUnsupported = false
    private var targets: List<Surface> = emptyList()
    @Volatile private var params = Camera2ParamApplier.Params()
    private var firstFrameSeen = false

    /** 会话实际出帧计数（采集 fps 统计用） */
    @Volatile var frameCount = 0L
        private set
    @Volatile var previewDesc: String = "-"
        private set
    @Volatile var curExposureNs = 0L
        private set
    @Volatile var curIso = 0
        private set
    @Volatile var curFrameDurNs = 0L
        private set

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val n = frameCount++
            if (!firstFrameSeen) {
                firstFrameSeen = true
                try { listener.onCameraFirstFrame() } catch (_: Throwable) {}
            }
            curExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: curExposureNs
            curIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: curIso
            curFrameDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: curFrameDurNs
            if (n % 150 == 0L) {
                val durMs = curFrameDurNs / 1_000_000.0
                SdLog.i("cam exp=${"%.1f".format(curExposureNs / 1_000_000.0)}ms frameDur=${"%.1f".format(durMs)}ms" +
                        "(≈${if (durMs > 0) (1000.0 / durMs).toInt() else -1}fps) aeRange=${request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)}" +
                        " iso=$curIso aeMode=${result.get(CaptureResult.CONTROL_AE_MODE)} 预览=$previewDesc")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun open(front: Boolean, encoderSurface: Surface, initial: Camera2ParamApplier.Params) {
        val id = cameraId(context, front) ?: run {
            listener.onCameraError("没有可用的摄像头")
            return
        }
        this.encoderSurface = encoderSurface
        this.params = initial
        handler.post { openDevice(id, front) }
        PreviewRegistry.addListener(this)
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(id: String, front: Boolean) {
        if (closed) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = id
        isFront = front
        firstFrameSeen = false
        try {
            chars = cm.getCameraCharacteristics(id)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    SdLog.i("摄像头已打开 id=$id ${if (front) "前置" else "后置"}")
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    session = null
                    listener.onCameraError("摄像头被系统断开（后台被收回/被其它应用占用）")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    session = null
                    val name = when (error) {
                        ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                        ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
                        ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
                        ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                        ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                        else -> error.toString()
                    }
                    listener.onCameraError("摄像头错误 $name")
                }
            }, handler)
        } catch (t: Throwable) {
            listener.onCameraError("打开摄像头失败: ${t.message}")
        }
    }

    /** 预览出现/消失：只重建会话，不重开摄像头 */
    override fun onPreviewChanged(surface: Surface?) {
        handler.post {
            previewUnsupported = false
            if (device != null && !closed) createSession()
        }
    }

    /**
     * 换编码器（分辨率/帧率/GOP/QP 变化）：不重开摄像头，停旧会话 → [releaseOld] → 用新 Surface 建会话。
     * 旧编码器必须在旧会话停掉之后再停，否则摄像头还往已释放的 Surface 写。
     */
    fun switchEncoder(newSurface: Surface, newParams: Camera2ParamApplier.Params, releaseOld: () -> Unit) {
        handler.post {
            try { session?.stopRepeating() } catch (_: Throwable) {}
            try { session?.close() } catch (_: Throwable) {}
            session = null
            releaseOld()
            encoderSurface = newSurface
            params = newParams
            previewUnsupported = false
            if (device != null && !closed) createSession()
        }
    }

    /** 切前后摄 / 相机自愈：关设备重开（编码器 Surface 不变，SRT 不断） */
    fun reopen(front: Boolean) {
        handler.post {
            try { session?.close() } catch (_: Throwable) {}
            session = null
            try { device?.close() } catch (_: Throwable) {}
            device = null
            val id = cameraId(context, front)
            if (id == null) {
                listener.onCameraError("没有${if (front) "前置" else "后置"}摄像头")
                return@post
            }
            // 给 CameraService 一拍收尾，防 CAMERA_IN_USE（§相机自愈 同一坑）
            handler.postDelayed({ openDevice(id, front) }, 300)
        }
    }

    /** PC 硬件参数变化：只换 repeating request，不重建会话 */
    fun setControls(p: Camera2ParamApplier.Params) {
        handler.post {
            params = p
            submitRepeating()
        }
    }

    val isRunning: Boolean get() = device != null && session != null

    private fun submitRepeating() {
        val camera = device ?: return
        val s = session ?: return
        val c = chars ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            targets.forEach { req.addTarget(it) }
            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            Camera2ParamApplier.applyToBuilder(req, c, params)
            s.setRepeatingRequest(req.build(), captureCallback, handler)
        } catch (t: Throwable) {
            SdLog.w("下发采集参数失败: ${t.message}")
        }
    }

    private fun createSession() {
        val camera = device ?: return
        val enc = encoderSurface ?: return
        try { session?.close() } catch (_: Throwable) {}
        session = null
        val preview = if (previewUnsupported) null else PreviewRegistry.current()
        val t = if (preview != null) listOf(enc, preview) else listOf(enc)
        targets = t
        previewDesc = when {
            preview != null -> "屏幕预览"
            previewUnsupported -> "无(带预览会话配置失败,只写编码器)"
            else -> "无(界面不可见,只写编码器)"
        }
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(t, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed || t !== targets) { s.close(); return }
                    session = s
                    submitRepeating()
                    SdLog.i("摄像头会话就绪 输出=${if (preview != null) "编码器+预览" else "仅编码器"}")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (preview != null) {
                        SdLog.w("带预览的会话配置失败 → 改为只写编码器（预览尺寸可能不匹配）")
                        previewUnsupported = true
                        handler.post { createSession() }
                    } else {
                        listener.onCameraError("摄像头会话配置失败（该分辨率可能不被支持）")
                    }
                }
            }, handler)
        } catch (t2: Throwable) {
            listener.onCameraError("创建摄像头会话失败: ${t2.message}")
        }
    }

    fun close() {
        closed = true
        PreviewRegistry.removeListener(this)
        handler.post {
            try { session?.close() } catch (_: Throwable) {}
            session = null
            try { device?.close() } catch (_: Throwable) {}
            device = null
            thread.quitSafely()
        }
    }
}
