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
import android.hardware.camera2.params.StreamConfigurationMap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.camera2app.camera.OrientationUtil.getJpegOrientation
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class Camera2Controller(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameLevelChanged: (rollDeg: Float) -> Unit,
    private val onSaved: (Uri) -> Unit,
    private val previewContainer: ViewGroup,          // ← 미리보기 컨테이너(가로 유지, 세로만 변경)
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

    // 수동 조절
    private var manualEnabled = false
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureTimeRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var currentIso = 200
    private var currentExposureNs = 8_000_000L
    private var currentAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO

    // 렌즈 방향
    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    // 원하는 화면비(가로/세로). 기본 4:3
    private var desiredAspect: Float = 4f / 3f

    fun getExposureCompRange(): Range<Int> = expRange
    fun getCurrentExposureComp(): Int = currentExp

    fun setManualEnabled(b: Boolean) { manualEnabled = b; updateRepeating() }
    fun setIso(v: Int) { currentIso = v.coerceIn(isoRange.lower, isoRange.upper); updateRepeating() }
    fun setExposureTimeNs(ns: Long) { currentExposureNs = ns.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper); updateRepeating() }
    fun setAwbMode(mode: Int) { currentAwbMode = mode; updateRepeating() }

    /** 외부에서 화면비 토글할 때 호출 (예: 4:3, 16:9, 1:1) */
    fun setAspectRatio(ratio: Float) {
        desiredAspect = ratio
        // 컨테이너 세로만 조절(가로는 match_parent), 가운데 정렬
        previewContainer.post {
            val w = previewContainer.width
            if (w > 0) {
                val h = (w / desiredAspect).roundToInt()
                val lp = (previewContainer.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = h
                lp.gravity = Gravity.CENTER
                previewContainer.layoutParams = lp
            }
            applyCenterCropTransform()
        }
        // 카메라 세션도 같은 비율의 사이즈로 재시작
        restartSessionForAspect()
    }

    fun onResume() {
        startBackground()
        if (textureView.isAvailable) openCamera(textureView.width, textureView.height)
        else textureView.surfaceTextureListener = surfaceListener
    }

    fun onPause() {
        closeSession()
        stopBackground()
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera(w, h)
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyCenterCropTransform() }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(viewW: Int, viewH: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        cameraId = findCameraIdFor(lensFacing)
        chars = cameraManager.getCameraCharacteristics(cameraId)
        sensorArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 0, 0)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as StreamConfigurationMap
        // 원하는 비율에 가장 근접한 사이즈 선택
        previewSize = chooseSizeByAspect(
            (map.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))).toList(),
            desiredAspect
        )

        expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        currentExp = currentExp.coerceIn(expRange.lower, expRange.upper)

        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
        exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: exposureTimeRange
        currentIso = currentIso.coerceIn(isoRange.lower, isoRange.upper)
        currentExposureNs = currentExposureNs.coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)

        imageReader?.close()
        imageReader = android.media.ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val buf: ByteBuffer = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                img.close()
                val uri = saveJpeg(bytes); onSaved(uri)
            }, bgHandler)
        }

        cameraManager.openCamera(cameraId, deviceCallback, bgHandler)

        // 컨테이너도 현재 비율로 즉시 갱신
        setAspectRatio(desiredAspect)
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
            startPreview()
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
                        applyColorAuto(this)                 // 노란화면 방지
                    }
                    s.setRepeatingRequest(req.build(), captureCallback, bgHandler)
                    textureView.post { applyCenterCropTransform() } // 센터 크롭
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler)
    }

    private fun restartSessionForAspect() {
        if (!this::chars.isInitialized || cameraDevice == null) return
        closeOnlySession()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        previewSize = chooseSizeByAspect(
            map.getOutputSizes(SurfaceTexture::class.java).toList(),
            desiredAspect
        )
        imageReader?.close()
        imageReader = android.media.ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes); img.close()
                onSaved(saveJpeg(bytes))
            }, bgHandler)
        }
        startPreview()
    }

    // 후보 중에서 비율이 가장 가까운 사이즈 선택(해상도는 큰 것 우선)
    private fun chooseSizeByAspect(candidates: List<Size>, aspect: Float): Size {
        val sorted = candidates
            .filter { it.width > 0 && it.height > 0 }
            .sortedByDescending { it.width * it.height }
        return sorted.minByOrNull { abs(it.width / it.height.toFloat() - aspect) } ?: sorted.first()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {}

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

    /** 노란 화면 방지용: 자동 색상/화이트밸런스 강제 */
    private fun applyColorAuto(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
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
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExp)
        }
        applyZoom(builder)
    }

    fun setZoom(zoomX: Float) { currentZoom = zoomX.coerceIn(1f, maxZoom()); updateRepeating() }
    fun setExposureCompensation(value: Int) { currentExp = value.coerceIn(expRange.lower, expRange.upper); updateRepeating() }

    private fun updateRepeating() {
        val st = textureView.surfaceTexture ?: return
        val previewSurface = Surface(st)
        val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(previewSurface)
            applyCommonControls(this, preview = true)
            applyColorAuto(this)
        } ?: return
        session?.setRepeatingRequest(req.build(), captureCallback, bgHandler)
        textureView.post { applyCenterCropTransform() }
    }

    /** 프리뷰 정중앙 기준 센터 크롭 */
    fun applyCenterCropTransform() {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f ||
            previewSize.width <= 0 || previewSize.height <= 0) return

        val bufferW = previewSize.width.toFloat()
        val bufferH = previewSize.height.toFloat()

        // view를 꽉 채우도록 scale 선택(가로는 유지, 세로 크롭/패드)
        val scale = max(viewW / bufferW, viewH / bufferH)
        val scaledW = bufferW * scale
        val scaledH = bufferH * scale
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        textureView.setTransform(matrix)
    }

    private fun maxZoom(): Float {
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return max(1f, maxZoom)
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val zoom = currentZoom
        val cropW = (sensorArray.width() / zoom).toInt()
        val cropH = (sensorArray.height() / zoom).toInt()
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

    private fun closeOnlySession() {
        session?.close(); session = null
    }

    private fun closeSession() {
        session?.close(); session = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        closeSession()
        val w = textureView.width; val h = textureView.height
        if (w > 0 && h > 0) openCamera(w, h)
        else textureView.surfaceTextureListener = surfaceListener
    }
}
