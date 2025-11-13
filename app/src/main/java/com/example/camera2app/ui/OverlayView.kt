package com.example.camera2app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x44FFFFFF.toInt()   // 연한 그리드
    }

    // 컨트롤러에서 전달받는 "실제 보이는 카메라 프리뷰 영역"
    private val visibleRect = RectF()
    private var hasRect = false

    /** Camera2Controller 에서 호출해서, 실제 보이는 영역을 알려줌 */
    fun setVisibleRect(rect: RectF) {
        synchronized(visibleRect) {
            visibleRect.set(rect)
            hasRect = true
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasRect) return

        val r = synchronized(visibleRect) { RectF(visibleRect) }

        // 여기서 r 안에 맞춰서 3x3 그리드 그리는 예시
        val thirdW = r.width() / 3f
        val thirdH = r.height() / 3f

        // 세로선 2개
        canvas.drawLine(r.left + thirdW,  r.top, r.left + thirdW,  r.bottom, gridPaint)
        canvas.drawLine(r.left + 2*thirdW, r.top, r.left + 2*thirdW, r.bottom, gridPaint)

        // 가로선 2개
        canvas.drawLine(r.left, r.top + thirdH,  r.right, r.top + thirdH,  gridPaint)
        canvas.drawLine(r.left, r.top + 2*thirdH, r.right, r.top + 2*thirdH, gridPaint)
    }
}
