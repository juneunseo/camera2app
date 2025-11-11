package com.example.camera2app

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.example.camera2app.camera.Camera2Controller
import com.example.camera2app.databinding.ActivityMainBinding
import com.example.camera2app.util.GalleryUtils
import com.example.camera2app.util.Permissions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller

    // 오버레이 태그(코틀린 네이밍 경고 안 뜨게 카멜케이스 사용)
    private val tagIso = "overlayIso"
    private val tagShutter = "overlayShutter"
    private val tagWb = "overlayWb"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Camera2 컨트롤러 + FPS 콜백 연결
        controller = Camera2Controller(
            context = this,
            textureView = binding.textureView,
            onFrameLevelChanged = { /* no-op */ },
            onSaved = { /* no-op (갤러리 토스트 등 원하면 여기서 처리) */ },
            previewContainer = binding.previewContainer,
            onFpsChanged = { fps ->
                runOnUiThread {
                    binding.fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
                }
            }
        )

        // 상태바 인셋을 topBar와 fpsText에 1회성으로 적용
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewContainer) { _, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = topInset
            }
            binding.fpsText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                // FPS 라벨은 약간 더 내려서 겹침 방지
                topMargin = topInset + dp(8)
            }
            insets
        }

        setupUi()
        requestPermissionsIfNeeded()
    }

    private fun setupUi() {
        binding.btnShutter.setOnClickListener { controller.takePicture() }
        binding.btnGallery.setOnClickListener { GalleryUtils.openSystemPicker(this) }
        binding.btnSwitch.setOnClickListener { controller.switchCamera() }

        binding.btnIso.setOnClickListener { showIsoOverlay() }
        binding.btnSec.setOnClickListener { showShutterOverlay() }
        binding.btnWb.setOnClickListener { showWbOverlay() }
    }

    // ───────────────── 공용 유틸 ─────────────────

    private fun statusBarHeight(): Int {
        val insets = ViewCompat.getRootWindowInsets(binding.root)
        return insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    }

    private fun dp(i: Int): Int =
        (resources.displayMetrics.density * i + 0.5f).toInt()

    private fun removeOverlayByTag(tag: String) {
        val parent = binding.previewContainer
        val toRemove = mutableListOf<View>()
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            if (tag == v.tag) toRemove += v
        }
        toRemove.forEach { parent.removeView(it) }
    }

    private fun removeAllSettingOverlays() {
        removeOverlayByTag(tagIso)
        removeOverlayByTag(tagShutter)
        removeOverlayByTag(tagWb)
    }

    private fun space(wDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(wDp), 1)
    }

    // ────────────── 공통 오버레이 생성 ──────────────
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

            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = statusBarHeight() + dp(8)
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val title = TextView(this@MainActivity).apply {
                text = titleText
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
            }
            addView(title)

            // 스페이서
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })

            addView(TextView(this@MainActivity).apply {
                text = "AUTO"
                setTextColor(0xFFBBB3FF.toInt())
                textSize = 14f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { onAuto(); removeOverlayByTag(tag) }
            })
            addView(space(6))
            addView(TextView(this@MainActivity).apply {
                text = "닫기"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { onClose(); removeOverlayByTag(tag) }
            })
        }

        val valueText = TextView(this).apply {
            text = initialValueText
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(0, dp(6), 0, dp(4))
        }

        overlay.addView(titleRow)
        overlay.addView(valueText)

        content(overlay, valueText)

        ViewCompat.setElevation(overlay, dp(12).toFloat())
        return overlay
    }

    // ────────────── ISO Overlay ──────────────
    @SuppressLint("SetTextI18n")
    private fun showIsoOverlay() {
        removeAllSettingOverlays()
        controller.setManualEnabled(true)

        val overlay = makeOverlay(
            tag = tagIso,
            titleText = "ISO",
            initialValueText = "ISO 400",
            onAuto = { controller.setManualEnabled(false) },
            onClose = { /* no-op */ }
        ) { container, valueText ->
            val seek = SeekBar(this@MainActivity).apply {
                max = 6400
                progress = 400
            }

            fun apply(p: Int) {
                val iso = p.coerceIn(50, 6400)
                valueText.text = "ISO $iso"
                controller.setIso(iso)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            container.addView(seek)
        }

        binding.previewContainer.addView(overlay)
        overlay.bringToFront()
    }

    // ────────────── Shutter Overlay ──────────────
    @SuppressLint("SetTextI18n")
    private fun showShutterOverlay() {
        removeAllSettingOverlays()
        controller.setManualEnabled(true)

        val overlay = makeOverlay(
            tag = tagShutter,
            titleText = "Shutter Speed",
            initialValueText = "Shutter 1/60 s",
            onAuto = { controller.setManualEnabled(false) },
            onClose = { /* no-op */ }
        ) { container, valueText ->
            val seek = SeekBar(this@MainActivity).apply {
                max = 1000
                progress = 300
            }

            fun progressToExposureNs(p: Int): Long {
                // 0..1000 → 0..1 로그 스케일
                val t = p / 1000.0
                val min = 1.25e-4  // 1/8000 s
                val max = 0.25     // 1/4 s
                val sec = min * (max / min).pow(t)
                return (sec * 1e9).toLong()
            }

            fun label(ns: Long): String {
                val s = ns / 1e9
                val denom = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4)
                val near = denom.minBy { d -> abs(1.0 / d - s) }
                return if (s < 0.9) "1/$near s" else String.format(Locale.US, "%.1fs", s)
            }

            fun apply(p: Int) {
                val ns = progressToExposureNs(p)
                valueText.text = "Shutter ${label(ns)}"
                controller.setExposureTimeNs(ns) // 컨트롤러가 120fps 유지 위해 자동 캡
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            container.addView(seek)
        }

        binding.previewContainer.addView(overlay)
        overlay.bringToFront()
    }

    // ────────────── White Balance Overlay ──────────────
    @SuppressLint("SetTextI18n")
    private fun showWbOverlay() {
        removeAllSettingOverlays()
        controller.setManualEnabled(true)

        val overlay = makeOverlay(
            tag = tagWb,
            titleText = "White Balance (K)",
            initialValueText = "A 4400K",
            onAuto = { controller.setManualEnabled(false) },
            onClose = { /* no-op */ }
        ) { container, valueText ->
            val seek = SeekBar(this@MainActivity).apply {
                max = 8000
                progress = 4400
            }

            fun apply(p: Int) {
                val k = p.coerceIn(2000, 8000)
                valueText.text = "A ${k}K"
                controller.setAwbTemperature(k)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            container.addView(seek)
        }

        binding.previewContainer.addView(overlay)
        overlay.bringToFront()
    }

    // ────────────── 권한 ──────────────
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
}
