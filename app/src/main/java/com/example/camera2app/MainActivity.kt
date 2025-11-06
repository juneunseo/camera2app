package com.example.camera2app

import android.Manifest
import android.app.AlertDialog
import android.hardware.camera2.CameraMetadata
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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller

    // 화면비 프리셋 (순환)
    private val aspects = arrayOf("4:3", "1:1", "16:9")
    private var aspectIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = Camera2Controller(
            context = this,
            textureView = binding.textureView,
            onFrameLevelChanged = { rollDeg ->
                binding.overlayView.levelRoll = rollDeg
                binding.overlayView.invalidate()
            },
            onSaved = { /* 썸네일 갱신 등 필요 시 처리 */ },
            previewContainer = binding.previewContainer
        )

        setupUi()
        requestPermissionsIfNeeded()
    }

    private fun setupUi() {
        binding.overlayView.showRuleOfThirds = true

        binding.btnShutter.setOnClickListener { controller.takePicture() }
        binding.btnGallery.setOnClickListener { GalleryUtils.openSystemPicker(this) }
        binding.btnSwitch.setOnClickListener { controller.switchCamera() }

        // 상단 빠른 제어
        binding.btnIso.setOnClickListener { showIsoQuickDialog() }
        binding.btnWb.setOnClickListener { showWbQuickDialog() }
        binding.btnSec.setOnClickListener { showShutterQuickDialog() }
        binding.btnAspect.setOnClickListener { cycleAspectRatio() }
    }

    // ---------- Quick dialogs ----------

    private fun showIsoQuickDialog() {
        controller.setManualEnabled(true)
        val seek = SeekBar(this).apply {
            max = 6400
            progress = 400
            setPadding(24, 24, 24, 8)
        }
        val tv = TextView(this).apply { setPadding(24, 0, 24, 16) }
        fun apply(p: Int) {
            val iso = p.coerceIn(50, 6400)
            tv.text = "ISO $iso"
            controller.setIso(iso)
        }
        apply(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = apply(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seek)
            addView(tv)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("ISO")
                .setView(this)
                .setNegativeButton("Auto") { _, _ -> controller.setManualEnabled(false) }
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showShutterQuickDialog() {
        controller.setManualEnabled(true)
        val seek = SeekBar(this).apply {
            max = 1000 // 0..1000 (로그 맵핑)
            progress = 300
            setPadding(24, 24, 24, 8)
        }
        val tv = TextView(this).apply { setPadding(24, 0, 24, 16) }

        fun progressToExposureNs(p: Int): Long {
            val min = 1.25e-4   // 1/8000s
            val max = 0.25      // 1/4s
            val t = p / 1000.0
            val sec = min * Math.pow(max / min, t)
            return (sec * 1e9).toLong()
        }
        fun label(ns: Long): String {
            val s = ns / 1e9
            val denom = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4)
            val near = denom.minBy { kotlin.math.abs(1.0 / it - s) }
            return if (s < 0.9) "1/$near s" else String.format("%.1fs", s)
        }
        fun apply(p: Int) {
            val ns = progressToExposureNs(p)
            tv.text = "Shutter ${label(ns)}"
            controller.setExposureTimeNs(ns)
        }
        apply(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = apply(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seek)
            addView(tv)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Shutter")
                .setView(this)
                .setNegativeButton("Auto") { _, _ -> controller.setManualEnabled(false) }
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showWbQuickDialog() {
        val items = arrayOf("AUTO", "INCANDESCENT", "FLUORESCENT", "DAYLIGHT", "CLOUDY")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, items)
        }
        AlertDialog.Builder(this)
            .setTitle("White balance")
            .setView(spinner)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val mode = when (spinner.selectedItem as String) {
                    "AUTO"         -> CameraMetadata.CONTROL_AWB_MODE_AUTO
                    "INCANDESCENT" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                    "FLUORESCENT"  -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                    "DAYLIGHT"     -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                    else           -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                }
                controller.setAwbMode(mode)
            }.show()
    }

    // ---------- Aspect ratio ----------

    private fun cycleAspectRatio() {
        aspectIndex = (aspectIndex + 1) % aspects.size
        val w = binding.previewContainer.width
        if (w <= 0) return
        val (rw, rh) = when (aspects[aspectIndex]) {
            "1:1"  -> 1 to 1
            "16:9" -> 16 to 9
            else   -> 4 to 3
        }
        setPreviewAspect(w, rw, rh)
        Toast.makeText(this, "Aspect ${aspects[aspectIndex]}", Toast.LENGTH_SHORT).show()
    }

    private fun setPreviewAspect(parentWidth: Int, rw: Int, rh: Int) {
        val targetH = (parentWidth * rh.toFloat() / rw).roundToInt()
        val lp: ViewGroup.LayoutParams = binding.previewContainer.layoutParams
        lp.height = targetH
        binding.previewContainer.layoutParams = lp
    }

    // ---------- Permissions / lifecycle ----------

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
