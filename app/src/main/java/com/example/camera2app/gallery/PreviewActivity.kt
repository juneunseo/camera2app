package com.example.camera2app.gallery

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bumptech.glide.Glide
import com.example.camera2app.databinding.ActivityPreviewBinding

class PreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }

    private lateinit var binding: ActivityPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        val uri = uriStr?.let { Uri.parse(it) }

        if (uri != null) {
            Glide.with(this)
                .load(uri)
                .into(binding.imageFull)
        }

        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
}

