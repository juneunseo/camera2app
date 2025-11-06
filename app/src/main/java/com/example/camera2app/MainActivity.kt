package com.example.camera2app

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.camera2app.camera.Camera2Controller
import com.example.camera2app.databinding.ActivityMainBinding
import com.example.camera2app.util.GalleryUtils
import com.example.camera2app.util.Permissions
import kotlin.math.abs

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Typeface
import java.util.Locale



class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller

    // 오버레이 구분용 태그
    private val TAG_ISO = "overlay_iso"
    private val TAG_SHT = "overlay_shutter"
    private val TAG_WB  = "overlay_wb"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1) 왼쪽 상단 FPS 라벨 추가 (반투명 검정 바탕)
        val fpsText = TextView(this).apply {
            text = "— FPS"
            setPadding(12, 8, 12, 8)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x66000000.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(12, 12, 12, 12) }
        binding.previewContainer.addView(fpsText, lp)

        // 2) 컨트롤러 생성 시 FPS 콜백 연결
        controller = Camera2Controller(
            context = this,
            textureView = binding.textureView,
            onFrameLevelChanged = { _ -> },   // (그리드 제거)
            onSaved = { /* 필요 시 썸네일 처리 */ },
            previewContainer = binding.previewContainer,
            onFpsChanged = { fps ->
                runOnUiThread {
                    fpsText.text = String.format(Locale.US, "%.1f FPS", fps)
                }
            }
        )

        setupUi()
        requestPermissionsIfNeeded()
    }


    private fun setupUi() {
        binding.btnShutter.setOnClickListener { controller.takePicture() }
        binding.btnGallery.setOnClickListener { GalleryUtils.openSystemPicker(this) }
        binding.btnSwitch.setOnClickListener { controller.switchCamera() }

        // 오버레이 버전으로 교체
        binding.btnIso.setOnClickListener { showIsoOverlay() }
        binding.btnSec.setOnClickListener { showShutterOverlay() }
        binding.btnWb.setOnClickListener  { showWbOverlay() }
    }

    // ---------- 공용 유틸 ----------

    private fun statusBarHeight(): Int {
        val insets = ViewCompat.getRootWindowInsets(binding.root)
        return insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    }


    private fun dp(i: Int): Int =
        (resources.displayMetrics.density * i + 0.5f).toInt()

    private fun removeOverlayByTag(tag: String) {
        val parent = binding.root
        // 루트 뷰 하위에서 tag로 찾기
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            if (tag == v.tag) {
                parent.removeView(v)
                return
            }
        }
    }

    private fun hasOverlay(tag: String): Boolean {
        val parent = binding.root
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).tag == tag) return true
        }
        return false
    }

    // 공통 오버레이 생성 헬퍼
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
            setBackgroundColor(0x99000000.toInt()) // 검은색 투명
            setPadding(dp(16), dp(12), dp(16), dp(12))

            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = statusBarHeight() + dp(8)
                marginStart = dp(8)
                marginEnd  = dp(8)
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

        // 여기서 valueText를 콜백으로 넘겨서 내부에서 직접 갱신하게 함
        content(overlay, valueText)

        return overlay
    }


    // overlay에 저장해둔 valueText 찾기 유틸
    private fun overlayValueText(overlay: View): TextView? =
        overlay.getTag(R.id.contentDescription) as? TextView

    // ---------- ISO Overlay ----------
    @SuppressLint("SetTextI18n")
    private fun showIsoOverlay() {
        val tag = "overlayIso" // 또는 TAG_ISO_PANEL
        if (hasOverlay(tag)) { removeOverlayByTag(tag); return }

        controller.setManualEnabled(true)

        val overlay = makeOverlay(
            tag = tag,
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

        binding.root.addView(overlay)
    }


    // ---------- Shutter Overlay ----------
    @SuppressLint("SetTextI18n")
    private fun showShutterOverlay() {
        val tag = "overlayShutter" // 또는 TAG_SHT_PANEL
        if (hasOverlay(tag)) { removeOverlayByTag(tag); return }

        controller.setManualEnabled(true)

        val overlay = makeOverlay(
            tag = tag,
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
                val min = 1.25e-4   // 1/8000 s
                val max = 0.25      // 1/4 s
                val t = p / 1000.0
                val sec = min * Math.pow(max / min, t)
                return (sec * 1e9).toLong()
            }
            fun label(ns: Long): String {
                val s = ns / 1e9
                val denom = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4)
                val near = denom.minBy { abs(1.0 / it - s) }
                return if (s < 0.9) "1/$near s" else String.format("%.1fs", s)
            }
            fun apply(p: Int) {
                val ns = progressToExposureNs(p)
                valueText.text = "Shutter ${label(ns)}"
                controller.setExposureTimeNs(ns)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            container.addView(seek)
        }

        binding.root.addView(overlay)
    }


    // ---------- White Balance Overlay ----------
    @SuppressLint("SetTextI18n")
    private fun showWbOverlay() {
        val tag = "overlayWb" // 또는 TAG_WB_PANEL
        if (hasOverlay(tag)) { removeOverlayByTag(tag); return }

        controller.setManualEnabled(true)

        val overlay = makeOverlay(
            tag = tag,
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

        binding.root.addView(overlay)
    }


    // ---------- PERMISSIONS ----------
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
