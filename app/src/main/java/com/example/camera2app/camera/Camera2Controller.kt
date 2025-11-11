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

import android.hardware.camera2.params.StreamConfigurationMap

import android.view.View
import com.example.camera2app.R




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

    // ====== 수동 제어 상태 ======
    private var manualEnabled = true          // 기본값: 수동 모드 ON
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureTimeRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var currentIso = 200
    private var currentExposureNs = 3_000_000L   // 기본 3ms (1/333s) → 120fps OK
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO

    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    // ====== FPS 계산 ======
    private var fpsCounter = 0
    private var lastFpsTickMs = 0L
    private var fpsSmoothed = 0.0

    // ====== 120 FPS 강제 관련 ======
    private val TARGET_FPS = 120
    private val FRAME_NS_120 = 1_000_000_000L / TARGET_FPS  // 8_333_333ns
    private var forceManual120 = true   // 플랜 A 사용

    private var shutterOverlay: View? = null

    // ----- 외부 제어 API -----
    fun setManualEnabled(b: Boolean) { manualEnabled = b; updateRepeating() }
    fun setIso(v: Int) { currentIso = v.coerceIn(isoRange.lower, isoRange.upper); updateRepeating() }
    fun setExposureTimeNs(ns: Long) {
        // 120fps 유지: 프레임 길이보다 짧게 강제
        val capped = ns.coerceAtMost(FRAME_NS_120 - 300_000L) // 여유 0.3ms
        currentExposureNs = capped.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
        updateRepeating()
    }
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

    private fun playShutterFlash() {
        if (shutterOverlay == null) {
            // ✅ 루트에서 찾기
            shutterOverlay = previewContainer.rootView.findViewById(R.id.shutterFlashView)
            // ✅ 항상 맨 위로
            shutterOverlay?.bringToFront()
        }
        val v = shutterOverlay ?: return

        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.animate().cancel()

        // (가시성 안전장치 – 원래 기본이 VISIBLE이지만 혹시 모를 경우 대비)
        v.visibility = View.VISIBLE

        v.alpha = 0f
        v.animate()
            .alpha(0.85f) //최대 밝기
            .setDuration(40) //속도 조절
            .withEndAction {
                v.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction { v.setLayerType(View.LAYER_TYPE_NONE, null) }
                    .start()
            }
            .start()
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

        dumpFpsAndSizes() // 로그 확인용

        // 화면 비율에 가장 가까운 프리뷰 사이즈 선택
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val viewAspect = if (viewW > 0 && viewH > 0) viewW.toFloat() / viewH else 9f / 16f
        previewSize = chooseBestPreviewSize(map, viewAspect)

        // 범위 클램프
        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureTimeRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        // 노출 기본값도 120fps를 깨지 않도록
        currentExposureNs = currentExposureNs
            .coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
            .coerceAtMost(FRAME_NS_120 - 300_000L)

        cameraManager.openCamera(cameraId, deviceCallback, bgHandler)
    }

    /** 화면 비율(±3%) 우선 → 16:9 우선 → 최근사 비율 */
    private fun chooseBestPreviewSize(map: StreamConfigurationMap, viewAspect: Float): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width > 0 && it.height > 0 } ?: return Size(1280, 720)

        val tol = 0.03f

        val nearScreen = sizes.filter {
            val ar = it.width / it.height.toFloat()
            abs(ar - viewAspect) <= tol
        }
        if (nearScreen.isNotEmpty()) return nearScreen.maxBy { it.width * it.height }

        val near169 = sizes.filter {
            val ar = it.width / it.height.toFloat()
            abs(ar - 16f / 9f) <= tol
        }
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
            // 사진 저장용 JPEG 리더
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

            if (forceManual120) startManual120Preview() else startPreviewNormal()
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
        override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
    }

    /** 플랜 A: 일반 세션에서 완전 수동 + 120fps 시도 */
    private fun startManual120Preview() {
        val st = textureView.surfaceTexture ?: return

        // 120fps 성공률을 높이기 위해 과도한 해상도 회피(필요시 조정)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val yuvSizes = map.getOutputSizes(SurfaceTexture::class.java).toList()
        val candidate = yuvSizes
            .filter { it.width > 0 && it.height > 0 }
            .sortedBy { it.width * it.height }
            .lastOrNull { it.width <= 1280 && it.height <= 720 } ?: yuvSizes.minBy { it.width * it.height }

        previewSize = candidate
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)

        val jpegSurface = imageReader!!.surface
        cameraDevice?.createCaptureSession(listOf(previewSurface, jpegSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)

                        // 완전 수동
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

                        // 120fps 강제
                        set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_NS_120)
                        val safeExp = currentExposureNs.coerceAtMost(FRAME_NS_120 - 300_000L)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)

                        set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(isoRange.lower, isoRange.upper))
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))

                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)

                        applyZoom(this)
                    }
                    s.setRepeatingRequest(req.build(), null, bgHandler)
                    textureView.post { applyCenterCropTransform() }

                    // 120 유지 안 되면 일반 프리뷰로 폴백
                    textureView.postDelayed({
                        if (fpsSmoothed < 90.0) {
                            Log.i("CAM", "Manual 120fps seems not achieved (fps=$fpsSmoothed). Fallback to normal preview.")
                            startPreviewNormal()
                        }
                    }, 900)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { startPreviewNormal() }
            }, bgHandler
        )
    }

    /** 일반 자동 프리뷰 */
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
                        applyCommonControls(this, preview = true) // manualEnabled 상태 반영
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
            // ✅ 올바른 EXIF 회전값 넣기
            set(
                CaptureRequest.JPEG_ORIENTATION,
                OrientationUtil.getJpegOrientation(chars, rotation)
            )

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

            // 120fps 강제(수동 모드에서도 유지)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_NS_120)
            val safeExp = currentExposureNs.coerceAtMost(FRAME_NS_120 - 300_000L)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(isoRange.lower, isoRange.upper))
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))

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

    /** 화면 꽉 채우는 센터-크롭(FILL + max scale) */
    /** 풀스크린 Center-Crop (비율 유지, 잘림 감수) */
    fun applyCenterCropTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f || previewSize.width <= 0 || previewSize.height <= 0) return

        val rotation = textureView.display?.rotation ?: Surface.ROTATION_0

        // 회전에 따라 버퍼 크기 결정
        val bufW = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
            previewSize.height.toFloat() else previewSize.width.toFloat()
        val bufH = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
            previewSize.width.toFloat() else previewSize.height.toFloat()

        val viewRect = android.graphics.RectF(0f, 0f, vw, vh)
        val bufferRect = android.graphics.RectF(0f, 0f, bufW, bufH)
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        // 1️⃣ 버퍼를 중앙으로 이동
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

        val matrix = Matrix()

        // 2️⃣ 버퍼 → 뷰 매핑 (기본 변환)
        matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)

        // 3️⃣ 화면 꽉 채우기: 비율 유지, 잘림 허용 (Center Crop)
        val scale = max(vh / bufH, vw / bufW)
        matrix.postScale(scale, scale, centerX, centerY)

        // 4️⃣ 회전 보정
        when (rotation) {
            Surface.ROTATION_90  -> matrix.postRotate(90f, centerX, centerY)
            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
            Surface.ROTATION_270 -> matrix.postRotate(270f, centerX, centerY)
        }

        textureView.setTransform(matrix)
    }


    private fun updateRepeating() {
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)

        val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(previewSurface)
            applyCommonControls(this, preview = true)  // 120fps 강제 포함
            applyColorAuto(this)
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
            // 수동 노출/ISO + 120fps 유지
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, currentIso.coerceIn(isoRange.lower, isoRange.upper))
            val safeExp = currentExposureNs.coerceAtMost(FRAME_NS_120 - 300_000L)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_NS_120)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
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
