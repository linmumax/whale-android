package com.whale.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** 飞行中点击宠物：粒子爆开 + 得分卡片。 */
class ScoreEffects(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = { v: Float -> v * context.resources.displayMetrics.density }
    private var view: ScoreView? = null
    private var params: WindowManager.LayoutParams? = null
    private var animator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    // 小窗左上角在屏幕上的位置：粒子坐标是屏幕绝对坐标，绘制时整体平移
    private var offsetX = 0f
    private var offsetY = 0f

    fun burst(x: Float, y: Float, score: Int, speed: Float, size: Int) {
        // 不要用全屏窗：系统会把 NOT_TOUCHABLE 窗口叠加计算遮挡透明度，
        // 两块 0.8 的全屏窗就超过 0.80 上限，整屏触摸都会被当成不可信触摸丢弃。
        // 这里只开一块以爆点为中心的小窗。
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val w = minOf(screenW, dp(760f).toInt())
        val h = minOf(screenH, dp(760f).toInt())
        val left = (x - w / 2f).toInt().coerceIn(0, (screenW - w).coerceAtLeast(0))
        val top = (y - h / 2f).toInt().coerceIn(0, (screenH - h).coerceAtLeast(0))
        offsetX = left.toFloat()
        offsetY = top.toFloat()

        val v = view ?: ScoreView(context).also {
            view = it
            params = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                fitInsetsTypes = 0
            }
            runCatching { wm.addView(it, params) }
        }
        params?.let { p ->
            if (p.x != left || p.y != top || p.width != w || p.height != h) {
                p.x = left
                p.y = top
                p.width = w
                p.height = h
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        v.addBurst(x, y, score, speed, size)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1150L
            addUpdateListener { v.progress = it.animatedValue as Float; v.invalidate() }
            start()
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            view?.let { runCatching { wm.removeView(it) } }
            view = null
            params = null
        }, 1200L)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        animator?.cancel()
        animator = null
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    private data class Particle(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val radius: Float,
        val color: Int,
        val phase: Float,
    )

    private inner class ScoreView(context: Context) : View(context) {
        var progress = 0f
        private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = UiKit.typeface(context)
            textAlign = Paint.Align.CENTER
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = UiKit.typeface(context)
            textAlign = Paint.Align.CENTER
        }
        private val cardRect = RectF()
        private var originX = 0f
        private var originY = 0f
        private var score = 1
        private var speed = 0f
        private var size = 1
        private val parts = mutableListOf<Particle>()

        fun addBurst(x: Float, y: Float, score: Int, speed: Float, size: Int) {
            originX = x
            originY = y
            this.score = score
            this.speed = speed
            this.size = size
            parts.clear()
            val colors = intArrayOf(
                Palette.accent, Palette.mint, Palette.warm, 0xFF7AA8FF.toInt(), 0xFFFF8CB4.toInt()
            )
            repeat(22) {
                val angle = Math.random() * Math.PI * 2
                val v = dp((80 + Math.random() * 260).toFloat())
                parts.add(
                    Particle(
                        x = x,
                        y = y,
                        vx = (Math.cos(angle) * v).toFloat(),
                        vy = (Math.sin(angle) * v - dp(40f)).toFloat(),
                        radius = dp((2.5f + Math.random() * 4.5f).toFloat()),
                        color = colors[(Math.random() * colors.size).toInt()],
                        phase = (Math.random() * 0.3f).toFloat(),
                    )
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val gp = progress
            canvas.save()
            canvas.translate(-offsetX, -offsetY)
            parts.forEach { p ->
                val t = (gp * 1.15f - p.phase).coerceIn(0f, 1f)
                if (t <= 0f) return@forEach
                val x = p.x + p.vx * t
                val y = p.y + p.vy * t + dp(90f) * t * t
                particlePaint.color = p.color
                particlePaint.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, p.radius * (1f - t * 0.55f), particlePaint)
            }
            if (gp > 0.55f) {
                canvas.restore()
                return
            }
            val appear = (gp / 0.12f).coerceAtMost(1f)
            val lift = (1f - gp / 0.55f).coerceAtLeast(0f)
            val w = dp(74f + (score.toString().length - 1).coerceAtLeast(0) * 10f)
            val h = dp(50f)
            val cy = originY - dp(48f) - (1f - lift) * dp(28f)
            val cx = originX.coerceIn(offsetX + dp(50f), offsetX + width - dp(50f))
            cardRect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            cardPaint.color = 0xE81B3A5E.toInt()
            cardPaint.alpha = (appear * 230).toInt()
            canvas.drawRoundRect(cardRect, dp(14f), dp(14f), cardPaint)
            cardPaint.style = Paint.Style.STROKE
            cardPaint.strokeWidth = dp(1.2f)
            cardPaint.color = Palette.accent
            cardPaint.alpha = (appear * 170).toInt()
            canvas.drawRoundRect(cardRect, dp(14f), dp(14f), cardPaint)
            cardPaint.style = Paint.Style.FILL

            textPaint.color = Palette.text
            textPaint.alpha = (appear * 255).toInt()
            textPaint.textSize = dp(24f)
            textPaint.typeface = android.graphics.Typeface.create(UiKit.typeface(context), android.graphics.Typeface.BOLD)
            canvas.drawText("+$score", cx, cy + dp(5f), textPaint)
            subPaint.color = Palette.sub
            subPaint.alpha = (appear * 220).toInt()
            subPaint.textSize = dp(9.5f)
            canvas.drawText("飞行点击得分", cx, cy + dp(19f), subPaint)
            canvas.restore()
        }
    }
}
