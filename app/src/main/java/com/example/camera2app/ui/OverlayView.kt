package com.example.camera2app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.camera2app.camera.Camera2Controller

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x55FFFFFF.toInt()  // 반투명 흰색
    }

    private var aspectMode: Camera2Controller.AspectMode = Camera2Controller.AspectMode.FULL

    /** 외부에서 현재 비율 모드 설정 */
    fun setAspectMode(mode: Camera2Controller.AspectMode) {
        aspectMode = mode
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // --- 비율 맞게 프리뷰 중심으로 그리드 프레임 계산 ---
        val targetRatio = when (aspectMode) {
            Camera2Controller.AspectMode.FULL -> w / h
            Camera2Controller.AspectMode.RATIO_1_1 -> 1f
            Camera2Controller.AspectMode.RATIO_3_4 -> 3f / 4f
            Camera2Controller.AspectMode.RATIO_9_16 -> 9f / 16f
        }

        val viewRatio = w / h
        var frameW = w
        var frameH = h

        if (viewRatio > targetRatio) {
            // 세로가 더 길 때 -> 세로 기준 크롭
            frameW = h * targetRatio
        } else if (viewRatio < targetRatio) {
            // 가로가 더 넓을 때 -> 가로 기준 크롭
            frameH = w / targetRatio
        }

        val left = (w - frameW) / 2f
        val top = (h - frameH) / 2f
        val right = left + frameW
        val bottom = top + frameH

        val frame = RectF(left, top, right, bottom)

        // --- 3x3 격자선 (rule of thirds) ---
        val col1 = frame.left + frame.width() / 3f
        val col2 = frame.left + 2f * frame.width() / 3f
        val row1 = frame.top + frame.height() / 3f
        val row2 = frame.top + 2f * frame.height() / 3f

        // 세로선
        canvas.drawLine(col1, frame.top, col1, frame.bottom, gridPaint)
        canvas.drawLine(col2, frame.top, col2, frame.bottom, gridPaint)

        // 가로선
        canvas.drawLine(frame.left, row1, frame.right, row1, gridPaint)
        canvas.drawLine(frame.left, row2, frame.right, row2, gridPaint)

        // 테두리
        canvas.drawRect(frame, gridPaint)
    }
}
