// ==========================
// MainActivity (EV 버전)
// ==========================
package com.example.camera2app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.example.camera2app.camera.Camera2Controller
import com.example.camera2app.databinding.ActivityMainBinding
import com.example.camera2app.gallery.GalleryActivity
import com.example.camera2app.util.Permissions
import java.util.Locale

import android.graphics.RenderEffect
import android.graphics.Shader

import android.view.MotionEvent


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var rootFrame: FrameLayout

    private val TAG_ISO = "overlayIso"
    private val TAG_SHT = "overlayShutter"
    private val TAG_EV = "overlayEv"   // ★ WB → EV 로 변경

    private val TAG_TAP_EV = "tapEvSlider"
    private var tapEvSlider: View? = null


    private var isAllAuto = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rootFrame = findViewById(android.R.id.content) as FrameLayout

        applyWindowInset()
        initCameraController()
        initPinchZoom()
        initButtons()
        setupGlobalAutoButton()

        requestPermissionsIfNeeded()

        setAspectText(Camera2Controller.AspectMode.RATIO_9_16)

        // 🔹 예전 레터박스(maskTop/maskBottom)는 제거했으니
        //     updateMask / initMask 호출도 더 이상 필요 없음.

        setupGlobalAutoButton()
        requestPermissionsIfNeeded()
    }

    // 프리뷰에 블러/디밍 효과 주는 함수
    private fun setPreviewBlur(enabled: Boolean) {
        // S(31) 이상: 진짜 블러
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (enabled) {
                binding.textureView.setRenderEffect(
                    RenderEffect.createBlurEffect(
                        100f, 100f,
                        Shader.TileMode.CLAMP
                    )
                )
            } else {
                binding.textureView.setRenderEffect(null)
            }
        } else {
            // 그 이하 버전: 알파만 살짝 줄여서 페이드 효과
            binding.textureView.alpha = if (enabled) 0.3f else 1f
        }
    }

    private fun applyWindowInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewContainer) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = status
            }
            binding.fpsText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = status + dp(56 + 8)
            }
            insets
        }
    }

    private fun initCameraController() {
        controller = Camera2Controller(
            context = this,
            overlayView = binding.overlayView,
            textureView = binding.textureView,
            onFrameLevelChanged = {},
            onSaved = {},
            previewContainer = binding.previewContainer
        ) { fps ->
            runOnUiThread {
                binding.fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
            }
        }
    }

    private fun initPinchZoom() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    controller.onPinchScale(detector.scaleFactor)
                    return true
                }
            }
        )

        // 🔥 터치 리스너는 overlayView에 단다 (프리뷰 위 레이어)
        binding.overlayView.setOnTouchListener { _, ev ->
            // 핀치 줌
            scaleDetector.onTouchEvent(ev)

            if (ev.actionMasked == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {

                // 프리뷰 중앙 영역을 탭했을 때만 처리되도록 midBar / bottomBar 영역은 무시해도 됨
                toggleTapEvSlider(ev.x, ev.y)
            }

            true  // ← 이거 꼭 true!
        }
    }


    // 프리뷰 탭 시 EV 슬라이더를 열거나 닫는 함수
    private fun toggleTapEvSlider(tapX: Float, tapY: Float) {
        // 이미 떠 있으면 제거 = 토글 동작
        if (tapEvSlider != null) {
            rootFrame.removeView(tapEvSlider)
            tapEvSlider = null
            return
        }

        // AUTO 모드에서는 조절 불가 → 안 띄움
//        if (isAllAuto) return

        if (isAllAuto) {
            isAllAuto = false
            applyGlobalAutoState()   // 여기서 controller.setAllManual() 등 이미 호출됨
        }

        tapEvSlider = createTapEvSlider(tapX, tapY)
        rootFrame.addView(tapEvSlider)
        tapEvSlider?.bringToFront()
    }

    // 실제로 세로 EV 슬라이더 뷰를 만드는 함수
    private fun createTapEvSlider(tapX: Float, tapY: Float): View {
        val container = FrameLayout(this).apply {
            tag = TAG_TAP_EV
            setBackgroundColor(0x00000000) // 필요하면 0x66000000 같이 살짝 배경 주기
        }

        // 가로 SeekBar 하나 만들어서 회전해서 세로처럼 쓰기
        val seek = SeekBar(this).apply {
            max = 800          // 기존 EV 오버레이와 같은 범위: p=400 → EV 0.0
            rotation = -90f    // 세로로 보이게
            progress = 400     // 시작값: EV 0.0

            // ★ EV 슬라이더 thumb 아이콘 추가
            thumb = resources.getDrawable(R.drawable.ic_ev_thumb, null)

            // ★ 트랙(선) 모양 지정
            progressDrawable = resources.getDrawable(R.drawable.ev_slider_progress, null)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (isAllAuto) return
                val ev = (p - 400) / 100.0    // p: 0~800 → EV: -4.0 ~ +4.0
                controller.applyEv(ev)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 세로 길이(px) – 대략 200dp 정도
        val sliderHeight = dp(200)

        // 화면 오른쪽에 붙이고, 탭한 y 근처에 중앙 맞추기
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            sliderHeight
        ).apply {
            gravity = Gravity.END
            rightMargin = dp(16)

            val half = sliderHeight / 2
            val rawTop = tapY.toInt() - half
            // 너무 위/아래로 안 가게 범위 제한
            topMargin = rawTop.coerceIn(
                dp(80),
                binding.previewContainer.height - sliderHeight - dp(80)
            )
        }

        // SeekBar를 컨테이너 안에 꽉 채워서 넣기
        container.addView(
            seek,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        container.layoutParams = lp

        // 슬라이더 바깥쪽(컨테이너) 터치하면 닫히도록
        container.setOnClickListener {
            rootFrame.removeView(container)
            tapEvSlider = null
        }

        return container
    }


    private fun initButtons() {

        binding.btnShutter.setOnClickListener { controller.takePicture() }

        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()
            controller.setFlashMode(Camera2Controller.FlashMode.OFF)
        }

        binding.btnFlash.setOnClickListener {
            val next = when (controller.getFlashMode()) {
                Camera2Controller.FlashMode.OFF -> Camera2Controller.FlashMode.TORCH
                Camera2Controller.FlashMode.TORCH -> Camera2Controller.FlashMode.OFF
                else -> Camera2Controller.FlashMode.OFF
            }
            controller.setFlashMode(next)
            Toast.makeText(this, "Flash: $next", Toast.LENGTH_SHORT).show()
        }

        binding.btnAspect.setOnClickListener {
            // 1) 블러 ON
            setPreviewBlur(true)

            // 2) 비율 전환 (레터박스는 OverlayView에서 애니메이션으로 처리)
            val mode = controller.cycleAspectMode()
            setAspectText(mode)

            // 3) 0.3초 후 블러 해제
            binding.textureView.postDelayed({
                setPreviewBlur(false)
            }, 500L)
        }

        //        binding.btnIso.setOnClickListener { showIsoOverlay() }
        binding.btnSec.setOnClickListener { showShutterOverlay() }
        binding.btnWb.setOnClickListener { showEvOverlay() } // ★ WB 버튼 → EV 슬라이더

        binding.btnResolution.setOnClickListener {
            toggleResolution()
        }
    }

    private fun toggleResolution() {
        val current = controller.getResolutionPreset()

        val next = when (current) {
            Camera2Controller.ResolutionPreset.R12MP -> Camera2Controller.ResolutionPreset.R50MP
            Camera2Controller.ResolutionPreset.R50MP -> Camera2Controller.ResolutionPreset.R12MP
        }

        controller.setResolutionPreset(next)
        binding.btnResolution.text =
            if (next == Camera2Controller.ResolutionPreset.R12MP) "12M" else "50M"
    }

    private fun setupGlobalAutoButton() {
        binding.btnAutoAll.setOnClickListener {
            isAllAuto = !isAllAuto
            applyGlobalAutoState()
        }
        applyGlobalAutoState()
    }

    @SuppressLint("SetTextI18n")
    private fun applyGlobalAutoState() {
        if (isAllAuto) {
            binding.btnAutoAll.text = "AUTO"
            controller.setAllAuto()
            disableOverlaySliders()

            // ★ AUTO로 바꿀 때 탭 EV 슬라이더도 닫기
            tapEvSlider?.let {
                rootFrame.removeView(it)
                tapEvSlider = null
            }

        } else {
            binding.btnAutoAll.text = "MANUAL"
            controller.setAllManual()
            enableOverlaySliders()
        }
    }

    private fun disableOverlaySliders() {
        listOf(TAG_ISO, TAG_SHT, TAG_EV).forEach { tag ->
            findOverlay(tag)?.let { recursiveSetEnabled(it, false) }
        }
    }

    private fun enableOverlaySliders() {
        listOf(TAG_ISO, TAG_SHT, TAG_EV).forEach { tag ->
            findOverlay(tag)?.let { recursiveSetEnabled(it, true) }
        }
    }

    private fun findOverlay(tag: String): View? {
        for (i in 0 until rootFrame.childCount) {
            val v = rootFrame.getChildAt(i)
            if (v.tag == tag) return v
        }
        return null
    }

    private fun recursiveSetEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        if (v is ViewGroup) {
            for (i in 0 until v.childCount)
                recursiveSetEnabled(v.getChildAt(i), enabled)
        }
    }

    private fun makeOverlay(
        tag: String,
        titleText: String,
        initialValueText: String,
        content: (container: LinearLayout, valueText: TextView) -> Unit
    ): LinearLayout {

        val overlay = LinearLayout(this).apply {
            this.tag = tag
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(64 + 100)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = titleText
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val valueText = TextView(this).apply {
            text = initialValueText
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.END
        }

        titleRow.addView(titleView)
        titleRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        titleRow.addView(valueText)

        val sliderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        overlay.addView(titleRow)
        overlay.addView(sliderContainer)

        content(sliderContainer, valueText)

        rootFrame.addView(overlay)
        overlay.bringToFront()

        if (isAllAuto) recursiveSetEnabled(overlay, false)

        return overlay
    }

    // ======================
    // ISO
    // ======================
    private fun showIsoOverlay() {
        removeAllOverlays()

        makeOverlay(
            tag = TAG_ISO,
            titleText = "ISO",
            initialValueText = "—"
        ) { container, valueText ->

            val maxIso = 3200
            val seek = SeekBar(this).apply { max = maxIso }

            fun applyIso(p: Int) {
                if (isAllAuto) return
                val iso = p.coerceIn(50, maxIso)
                valueText.text = "ISO $iso"
                controller.setIso(iso)
            }

            val init = controller.getCurrentIso().coerceIn(0, maxIso)
            seek.progress = init
            applyIso(init)

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) =
                    applyIso(p)

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            container.addView(seek)
        }
    }

    // ======================
    // Shutter
    // ======================
    private fun showShutterOverlay() {
        removeAllOverlays()

        makeOverlay(
            tag = TAG_SHT,
            titleText = "Shutter Speed",
            initialValueText = "—"
        ) { container, valueText ->

            val seek = SeekBar(this).apply { max = 1000 }

            fun pToNs(p: Int): Long {
                val min = 1.0 / 8000.0
                val max = 1.0 / 60.0
                val t = p / 1000.0
                return (min * Math.pow(max / min, t) * 1e9).toLong()
            }

            fun nsToP(ns: Long): Int {
                val min = 1.0 / 8000.0
                val max = 1.0 / 60.0
                var sec = ns / 1e9
                sec = sec.coerceIn(min, max)
                val t = kotlin.math.ln(sec / min) / kotlin.math.ln(max / min)
                return (t * 1000).toInt()
            }

            fun applyShutter(p: Int) {
                if (isAllAuto) return
                val ns = pToNs(p)
                controller.setExposureTimeNs(ns)

                val applied = controller.getAppliedExposureNs()
                val sec = applied / 1_000_000_000.0
                valueText.text = formatAsFraction(sec)
            }

            val init = nsToP(controller.getAppliedExposureNs())
            seek.progress = init
            applyShutter(init)

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) =
                    applyShutter(p)

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            container.addView(seek)
        }
    }

    // ======================
    // EV (WB → EV 로 변경)
    // ======================
    private fun showEvOverlay() {
        removeAllOverlays()

        makeOverlay(
            tag = TAG_EV,
            titleText = "Exposure (EV)",
            initialValueText = "0.0"
        ) { container, valueText ->

            val seek = SeekBar(this).apply { max = 800 }

            fun applyEv(p: Int) {
                if (isAllAuto) return
                val ev = (p - 400) / 100.0
                valueText.text = String.format(Locale.US, "%.1f", ev)
                controller.applyEv(ev)
            }

            // ① 리스너 먼저 붙임
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) =
                    applyEv(p)

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            // ② post 로 attachment 보장 후 progress 적용
            seek.post {
                seek.progress = 400
                applyEv(400)
            }

            container.addView(seek)
        }
    }

    // ======================
    private fun removeAllOverlays() {
        val tags = setOf(TAG_ISO, TAG_SHT, TAG_EV)
        val removeList = mutableListOf<View>()
        for (i in 0 until rootFrame.childCount) {
            val v = rootFrame.getChildAt(i)
            if (v.tag in tags) removeList.add(v)
        }
        removeList.forEach { rootFrame.removeView(it) }
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
        controller.setAllAuto()
        isAllAuto = true
        binding.btnAutoAll.text = "AUTO"
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    private fun dp(i: Int) = (resources.displayMetrics.density * i + 0.5f).toInt()

    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33)
            needs += Manifest.permission.READ_MEDIA_IMAGES
        else
            needs += Manifest.permission.READ_EXTERNAL_STORAGE

        Permissions.requestIfNeeded(this, needs.toTypedArray())
    }

    private fun formatAsFraction(sec: Double): String {
        if (sec >= 1.0) return String.format(Locale.US, "%.1f s", sec)
        val d = (1.0 / sec).toInt()
        return "1/$d s"
    }

    private fun setAspectText(mode: Camera2Controller.AspectMode) {
        binding.btnAspect.text = when (mode) {
            Camera2Controller.AspectMode.RATIO_1_1 -> "1:1"
            Camera2Controller.AspectMode.RATIO_3_4 -> "4:3"
            Camera2Controller.AspectMode.RATIO_9_16 -> "16:9"
        }
    }
}
