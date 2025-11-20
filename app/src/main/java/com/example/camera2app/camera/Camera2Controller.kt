package com.example.camera2app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
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
import com.example.camera2app.ui.OverlayView
import com.example.camera2app.camera.OrientationUtil.getJpegOrientation
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// === manual WB ===
private var manualWbGains: RggbChannelVector? = null

class Camera2Controller(
    private val context: Context,
    private val overlayView: OverlayView,
    private val textureView: TextureView,
    private val onFrameLevelChanged: (Float) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val previewContainer: ViewGroup,
    private val onFpsChanged: (Double) -> Unit = {}
) {

    private val TAG = "Camera2Controller"

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size = Size(1920, 1080)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var chars: CameraCharacteristics
    private lateinit var cameraId: String
    private lateinit var sensorArray: Rect
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK

    // exposure
    private var manualEnabled = true
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var currentIso = 200
    private var currentExposureNs = 3_000_000L
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var currentZoom = 1f

    // EV compensation (-4 ~ +4 정도)
    private var expRange: Range<Int> = Range(0, 0)
    private var currentExp = 0

    // FPS
    private var targetFps = 60
    private val frameNs: Long get() = 1_000_000_000L / targetFps
    private val exposureMarginNs = 300_000L

    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // aspect
    enum class AspectMode { FULL, RATIO_1_1, RATIO_3_4, RATIO_9_16 }
    private var aspectMode = AspectMode.FULL

    // shutter overlay
    private var shutterOverlay: View? = null

    private var currentKelvin = 4400

    // resolution adaptation
    private var adaptiveResolution = false
    private lateinit var sizeLadder: List<Size>
    private var sizeIndex = 0
    private val MAX_W = 1920
    private val MAX_H = 1080

    // flash
    enum class FlashMode { OFF, AUTO, ON, TORCH }
    private var flashMode = FlashMode.OFF

    fun getFlashMode() = flashMode
    fun setFlashMode(m: FlashMode) { flashMode = m; updateRepeating() }

    private fun flashAvailable() =
        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    private fun applyFlash(builder: CaptureRequest.Builder, forPreview: Boolean) {
        if (!flashAvailable()) return

        when (flashMode) {
            FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                if (manualEnabled)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            }
            FlashMode.TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            FlashMode.AUTO -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_OFF
                    else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            FlashMode.ON -> {
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (forPreview) CameraMetadata.FLASH_MODE_OFF
                    else CameraMetadata.FLASH_MODE_SINGLE
                )
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }
        }
    }

    // =========================================================================================
    // Aspect control
    // =========================================================================================
    fun cycleAspectMode(): AspectMode {
        aspectMode = when (aspectMode) {
            AspectMode.FULL -> AspectMode.RATIO_1_1
            AspectMode.RATIO_1_1 -> AspectMode.RATIO_3_4
            AspectMode.RATIO_3_4 -> AspectMode.RATIO_9_16
            AspectMode.RATIO_9_16 -> AspectMode.FULL
        }
        Log.d(TAG, "cycleAspectMode -> $aspectMode")

        maybeSwitchPreviewAspect()
        updateRepeating()
        textureView.post { applyCenterCropTransform() }
        return aspectMode
    }

    fun setAspectMode(m: AspectMode) {
        aspectMode = m
        Log.d(TAG, "setAspectMode -> $aspectMode")
        maybeSwitchPreviewAspect()
        updateRepeating()
        textureView.post { applyCenterCropTransform() }
    }

    // =========================================================================================
    // Manual / Auto switches
    // =========================================================================================
    fun setManualEnabled(b: Boolean) { manualEnabled = b; updateRepeating() }

    fun setTargetFps(fps: Int) {
        targetFps = if (fps <= 60) 60 else 120
        currentExposureNs = currentExposureNs
            .coerceAtMost(frameNs - exposureMarginNs)
        updateRepeating()
    }

    fun setIso(v: Int) {
        currentIso = v.coerceIn(isoRange.lower, isoRange.upper)
        updateRepeating()
    }

    fun setExposureTimeNs(ns: Long) {
        val capped = ns.coerceAtMost(frameNs - exposureMarginNs)
        currentExposureNs = capped
            .coerceIn(exposureRange.lower, exposureRange.upper)
        updateRepeating()
    }

    fun setAwbMode(mode: Int) {
        currentAwbMode = mode
        updateRepeating()
    }

    // ★ EV 값 설정 (MainActivity 슬라이더에서 호출)
    fun setExposureCompensation(v: Int) {
        currentExp = v.coerceIn(expRange.lower, expRange.upper)
        updateRepeating()
    }

    fun setZoom(z: Float) {
        currentZoom = z.coerceIn(1f, maxZoom())
        updateRepeating()
    }

    fun onPinchScale(scale: Float) {
        if (!::chars.isInitialized) return
        val newZoom = (currentZoom * scale)
            .coerceIn(1f, maxZoom())
        currentZoom = newZoom
        updateRepeating()
    }

    fun setMinIsoFloor(minIso: Int) {
        isoRange = Range(max(minIso, isoRange.lower), isoRange.upper)
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        updateRepeating()
    }

    fun setAdaptiveResolutionEnabled(b: Boolean) {
        adaptiveResolution = b
    }

    fun setFillPreview(b: Boolean) {
        textureView.post { applyCenterCropTransform() }
    }

    fun setAwbTemperature(kelvin: Int) {
        val rGain = when {
            kelvin < 3500 -> 2.2f
            kelvin < 4500 -> 1.8f
            kelvin < 5500 -> 1.5f
            kelvin < 6500 -> 1.3f
            else -> 1.1f
        }
        val bGain = when {
            kelvin < 3500 -> 1.1f
            kelvin < 4500 -> 1.3f
            kelvin < 5500 -> 1.5f
            kelvin < 6500 -> 1.8f
            else -> 2.2f
        }
        manualEnabled = true
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_OFF
        currentKelvin = kelvin
        manualWbGains = RggbChannelVector(rGain, 1f, 1f, bGain)
        updateRepeating()
    }

    // =========================================================================================
    // Lifecycle
    // =========================================================================================
    fun onResume() {
        startBackground()
        textureView.surfaceTextureListener = surfaceListener
        if (textureView.isAvailable)
            openCamera(textureView.width, textureView.height)
    }

    fun onPause() {
        closeSession()
        stopBackground()
    }

    // =========================================================================================
    // Surface listener
    // =========================================================================================
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera(w, h)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            applyCenterCropTransform()
        }

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
            }
        }
    }

    // =========================================================================================
    // Camera open
    // =========================================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera(w: Int, h: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        cameraId = findCameraId(lensFacing)
        cameraDevice = null

        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        // 현재 aspectMode(1:1, 3:4, 9:16, FULL)에 맞는 preset 해상도 하나 정함
        val desiredPreview = fixedPreviewSizeFor(aspectMode)

        // 실제 디바이스가 지원하는 사이즈 중에서 가장 가까운 해상도 선택
        previewSize = nearestSupportedPreviewSize(desiredPreview, map)

        Log.d(TAG, "openCamera: aspect=$aspectMode, previewSize=$previewSize")

        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)

        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)

        cameraManager.openCamera(cameraId, deviceCallback, bgHandler)
    }

    private fun findCameraId(facing: Int): String {
        cameraManager.cameraIdList.forEach { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == facing)
                return id
        }
        return cameraManager.cameraIdList.first()
    }

    // =========================================================================================
    // CameraDevice callback
    // =========================================================================================
    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            setupImageReader()
            startPreview()
        }

        override fun onDisconnected(device: CameraDevice) {
            cameraDevice = null
            device.close()
        }

        override fun onError(device: CameraDevice, error: Int) {
            cameraDevice = null
            device.close()
        }
    }

    // =========================================================================================
    // ImageReader (JPEG capture + central crop)
    // =========================================================================================
    private fun setupImageReader() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)

        val sensorAspect = sensorArray.width().toFloat() / sensorArray.height()
        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
            AspectMode.FULL -> 9f / 20f   // ★ FULL = 20:9 고정
        }

        val captureSize = jpegSizes.minBy {
            val a = it.width.toFloat() / it.height
            abs(a - targetAspect)
        }

        Log.d(TAG, "setupImageReader: aspect=$aspectMode, targetAspect=$targetAspect, captureSize=$captureSize")

        imageReader?.close()
        imageReader = ImageReader.newInstance(
            captureSize.width,
            captureSize.height,
            ImageFormat.JPEG,
            2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).apply { buf.get(this) }
            img.close()

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // rotate according to JPEG_ORIENTATION
            val rotated = rotateBitmap(bmp, lastJpegOrientation)

            val aspect = when (aspectMode) {
                AspectMode.RATIO_1_1 -> 1f
                AspectMode.RATIO_3_4 -> 3f / 4f
                AspectMode.RATIO_9_16 -> 9f / 16f
                AspectMode.FULL -> 9f / 20f   // ★ FULL = 20:9 고정
            }

            val w = rotated.width
            val h = rotated.height
            val currentAspect = w.toFloat() / h

            var cropW = w
            var cropH = h

            if (currentAspect > aspect)
                cropW = (h * aspect).toInt()
            else
                cropH = (w / aspect).toInt()

            val left = (w - cropW) / 2
            val top = (h - cropH) / 2

            Log.d(
                TAG,
                "onImageAvailable: mode=$aspectMode, rotated=${w}x$h, currentAspect=$currentAspect, " +
                        "targetAspect=$aspect, crop=${cropW}x$cropH"
            )

            val cropped = Bitmap.createBitmap(rotated, left, top, cropW, cropH)

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
            val finalBytes = out.toByteArray()

            onSaved(saveJpeg(finalBytes))
        }, bgHandler)
    }

    // =========================================================================================
    // Preview start
    // =========================================================================================
    private fun startPreview() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return

        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)
        val jpegSurface = imageReader!!.surface

        device.createCaptureSession(
            listOf(previewSurface, jpegSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s

                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        applyCommonControls(this, preview = true)
                        applyColorAuto(this)
                        applyFlash(this, true)
                    }

                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler
        )
    }

    // =========================================================================================
    // takePicture()
    // =========================================================================================
    private var lastJpegOrientation = 0

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

    fun takePicture() {
        playShutterFlash()

        val device = cameraDevice ?: return
        val jpegSurface = imageReader?.surface ?: return

        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0
        lastJpegOrientation = getJpegOrientation(chars, rotation)

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegSurface)

            set(CaptureRequest.JPEG_ORIENTATION, lastJpegOrientation)
            applyCommonControls(this, preview = false)
            applyColorAuto(this)
            applyFlash(this, false)
            applyZoomAndAspect(this)

            // quality boost for still capture
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        }

        session?.capture(req.build(), null, bgHandler)
    }

    // =========================================================================================
    // Auto / Manual WB / Color controls
    // =========================================================================================
    private fun applyColorAuto(builder: CaptureRequest.Builder) {
        if (currentAwbMode == CameraMetadata.CONTROL_AWB_MODE_OFF && manualWbGains != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, manualWbGains)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            )
        }
    }

    // =========================================================================================
    // Common preview/still controls
    // =========================================================================================
    private fun applyCommonControls(builder: CaptureRequest.Builder, preview: Boolean) {
        if (manualEnabled) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)

            val safeExp = currentExposureNs.coerceAtMost(frameNs - 300_000L)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)

            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(targetFps, targetFps)
            )
            builder.set(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            )
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            if (preview) {
                builder.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                )
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            }
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExp)
            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(targetFps, targetFps)
            )
        }

        applyZoomAndAspect(builder)
    }

    // =========================================================================================
    // Sensor crop for preview and capture
    // =========================================================================================
    // 🔁 센서에서는 "줌만" 적용하고, 비율은 건드리지 않는다.
    private fun applyZoomAndAspect(builder: CaptureRequest.Builder) {
        if (!::sensorArray.isInitialized) return

        val base = sensorArray
        val zoom = currentZoom.coerceAtLeast(1f)

        // 줌에 따른 센서 크롭만 계산
        val cropW = (base.width() / zoom).toInt()
        val cropH = (base.height() / zoom).toInt()

        val cx = base.centerX()
        val cy = base.centerY()

        val left = cx - cropW / 2
        val top = cy - cropH / 2

        val rect = Rect(left, top, left + cropW, top + cropH)
        builder.set(CaptureRequest.SCALER_CROP_REGION, rect)
    }

    // =========================================================================================
    // Preview transform (CENTER CROP)
    // =========================================================================================
    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0 || vh <= 0) return

        val bw = previewSize.width.toFloat()
        val bh = previewSize.height.toFloat()

        val viewRect = RectF(0f, 0f, vw, vh)
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()

        // (필요시 사용할 수 있는 스케일, 현재는 setRectToRect로 대체)
        val scale = max(vw / bw, vh / bh)

        // Aspect ratio crop mask
        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
            AspectMode.FULL -> vw / vh
        }

        val desiredH = vw / targetAspect
        val desiredW = vh * targetAspect

        val cropRect =
            if (vw / vh > targetAspect) {
                RectF((vw - desiredW) / 2f, 0f, (vw + desiredW) / 2f, vh)
            } else {
                RectF(0f, (vh - desiredH) / 2f, vw, (vh + desiredH) / 2f)
            }

        val m = Matrix()
        m.setRectToRect(viewRect, cropRect, Matrix.ScaleToFit.FILL)
        textureView.setTransform(m)

        val rectMapped = RectF(viewRect)
        m.mapRect(rectMapped)
        overlayView.setVisibleRect(rectMapped)
        overlayView.invalidate()
    }

    // =========================================================================================
    // Size selection
    // =========================================================================================
    // 내가 원하는 비율에 맞는 "목표" 해상도 (preset)
    private fun fixedPreviewSizeFor(mode: AspectMode): Size {
        return when (mode) {
            AspectMode.RATIO_1_1 -> Size(1440, 1440)   // 1:1
            AspectMode.RATIO_3_4 -> Size(1440, 1920)   // 3:4  (0.75)
            AspectMode.RATIO_9_16 -> Size(1440, 2560)  // 9:16 (0.5625)
            AspectMode.FULL -> Size(1440, 3200)        // ≈ 9:20 (0.45)
        }
    }

    // 위 preset과 가장 가까운, 실제 "지원되는" 프리뷰 사이즈를 선택
    private fun nearestSupportedPreviewSize(
        desired: Size,
        map: StreamConfigurationMap
    ): Size {
        val all = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= MAX_W && it.height <= MAX_H }

        // 혹시라도 필터 후 비어 있으면 그냥 첫 번째 사용
        if (all.isEmpty()) return map.getOutputSizes(SurfaceTexture::class.java).first()

        return all.minBy { s ->
            val dw = (s.width - desired.width).toDouble()
            val dh = (s.height - desired.height).toDouble()
            dw * dw + dh * dh
        }
    }

    private fun buildSizeLadder(map: StreamConfigurationMap) {
        val all = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= MAX_W && it.height <= MAX_H }

        sizeLadder = all.sortedByDescending { it.width * it.height }
        sizeIndex = 0
    }

    private fun maybeSwitchPreviewAspect() {
        if (!::chars.isInitialized) return

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
            AspectMode.FULL ->
                previewContainer.width.toFloat() / previewContainer.height.toFloat()
        }

        val newSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= MAX_W && it.height <= MAX_H }
            .minBy {
                val a = it.width.toFloat() / it.height
                abs(a - targetAspect)
            }

        if (newSize != previewSize) {
            previewSize = newSize
            val st = textureView.surfaceTexture
            st?.setDefaultBufferSize(newSize.width, newSize.height)
        }
    }

    // =========================================================================================
    // Save jpeg
    // =========================================================================================
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
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return uri
    }

    // =========================================================================================
    // Background / cleanup
    // =========================================================================================
    private fun startBackground() {
        bgThread = HandlerThread("CameraBG").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackground() {
        bgThread?.quitSafely()
        bgThread?.join()
        bgThread = null
        bgHandler = null
    }

    private fun closeSession() {
        session?.close()
        session = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    // =========================================================================================
    // Utils / getters
    // =========================================================================================
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun getAppliedExposureNs() = currentExposureNs
    fun getCurrentIso() = currentIso
    fun getCurrentKelvin() = currentKelvin

    // ★ EV 범위 / 현재값 getter (슬라이더 초기 세팅용)
    fun getEvRange(): Range<Int> = expRange
    fun getCurrentEv(): Int = currentExp

    private fun maxZoom(): Float {
        val maxZ = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return max(1f, maxZ)
    }

    private fun updateRepeating() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return

        val previewSurface = Surface(st)

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)

            applyCommonControls(this, preview = true)
            applyColorAuto(this)
            applyFlash(this, true)
        }

        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    // =========================================================================================
    // 전체 Auto / Manual 토글 (MainActivity에서 사용)
    // =========================================================================================
    fun setAllAuto() {
        manualEnabled = false
        // WB 자동
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        manualWbGains = null
        // EV는 0으로 초기화
        currentExp = 0.coerceIn(expRange.lower, expRange.upper)
        updateRepeating()
    }

    fun setAllManual() {
        manualEnabled = true
        // 노출은 수동이지만 WB는 자동 유지해서 초록색 안 뜨게
        currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        updateRepeating()
    }

    // -------------------------------
    // 🔄 전·후면 카메라 전환
    // -------------------------------
    fun switchCamera() {
        lensFacing =
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
                CameraCharacteristics.LENS_FACING_FRONT
            else
                CameraCharacteristics.LENS_FACING_BACK

        closeSession()
        currentZoom = 1f

        val w = textureView.width
        val h = textureView.height

        if (w > 0 && h > 0) {
            openCamera(w, h)
        } else {
            textureView.surfaceTextureListener = surfaceListener
        }
    }
}
