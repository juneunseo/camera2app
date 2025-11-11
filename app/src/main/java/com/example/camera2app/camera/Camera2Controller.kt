package com.example.camera2app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.example.camera2app.camera.OrientationUtil.getJpegOrientation
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameLevelChanged: (rollDeg: Float) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val previewContainer: ViewGroup,
    private val onFpsChanged: (Double) -> Unit = {}   // FPS 콜백
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSize: Size = Size(1280, 720)
    private var imageReader: android.media.ImageReader? = null

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var cameraId: String
    private lateinit var chars: CameraCharacteristics
    private lateinit var sensorArray: Rect

    private var currentZoom = 1.0f
    private var expRange: Range<Int> = Range(0, 0)
    private var currentExp = 0

    // 수동 제어 상태
    private var manualEnabled = false
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureTimeRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var currentIso = 200
    private var currentExposureNs = 8_000_000L
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO

    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    // FPS 계산용
    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // ----- 수동 제어 API -----
    fun setManualEnabled(b: Boolean) { manualEnabled = b; updateRepeating() }
    fun setIso(v: Int) { currentIso = v.coerceIn(isoRange.lower, isoRange.upper); updateRepeating() }
    fun setExposureTimeNs(ns: Long) { currentExposureNs = ns.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper); updateRepeating() }
    fun setAwbMode(mode: Int) { currentAwbMode = mode; updateRepeating() }
    fun setExposureCompensation(value: Int) { currentExp = value.coerceIn(expRange.lower, expRange.upper); updateRepeating() }
    fun setZoom(zoomX: Float) { currentZoom = zoomX.coerceIn(1f, maxZoom()); updateRepeating() }

    // ----- Lifecycle -----
    fun onResume() {
        startBackground()
        textureView.surfaceTextureListener = surfaceListener
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyCenterCropTransform()
        }
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        }
    }

    fun onPause() {
        // FPS 초기화
        fpsCounter = 0
        lastFpsTickMs = 0L
        fpsSmoothed = 0.0
        closeSession()
        stopBackground()
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera(w, h)
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCenterCropTransform()
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            // 매 프레임 콜백 → FPS 계산
            fpsCounter++
            val now = SystemClock.elapsedRealtime()
            if (lastFpsTickMs == 0L) lastFpsTickMs = now
            val dt = now - lastFpsTickMs
            if (dt >= 500) { // 0.5초마다 갱신
                val inst = fpsCounter * 1000.0 / dt
                fpsSmoothed = if (fpsSmoothed == 0.0) inst else 0.6 * inst + 0.4 * fpsSmoothed
                fpsCounter = 0
                lastFpsTickMs = now
                onFpsChanged(fpsSmoothed)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(viewW: Int, viewH: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        cameraId = findCameraIdFor(lensFacing)
        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()

        dumpFpsAndSizes() // 로그 확인용(선택)

        // 화면 비율에 가장 가까운 프리뷰 사이즈 선택
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val out = map.getOutputSizes(SurfaceTexture::class.java).toList()
        val viewAspect = if (viewW > 0 && viewH > 0) viewW.toFloat() / viewH else 9f / 16f
        previewSize = chooseByAspect(out, viewAspect)

        // 범위 클램프
        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureTimeRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        currentExposureNs = currentExposureNs.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)

        // 캡처(사진)용 JPEG 리더
        imageReader?.close()
        imageReader = android.media.ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val buf: ByteBuffer = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()
                onSaved(saveJpeg(bytes))
            }, bgHandler)
        }

        cameraManager.openCamera(cameraId, deviceCallback, bgHandler)
    }

    private fun chooseByAspect(candidates: List<Size>, target: Float): Size {
        val sorted = candidates.filter { it.width > 0 && it.height > 0 }
            .sortedByDescending { it.width * it.height }
        return sorted.minByOrNull { abs(it.width / it.height.toFloat() - target) } ?: sorted.first()
    }

    private fun findCameraIdFor(facing: Int): String {
        cameraManager.cameraIdList.forEach { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == facing) return id
        }
        return cameraManager.cameraIdList.first()
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            startPreview()   // ★ 항상 일반 프리뷰만 사용
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
        override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
    }

    private fun startPreview() {
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)
        val jpegSurface = imageReader!!.surface

        cameraDevice?.createCaptureSession(listOf(previewSurface, jpegSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        applyCommonControls(this, preview = true)
                        applyColorAuto(this)
                        // 필요 시 프레임 고정: 30 또는 60
                        // set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    }
                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler)
    }

    fun takePicture() {
        val jpegSurface = imageReader?.surface ?: return
        val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegSurface)
            set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(chars))
            applyCommonControls(this, preview = false)
            applyColorAuto(this)
        }
        session?.capture(req.build(), null, bgHandler)
    }

    private fun applyColorAuto(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
    }

    private fun applyCommonControls(builder: CaptureRequest.Builder, preview: Boolean) {
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExp)
        }
        applyZoom(builder)
    }

    /** 풀스크린 센터-크롭 (회전 포함, 중심 pivot) */
    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f || previewSize.width <= 0 || previewSize.height <= 0) return

        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()

        val viewRect = android.graphics.RectF(0f, 0f, vw, vh)
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val bufW: Float
        val bufH: Float
        val degrees: Float
        when (rotation) {
            Surface.ROTATION_90 -> { bufW = previewSize.height.toFloat(); bufH = previewSize.width.toFloat(); degrees = 90f }
            Surface.ROTATION_270 -> { bufW = previewSize.height.toFloat(); bufH = previewSize.width.toFloat(); degrees = 270f }
            Surface.ROTATION_180 -> { bufW = previewSize.width.toFloat();  bufH = previewSize.height.toFloat(); degrees = 180f }
            else -> { bufW = previewSize.width.toFloat();  bufH = previewSize.height.toFloat(); degrees = 0f }
        }

        val bufferRect = android.graphics.RectF(0f, 0f, bufW, bufH)
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

        val scale = kotlin.math.max(vh / bufH, vw / bufW)
        matrix.postScale(scale, scale, centerX, centerY)

        if (degrees != 0f) matrix.postRotate(degrees, centerX, centerY)

        textureView.setTransform(matrix)
    }

    private fun updateRepeating() {
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)
        val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(previewSurface)
            applyCommonControls(this, preview = true)
            applyColorAuto(this)
            // 필요 시 프레임 고정:
            // set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        } ?: return
        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    private fun maxZoom(): Float {
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return max(1f, maxZoom)
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val cropW = (sensorArray.width() / currentZoom).toInt()
        val cropH = (sensorArray.height() / currentZoom).toInt()
        val left = (sensorArray.centerX() - cropW / 2).coerceAtLeast(0)
        val top = (sensorArray.centerY() - cropH / 2).coerceAtLeast(0)
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
    }

    private fun saveJpeg(bytes: ByteArray): Uri {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera2App")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun startBackground() {
        bgThread = HandlerThread("Camera2BG").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackground() {
        bgThread?.quitSafely()
        bgThread?.join()
        bgThread = null
        bgHandler = null
    }

    private fun closeSession() {
        session?.close(); session = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    /** Kelvin 근사로 RGGB Gains 적용(수동 WB) */
    private fun updateRepeatingWithGains(gains: RggbChannelVector) {
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)
        val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            // 수동 노출/ISO 유지
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } ?: return
        session?.setRepeatingRequest(req.build(), null, bgHandler)
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        closeSession()
        val w = textureView.width
        val h = textureView.height
        if (w > 0 && h > 0) openCamera(w, h)
        else textureView.surfaceTextureListener = surfaceListener
    }

    fun setAwbTemperature(kelvin: Int) {
        // 단순 근사: 차갑게(고켈빈) → Blue gain↑, 따뜻하게(저켈빈) → Red gain↑
        val rGain = when {
            kelvin < 4000 -> 2.0f
            kelvin > 7000 -> 1.0f
            else -> 1.5f
        }
        val bGain = when {
            kelvin < 4000 -> 1.0f
            kelvin > 7000 -> 2.0f
            else -> 1.5f
        }

        val gains = RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
        manualEnabled = true
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_OFF
        updateRepeatingWithGains(gains)
    }

    // ----- 지원 정보 로그 (선택) -----
    private fun dumpFpsAndSizes() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val aeFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (aeFpsRanges.isNullOrEmpty()) {
            Log.i("CAM", "AE target FPS ranges: <none>")
        } else {
            aeFpsRanges.forEach { r ->
                Log.i("CAM", "AE target FPS range: ${r.lower}..${r.upper}")
            }
        }
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        sizes?.forEach { sz -> Log.i("CAM", "Preview size supported: ${sz.width}x${sz.height}") }
    }
}
