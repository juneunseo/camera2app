package com.example.camera2app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
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
import com.example.camera2app.camera.OrientationUtil.getJpegOrientation
import com.example.camera2app.ui.OverlayView
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

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

    // ============================================================================
    // Camera core
    // ============================================================================
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private lateinit var chars: CameraCharacteristics
    private lateinit var cameraId: String
    private lateinit var sensorArray: Rect

    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK

    // Preview size
    private var previewSize: Size = Size(1920, 1080)

    // Background thread
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    // ============================================================================
    // Exposure / ISO / Zoom / EV state
    // ============================================================================
    private var manualEnabled = false     // 전체 Manual ON/OFF

    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureRange: Range<Long> = Range(1_000_000L, 100_000_000L)

    private var currentIso = 200
    private var currentExposureNs = 3_000_000L
    private var currentZoom = 1f

    // EV
    private var aeCompRange: Range<Int> = Range(0, 0)
    private var currentEv = 0

    // Auto 모드에서 실제 적용된 값들 저장
    private var lastAutoIso = 200
    private var lastAutoExposureNs = 8_000_000L

    // ============================================================================
    // FPS control
    // ============================================================================
    private var targetFps = 60
    private val frameNs: Long get() = 1_000_000_000L / targetFps
    private val exposureMarginNs = 300_000L

    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // ============================================================================
    // Aspect ratio
    // ============================================================================
    enum class AspectMode { FULL, RATIO_1_1, RATIO_3_4, RATIO_9_16 }
    private var aspectMode = AspectMode.FULL

    // shutter overlay
    private var shutterOverlay: View? = null

    // preview limit
    private val MAX_W = 1920
    private val MAX_H = 1080

    // ============================================================================
    // Flash
    // ============================================================================
    enum class FlashMode { OFF, AUTO, ON, TORCH }
    private var flashMode = FlashMode.OFF
    fun getFlashMode() = flashMode
    fun setFlashMode(m: FlashMode) { flashMode = m; updateRepeating() }

    private fun flashAvailable() =
        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    // ============================================================================
    // Flash Apply
    // ============================================================================
    private fun applyFlash(builder: CaptureRequest.Builder, preview: Boolean) {
        if (!flashAvailable()) return

        when (flashMode) {
            FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            FlashMode.TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            }
            FlashMode.AUTO -> {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
            }
            FlashMode.ON -> {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                )
            }
        }
    }

    // ============================================================================
    // Aspect Cycle
    // ============================================================================
    fun cycleAspectMode(): AspectMode {
        aspectMode = when (aspectMode) {
            AspectMode.FULL -> AspectMode.RATIO_1_1
            AspectMode.RATIO_1_1 -> AspectMode.RATIO_3_4
            AspectMode.RATIO_3_4 -> AspectMode.RATIO_9_16
            AspectMode.RATIO_9_16 -> AspectMode.FULL
        }
        maybeSwitchPreviewAspect()
        updateRepeating()
        textureView.post { applyCenterCropTransform() }
        return aspectMode
    }

    // ============================================================================
    // Manual Control
    // ============================================================================
    fun setManualEnabled(b: Boolean) {
        manualEnabled = b
        updateRepeating()
    }

    fun setIso(v: Int) {
        currentIso = v.coerceIn(isoRange.lower, isoRange.upper)
        updateRepeating()
    }

    fun setExposureTimeNs(ns: Long) {
        val cap = ns.coerceAtMost(frameNs - exposureMarginNs)
        currentExposureNs = cap.coerceIn(exposureRange.lower, exposureRange.upper)
        updateRepeating()
    }

    fun setZoom(z: Float) {
        currentZoom = z.coerceIn(1f, maxZoom())
        updateRepeating()
    }

    fun onPinchScale(scale: Float) {
        val newZoom = (currentZoom * scale).coerceIn(1f, maxZoom())
        currentZoom = newZoom
        updateRepeating()
    }

    // ============================================================================
    // EV Control
    // ============================================================================
    fun getAeCompRange() = Pair(aeCompRange.lower, aeCompRange.upper)
    fun getCurrentEv() = currentEv

    fun setExposureCompensation(ev: Int) {
        currentEv = ev.coerceIn(aeCompRange.lower, aeCompRange.upper)
        if (!manualEnabled) updateRepeating()   // AE모드에서만 적용
    }

    // ============================================================================
    // Lifecycle
    // ============================================================================
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

    // ============================================================================
    // SurfaceTexture Listener
    // ============================================================================
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

    // ============================================================================
    // Open Camera
    // ============================================================================
    @SuppressLint("MissingPermission")
    private fun openCamera(w: Int, h: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        cameraId = findCameraId(lensFacing)
        cameraDevice = null

        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        previewSize = nearestSupportedPreviewSize(fixedPreviewSizeFor(aspectMode), map)

        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureRange
        aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0,0)

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

    // ============================================================================
    // CameraDevice Callback
    // ============================================================================
    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            setupImageReader()
            startPreview()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
        }
    }

    // ============================================================================
    // ImageReader
    // ============================================================================
    private fun setupImageReader() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)

        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
            AspectMode.FULL -> 9f / 20f
        }

        val captureSize = jpegSizes.minBy {
            abs(it.width.toFloat() / it.height - targetAspect)
        }

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
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            img.close()

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rotated = rotateBitmap(bmp, lastJpegOrientation)

            val targetAspectBmp = targetAspect
            val w = rotated.width
            val h = rotated.height
            val currentAspect = w.toFloat() / h

            var cropW = w
            var cropH = h

            if (currentAspect > targetAspectBmp) {
                cropW = (h * targetAspectBmp).toInt()
            } else {
                cropH = (w / targetAspectBmp).toInt()
            }

            val left = (w - cropW) / 2
            val top = (h - cropH) / 2

            val cropped = Bitmap.createBitmap(rotated, left, top, cropW, cropH)

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
            val savedUri = saveJpeg(out.toByteArray())

            onSaved(savedUri)
        }, bgHandler)
    }

    // ============================================================================
    // Preview Session
    // ============================================================================
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
                        applyFlash(this, true)
                    }

                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            },
            bgHandler
        )
    }

    // ============================================================================
    // Still Capture
    // ============================================================================
    private var lastJpegOrientation = 0

    private fun playShutterFlash() {
        if (shutterOverlay == null)
            shutterOverlay = previewContainer.rootView.findViewById(R.id.shutterFlashView)

        val v = shutterOverlay ?: return

        v.bringToFront()
        v.alpha = 0f
        v.visibility = View.VISIBLE

        v.animate().alpha(0.85f).setDuration(40).withEndAction {
            v.animate().alpha(0f).setDuration(180).start()
        }.start()
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
            applyFlash(this, false)
            applyZoomAndAspect(this)
        }

        session?.capture(req.build(), null, bgHandler)
    }

    // ============================================================================
    // Common Controls (Preview + Capture)
    // ============================================================================
    private fun applyCommonControls(builder: CaptureRequest.Builder, preview: Boolean) {

        if (manualEnabled) {
            // ---------------- MANUAL MODE ----------------
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            val safeExp = currentExposureNs.coerceAtMost(frameNs - exposureMarginNs)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)

        } else {
            // ---------------- AUTO MODE ----------------
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))

            // EV
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEv)

            readAutoExposureValues()
        }

        // AF 공통
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        applyZoomAndAspect(builder)
    }

    // AUTO일 때 AE가 결정한 값 읽기
    private fun readAutoExposureValues() {
        try {
            session?.capture(
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(Surface(textureView.surfaceTexture))
                }.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                            lastAutoExposureNs = it
                        }
                        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                            lastAutoIso = it
                        }
                    }
                },
                bgHandler
            )
        } catch (_: Exception) {}
    }

    // ============================================================================
    // Zoom
    // ============================================================================
    private fun applyZoomAndAspect(builder: CaptureRequest.Builder) {
        val base = sensorArray
        val z = max(1f, currentZoom)

        val cropW = (base.width() / z).toInt()
        val cropH = (base.height() / z).toInt()

        val cx = base.centerX()
        val cy = base.centerY()

        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            Rect(cx - cropW / 2, cy - cropH / 2, cx + cropW / 2, cy + cropH / 2)
        )
    }

    // ============================================================================
    // Preview Transform (CENTER CROP)
    // ============================================================================
    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0 || vh <= 0) return

        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
            AspectMode.FULL -> vw / vh
        }

        val desiredH = vw / targetAspect
        val desiredW = vh * targetAspect

        val crop =
            if (vw / vh > targetAspect) {
                RectF((vw - desiredW) / 2, 0f, (vw + desiredW) / 2, vh)
            } else {
                RectF(0f, (vh - desiredH) / 2, vw, (vh + desiredH) / 2)
            }

        val m = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        m.setRectToRect(viewRect, crop, Matrix.ScaleToFit.FILL)

        textureView.setTransform(m)

        val mapped = RectF(viewRect)
        m.mapRect(mapped)
        overlayView.setVisibleRect(mapped)
        overlayView.invalidate()
    }

    // ============================================================================
    // Preview Size
    // ============================================================================
    private fun fixedPreviewSizeFor(mode: AspectMode): Size {
        return when (mode) {
            AspectMode.RATIO_1_1 -> Size(1440, 1440)
            AspectMode.RATIO_3_4 -> Size(1440, 1920)
            AspectMode.RATIO_9_16 -> Size(1440, 2560)
            AspectMode.FULL -> Size(1440, 3200)
        }
    }

    private fun nearestSupportedPreviewSize(desired: Size, map: StreamConfigurationMap): Size {
        val all = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= MAX_W && it.height <= MAX_H }

        if (all.isEmpty()) return map.getOutputSizes(SurfaceTexture::class.java).first()

        return all.minBy {
            val dw = (it.width - desired.width).toDouble()
            val dh = (it.height - desired.height).toDouble()
            dw*dw + dh*dh
        }
    }

    private fun maybeSwitchPreviewAspect() {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        val targetAspect = when (aspectMode) {
            AspectMode.RATIO_1_1 -> 1f
            AspectMode.RATIO_3_4 -> 3f / 4f
            AspectMode.RATIO_9_16 -> 9f / 16f
            AspectMode.FULL -> previewContainer.width.toFloat() /
                    previewContainer.height.toFloat()
        }

        val newSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= MAX_W && it.height <= MAX_H }
            .minBy {
                abs(it.width.toFloat() / it.height - targetAspect)
            }

        if (newSize != previewSize) {
            previewSize = newSize
            textureView.surfaceTexture?.setDefaultBufferSize(newSize.width, newSize.height)
        }
    }

    // ============================================================================
    // All AUTO/MANUAL
    // ============================================================================
    fun setAllAuto() {
        manualEnabled = false
        updateRepeating()
    }

    fun setAllManual() {
        manualEnabled = true
        currentIso = lastAutoIso
        currentExposureNs = lastAutoExposureNs
        updateRepeating()
    }

    // ============================================================================
    // Save JPEG
    // ============================================================================
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

    // ============================================================================
    // Background Thread / Cleanup
    // ============================================================================
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

    // ============================================================================
    // Utils
    // ============================================================================
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun maxZoom(): Float {
        val z = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return max(1f, z)
    }

    fun getAppliedExposureNs() = currentExposureNs
    fun getCurrentIso() = currentIso

    // ============================================================================
    // Update Repeating
    // ============================================================================
    private fun updateRepeating() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return

        val previewSurface = Surface(st)

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            applyCommonControls(this, preview = true)
            applyFlash(this, true)
        }

        session?.setRepeatingRequest(req.build(), null, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    // ============================================================================
    // Switch Camera
    // ============================================================================
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

        if (w > 0 && h > 0) openCamera(w, h)
        else textureView.surfaceTextureListener = surfaceListener
    }
}
