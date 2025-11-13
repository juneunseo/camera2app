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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
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

    private val TAG_ISO = "overlayIso"
    private val TAG_SHT = "overlayShutter"
    private val TAG_WB = "overlayWb"

    private lateinit var rootFrame: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 화면 전체 루트 FrameLayout (오버레이 붙일 부모)
        rootFrame = findViewById(android.R.id.content) as FrameLayout

        // ─────────────────────────────────────────
        // 상태바 여백 반영: topBar와 fpsText 위치 조정
        // ─────────────────────────────────────────
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewContainer) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            // 상단 바는 상태바 바로 아래
            binding.topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = status
            }

            // FPS는 topBar 바로 아래 왼쪽
            binding.fpsText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                // topBar 높이 56dp + 여유 8dp
                topMargin = status + dp(56 + 8)
            }

            insets
        }

        // ─────────────────────────────────────────
        // Camera2Controller
        // ─────────────────────────────────────────
        controller = Camera2Controller(
            context = this,
            overlayView = binding.overlayView,
            textureView = binding.textureView,
            onFrameLevelChanged = {},
            onSaved = {},
            previewContainer = binding.previewContainer,
            onFpsChanged = { fps ->
                runOnUiThread {
                    binding.fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
                }
            }
        )

        // Aspect 버튼
        binding.btnAspect.setOnClickListener {
            val mode = controller.cycleAspectMode()
            setAspectText(mode)
        }


        // 플래시
        binding.btnFlash.setOnClickListener {
            val next = when (controller.getFlashMode()) {
                Camera2Controller.FlashMode.OFF   -> Camera2Controller.FlashMode.TORCH
                Camera2Controller.FlashMode.TORCH -> Camera2Controller.FlashMode.OFF
                else -> Camera2Controller.FlashMode.OFF
            }
            controller.setFlashMode(next)
            Toast.makeText(this, "Flash: ${flashLabel(next)}", Toast.LENGTH_SHORT).show()
        }

        // 핀치 줌
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

        setupUi()
        requestPermissionsIfNeeded()
    }

    // ─────────────────────────────────────────
    // 기본 UI 버튼들
    // ─────────────────────────────────────────
    private fun setupUi() {
        binding.btnShutter.setOnClickListener { controller.takePicture() }

        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        binding.btnSwitch.setOnClickListener {
            controller.switchCamera()
            controller.setFlashMode(Camera2Controller.FlashMode.OFF)
        }

        binding.btnIso.setOnClickListener { showIsoOverlay() }
        binding.btnSec.setOnClickListener { showShutterOverlay() }
        binding.btnWb.setOnClickListener { showWbOverlay() }
    }

    private fun flashLabel(m: Camera2Controller.FlashMode) = when (m) {
        Camera2Controller.FlashMode.OFF   -> "OFF"
        Camera2Controller.FlashMode.TORCH -> "TORCH"
        else -> "OFF"
    }

    private fun dp(i: Int) = (resources.displayMetrics.density * i + 0.5f).toInt()

    private fun space(wDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(wDp), 1)
    }

    // ─────────────────────────────────────────
    // 오버레이 관리: 하나만 존재하도록
    // ─────────────────────────────────────────
    private fun removeOverlayByTag(tag: String) {
        for (i in 0 until rootFrame.childCount) {
            val v = rootFrame.getChildAt(i)
            if (v.tag == tag) {
                rootFrame.removeView(v)
                return
            }
        }
    }

    private fun removeAllSettingOverlays() {
        val tags = setOf(TAG_ISO, TAG_SHT, TAG_WB)
        val toRemove = mutableListOf<View>()
        for (i in 0 until rootFrame.childCount) {
            val v = rootFrame.getChildAt(i)
            if (v.tag in tags) {
                toRemove.add(v)
            }
        }
        toRemove.forEach { rootFrame.removeView(it) }
    }

    // ─────────────────────────────────────────
    // 공통 오버레이 생성
    // ─────────────────────────────────────────
    @SuppressLint("SetTextI18n")
    private fun makeOverlay(
        tag: String,
        titleText: String,
        initialValueText: String,
        onAuto: () -> Unit,
        onClose: () -> Unit,
        content: (container: LinearLayout, valueText: TextView) -> Unit
    ): LinearLayout {

        val overlay = LinearLayout(this).apply {
            this.tag = tag
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
        }

        // midBar(64dp) + bottomBar(100dp) 위에 딱 붙게
        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(64 + 100)
        }

        // 제목 + AUTO + 닫기
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL

            addView(TextView(this@MainActivity).apply {
                text = titleText
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
            })

            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })

            addView(TextView(this@MainActivity).apply {
                text = "AUTO"
                setTextColor(0xFFBBB3FF.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    onAuto()
                    removeOverlayByTag(tag)
                }
            })

            addView(space(6))

            addView(TextView(this@MainActivity).apply {
                text = "닫기"
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    onClose()
                    removeOverlayByTag(tag)
                }
            })
        }

        val valueText = TextView(this).apply {
            text = initialValueText
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(0, dp(6), 0, dp(4))
        }

        overlay.addView(row)
        overlay.addView(valueText)

        // 슬라이더 등 내용 추가
        content(overlay, valueText)

        // 실제 화면에 추가
        rootFrame.addView(overlay)
        overlay.bringToFront()

        return overlay
    }

    // ─────────────────────────────────────────
    // ISO Overlay
    // ─────────────────────────────────────────
    @SuppressLint("SetTextI18n")
    private fun showIsoOverlay() {
        removeAllSettingOverlays()
        controller.setManualEnabled(true)

        makeOverlay(
            tag = TAG_ISO,
            titleText = "ISO",
            initialValueText = "ISO 400",
            onAuto = { controller.setManualEnabled(false) },
            onClose = { /* no-op */ }
        ) { container, valueText ->

            val maxIso = 3200

            val seek = SeekBar(this@MainActivity).apply {
                max = maxIso
            }

            fun apply(p: Int) {
                val iso = p.coerceIn(50, maxIso)
                valueText.text = "ISO $iso"
                controller.setIso(iso)
            }

            val currentIso = controller.getCurrentIso()
            val initProgress = currentIso.coerceIn(0, maxIso)
            seek.progress = initProgress
            apply(initProgress)

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            container.addView(seek)
        }
    }

    // ─────────────────────────────────────────
    // Shutter Overlay
    // ─────────────────────────────────────────
    @SuppressLint("SetTextI18n")
    private fun showShutterOverlay() {
        removeAllSettingOverlays()
        controller.setManualEnabled(true)

        makeOverlay(
            tag = TAG_SHT,
            titleText = "Shutter Speed",
            initialValueText = "Shutter 1/60 s",
            onAuto = { controller.setManualEnabled(false) },
            onClose = { /* no-op */ }
        ) { container, valueText ->

            val seek = SeekBar(this@MainActivity).apply {
                max = 1000
            }

            fun progressToExposureNs(p: Int): Long {
                val min = 1.0 / 8000.0
                val max = 1.0 / 60.0
                val t = p / 1000.0
                val sec = min * Math.pow(max / min, t)
                return (sec * 1e9).toLong()
            }

            fun exposureNsToProgress(ns: Long): Int {
                val min = 1.0 / 8000.0
                val max = 1.0 / 60.0
                var sec = ns / 1e9
                if (sec < min) sec = min
                if (sec > max) sec = max
                val t = kotlin.math.ln(sec / min) / kotlin.math.ln(max / min)
                return (t * 1000.0).toInt().coerceIn(0, 1000)
            }

            fun apply(p: Int) {
                val requested = progressToExposureNs(p)
                controller.setExposureTimeNs(requested)

                val applied = controller.getAppliedExposureNs()
                val actualSec = applied / 1_000_000_000.0
                valueText.text = "Shutter ${formatAsFraction(actualSec)}"
            }

            val currentNs = controller.getAppliedExposureNs()
            val initProgress = exposureNsToProgress(currentNs)
            seek.progress = initProgress
            apply(initProgress)

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            container.addView(seek)
        }
    }

    // ─────────────────────────────────────────
    // White Balance Overlay
    // ─────────────────────────────────────────
    @SuppressLint("SetTextI18n")
    private fun showWbOverlay() {
        removeAllSettingOverlays()
        controller.setManualEnabled(true)

        makeOverlay(
            tag = TAG_WB,
            titleText = "White Balance (K)",
            initialValueText = "A 4400K",
            onAuto = { controller.setManualEnabled(false) },
            onClose = { /* no-op */ }
        ) { container, valueText ->

            val seek = SeekBar(this@MainActivity).apply {
                max = 8000
            }

            fun apply(p: Int) {
                val k = p.coerceIn(2000, 8000)
                valueText.text = "A ${k}K"
                controller.setAwbTemperature(k)
            }

            val currentK = controller.getCurrentKelvin()
            val initProgress = currentK.coerceIn(0, 8000)
            seek.progress = initProgress
            apply(initProgress)

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            container.addView(seek)
        }
    }

    // ─────────────────────────────────────────
    // 권한 / 라이프사이클
    // ─────────────────────────────────────────
    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needs += Manifest.permission.READ_MEDIA_IMAGES
        else needs += Manifest.permission.READ_EXTERNAL_STORAGE
        Permissions.requestIfNeeded(this, needs.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    // ─────────────────────────────────────────
    // 셔터값 문자열 포맷
    // ─────────────────────────────────────────
    private fun formatAsFraction(sec: Double): String {
        return if (sec >= 1.0) {
            String.format(Locale.US, "%.1f s", sec)
        } else {
            val denom = (1.0 / sec).toInt()
            "1/$denom s"
        }
    }

    private fun setAspectText(mode: Camera2Controller.AspectMode) {
        val text = when (mode) {
            Camera2Controller.AspectMode.RATIO_1_1  -> "1:1"
            Camera2Controller.AspectMode.RATIO_3_4  -> "4:3"
            Camera2Controller.AspectMode.RATIO_9_16 -> "16:9"
            Camera2Controller.AspectMode.FULL       -> "FULL"
        }
        binding.btnAspect.text = text
    }


}
