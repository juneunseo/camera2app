package com.example.camera2app.camera

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface

object OrientationUtil {

    // 화면 회전값 → 각도(디그리) 매핑
    private val ORIENTATIONS = mapOf(
        Surface.ROTATION_0   to 0,
        Surface.ROTATION_90  to 90,
        Surface.ROTATION_180 to 180,
        Surface.ROTATION_270 to 270
    )

    /**
     * JPEG_ORIENTATION 계산
     * - 센서 고정각 + (기기 회전, 전면이면 부호 반전) 조합
     */
    fun getJpegOrientation(
        chars: CameraCharacteristics,
        displayRotation: Int
    ): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val device = ORIENTATIONS[displayRotation] ?: 0
        val front  = (chars.get(CameraCharacteristics.LENS_FACING)
                == CameraCharacteristics.LENS_FACING_FRONT)

        // 전면은 미러 보정: 회전 부호를 반대로
        val deviceForExif = if (front) -device else device

        // 0..359 로 정규화
        return (sensor + deviceForExif + 360) % 360
    }
}
