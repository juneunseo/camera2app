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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var rootFrame: FrameLayout

    private val TAG_ISO = "overlayIso"
    private val TAG_SHT = "overlayShutter"
    private val TAG_EV = "overlayEv"   // ★ WB → EV 로 변경

    private var isAllAuto = true

    private lateinit var maskTop: View
    private lateinit var maskBottom: View

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
        initMask()
    }

    private fun updateMask(mode: Camera2Controller.AspectMode) {
        val previewH = binding.previewContainer.height
        val previewW = binding.previewContainer.width

        val aspect = when (mode) {
            Camera2Controller.AspectMode.RATIO_1_1 -> 1f
            Camera2Controller.AspectMode.RATIO_3_4 -> 3f / 4f
            Camera2Controller.AspectMode.RATIO_9_16 -> 9f / 16f
        }

        val desiredH = (previewW / aspect).toInt()
        val mask = (previewH - desiredH) / 2

        maskTop.layoutParams.height = mask.coerceAtLeast(0)
        maskBottom.layoutParams.height = mask.coerceAtLeast(0)

        maskTop.requestLayout()
        maskBottom.requestLayout()
    }


    private fun initMask() {
        val mask = layoutInflater.inflate(R.layout.overlay_mask, binding.previewContainer, true)
        maskTop = mask.findViewById(R.id.maskTop)
        maskBottom = mask.findViewById(R.id.maskBottom)
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

        binding.textureView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            true
        }
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
            val mode = controller.cycleAspectMode()
            setAspectText(mode)
            updateMask(mode)
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
        binding.btnResolution.text = if (next == Camera2Controller.ResolutionPreset.R12MP) "12M" else "50M"
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