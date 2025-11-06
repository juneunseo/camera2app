package com.example.camera2app.camera


import android.hardware.camera2.CameraCharacteristics


object OrientationUtil {
    fun getJpegOrientation(chars: CameraCharacteristics): Int {
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
// 단순화: 세로 고정 가정(회전 센서 연동 시 보정 필요)
        return (sensorOrientation + 90) % 360
    }
}