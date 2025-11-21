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

    // 레터박스(마스크)용 페인트
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt()   // 반투명 검은색 (원하는 색으로 바꿔도 됨)
    }

    // 그리드용 페인트
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

        val w = width.toFloat()
        val h = height.toFloat()

        // ============================
        // 1) 레터박스(마스크) 그리기
        //    → visibleRect 바깥 영역 전부 덮기
        // ============================

        // 위
        canvas.drawRect(0f, 0f, w, r.top, maskPaint)
        // 아래
        canvas.drawRect(0f, r.bottom, w, h, maskPaint)
        // 왼쪽
        canvas.drawRect(0f, r.top, r.left, r.bottom, maskPaint)
        // 오른쪽
        canvas.drawRect(r.right, r.top, w, r.bottom, maskPaint)

        // (여기에서 블러 효과도 쓰고 있으면,
        //  r 기준으로 처리하거나, maskPaint 대신 blur 처리한 결과를 그리면 됨)

        // ============================
        // 2) visibleRect 안에 그리드 그리기
        // ============================

        val thirdW = r.width() / 3f
        val thirdH = r.height() / 3f

        // 세로선 2개
        canvas.drawLine(r.left + thirdW,      r.top, r.left + thirdW,      r.bottom, gridPaint)
        canvas.drawLine(r.left + 2 * thirdW,  r.top, r.left + 2 * thirdW,  r.bottom, gridPaint)

        // 가로선 2개
        canvas.drawLine(r.left, r.top + thirdH,      r.right, r.top + thirdH,      gridPaint)
        canvas.drawLine(r.left, r.top + 2 * thirdH,  r.right, r.top + 2 * thirdH,  gridPaint)
    }
}
