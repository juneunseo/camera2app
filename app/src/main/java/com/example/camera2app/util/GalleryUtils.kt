package com.example.camera2app.util


import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.MediaStore


object GalleryUtils {
    fun openSystemPicker(activity: Activity) {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
// 시스템 사진 선택기: 권한 없이도 가능
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        activity.startActivity(intent)
    }
}