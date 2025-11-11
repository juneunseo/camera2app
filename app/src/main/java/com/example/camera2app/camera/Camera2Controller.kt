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
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.example.camera2app.R
import com.example.camera2app.camera.OrientationUtil.getJpegOrientation
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import android.hardware.camera2.params.StreamConfigurationMap

class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameLevelChanged: (rollDeg: Float) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val previewContainer: ViewGroup,
    private val onFpsChanged: (Double) -> Unit = {}
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSize: Size = Size(1920, 1080)
    private var imageReader: android.media.ImageReader? = null

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var cameraId: String
    private lateinit var chars: CameraCharacteristics
    private lateinit var sensorArray: Rect

    private var currentZoom = 1.0f
    private var expRange: Range<Int> = Range(0, 0)
    private var currentExp = 0

    // ===== 수동 제어 상태 =====
    private var manualEnabled = true
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureTimeRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var currentIso = 200
    private var currentExposureNs = 3_000_000L     // 3ms
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO

    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    // ===== FPS 계산 =====
    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // ===== 타겟 FPS / 프레임 제한 =====
    private var targetFps = 60                     // 60 혹은 120
    private val exposureMarginNs = 300_000L        // 0.3ms 마진
    private val frameNs: Long get() = 1_000_000_000L / targetFps

    // ===== 셔터 플래시 =====
    private var shutterOverlay: View? = null

    // ===== 해상도 제어 =====
    private var adaptiveResolutionEnabled = false  // ✅ FHD 고정: false
    private var fillPreview = true                 // true=CENTER_CROP, false=FIT(레터박스)
    private lateinit var sizeLadder: List<Size>
    private var sizeIndex = 0
    private var lastAdaptMs = 0L

    // ===== FHD 캡 =====
    private val MAX_W = 1920
    private val MAX_H = 1080
    private fun isAtMostFhd(sz: Size): Boolean {
        val w = max(sz.width, sz.height)
        val h = minOf(sz.width, sz.height)
        return (w <= MAX_W && h <= MAX_H)
    }

    // ----- 외부 제어 API -----
    fun setManualEnabled(b: Boolean) { manualEnabled = b; updateRepeating() }
    fun setTargetFps(fps: Int) {
        targetFps = if (fps <= 60) 60 else 120
        currentExposureNs = currentExposureNs
            .coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
            .coerceAtMost(frameNs - exposureMarginNs)
        updateRepeating()
    }
    fun setIso(v: Int) { currentIso = v.coerceIn(isoRange.lower, isoRange.upper); updateRepeating() }
    fun setExposureTimeNs(ns: Long) {
        val cappedByFps = ns.coerceAtMost(frameNs - exposureMarginNs)
        currentExposureNs = cappedByFps.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
        updateRepeating()
    }
    fun setAwbMode(mode: Int) { currentAwbMode = mode; updateRepeating() }
    fun setExposureCompensation(value: Int) { currentExp = value.coerceIn(expRange.lower, expRange.upper); updateRepeating() }
    fun setZoom(zoomX: Float) { currentZoom = zoomX.coerceIn(1f, maxZoom()); updateRepeating() }
    fun setMinIsoFloor(minIso: Int) {
        isoRange = Range(max(minIso, isoRange.lower), isoRange.upper)
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        updateRepeating()
    }
    fun setFillPreview(enableCropFill: Boolean) { fillPreview = enableCropFill; applyCenterCropTransform() }
    fun setAdaptiveResolutionEnabled(enabled: Boolean) { adaptiveResolutionEnabled = enabled }

    // ----- Lifecycle -----
    fun onResume() {
        startBackground()
        textureView.surfaceTextureListener = surfaceListener
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCenterCropTransform() }
        if (textureView.isAvailable) openCamera(textureView.width, textureView.height)
    }
    fun onPause() {
        fpsCounter = 0; lastFpsTickMs = 0L; fpsSmoothed = 0.0
        closeSession()
        stopBackground()
    }

    // 카메라 전후면 전환
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        // 세션/디바이스 정리
        closeSession()

        // 줌 초기화(이전 CROP 잔존 방지)
        currentZoom = 1f

        // 화면 크기 기준으로 다시 오픈
        val w = textureView.width
        val h = textureView.height
        if (w > 0 && h > 0) {
            openCamera(w, h)
        } else {
            // 아직 TextureView가 준비 안 됐으면 리스너로 대기
            textureView.surfaceTextureListener = surfaceListener
        }
    }

    // 간이 켈빈 → RGGB 게인 변환(수동 WB)
    fun setAwbTemperature(kelvin: Int) {
        // 대략적인 근사: 낮은 K -> R↑ / 높은 K -> B↑
        val rGain = when {
            kelvin < 3500 -> 2.2f
            kelvin < 4500 -> 1.8f
            kelvin < 5500 -> 1.5f
            kelvin < 6500 -> 1.3f
            else          -> 1.1f
        }
        val bGain = when {
            kelvin < 3500 -> 1.1f
            kelvin < 4500 -> 1.3f
            kelvin < 5500 -> 1.5f
            kelvin < 6500 -> 1.8f
            else          -> 2.2f
        }

        val gains = RggbChannelVector(rGain, 1.0f, 1.0f, bGain)

        // 수동 WB로 전환 + 수동 노출 유지
        manualEnabled = true
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_OFF

        // 이미 클래스에 있는 함수: 수동 WB 게인으로 프리뷰 반복요청 갱신
        updateRepeatingWithGains(gains)
    }

    // ----- 셔터 플래시 -----
    private fun playShutterFlash() {
        if (shutterOverlay == null) {
            shutterOverlay = previewContainer.rootView.findViewById(R.id.shutterFlashView)
            shutterOverlay?.bringToFront()
        }
        val v = shutterOverlay ?: return
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.animate()
            .alpha(0.85f)
            .setDuration(40)
            .withEndAction {
                v.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction { v.setLayerType(View.LAYER_TYPE_NONE, null) }
                    .start()
            }
            .start()
    }

    // ----- FPS 측정 -----
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera(w, h)
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCenterCropTransform()
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            fpsCounter++
            val now = SystemClock.elapsedRealtime()
            if (lastFpsTickMs == 0L) lastFpsTickMs = now
            val dt = now - lastFpsTickMs
            if (dt >= 500) {
                val inst = fpsCounter * 1000.0 / dt
                fpsSmoothed = if (fpsSmoothed == 0.0) inst else 0.6 * inst + 0.4 * fpsSmoothed
                fpsCounter = 0
                lastFpsTickMs = now
                onFpsChanged(fpsSmoothed)
                maybeAdaptResolutionForFps()
            }
        }
    }

    // ----- 카메라 열기 -----
    @SuppressLint("MissingPermission")
    private fun openCamera(viewW: Int, viewH: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        cameraId = findCameraIdFor(lensFacing)
        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()

        dumpFpsAndSizes()

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val viewAspect = if (viewW > 0 && viewH > 0) viewW.toFloat() / viewH else 9f / 16f

        // 해상도 사다리 구성(FHD 제한 + FHD 우선)
        buildSizeLadder(map, viewAspect)
        previewSize = sizeLadder.firstOrNull() ?: chooseBestPreviewSize(map, viewAspect)

        // 범위/기본값
        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureTimeRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)

        // FPS 지키도록 노출 상한
        currentExposureNs = currentExposureNs
            .coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
            .coerceAtMost(frameNs - exposureMarginNs)

        // ✅ 줌 초기화(줌 잔존 방지)
        currentZoom = 1f

        cameraManager.openCamera(cameraId, deviceCallback, bgHandler)
    }

    /** FHD 이하 우선으로 화면 비율(±3%) → 16:9 → 최인접 */
    private fun chooseBestPreviewSize(map: StreamConfigurationMap, viewAspect: Float): Size {
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .filter { it.width > 0 && it.height > 0 }
        val sizes = all.filter { isAtMostFhd(it) }.ifEmpty { all }

        val tol = 0.03f
        val nearScreen = sizes.filter { abs(it.width / it.height.toFloat() - viewAspect) <= tol }
        if (nearScreen.isNotEmpty()) return nearScreen.maxBy { it.width * it.height }

        val near169 = sizes.filter { abs(it.width / it.height.toFloat() - 16f / 9f) <= tol }
        if (near169.isNotEmpty()) return near169.maxBy { it.width * it.height }

        return sizes.minBy { abs(it.width / it.height.toFloat() - viewAspect) }
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

            // JPEG 리더 (previewSize 기반 → FHD 제한 적용)
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

            startPreviewNormal()
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
        override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
    }

    /** 일반 프리뷰(수동 유지) */
    private fun startPreviewNormal() {
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
                    }
                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler)
    }

    fun takePicture() {
        textureView.post { playShutterFlash() }

        val jpegSurface = imageReader?.surface ?: return
        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0

        val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegSurface)
            set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(chars, rotation))

            // 스틸만 화질 우선
            applyCommonControls(this, preview = false)
            applyColorAuto(this)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
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

    /** 수동 유지 + FPS 보장 + 줌 리셋 로직 반영 */
    private fun applyCommonControls(builder: CaptureRequest.Builder, preview: Boolean) {
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            val safeExp = currentExposureNs.coerceAtMost(frameNs - exposureMarginNs)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(isoRange.lower, isoRange.upper))
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))

            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            if (preview) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
            }
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExp)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ)
        }
        applyZoom(builder) // ✅ 항상 마지막에 적용
    }

    /** 화면 가득(CROP) 또는 FIT(레터박스) */
    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f || previewSize.width <= 0 || previewSize.height <= 0) return

        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val bufW = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
            previewSize.height.toFloat() else previewSize.width.toFloat()
        val bufH = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
            previewSize.width.toFloat() else previewSize.height.toFloat()

        val viewRect = android.graphics.RectF(0f, 0f, vw, vh)
        val bufferRect = android.graphics.RectF(0f, 0f, bufW, bufH)
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())

        val matrix = Matrix()
        matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)

        val scale = if (fillPreview) max(vh / bufH, vw / bufW) else minOf(vh / bufH, vw / bufW)
        matrix.postScale(scale, scale, cx, cy)

        when (rotation) {
            Surface.ROTATION_90  -> matrix.postRotate(90f, cx, cy)
            Surface.ROTATION_180 -> matrix.postRotate(180f, cx, cy)
            Surface.ROTATION_270 -> matrix.postRotate(270f, cx, cy)
        }
        textureView.setTransform(matrix)
    }

    private fun updateRepeating() {
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)
        val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(previewSurface)
            applyCommonControls(this, preview = true)
            applyColorAuto(this)
        } ?: return
        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    private fun maxZoom(): Float {
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return max(1f, maxZoom)
    }

    /** ✅ 1×에서 센서 전체로 크롭 리셋 */
    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (currentZoom <= 1.0001f) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, sensorArray)
            return
        }
        val cropW = (sensorArray.width() / currentZoom).toInt()
        val cropH = (sensorArray.height() / currentZoom).toInt()
        val left = (sensorArray.centerX() - cropW / 2).coerceAtLeast(0)
        val top  = (sensorArray.centerY() - cropH / 2).coerceAtLeast(0)
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

    /** Kelvin 근사로 수동 WB */
    private fun updateRepeatingWithGains(gains: RggbChannelVector) {
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)
        val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)

            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(isoRange.lower, isoRange.upper))
            val safeExp = currentExposureNs.coerceAtMost(frameNs - exposureMarginNs)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } ?: return
        session?.setRepeatingRequest(req.build(), null, bgHandler)
    }

    // ===== 해상도 사다리 (FHD 우선) =====
    private fun buildSizeLadder(map: StreamConfigurationMap, viewAspect: Float) {
        val sizesAll = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .filter { it.width > 0 && it.height > 0 }
            .filter { isAtMostFhd(it) } // ✅ FHD 이하만

        val tol = 0.03f
        val candidates = sizesAll
            .filter { abs(it.width / it.height.toFloat() - viewAspect) <= tol }
            .ifEmpty { sizesAll }

        // ✅ 1920x1080 정확 일치 우선
        val fhd = candidates.firstOrNull {
            (it.width == 1920 && it.height == 1080) || (it.width == 1080 && it.height == 1920)
        }
        sizeLadder = if (fhd != null) listOf(fhd) else candidates.sortedByDescending { it.width * it.height }
        sizeIndex = 0
    }

    private fun maybeAdaptResolutionForFps() {
        if (!adaptiveResolutionEnabled) return   // ✅ FHD 고정이면 종료
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdaptMs < 1200) return

        val guard = 2.0
        if (fpsSmoothed > targetFps + guard && sizeIndex > 0) {
            sizeIndex--
            switchPreviewSize(sizeLadder[sizeIndex])
            lastAdaptMs = now
        } else if (fpsSmoothed < targetFps - guard && sizeIndex < sizeLadder.lastIndex) {
            sizeIndex++
            switchPreviewSize(sizeLadder[sizeIndex])
            lastAdaptMs = now
        }
    }

    private fun switchPreviewSize(newSize: Size) {
        val st = textureView.surfaceTexture ?: return
        previewSize = newSize
        currentZoom = 1f                    // ✅ 해상도 변경 시 줌 리셋
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        updateRepeating()
        textureView.post { applyCenterCropTransform() }
    }

    // ----- 로그 -----
    private fun dumpFpsAndSizes() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (ranges.isNullOrEmpty()) Log.i("CAM", "AE FPS ranges: <none>")
        else ranges.forEach { Log.i("CAM", "AE FPS: ${it.lower}..${it.upper}") }
        map.getOutputSizes(SurfaceTexture::class.java)?.forEach {
            Log.i("CAM", "Preview size supported: ${it.width}x${it.height}")
        }
    }
}
