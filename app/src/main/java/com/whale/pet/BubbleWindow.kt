package com.whale.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 跟随宠物的白色对话气泡，可带一张表情包。 */
class BubbleWindow(
    private val context: Context,
    private val anchor: () -> RectI?,
) {
    data class RectI(val x: Int, val y: Int, val w: Int, val h: Int)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

    /** 非空时气泡作为宠物窗口内的一个 View 渲染，跟随零 IPC、零延迟。 */
    private var container: android.view.ViewGroup? = null

    fun attachInline(parent: android.view.ViewGroup?) {
        container = parent
    }

    private var view: FrameLayout? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentW = 0
    private var currentH = 0
    private var tailDown = true
    private var bubbleDrawable: BubbleDrawable? = null
    private var hideRunnable: Runnable? = null
    private var animator: ValueAnimator? = null

    fun show(text: String, image: String? = null, durationMs: Long = 10_000L) {
        if (text.isBlank()) return
        dismiss()
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels

        val root = FrameLayout(context)
        root.isClickable = false
        root.alpha = 0f

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        content = box

        if (!image.isNullOrBlank()) {
            val bmp = MemeCatalog.bitmap(context, image)
            if (bmp != null) {
                val img = ImageView(context).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    layoutParams = LinearLayout.LayoutParams(dp(126), dp(126)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(9)
                    }
                    background = UiKit.rounded(0xFFEAF1F9.toInt(), 15, context)
                    roundedClip(this, 15)
                }
                box.addView(img)
            }
        }

        val tv = UiKit.textView(
            context,
            text,
            sizeSp = 13.5f,
            color = Palette.bubbleText,
            gravity = Gravity.CENTER_HORIZONTAL,
        ).apply {
            maxWidth = minOf(dp(300), (screenW * 0.72f).toInt())
            setLineSpacing(0f, 1.28f)
            if (image.isNullOrBlank()) {
                setPadding(dp(2), dp(1), dp(2), dp(1))
            }
        }
        box.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(box)

        bubbleDrawable = BubbleDrawable(
            fillColor = Palette.bubble,
            strokeColor = 0x2E244A72,
            radius = dp(18).toFloat(),
            tail = dp(9).toFloat(),
            tailDown = tailDown,
            shadow = true,
        )
        applyBubblePadding(root, box)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 和宠物窗口一致：按整屏坐标定位，否则会被状态栏/导航栏内缩顶下来
            fitInsetsTypes = 0
        }

        val widthSpec = View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
        root.measure(widthSpec, heightSpec)
        currentW = root.measuredWidth
        currentH = root.measuredHeight

        view = root
        val host = container
        if (host != null) {
            root.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            host.addView(root)
            root.bringToFront()
        } else {
            runCatching { wm.addView(root, params) }
        }
        updatePosition(force = true)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            // 用 View 自身 alpha 做淡入，避免每帧改 WindowManager 参数触发 relayout
            addUpdateListener { view?.alpha = it.animatedValue as Float }
            start()
        }

        val r = Runnable { dismiss() }
        hideRunnable = r
        handler.postDelayed(r, durationMs)
    }

    private fun applyBubblePadding(root: FrameLayout, box: LinearLayout) {
        root.setPadding(dp(5), if (tailDown) dp(2) else dp(9), dp(5), if (tailDown) dp(9) else dp(2))
        root.background = bubbleDrawable
    }

    /**
     * 跟随宠物移动。内联模式下只是改 View 的 translation；窗口模式下位置没变直接跳过，
     * 变化时才 relayout，避免拖拽时把帧预算挤爆。
     */
    fun updatePosition(force: Boolean = false) {
        val p = params ?: return
        val v = view ?: return
        val a = anchor() ?: return
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val candX = a.x + a.w / 2 - currentW / 2
        val nx = candX.coerceIn(dp(4), (screenW - currentW - dp(4)).coerceAtLeast(dp(4)))
        val aboveY = a.y - currentH + dp(7)
        val wantTailDown = aboveY >= dp(20)
        val ny = if (wantTailDown) {
            aboveY
        } else {
            (a.y + a.h - dp(7)).coerceAtMost((screenH - currentH - dp(8)).coerceAtLeast(dp(8)))
        }
        val inline = container != null
        if (!force && nx == p.x && ny == p.y && wantTailDown == tailDown) return
        if (wantTailDown != tailDown) {
            tailDown = wantTailDown
            bubbleDrawable?.tailDown = wantTailDown
            bubbleDrawable?.invalidateSelf()
            content?.let { applyBubblePadding(v, it) }
        }
        p.x = nx
        p.y = ny
        if (inline) {
            v.translationX = nx.toFloat()
            v.translationY = ny.toFloat()
        } else {
            runCatching { wm.updateViewLayout(v, p) }
        }
    }

    fun dismiss() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        animator?.cancel()
        animator = null
        val v = view
        if (v != null) {
            if (container != null) {
                runCatching { container?.removeView(v) }
            } else {
                runCatching { wm.removeView(v) }
            }
        }
        view = null
        content = null
        params = null
    }

    fun isShowing(): Boolean = view != null

    /** 白色圆角气泡 + 小尾巴 + 柔和阴影，Drawable 自绘，避免外层阴影被窗口裁掉。 */
    private class BubbleDrawable(
        private val fillColor: Int,
        private val strokeColor: Int,
        private val radius: Float,
        private val tail: Float,
        var tailDown: Boolean,
        private val shadow: Boolean,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = strokeColor
            strokeWidth = 1.2f
        }
        private val path = Path()
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val top = if (tailDown) b.top + 2f else b.top + tail
            val bottom = if (tailDown) b.bottom - tail else b.bottom - 2f
            rect.set(b.left + 4f, top, b.right - 4f, bottom)
            if (shadow) {
                canvas.save()
                canvas.translate(0f, 3f)
                paint.alpha = 56
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.alpha = 255
                canvas.restore()
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.drawRoundRect(rect, radius, radius, stroke)
            // 尾巴
            path.reset()
            val cx = b.exactCenterX()
            if (tailDown) {
                path.moveTo(cx - tail, bottom - 1f)
                path.lineTo(cx, bottom + tail)
                path.lineTo(cx + tail, bottom - 1f)
            } else {
                path.moveTo(cx - tail, top + 1f)
                path.lineTo(cx, top - tail)
                path.lineTo(cx + tail, top + 1f)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            stroke.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            stroke.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
