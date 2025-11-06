package com.example.camera2app.ui


import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View


class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {


    var showRuleOfThirds: Boolean = true
    var levelRoll: Float = 0f // 필요 시 센서 연동


    private val gridPaint = Paint().apply {
        color = 0x66FFFFFF
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }


    private val levelPaint = Paint().apply {
        color = 0xAAFFFFFF.toInt()
        strokeWidth = 4f
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()


        if (showRuleOfThirds) {
// 세로 2줄
            canvas.drawLine(w/3f, 0f, w/3f, h, gridPaint)
            canvas.drawLine(2f*w/3f, 0f, 2f*w/3f, h, gridPaint)
// 가로 2줄
            canvas.drawLine(0f, h/3f, w, h/3f, gridPaint)
            canvas.drawLine(0f, 2f*h/3f, w, 2f*h/3f, gridPaint)
        }


// 수평 가이드 (단순 수평선)
        val y = h/2f
        canvas.drawLine(w*0.2f, y, w*0.8f, y, levelPaint)
    }
}