package com.whale.pet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    const val bgTop = 0xFF071425.toInt()
    const val bgMid = 0xFF0C223A.toInt()
    const val bgBottom = 0xFF050E1A.toInt()
    const val card = 0xE6142C46.toInt()
    const val cardSoft = 0xB3122942.toInt()
    const val cardStrong = 0xFF1A3857.toInt()
    const val accent = 0xFF5BD8FF.toInt()
    const val accentDeep = 0xFF3C9FE8.toInt()
    const val blue = 0xFF4D6BFE.toInt()
    const val mint = 0xFF66E6B8.toInt()
    const val text = 0xFFF2F8FF.toInt()
    const val sub = 0xFFA9C3DD.toInt()
    const val muted = 0xFF6E91B3.toInt()
    const val danger = 0xFFFF8194.toInt()
    const val warm = 0xFFFFCF8A.toInt()
    const val bubble = 0xF8F8FBFF.toInt()
    const val bubbleText = 0xFF24364D.toInt()
}

object UiKit {
    private var typeface: Typeface? = null

    fun typeface(context: Context): Typeface {
        typeface?.let { return it }
        val tf = runCatching {
            Typeface.createFromAsset(context.applicationContext.assets, "fonts/上首软糖体.ttf")
        }.getOrElse { Typeface.DEFAULT }
        typeface = tf
        return tf
    }

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

    fun rounded(
        color: Int,
        radiusDp: Int,
        context: Context,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dpf(radiusDp.toFloat())
        setColor(color)
        if (strokeColor != null) setStroke(context.dp(strokeWidthDp), strokeColor)
    }

    fun gradient(
        context: Context,
        colors: IntArray,
        radiusDp: Int = 0,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR,
    ): GradientDrawable = GradientDrawable(orientation, colors).apply {
        cornerRadius = context.dpf(radiusDp.toFloat())
    }

    fun ripple(context: Context, background: GradientDrawable, rippleColor: Int = 0x33FFFFFF): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), background, null)

    fun textView(
        context: Context,
        text: String = "",
        sizeSp: Float = 14f,
        color: Int = Palette.text,
        bold: Boolean = false,
        gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        this.gravity = gravity
        typeface = if (bold) Typeface.create(UiKit.typeface(context), Typeface.BOLD) else UiKit.typeface(context)
        includeFontPadding = false
    }

    fun button(
        context: Context,
        label: String,
        primary: Boolean = false,
        textSizeSp: Float = 14f,
    ): Button = Button(context).apply {
        text = label
        textSize = textSizeSp
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        typeface = Typeface.create(UiKit.typeface(context), Typeface.BOLD)
        setTextColor(if (primary) 0xFF061425.toInt() else Palette.text)
        val bg = if (primary) {
            gradient(context, intArrayOf(Palette.accent, Palette.accentDeep), 18)
        } else {
            rounded(0xFF173753.toInt(), 16, context, 0x335BD8FF, 1)
        }
        background = ripple(context, bg, if (primary) 0x33000000 else 0x22FFFFFF)
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
        stateListAnimator = null
        elevation = 0f
    }

    fun card(context: Context, radiusDp: Int = 22, fill: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(context, rounded(Palette.card, radiusDp, context, 0x1A8FD9FF, 1))
            setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(16))
            fill()
        }

    fun pill(context: Context, label: String, accent: Boolean = false): TextView = textView(
        context,
        label,
        sizeSp = 11.5f,
        color = if (accent) Palette.accent else Palette.sub,
        bold = accent,
    ).apply {
        background = rounded(if (accent) 0x265BD8FF else 0x221A3857, 999, context)
        setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
    }

    fun divider(context: Context): View = View(context).apply {
        background = rounded(0x1A9DB9D6, 999, context)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            topMargin = context.dp(12)
            bottomMargin = context.dp(12)
        }
    }

    fun spacer(context: Context, dp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(dp))
    }

    fun formatRelativeMs(ms: Long): String {
        if (ms <= 0) return "已满"
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }
}

/** 深海渐变 + 缓慢气泡装饰，纯绘制、零依赖。 */
class OceanBackgroundView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbles = listOf(
        floatArrayOf(0.84f, 0.14f, 26f),
        floatArrayOf(0.12f, 0.28f, 18f),
        floatArrayOf(0.92f, 0.46f, 38f),
        floatArrayOf(0.20f, 0.68f, 14f),
        floatArrayOf(0.78f, 0.78f, 22f),
        floatArrayOf(0.40f, 0.12f, 11f),
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(
            Palette.bgTop, Palette.bgMid, Palette.bgBottom
        ), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)

        bubblePaint.shader = null
        bubbles.forEachIndexed { i, b ->
            val cx = b[0] * w
            val cy = b[1] * h
            val r = UiKit.run { context.dpf(b[2]) }
            bubblePaint.style = Paint.Style.FILL
            bubblePaint.color = if (i % 2 == 0) 0x0F5BD8FF else 0x08FFFFFF
            canvas.drawCircle(cx, cy, r, bubblePaint)
            bubblePaint.style = Paint.Style.STROKE
            bubblePaint.strokeWidth = UiKit.run { context.dpf(1f) }
            bubblePaint.color = 0x225BD8FF
            canvas.drawCircle(cx, cy, r, bubblePaint)
        }

        wavePaint.style = Paint.Style.FILL
        wavePaint.color = 0x0D5BD8FF
        val path = Path()
        path.moveTo(0f, h * 0.88f)
        path.cubicTo(w * 0.18f, h * 0.83f, w * 0.34f, h * 0.94f, w * 0.53f, h * 0.88f)
        path.cubicTo(w * 0.72f, h * 0.82f, w * 0.86f, h * 0.92f, w, h * 0.87f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, wavePaint)

        wavePaint.color = 0x08FFFFFF
        val p2 = Path()
        p2.moveTo(0f, h * 0.94f)
        p2.cubicTo(w * 0.22f, h * 0.90f, w * 0.38f, h * 0.99f, w * 0.58f, h * 0.94f)
        p2.cubicTo(w * 0.76f, h * 0.89f, w * 0.9f, h * 0.97f, w, h * 0.92f)
        p2.lineTo(w, h)
        p2.lineTo(0f, h)
        p2.close()
        canvas.drawPath(p2, wavePaint)
    }
}

/** 圆角遮罩容器，用来给表情包缩略图裁圆角。 */
fun roundedClip(view: View, radiusDp: Int) {
    val r = (radiusDp * view.resources.displayMetrics.density)
    view.outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, v.width, v.height, r)
        }
    }
    view.clipToOutline = true
}
