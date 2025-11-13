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
import android.hardware.camera2.params.StreamConfigurationMap
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
import kotlin.math.min   // ★ 추가

class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameLevelChanged: (rollDeg: Float) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val previewContainer: ViewGroup,
    private val onFpsChanged: (Double) -> Unit = {}
) {
    // --- System / handles ---
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: android.media.ImageReader? = null
    private var previewSize: Size = Size(1920, 1080)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    // --- Characteristics / sensor ---
    private lateinit var cameraId: String
    private lateinit var chars: CameraCharacteristics
    private lateinit var sensorArray: Rect
    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    // --- Exposure / ISO / AWB / Zoom ---
    private var manualEnabled = true
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureTimeRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var currentIso = 200
    private var currentExposureNs = 3_000_000L
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var currentZoom = 1.0f
    private var expRange: Range<Int> = Range(0, 0)
    private var currentExp = 0

    // --- FPS ---
    private var targetFps = 60
    private val exposureMarginNs = 300_000L
    private val frameNs: Long get() = 1_000_000_000L / targetFps
    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // --- UI overlays ---
    private var shutterOverlay: View? = null

    // --- Resolution adaptation ---
    private var adaptiveResolutionEnabled = false   // 기본 FHD 고정
    private var fillPreview = true                  // true: CENTER_CROP, false: FIT
    private lateinit var sizeLadder: List<Size>
    private var sizeIndex = 0
    private var lastAdaptMs = 0L
    private val MAX_W = 1920
    private val MAX_H = 1080

    private fun isAtMostFhd(sz: Size): Boolean {
        val w = max(sz.width, sz.height)
        val h = min(sz.width, sz.height)
        return (w <= MAX_W && h <= MAX_H)
    }

    // --- Flash ---
    enum class FlashMode { OFF, AUTO, ON, TORCH } // 토치=지속광
    private var flashMode: FlashMode = FlashMode.OFF
    fun setFlashMode(mode: FlashMode) { flashMode = mode; updateRepeating() }
    fun getFlashMode(): FlashMode = flashMode
    private fun flashAvailable(): Boolean =
        try { chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } catch (_: Exception) { false }

    private fun applyFlash(builder: CaptureRequest.Builder, forPreview: Boolean) {
        if (!flashAvailable()) return
        when (flashMode) {
            FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    if (manualEnabled) CameraMetadata.CONTROL_AE_MODE_OFF else CameraMetadata.CONTROL_AE_MODE_ON
                )
            }
            FlashMode.TORCH -> {
                // 프리뷰·스틸에서 지속광 유지
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            FlashMode.AUTO -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_OFF else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            FlashMode.ON -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_OFF else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }
        }
    }

    // --- Aspect ratio ---
    enum class AspectMode { FULL, RATIO_1_1, RATIO_3_4, RATIO_9_16 }
    private var aspectMode: AspectMode = AspectMode.FULL

    fun cycleAspectMode(): AspectMode {
        aspectMode = when (aspectMode) {
            AspectMode.FULL -> AspectMode.RATIO_1_1
            AspectMode.RATIO_1_1 -> AspectMode.RATIO_3_4
            AspectMode.RATIO_3_4 -> AspectMode.RATIO_9_16
            AspectMode.RATIO_9_16 -> AspectMode.FULL
        }
        updatePreviewContainerForAspect()  // ★ 컨테이너 비율 먼저 조정
        maybeSwitchPreviewAspect()
        updateRepeating()
        textureView.post { applyCenterCropTransform() }
        return aspectMode
    }

    fun setAspectMode(mode: AspectMode) {
        if (aspectMode == mode) return
        aspectMode = mode
        updatePreviewContainerForAspect()  // ★ 컨테이너 비율 먼저 조정
        maybeSwitchPreviewAspect()
        updateRepeating()
        textureView.post { applyCenterCropTransform() }
    }

    fun getAspectMode(): AspectMode = aspectMode

    // --- External API ---
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
        val capped = ns.coerceAtMost(frameNs - exposureMarginNs)
        currentExposureNs = capped.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
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

    // Kelvin → RGGB gains (간단 근사)
    fun setAwbTemperature(kelvin: Int) {
        val rGain = when {
            kelvin < 3500 -> 2.2f; kelvin < 4500 -> 1.8f; kelvin < 5500 -> 1.5f; kelvin < 6500 -> 1.3f
            else -> 1.1f
        }
        val bGain = when {
            kelvin < 3500 -> 1.1f; kelvin < 4500 -> 1.3f; kelvin < 5500 -> 1.5f; kelvin < 6500 -> 1.8f
            else -> 2.2f
        }
        val gains = RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
        manualEnabled = true
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_OFF
        updateRepeatingWithGains(gains)
    }

    // --- Lifecycle ---
    fun onResume() {
        startBackground()
        textureView.surfaceTextureListener = surfaceListener
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyCenterCropTransform()
        }
        if (textureView.isAvailable) {
            updatePreviewContainerForAspect()   // ★ 재개 시 비율 적용
            openCamera(textureView.width, textureView.height)
        }
    }
    fun onPause() {
        fpsCounter = 0; lastFpsTickMs = 0L; fpsSmoothed = 0.0
        closeSession(); stopBackground()
    }

    // --- Switch camera ---
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        closeSession()
        currentZoom = 1f
        val w = textureView.width; val h = textureView.height
        if (w > 0 && h > 0) {
            updatePreviewContainerForAspect()
            openCamera(w, h)
        } else textureView.surfaceTextureListener = surfaceListener
    }

    // --- Surface listener / FPS ---
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            updatePreviewContainerForAspect()
            openCamera(w, h)
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) =
            applyCenterCropTransform()
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            fpsCounter++
            val now = SystemClock.elapsedRealtime()
            if (lastFpsTickMs == 0L) lastFpsTickMs = now
            val dt = now - lastFpsTickMs
            if (dt >= 500) {
                val inst = fpsCounter * 1000.0 / dt
                fpsSmoothed = if (fpsSmoothed == 0.0) inst else 0.6 * inst + 0.4 * fpsSmoothed
                fpsCounter = 0; lastFpsTickMs = now
                onFpsChanged(fpsSmoothed)
                maybeAdaptResolutionForFps()
            }
        }
    }

    // --- Open camera ---
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
        buildSizeLadder(map, viewAspect)
        previewSize = sizeLadder.firstOrNull() ?: chooseBestPreviewSize(map, viewAspect)

        // ranges & defaults
        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureTimeRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        currentExposureNs = currentExposureNs
            .coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
            .coerceAtMost(frameNs - exposureMarginNs)
        currentZoom = 1f

        cameraManager.openCamera(cameraId, deviceCallback, bgHandler)
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

    // --- Start preview ---
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
                        applyFlash(this, forPreview = true)
                    }
                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler)
    }

    // --- Capture still ---
    fun takePicture() {
        textureView.post { playShutterFlash() }

        val jpegSurface = imageReader?.surface ?: return
        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0

        val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegSurface)
            set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(chars, rotation))

            applyCommonControls(this, preview = false)
            applyColorAuto(this)
            applyFlash(this, forPreview = false)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        }
        session?.capture(req.build(), null, bgHandler)
    }

    // --- Color defaults ---
    private fun applyColorAuto(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
    }

    // --- Repeating update ---
    private fun updateRepeating() {
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)
        val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(previewSurface)
            applyCommonControls(this, preview = true)
            applyColorAuto(this)
            applyFlash(this, forPreview = true)
        } ?: return
        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    // --- Manual/common controls + zoom only crop ---
    private fun applyCommonControls(builder: CaptureRequest.Builder, preview: Boolean) {
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            val safeExp = currentExposureNs.coerceAtMost(frameNs - exposureMarginNs)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                currentIso.coerceIn(isoRange.lower, isoRange.upper)
            )
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
        applyZoomOnly(builder)
    }

    private fun applyZoomOnly(builder: CaptureRequest.Builder) {
        if (!::sensorArray.isInitialized) return
        val base = sensorArray
        if (currentZoom <= 1.0001f) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, base); return
        }
        val cropW = (base.width() / currentZoom).toInt()
        val cropH = (base.height() / currentZoom).toInt()
        val cx = base.centerX(); val cy = base.centerY()
        val left = cx - cropW / 2; val top = cy - cropH / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
    }

    // --- View transform (center crop / fit) ---
    /**
     * 여기서는 **항상 균일 스케일**만 한다.
     * 화면비(1:1 / 3:4 / 9:16 등)는 previewContainer의 레이아웃으로 맞추고,
     * 이 함수는 "카메라 버퍼를 컨테이너에 맞게 채우기/맞추기"만 담당.
     */
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

        val m = Matrix()
        m.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)

        // ★ 균일 스케일만 사용 (비율 왜곡 없음)
        val scale = if (fillPreview) max(vh / bufH, vw / bufW) else min(vh / bufH, vw / bufW)
        m.postScale(scale, scale, cx, cy)

        when (rotation) {
            Surface.ROTATION_90 -> m.postRotate(90f, cx, cy)
            Surface.ROTATION_180 -> m.postRotate(180f, cx, cy)
            Surface.ROTATION_270 -> m.postRotate(270f, cx, cy)
        }
        textureView.setTransform(m)
    }

    // --- Size choices / ladder ---
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

    private fun buildSizeLadder(map: StreamConfigurationMap, viewAspect: Float) {
        val sizesAll = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .filter { it.width > 0 && it.height > 0 }
            .filter { isAtMostFhd(it) }

        val tol = 0.03f
        val candidates = sizesAll
            .filter { abs(it.width / it.height.toFloat() - viewAspect) <= tol }
            .ifEmpty { sizesAll }

        val fhd = candidates.firstOrNull {
            (it.width == 1920 && it.height == 1080) || (it.width == 1080 && it.height == 1920)
        }
        sizeLadder = if (fhd != null) listOf(fhd) else candidates.sortedByDescending { it.width * it.height }
        sizeIndex = 0
    }

    private fun maybeAdaptResolutionForFps() {
        if (!adaptiveResolutionEnabled) return
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
        currentZoom = 1f
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        updateRepeating()
        textureView.post { applyCenterCropTransform() }
    }

    // --- Preview aspect helpers ---
    private fun targetAspectValue(): Float = when (aspectMode) {
        AspectMode.FULL -> {
            val vw = max(1, previewContainer.width)
            val vh = max(1, previewContainer.height)
            vw.toFloat() / vh
        }
        AspectMode.RATIO_1_1 -> 1f / 1f
        AspectMode.RATIO_3_4 -> 3f / 4f
        AspectMode.RATIO_9_16 -> 9f / 16f
    }

    private fun pickSizeForAspect(map: StreamConfigurationMap, aspect: Float): Size {
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .filter { it.width > 0 && it.height > 0 }
            .filter { isAtMostFhd(it) }
        val tol = 0.02f
        val exact = all.filter { abs(it.width / it.height.toFloat() - aspect) <= tol }
        if (exact.isNotEmpty()) return exact.maxBy { it.width * it.height }
        return all.minBy { abs(it.width / it.height.toFloat() - aspect) }
    }

    private fun maybeSwitchPreviewAspect() {
        if (!::chars.isInitialized) return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val target = targetAspectValue()
        val newSize = pickSizeForAspect(map, target)
        val curAspect = previewSize.width / previewSize.height.toFloat()
        val diff = abs(curAspect - (newSize.width / newSize.height.toFloat()))
        if (diff > 0.01f || (newSize.width != previewSize.width || newSize.height != previewSize.height)) {
            switchPreviewSize(newSize)
        }
    }

    // ★ NEW: previewContainer의 레이아웃을 실제 비율에 맞게 조정
    // ★ previewContainer의 레이아웃을 실제 비율에 맞게 조정 + 세로 중앙 정렬
    private fun updatePreviewContainerForAspect() {
        val parent = previewContainer.parent as? ViewGroup ?: return
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) {
            // 레이아웃 아직 안 잡혀있으면 나중에 다시 시도
            previewContainer.post { updatePreviewContainerForAspect() }
            return
        }

        val lp = previewContainer.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            when (aspectMode) {
                AspectMode.FULL -> {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                }
                AspectMode.RATIO_1_1,
                AspectMode.RATIO_3_4,
                AspectMode.RATIO_9_16 -> {
                    val aspect = when (aspectMode) {
                        AspectMode.RATIO_1_1 -> 1f / 1f
                        AspectMode.RATIO_3_4 -> 3f / 4f
                        AspectMode.RATIO_9_16 -> 9f / 16f
                        else -> 1f
                    }

                    // 부모 너비 기준으로 크기 계산
                    var w = pw
                    var h = (w / aspect).toInt()

                    // 너무 길면 부모 높이에 맞추고 다시 너비 계산
                    if (h > ph) {
                        h = ph
                        w = (h * aspect).toInt()
                    }

                    lp.width = w
                    lp.height = h

                    // ★ 세로 중앙 정렬: 위/아래 마진을 반반
                    val remaining = ph - h
                    val top = remaining / 2
                    val bottom = remaining - top
                    lp.topMargin = top.coerceAtLeast(0)
                    lp.bottomMargin = bottom.coerceAtLeast(0)
                }
            }
            previewContainer.layoutParams = lp
        } else {
            // MarginLayoutParams가 아닌 특이 케이스는 기존 로직만 유지
            when (aspectMode) {
                AspectMode.FULL -> {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                AspectMode.RATIO_1_1,
                AspectMode.RATIO_3_4,
                AspectMode.RATIO_9_16 -> {
                    val aspect = when (aspectMode) {
                        AspectMode.RATIO_1_1 -> 1f / 1f
                        AspectMode.RATIO_3_4 -> 3f / 4f
                        AspectMode.RATIO_9_16 -> 9f / 16f
                        else -> 1f
                    }
                    var w = pw
                    var h = (w / aspect).toInt()
                    if (h > ph) {
                        h = ph
                        w = (h * aspect).toInt()
                    }
                    lp.width = w
                    lp.height = h
                }
            }
            previewContainer.layoutParams = lp
        }

        previewContainer.requestLayout()
    }


    // 센서가 허용하는 최대 디지털 줌 값(최소 1.0 보장)
    private fun maxZoom(): Float {
        if (!::chars.isInitialized) return 1f
        val v = try {
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        } catch (_: Exception) {
            1f
        }
        return kotlin.math.max(1f, v)
    }

    // --- Shutter white flash overlay ---
    private fun playShutterFlash() {
        if (shutterOverlay == null) {
            shutterOverlay = previewContainer.rootView.findViewById(R.id.shutterFlashView)
        }
        val v = shutterOverlay ?: return
        v.bringToFront()
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

    // --- Save JPEG ---
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

    // --- Threads / session ---
    private fun startBackground() {
        bgThread = HandlerThread("Camera2BG").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }
    private fun stopBackground() {
        bgThread?.quitSafely(); bgThread?.join()
        bgThread = null; bgHandler = null
    }
    private fun closeSession() {
        session?.close(); session = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    // --- Manual WB repeat with gains ---
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

            applyZoomOnly(this)
            applyFlash(this, forPreview = true)
        } ?: return
        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    // --- Logs ---
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
