package com.example.camera2app

import android.Manifest
import android.app.AlertDialog
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.camera2app.camera.Camera2Controller
import com.example.camera2app.databinding.ActivityMainBinding
import com.example.camera2app.util.GalleryUtils
import com.example.camera2app.util.Permissions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: Camera2Controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = Camera2Controller(
            context = this,
            textureView = binding.textureView,
            onFrameLevelChanged = { _ -> },   // (그리드 제거)
            onSaved = { /* 필요 시 썸네일 처리 */ },
            previewContainer = binding.previewContainer
        )

        setupUi()
        requestPermissionsIfNeeded()
    }

    private fun setupUi() {
        binding.btnShutter.setOnClickListener { controller.takePicture() }
        binding.btnGallery.setOnClickListener { GalleryUtils.openSystemPicker(this) }
        binding.btnSwitch.setOnClickListener { controller.switchCamera() }

        binding.btnIso.setOnClickListener { showIsoQuickDialog() }
        binding.btnWb.setOnClickListener { showWbQuickDialog() }
        binding.btnSec.setOnClickListener { showShutterQuickDialog() }
        // ⚠️ Aspect 관련 UI/로직 전부 삭제
    }

    private fun showIsoQuickDialog() {
        controller.setManualEnabled(true)
        val seek = SeekBar(this).apply { max = 6400; progress = 400; setPadding(24,24,24,8) }
        val tv = TextView(this).apply { setPadding(24,0,24,16) }
        fun apply(p: Int) {
            val iso = p.coerceIn(50, 6400)
            tv.text = "ISO $iso"
            controller.setIso(iso)
        }
        apply(seek.progress)
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seek); addView(tv)
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
        val seek = SeekBar(this).apply { max = 1000; progress = 300; setPadding(24,24,24,8) }
        val tv = TextView(this).apply { setPadding(24,0,24,16) }

        fun progressToExposureNs(p: Int): Long {
            val min = 1.25e-4; val max = 0.25
            val t = p / 1000.0
            val sec = min * Math.pow(max / min, t)
            return (sec * 1e9).toLong()
        }
        fun label(ns: Long): String {
            val s = ns / 1e9
            val denom = listOf(8000,4000,2000,1000,500,250,125,60,30,15,8,4)
            val near = denom.minBy { kotlin.math.abs(1.0/it - s) }
            return if (s < 0.9) "1/$near s" else String.format("%.1fs", s)
        }
        fun apply(p: Int) {
            val ns = progressToExposureNs(p)
            tv.text = "Shutter ${label(ns)}"
            controller.setExposureTimeNs(ns)
        }
        apply(seek.progress)
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) = apply(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seek); addView(tv)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Shutter")
                .setView(this)
                .setNegativeButton("Auto") { _, _ -> controller.setManualEnabled(false) }
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showWbQuickDialog() {
        val items = arrayOf("AUTO","INCANDESCENT","FLUORESCENT","DAYLIGHT","CLOUDY")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        }
        AlertDialog.Builder(this)
            .setTitle("White balance")
            .setView(spinner)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val mode = when (spinner.selectedItem as String) {
                    "AUTO" -> CameraMetadata.CONTROL_AWB_MODE_AUTO
                    "INCANDESCENT" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                    "FLUORESCENT" -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                    "DAYLIGHT" -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                    else -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                }
                controller.setAwbMode(mode)
            }.show()
    }

    private fun requestPermissionsIfNeeded() {
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needs += Manifest.permission.READ_MEDIA_IMAGES
        else needs += Manifest.permission.READ_EXTERNAL_STORAGE
        Permissions.requestIfNeeded(this, needs.toTypedArray())
    }

    override fun onResume() { super.onResume(); controller.onResume() }
    override fun onPause()  { controller.onPause(); super.onPause() }
}
