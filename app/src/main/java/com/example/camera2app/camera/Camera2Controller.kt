package com.example.camera2app.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import android.graphics.SurfaceTexture


class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameLevelChanged: (rollDeg: Float) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val previewContainer: FrameLayout,
    private val onFpsChanged: (Double) -> Unit
) {

    // ------------------------- Aspect -------------------------
    enum class AspectMode { FULL, RATIO_1_1, RATIO_3_4, RATIO_9_16 }
    private var aspectMode: AspectMode = AspectMode.FULL

    // ------------------------- Camera2 core --------------------
    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    private var cameraId: String = ""
    private var isFront: Boolean = false

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null

    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null

    private var characteristics: CameraCharacteristics? = null
    private var sensorActiveArray: Rect? = null

    private var previewSize: Size = Size(1280, 720)
    private lateinit var previewSurface: Surface

    private var jpegReader: android.media.ImageReader? = null

    private val cameraOpenCloseLock = Semaphore(1)

    // ------------------------- Threads -------------------------
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // ------------------------- Manual controls -----------------
    private var manualEnabled = false
    private var manualIso: Int? = null
    private var manualExposureNs: Long? = null
    private var manualWbKelvin: Int? = null

    // ------------------------- FPS -----------------------------
    private var lastFrameTsNs: Long = 0L
    private var fpsEMA: Double = 0.0 // Exponential moving average

    // ------------------------- Public APIs ---------------------
    fun cycleAspectMode(): AspectMode {
        aspectMode = when (aspectMode) {
            AspectMode.FULL      -> AspectMode.RATIO_1_1
            AspectMode.RATIO_1_1 -> AspectMode.RATIO_3_4
            AspectMode.RATIO_3_4 -> AspectMode.RATIO_9_16
            AspectMode.RATIO_9_16-> AspectMode.FULL
        }
        applyAspectMode()
        return aspectMode
    }

    fun setManualEnabled(enabled: Boolean) {
        manualEnabled = enabled
        apply3AandManual()
        // 미리보기 즉시 반영
        updateRepeating()
    }

    fun setIso(iso: Int) {
        manualIso = iso
        if (manualEnabled) {
            previewRequestBuilder?.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            updateRepeating()
        }
    }

    fun setExposureTimeNs(ns: Long) {
        manualExposureNs = ns
        if (manualEnabled) {
            previewRequestBuilder?.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            updateRepeating()
        }
    }

    fun setAwbTemperature(kelvin: Int) {
        manualWbKelvin = kelvin
        if (manualEnabled) {
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            previewRequestBuilder?.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            previewRequestBuilder?.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToGains(kelvin))
            updateRepeating()
        }
    }

    fun switchCamera() {
        isFront = !isFront
        closeCamera()
        openCamera()
    }

    fun onResume() {
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    fun onPause() {
        closeCamera()
        stopBackgroundThread()
    }

    fun takePicture() {
        val device = cameraDevice ?: return
        val session = cameraCaptureSession ?: return
        val active = sensorActiveArray ?: return

        val reader = jpegReader ?: return
        val stillBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)

            // 미리보기와 동일한 SCALER_CROP_REGION
            val crop = currentCrop(active)
            set(CaptureRequest.SCALER_CROP_REGION, crop)

            // 플래시/AF/AE/WB/Manual 반영
            copy3AFromPreview(this)

            // JPEG 방향
            val rotation = (context.resources.configuration.orientation)
            set(CaptureRequest.JPEG_ORIENTATION, OrientationUtil.getJpegOrientation(characteristics, rotation))
        }

        // 캡처 수행
        session.stopRepeating()
        session.capture(stillBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // 캡처 후 미리보기 재개
                updateRepeating()
            }
        }, backgroundHandler)
    }

    // ------------------------- Open / Session ------------------

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        chooseCameraId()
        characteristics = cameraManager.getCameraCharacteristics(cameraId)
        sensorActiveArray = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("No StreamConfigurationMap")

        previewSize = choosePreviewSize(map, textureView.width, textureView.height)

        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Time out waiting to lock camera opening.")
        }
        cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            jpegReader?.close()
            jpegReader = null
        } catch (_: InterruptedException) {
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun createCameraSession(device: CameraDevice) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(texture)

        // JPEG 리더 준비(최대 해상도 또는 미리보기 크기)
        val jpegSize = chooseJpegSize(
            characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        )
        jpegReader = android.media.ImageReader.newInstance(
            jpegSize.width, jpegSize.height, ImageFormat.JPEG, /*maxImages*/2
        ).apply {
            setOnImageAvailableListener(onJpegAvailableListener, backgroundHandler)
        }

        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            // 초기 3A
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange(characteristics))
        }

        device.createCaptureSession(
            listOf(previewSurface, jpegReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    // 현재 AspectMode 적용
                    applyAspectMode()
                    // 현재 3A/Manual 적용
                    apply3AandManual()
                    // 미리보기 시작
                    updateRepeating()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "onConfigureFailed")
                }
            },
            backgroundHandler
        )
    }

    private fun updateRepeating() {
        val session = cameraCaptureSession ?: return
        val builder = previewRequestBuilder ?: return
        previewRequest = builder.build()
        session.setRepeatingRequest(previewRequest!!, captureCallback, backgroundHandler)
        // TextureView 변환 행렬도 최신 상태로
        val active = sensorActiveArray ?: return
        updatePreviewViewportRect(currentCrop(active))
    }

    // ------------------------- Aspect 적용 ---------------------

    private fun applyAspectMode() {
        val builder = previewRequestBuilder ?: return
        val active = sensorActiveArray ?: return
        builder.set(CaptureRequest.SCALER_CROP_REGION, currentCrop(active))
        // 곧바로 반복요청 갱신
        updateRepeating()
    }

    private fun currentCrop(active: Rect): Rect {
        return when (aspectMode) {
            AspectMode.FULL      -> active
            AspectMode.RATIO_1_1 -> centerCropForAspect(active, 1f / 1f)
            AspectMode.RATIO_3_4 -> centerCropForAspect(active, 3f / 4f)
            AspectMode.RATIO_9_16-> centerCropForAspect(active, 9f / 16f)
        }
    }

    private fun centerCropForAspect(active: Rect, targetAspect: Float): Rect {
        val sw = active.width().toFloat()
        val sh = active.height().toFloat()
        val sAspect = sw / sh

        var outW = sw
        var outH = sh
        if (sAspect > targetAspect) {
            outW = sh * targetAspect
        } else if (sAspect < targetAspect) {
            outH = sw / targetAspect
        }
        val left = (active.left + (sw - outW) * 0.5f).toInt()
        val top  = (active.top  + (sh - outH) * 0.5f).toInt()
        return Rect(left, top, (left + outW).toInt(), (top + outH).toInt())
    }

    private fun updatePreviewViewportRect(effectiveCrop: Rect) {
        // 화면 컨테이너가 배치된 뒤에만 가능
        val vw = previewContainer.width
        val vh = previewContainer.height
        if (vw <= 0 || vh <= 0) return

        val cropW = effectiveCrop.width().toFloat()
        val cropH = effectiveCrop.height().toFloat()
        val targetAspect = cropW / cropH
        val viewAspect = vw.toFloat() / vh

        var visW = vw.toFloat()
        var visH = vh.toFloat()
        if (viewAspect > targetAspect) {
            visW = vh * targetAspect
        } else if (viewAspect < targetAspect) {
            visH = vw / targetAspect
        }

        val matrix = Matrix()
        val sx = visW / vw
        val sy = visH / vh
        matrix.setScale(sx, sy, vw / 2f, vh / 2f)
        textureView.setTransform(matrix)
    }

    // ------------------------- 3A / Manual ---------------------

    private fun apply3AandManual() {
        val builder = previewRequestBuilder ?: return
        if (!manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, null)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO) // 필요 시 터치 AF로 트리거
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)

            manualIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualExposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            manualWbKelvin?.let { builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToGains(it)) }
        }
    }

    private fun copy3AFromPreview(dst: CaptureRequest.Builder) {
        val src = previewRequestBuilder ?: return
        // 주요 키 복사(필요한 것만)
        fun <T> copy(key: CaptureRequest.Key<T>) {
            src.get(key)?.let { dst.set(key, it) }
        }
        copy(CaptureRequest.CONTROL_MODE)
        copy(CaptureRequest.CONTROL_AF_MODE)
        copy(CaptureRequest.CONTROL_AE_MODE)
        copy(CaptureRequest.CONTROL_AWB_MODE)
        copy(CaptureRequest.SENSOR_SENSITIVITY)
        copy(CaptureRequest.SENSOR_EXPOSURE_TIME)
        copy(CaptureRequest.COLOR_CORRECTION_MODE)
        copy(CaptureRequest.COLOR_CORRECTION_GAINS)
        copy(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
    }

    // Kelvin을 대략적인 RGB gains로 변환(간단 근사)
    private fun kelvinToGains(kelvin: Int): RggbChannelVector {
        val k = kelvin.coerceIn(2000, 8000).toDouble()
        val t = k / 100.0

        val r: Double = if (t <= 66.0) {
            255.0
        } else {
            329.698727446 * (t - 60.0).pow(-0.1332047592)
        }

        val g: Double = if (t <= 66.0) {
            99.4708025861 * ln(t) - 161.1195681661
        } else {
            288.1221695283 * (t - 60.0).pow(-0.0755148492)
        }

        val b: Double = when {
            t >= 66.0 -> 255.0
            t <= 19.0 -> 0.0
            else      -> 138.5177312231 * ln(t - 10.0) - 305.0447927307
        }

        // 0~255 → 대략 0~2.0 스케일로 변환(필요 시 조정)
        fun clamp01(x: Double) = min(255.0, max(0.0, x)) / 128.0

        val rr = clamp01(r).toFloat()
        val gg = clamp01(g).toFloat()
        val bb = clamp01(b).toFloat()

        return RggbChannelVector(rr, gg, gg, bb)
    }

    // ------------------------- Size / FPS utils ----------------

    private fun choosePreviewSize(map: StreamConfigurationMap, viewW: Int, viewH: Int): Size {
        val out = map.getOutputSizes(SurfaceTexture::class.java)
        if (out.isNullOrEmpty()) return Size(1280, 720)

        // 화면 비율(현재 컨테이너)과 가장 가까운 사이즈 선택
        val targetAspect = if (viewW > 0 && viewH > 0) viewW.toFloat() / viewH else 16f / 9f

        var best: Size = out[0]
        var bestScore = Double.MAX_VALUE
        for (s in out) {
            val a = s.width.toFloat() / s.height
            val aspectDiff = abs(a - targetAspect)
            val area = s.width * s.height
            val score = aspectDiff * 10 + (1e7 / max(1, area).toDouble()) // 비율 우선 + 너무 큰 사이즈 페널티
            if (score < bestScore) {
                best = s
                bestScore = score
            }
        }
        return best
    }

    private fun chooseJpegSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)
        if (sizes.isNullOrEmpty()) return Size(previewSize.width, previewSize.height)
        // 지나치게 큰 해상도는 저장 부담 → 12MP 근처 제한
        val limit = 4000 * 3000
        return sizes.sortedByDescending { it.width * it.height }
            .firstOrNull { it.width * it.height <= limit } ?: sizes.last()
    }

    private fun bestFpsRange(ch: CameraCharacteristics?): Range<Int> {
        val ranges = ch?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(30, 30)
        // 60fps 우선 선택, 없으면 최고 하한/상한이 높은 것 선택
        var best = ranges[0]
        var bestScore = -1
        for (r in ranges) {
            val score = (if (r.lower >= 60) 1000 else 0) + r.upper * 2 + r.lower
            if (score > bestScore) {
                best = r
                bestScore = score
            }
        }
        return best
    }

    // ------------------------- Threads -------------------------

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2BG").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: InterruptedException) {
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    // ------------------------- Listeners / Callbacks -----------

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            // 화면 회전/리사이즈 시 미리보기 행렬 갱신
            sensorActiveArray?.let { updatePreviewViewportRect(currentCrop(it)) }
        }
        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = device
            createCameraSession(device)
        }
        override fun onDisconnected(device: CameraDevice) {
            cameraOpenCloseLock.release()
            device.close()
            cameraDevice = null
        }
        override fun onError(device: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            device.close()
            cameraDevice = null
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // FPS 측정 (간단한 EMA)
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (lastFrameTsNs != 0L) {
                val dt = (ts - lastFrameTsNs) / 1e9
                if (dt > 0) {
                    val inst = 1.0 / dt
                    fpsEMA = if (fpsEMA == 0.0) inst else fpsEMA * 0.9 + inst * 0.1
                    onFpsChanged(fpsEMA)
                }
            }
            lastFrameTsNs = ts
        }
    }

    private val onJpegAvailableListener = android.media.ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        backgroundHandler?.post {
            try {
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                val resolver = context.contentResolver
                val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera2App")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                uri?.let {
                    resolver.openOutputStream(it)?.use { os ->
                        os.write(bytes)
                    }
                    cv.clear()
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, cv, null, null)
                    onSaved(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save JPEG failed: ${e.message}")
            } finally {
                image.close()
            }
        }
    }

    // ------------------------- Helpers -------------------------

    private fun chooseCameraId() {
        val ids = cameraManager.cameraIdList
        // 전/후면 스위치
        val backFirst = !isFront
        val desiredFacing = if (isFront) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

        // 1) 원하는 방향
        ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desiredFacing
        }?.let { cameraId = it; return }

        // 2) 없다면 반대쪽이라도
        ids.firstOrNull()?.let { cameraId = it } ?: run {
            throw IllegalStateException("No camera available")
        }
    }

    companion object {
        private const val TAG = "Camera2Controller"

        object OrientationUtil {
            fun getJpegOrientation(chars: CameraCharacteristics?, orientation: Int): Int {
                // 간단한 90도 배수 처리(필요하면 실제 디바이스 회전값 사용)
                val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                return (sensorOrientation + 0) % 360
            }
        }
    }
}
