package com.example.camera2app.util


import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


object Permissions {
    fun requestIfNeeded(activity: Activity, perms: Array<String>, reqCode: Int = 1001) {
        val need = perms.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(activity, need.toTypedArray(), reqCode)
    }
}