package com.whale.pet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 一只鲸鱼娘的全部运行时：透明视频、随机动画链、漫游、拖拽弹簧、甩抛物理、
 * 挤压回弹、点击积分、气泡、养成与菜单入口。
 */
class PetWindow(
    private val service: PetOverlayService,
    initialSpec: PetSpec,
) {
    val petId: String = initialSpec.id
    // 必须持有快照：ConfigStore 的配置是可变单例，设置页会原地改字段，
    // 若这里直接引用同一个对象，updateSpec 里所有新旧对比都会恒等，改动永远不生效。
    var spec: PetSpec = initialSpec.copy()
        private set

    private val context: Context = service
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val dp = { v: Float -> v * context.resources.displayMetrics.density }
    private val dpi = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

    private var params: WindowManager.LayoutParams? = null
    // 连续（浮点）坐标：速度逐帧积分到小数位。
    // 窗口只有角色大小，移动就是改窗口 x/y，窗口内不再做额外位移。
    private var posXf = 0f
    private var posYf = 0f
    // 抛掷性能诊断（落地后汇总一条日志）
    private var flyFrames = 0
    private var flyStepSum = 0L
    private var flySlow = 0
    private var root: FrameLayout? = null
    /** 真正承载角色的层：窗口本身就是角色大小，stage 填满窗口。 */
    private var stage: FrameLayout? = null
    // 渲染层：WebView（Chromium）直接播放带 alpha 的 VP9 WebM，和电脑端同一套解码器
    private var player: android.webkit.WebView? = null
    private var playerReady = false
    private var pendingPlay: PendingPlay? = null
    private var currentAnim: String = AnimationCatalog.IDLE
    private var currentOnce: Boolean = true
    private var currentEvent: Boolean = false
    private var started = false
    private data class PendingPlay(val name: String, val once: Boolean, val event: Boolean)
    private var widthPx = 0
    private var heightPx = 0
    private var facingRight = false
    private var lastSavedX = -1f
    private var lastSavedY = -1f

    private val bubble = BubbleWindow(context) { bubbleAnchor() }
    private var menu: PetMenuWindow? = null

    // 手势
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var moved = false
    private var dragging = false
    private var longPressTriggered = false
    private var pinching = false
    private var pinchStartDistance = 0f
    private var pinchStartWidth = 0
    private var lastPinchApply = 0L
    private var suppressGestureUntilUp = false
    private var trail = mutableListOf<TrailSample>()
    private var clickPending = false
    private var scoreFiredOnDown = false

    private val longPressRunnable = Runnable {
        if (!moved && !pinching && started) {
            longPressTriggered = true
            dragging = false
            clickPending = false
            handler.removeCallbacks(patRunnable)
            playDragLoopStop()
            openMenu()
        }
    }
    private val patRunnable = Runnable {
        if (!clickPending) return@Runnable
        clickPending = false
        onPat()
    }

    // 拖拽弹簧
    private var dragTargetX = 0f
    private var dragTargetY = 0f
    private var dragVelX = 0f
    private var dragVelY = 0f

    // 抛掷
    private var flying = false
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFrameNs = 0L
    private var frameScheduled = false
    private var pendingLandingSquash: Float? = null
    private var grounded = false
    private var bounceCount = 0

    private companion object {
        /** 落地冲击小于该速度直接停住，不再弹起。 */
        const val BOUNCE_MIN_SPEED = 90f
        /** 一次抛掷最多反弹次数，防止无限弹跳。 */
        const val MAX_BOUNCES = 1
        /** 水平速度低于该值视为完全静止。 */
        const val REST_SPEED_X = 22f
        /** 用户导入素材的虚拟 URL 前缀，由 shouldInterceptRequest 直接读私有目录。 */
        const val CUSTOM_HOST = "https://pet.local/custom/"
    }

    // 漫游
    private var moving = false
    private var moveStartX = 0
    private var moveTargetX = 0
    private var moveStartAt = 0L
    private var moveEndAt = 0L
    private var pendingMove: PendingMove? = null

    // Q 弹
    private var squashStart = 0L
    private var squashDepth = 0.55f
    private var squashActive = false

    // 碎碎念
    private var whisperScheduled = false
    private val whisperRunnable = Runnable {
        whisperScheduled = false
        if (!started || !spec.whisperEnabled) return@Runnable
        if (dragging || flying || moving || menu?.isShowing() == true || service.isChatOpen) {
            scheduleWhisper(20_000L)
            return@Runnable
        }
        triggerWhisper(false)
        scheduleWhisper()
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNs ->
        frameScheduled = false
        val dt = if (lastFrameNs == 0L) 0.016f
        else ((frameTimeNs - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNs = frameTimeNs
        var needFrame = false

        // 位置更新现在只是改 View 的 translation（普通模式不发 IPC），可以跟着 vsync 走
        if (dragging) {
            val power = ConfigStore.get().physics.throwPower.coerceAtLeast(0.05f)
            dragVelX = springStep(dragVelX, posXf, dragTargetX, dt, power)
            dragVelY = springStep(dragVelY, posYf, dragTargetY, dt, power)
            setWindowPosF(posXf + dragVelX * dt, posYf + dragVelY * dt)
            needFrame = true
        }
        if (flying) {
            val stepStart = SystemClock.uptimeMillis()
            stepFlight(dt)
            val cost = SystemClock.uptimeMillis() - stepStart
            flyFrames++
            flyStepSum += cost
            if (cost > 8L) flySlow++
            if (!flying) {
                android.util.Log.d(
                    "WhalePet",
                    "fly done frames=$flyFrames stepAvg=${flyStepSum / flyFrames.coerceAtLeast(1)}ms slow=$flySlow"
                )
            }
            if (flying) needFrame = true
        }
        if (moving) {
            updateMove()
            needFrame = true
        }
        if (squashActive) {
            updateSquash()
            needFrame = squashActive
        }
        if (needFrame) scheduleFrame()
    }

    // ================= 生命周期 =================

    @Suppress("DEPRECATION")
    fun start() {
        if (started) return
        started = true
        val cfg = ConfigStore.get()
        applyInitialGeometry()
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            alpha = 1f
        }
        // 角色层：固定 widthPx x heightPx，移动 = 改 translation，不需要重新布局
        val stageView = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
        }
        val web = android.webkit.WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(PlayerBridge(), "petBridge")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? =
                    serveCustomAnimation(request?.url?.toString())

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    if (!started) return
                    playerReady = true
                    val queued = pendingPlay
                    pendingPlay = null
                    if (queued != null) {
                        switchTo(queued.name, queued.once, queued.event)
                    } else {
                        switchTo(currentAnim, once = currentOnce, event = currentEvent)
                    }
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        player = web
        stageView.addView(web)
        overlay.addView(stageView)
        stage = stageView
        root = overlay
        posXf = geometry.x.toFloat()
        posYf = geometry.y.toFloat()
        // 窗口永远只覆盖角色自身，绝不开全屏透明窗：
        // 1) 每帧只需合成角色大小的一块，双宠物时合成开销从 2×全屏降到 2×角色；
        // 2) 窗口可触摸且 alpha = 1，不会叠加出系统的「不可信触摸」遮挡判定。
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        params = WindowManager.LayoutParams(
            widthPx, heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geometry.x
            y = geometry.y
            // 否则窗口坐标会被系统按状态栏/导航栏内缩，角色会整体下移并贴边被裁掉
            fitInsetsTypes = 0
            // 完全遮挡：整个矩形窗口都吃触摸，下方的点击不会穿透
            alpha = 1f
        }
        setPivot()
        runCatching { wm.addView(overlay, params) }

        // 窗口本身就是触摸目标（角色周边的透明区域也会挡住下层 App 的点击）
        bubble.attachInline(null)
        setupTouch(web)
        switchTo(AnimationCatalog.IDLE, once = true)
        web.loadUrl("file:///android_asset/pet/player.html")
        if (cfg.pets.any { it.id == petId }) scheduleWhisper(60_000L)
        service.refreshForegroundNotification()
    }

    fun stop() {
        if (!started) return
        started = false
        savePosition()
        handler.removeCallbacksAndMessages(null)
        whisperScheduled = false
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        bubble.dismiss()
        menu?.dismiss()
        menu = null
        runCatching { player?.evaluateJavascript("petStop()", null) }
        root?.let { runCatching { wm.removeView(it) } }
        runCatching { player?.destroy() }
        player = null
        playerReady = false
        pendingPlay = null
        root = null
        stage = null
        params = null
        service.refreshForegroundNotification()
    }

    fun updateSpec(newSpec: PetSpec) {
        val old = spec
        spec = newSpec.copy()
        if (!started) return
        if (!spec.visible) {
            stop()
            return
        }
        if (old.visible != spec.visible && spec.visible) {
            start()
            return
        }
        if (abs(old.sizeRatio - spec.sizeRatio) > 0.001f) {
            applySize((service.contentBounds().width() * spec.sizeRatio).toInt(), preserveCenter = true)
        }
        // 机位缓存在 geometry 里，设置页改了角落/边距/归一化坐标后必须重新计算，
        // 否则会拿着旧坐标判断「位置没变」而不做任何事。
        refreshGeometry()
        if (!old.fixedEnabled && spec.fixedEnabled) {
            // 刚打开「宠物固定」：就地停住，不漫游、不拖拽、不甩飞
            stopAllMotion()
            val b = bounds()
            val spanX = (maxWinX(b) - minWinX(b)).coerceAtLeast(1)
            val spanY = (maxWinY(b) - minWinY(b)).coerceAtLeast(1)
            val rx = ((winX() - minWinX(b)).toFloat() / spanX).coerceIn(0f, 1f)
            val ry = ((winY() - minWinY(b)).toFloat() / spanY).coerceIn(0f, 1f)
            // 把「此刻」写进配置，重启后仍停在这个点
            spec = spec.copy(xRatio = rx, yRatio = ry)
            lastSavedX = rx
            lastSavedY = ry
            ConfigStore.savePosition(petId, rx, ry)
            refreshGeometry()
            setWindowPos(geometry.x, geometry.y)
            bubble.updatePosition()
        }
        val anchorOrPosChanged = old.corner != spec.corner ||
            old.marginX != spec.marginX ||
            old.marginY != spec.marginY ||
            old.xRatio != spec.xRatio ||
            old.yRatio != spec.yRatio
        if (anchorOrPosChanged && !dragging && !flying &&
            (abs(winX() - geometry.x) > 2 || abs(winY() - geometry.y) > 2)
        ) {
            // 用户在设置页明确改位置时，即使正在随机走动也立刻停下并回位
            stopMoveDrive()
            setWindowPos(geometry.x, geometry.y)
            savePosition()
            bubble.updatePosition()
        }
        // 全局边界 / 尺寸变化后把位置夹回安全区
        setWindowPosF(posXf, posYf)
        bubble.updatePosition()
        if (old.whisperEnabled != spec.whisperEnabled) {
            if (spec.whisperEnabled) scheduleWhisper() else cancelWhisper()
        }
    }

    fun isVisible(): Boolean = started && params != null

    private var appHidden = false

    fun setAppHidden(hidden: Boolean) {
        if (appHidden == hidden) return
        appHidden = hidden
        if (!started) return
        val p = params ?: return
        if (hidden) {
            bubble.dismiss()
            menu?.dismiss()
            menu = null
            cancelWhisper()
            root?.visibility = View.INVISIBLE
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // alpha 归零，避免隐藏期间还参与系统的遮挡计算
            p.alpha = 0f
            // 看不见的时候没必要继续解码视频，省下双宠物的两份解码开销
            runCatching { player?.evaluateJavascript("petPause()", null) }
        } else {
            root?.visibility = View.VISIBLE
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            p.alpha = 1f
            runCatching { player?.evaluateJavascript("petResume()", null) }
            scheduleWhisper()
        }
        runCatching { wm.updateViewLayout(root, p) }
    }

    // ================= 几何 / 窗口 =================

    private fun bounds(): Rect {
        val b = service.contentBounds()
        val cfg = ConfigStore.get()
        return Rect(
            b.left + cfg.insetLeft,
            b.top + cfg.insetTop,
            b.right - cfg.insetRight,
            b.bottom - cfg.insetBottom,
        )
    }

    private fun applyInitialGeometry() {
        val b = bounds()
        widthPx = (b.width() * spec.sizeRatio).toInt()
            .coerceIn(dpi(120), b.width())
        heightPx = widthPx * 360 / 640
        refreshGeometry()
    }

    /** 按当前 spec 重新计算机位（归一化坐标 / 角落锚点），不改变窗口尺寸。 */
    private fun refreshGeometry() {
        val b = bounds()
        val xR = spec.xRatio
        val yR = spec.yRatio
        geometry = if (xR != null && yR != null) {
            PointI(
                (minWinX(b) + xR * (maxWinX(b) - minWinX(b))).toInt()
                    .coerceIn(minWinX(b), maxWinX(b)),
                (minWinY(b) + yR * (maxWinY(b) - minWinY(b))).toInt()
                    .coerceIn(minWinY(b), maxWinY(b)),
            )
        } else {
            anchorGeometry(b)
        }
    }

    private data class PointI(val x: Int, val y: Int)

    private var geometry = PointI(0, 0)

    private fun minWinX(b: Rect): Int = b.left - hitOffsetX()
    private fun maxWinX(b: Rect): Int =
        (b.right - hitOffsetX() - hitWidth()).coerceAtLeast(minWinX(b))

    private fun minWinY(b: Rect): Int = b.top - hitOffsetY()
    private fun maxWinY(b: Rect): Int =
        (b.bottom - hitOffsetY() - hitHeight()).coerceAtLeast(minWinY(b))

    /**
     * 边距按「角色身体」而不是整块视频画布来算：
     * margin=0 时身体边缘正好贴屏幕边，透明两侧可以伸出屏幕外。
     */
    private fun anchorGeometry(b: Rect): PointI {
        val mx = dpi(spec.marginX.toInt())
        val my = dpi(spec.marginY.toInt())
        val bodyLeft = hitOffsetX()
        val bodyRight = hitOffsetX() + hitWidth()
        val bodyTop = hitOffsetY()
        val bodyBottom = hitOffsetY() + hitHeight()
        val x = if (spec.corner.endsWith("left")) b.left + mx - bodyLeft
        else b.right - mx - bodyRight
        val y = if (spec.corner.startsWith("bottom")) b.bottom - my - bodyBottom
        else b.top + my - bodyTop
        return PointI(
            x.coerceIn(minWinX(b), maxWinX(b)),
            y.coerceIn(minWinY(b), maxWinY(b)),
        )
    }

    private fun hitWidth(): Int = (widthPx * 0.44f).toInt().coerceAtLeast(dpi(70))
    private fun hitHeight(): Int = (heightPx * 0.84f).toInt().coerceAtLeast(dpi(70))
    private fun hitOffsetX(): Int = (widthPx * 0.28f).toInt()
    private fun hitOffsetY(): Int = (heightPx * 0.10f).toInt()

    private fun winX(): Int = Math.round(posXf)
    private fun winY(): Int = Math.round(posYf)

    private fun setWindowPos(x: Int, y: Int) = setWindowPosF(x.toFloat(), y.toFloat())

    /** 把角色位置写到屏幕上：窗口本身就是角色大小，移动窗口即可。 */
    private fun setWindowPosF(
        xf: Float,
        yf: Float,
    ) {
        val p = params ?: return
        val b = bounds()
        // 固定模式：位置只由配置决定，任何物理、拖拽、漫游都不再改变它
        val wantX = if (spec.fixedEnabled) geometry.x.toFloat() else xf
        val wantY = if (spec.fixedEnabled) geometry.y.toFloat() else yf
        val nx = wantX.coerceIn(minWinX(b).toFloat(), maxWinX(b).toFloat())
        val ny = wantY.coerceIn(minWinY(b).toFloat(), maxWinY(b).toFloat())
        posXf = nx
        posYf = ny
        val ix = Math.round(nx)
        val iy = Math.round(ny)
        if (ix != p.x || iy != p.y) {
            p.x = ix
            p.y = iy
            runCatching { wm.updateViewLayout(root, p) }
        }
        bubble.updatePosition()
    }

    private fun applySize(newWidth: Int, preserveCenter: Boolean) {
        val b = bounds()
        val w = newWidth.coerceIn(dpi(110), b.width())
        if (w == widthPx) return
        val p = params ?: return
        val oldW = widthPx
        val oldH = heightPx
        val center = posXf + oldW / 2f
        val bottom = posYf + oldH
        widthPx = w
        heightPx = w * 360 / 640
        stage?.let { st ->
            val lp = st.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(widthPx, heightPx)
            lp.width = widthPx
            lp.height = heightPx
            st.layoutParams = lp
        }
        // 窗口尺寸等于角色尺寸（两种模式一致）
        p.width = widthPx
        p.height = heightPx
        if (preserveCenter) {
            posXf = center - widthPx / 2f
            posYf = bottom - heightPx
        }
        setPivot()
        setWindowPosF(posXf, posYf)
        runCatching { wm.updateViewLayout(root, p) }
        bubble.updatePosition()
        service.refreshForegroundNotification()
    }

    private fun setPivot() {
        stage?.pivotX = widthPx / 2f
        stage?.pivotY = heightPx * 0.94f
    }

    private fun savePosition() {
        if (params == null) return
        val b = bounds()
        val spanX = (maxWinX(b) - minWinX(b)).coerceAtLeast(1)
        val spanY = (maxWinY(b) - minWinY(b)).coerceAtLeast(1)
        val rx = ((winX() - minWinX(b)).toFloat() / spanX).coerceIn(0f, 1f)
        val ry = ((winY() - minWinY(b)).toFloat() / spanY).coerceIn(0f, 1f)
        if (abs(rx - lastSavedX) < 0.0005f && abs(ry - lastSavedY) < 0.0005f) return
        lastSavedX = rx
        lastSavedY = ry
        ConfigStore.savePosition(petId, rx, ry)
    }

    private fun bubbleAnchor(): BubbleWindow.RectI? {
        if (params == null) return null
        return BubbleWindow.RectI(
            winX() + hitOffsetX(),
            winY() + hitOffsetY(),
            hitWidth(),
            hitHeight()
        )
    }

    // ================= 触摸 =================

    private fun setupTouch(view: View) {
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(patRunnable)
                    downRawX = e.rawX
                    downRawY = e.rawY
                    downWinX = winX()
                    downWinY = winY()
                    moved = false
                    dragging = false
                    longPressTriggered = false
                    pinching = false
                    suppressGestureUntilUp = false
                    trail.clear()
                    trail.add(TrailSample(e.eventTime, e.rawX, e.rawY))
                    scoreFiredOnDown = false
                    val grabSpeed = if (flying) hypot(velocityX, velocityY) else 0f
                    if (flying && grabSpeed >= 400f) {
                        val score = scoreFor(grabSpeed, widthPx)
                        service.showScore(e.rawX, e.rawY, score, grabSpeed, widthPx)
                        scoreFiredOnDown = true
                    }
                    if (flying) {
                        flying = false
                        velocityX = 0f
                        velocityY = 0f
                    }
                    stopMoveDrive()
                    handler.postDelayed(longPressRunnable, 500L)
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount == 2) {
                        handler.removeCallbacks(longPressRunnable)
                        handler.removeCallbacks(patRunnable)
                        clickPending = false
                        pinching = true
                        moved = true
                        dragging = false
                        dragVelX = 0f
                        dragVelY = 0f
                        pinchStartDistance = spacing(e).coerceAtLeast(1f)
                        pinchStartWidth = widthPx
                        stopMoveDrive()
                        if (flying) {
                            flying = false
                            velocityX = 0f
                            velocityY = 0f
                        }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (suppressGestureUntilUp) return@setOnTouchListener true
                    if (pinching && e.pointerCount >= 2) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPinchApply > 24L) {
                            lastPinchApply = now
                            val scale = spacing(e) / pinchStartDistance
                            val target = (pinchStartWidth * scale).toInt()
                            val b = bounds()
                            applySize(
                                target.coerceIn(
                                    (b.width() * 0.22f).toInt(),
                                    (b.width() * 0.90f).toInt()
                                ),
                                preserveCenter = true,
                            )
                        }
                    } else if (!pinching) {
                        val dx = e.rawX - downRawX
                        val dy = e.rawY - downRawY
                        if (!moved && hypot(dx, dy) > dp(7f)) {
                            moved = true
                            handler.removeCallbacks(longPressRunnable)
                            clickPending = false
                            // 固定模式：只允许点按/长按，拖动与甩飞全部禁用
                            if (!spec.fixedEnabled) {
                                dragging = true
                                grounded = false
                                bounceCount = 0
                                dragTargetX = posXf
                                dragTargetY = posYf
                                dragVelX = 0f
                                dragVelY = 0f
                                switchTo(AnimationCatalog.DRAG, once = false)
                                scheduleFrame()
                            }
                        }
                        if (moved && !longPressTriggered && !spec.fixedEnabled) {
                            dragTargetX = downWinX + dx
                            dragTargetY = downWinY + dy
                            val b = bounds()
                            // 按「身体」而不是整块视频画布贴边：画布两侧有透明留白，
                            // 若把窗口整体限制在屏内，放大后身体就永远够不到屏幕边缘。
                            dragTargetX = dragTargetX.coerceIn(
                                minWinX(b).toFloat(), maxWinX(b).toFloat()
                            )
                            dragTargetY = dragTargetY.coerceIn(
                                minWinY(b).toFloat(), maxWinY(b).toFloat()
                            )
                            scheduleFrame()
                            val last = trail.lastOrNull()
                            if (last == null || e.eventTime - last.t >= 8L) {
                                trail.add(TrailSample(e.eventTime, e.rawX, e.rawY))
                                if (trail.size > 80) trail.removeAt(0)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (pinching) {
                        // 缩放结束：忽略剩下的那根手指，直到本次手势全部抬起
                        pinching = false
                        suppressGestureUntilUp = true
                        savePinchResult()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (pinching) {
                        pinching = false
                        savePinchResult()
                        suppressGestureUntilUp = false
                        return@setOnTouchListener true
                    }
                    if (suppressGestureUntilUp) {
                        suppressGestureUntilUp = false
                        moved = false
                        dragging = false
                        return@setOnTouchListener true
                    }
                    if (longPressTriggered) {
                        longPressTriggered = false
                        return@setOnTouchListener true
                    }
                    if (moved) {
                        if (dragging) handleRelease(e) else switchTo(AnimationCatalog.IDLE, once = true)
                    } else {
                        handleTap()
                    }
                    moved = false
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun savePinchResult() {
        val b = bounds()
        val p = params ?: return
        ConfigStore.updatePet(petId) {
            it.sizeRatio = (widthPx.toFloat() / b.width()).coerceIn(0.22f, 0.90f)
        }
        savePosition()
        if (currentAnim != AnimationCatalog.IDLE || !isVisible()) {
            switchTo(AnimationCatalog.IDLE, once = true)
        }
    }

    private fun handleTap() {
        if (scoreFiredOnDown) return
        if (clickPending) {
            handler.removeCallbacks(patRunnable)
            clickPending = false
            openMenu()
        } else {
            clickPending = true
            handler.postDelayed(patRunnable, 280L)
        }
    }

    private fun handleRelease(e: MotionEvent) {
        if (params == null || spec.fixedEnabled) return
        if (flying) {
            flying = false
            velocityX = 0f
            velocityY = 0f
        }
        val cfg = ConfigStore.get()
        val v = estimateReleaseVelocity(trail, e.eventTime, cfg.physics)
        if (v != null) {
            velocityX = v.first
            velocityY = v.second
            flying = true
            grounded = false
            bounceCount = 0
            lastFrameNs = 0L
            flyFrames = 0
            flyStepSum = 0L
            flySlow = 0
            showBubble(PetLines.THROWN.random())
            if (currentAnim != AnimationCatalog.DRAG) switchTo(AnimationCatalog.DRAG, once = false)
            scheduleFrame()
        } else {
            savePosition()
            bubble.updatePosition()
            switchTo(AnimationCatalog.IDLE, once = true)
        }
    }

    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 1f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return hypot(dx, dy)
    }

    // ================= 物理 =================

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stepFlight(dt: Float) {
        params ?: return
        if (spec.fixedEnabled) {
            flying = false
            velocityX = 0f
            velocityY = 0f
            return
        }
        val physics = ConfigStore.get().physics
        val b = bounds()

        val minX = minWinX(b).toFloat()
        val maxX = maxWinX(b).toFloat()
        val minY = minWinY(b).toFloat()
        val maxY = maxWinY(b).toFloat()

        // 落地静止后不再施加重力，否则每帧重力分量会在底边反复触发"落地"。
        if (!grounded) velocityY += physics.gravity * dt
        var x = posXf + velocityX * dt
        var y = posYf + velocityY * dt
        var floorImpact = 0f

        if (x < minX) {
            x = minX
            velocityX = abs(velocityX) * physics.restitution
        } else if (x > maxX) {
            x = maxX
            velocityX = -abs(velocityX) * physics.restitution
        }
        if (y < minY) {
            // 手机端始终留在安全区内（天花板必反弹，避免宠物永久飞出视野）
            y = minY
            velocityY = abs(velocityY) * physics.restitution
            grounded = false
        } else if (y >= maxY) {
            y = maxY
            floorImpact = abs(velocityY)
            velocityX *= max(0f, 1f - physics.groundFriction * dt)
            // 冲击不足或已经反弹过，直接静止；低速时不再给竖直速度，避免持续弹跳。
            if (floorImpact < BOUNCE_MIN_SPEED || bounceCount >= MAX_BOUNCES) {
                velocityY = 0f
                grounded = true
            } else {
                bounceCount++
                velocityY = -floorImpact * physics.restitution
                grounded = false
            }
            if (floorImpact > 120f) pendingLandingSquash = landingSquash(floorImpact)
        }

        setWindowPosF(x, y)

        if (physics.petCollision) {
            service.resolveCollision(this)
        }

        val atRest = grounded && abs(velocityY) < 1f && abs(velocityX) < REST_SPEED_X
        if (atRest) {
            flying = false
            velocityX = 0f
            velocityY = 0f
            pendingLandingSquash?.let {
                startSquash(it)
                pendingLandingSquash = null
            }
            savePosition()
            switchTo(AnimationCatalog.IDLE, once = true)
        }
    }

    private fun landingSquash(impact: Float): Float {
        val t = ((impact - 300f) / 1200f).coerceIn(0f, 1f)
        return min(0.8f, 1f - t * 0.45f)
    }

    private fun springStep(v: Float, x: Float, target: Float, dt: Float, power: Float): Float {
        return v + ((target - x) * 200f - v * 30f) * power * dt
    }

    private fun estimateReleaseVelocity(
        samples: List<TrailSample>,
        now: Long,
        physics: PhysicsConfig,
    ): Pair<Float, Float>? {
        if (samples.isEmpty()) return null
        val last = samples.last()
        if (now - last.t > 150L) return null
        val win = samples.filter { now - it.t <= 150L }
        if (win.size < 2) return null
        val t0 = win.first().t
        val x0 = win.first().x
        val y0 = win.first().y
        val t1 = win.last().t
        val x1 = win.last().x
        val y1 = win.last().y
        val span = (t1 - t0).toFloat()
        if (span < 20f) return null
        val baseVx = (x1 - x0) / span * 1000f
        val baseVy = (y1 - y0) / span * 1000f
        val baseSpeed = hypot(baseVx, baseVy)
        if (baseSpeed < 1e-6f) return null
        var px = x0
        var py = y0
        var pt = t0
        val segs = mutableListOf<Pair<Float, Long>>()
        for (i in 1 until win.size) {
            val s = win[i]
            val dt = s.t - pt
            if (dt >= 8L) {
                segs.add((hypot(s.x - px, s.y - py) / dt * 1000f) to s.t)
                px = s.x; py = s.y; pt = s.t
            }
        }
        val peak = segs.maxOfOrNull { it.first } ?: baseSpeed
        var accel = 0f
        if (segs.size >= 2) {
            val lastSeg = segs.last()
            val firstSeg = segs.first()
            accel = (lastSeg.first - firstSeg.first) /
                max((lastSeg.second - firstSeg.second) / 1000f, 0.02f)
        }
        val beforeClamp = ((1f - 0.5f) * baseSpeed + 0.5f * peak) *
            (1f + min(max(accel, 0f) / 8000f, 1f) * 0.6f)
        val clamped = 3600f * (1f - kotlin.math.exp(-beforeClamp / 3600f))
        val speed = clamped * physics.throwPower
        if (speed < 500f) return null
        return Pair(baseVx / baseSpeed * speed, baseVy / baseSpeed * speed)
    }

    private fun scoreFor(speed: Float, size: Int): Int {
        if (speed <= 0f || size <= 0) return 0
        return max(1, Math.round((speed / 100f) * (462f / size)))
    }

    // ================= 动画链 =================

    private fun filtered(pool: List<String>): List<String> =
        pool.filter { AnimationCatalog.exists(context, it) }.ifEmpty { listOf(AnimationCatalog.IDLE) }

    private fun pickNext() {
        if (!started) return
        val cfg = ConfigStore.get()
        // 固定模式的宠物不再转向/走远，只原地活动
        val fixed = spec.fixedEnabled
        val roll = Math.random()
        val idleW = 0.10f
        val turnW = if (fixed) 0f else 0.05f
        val moveW = if (fixed) 0f else 0.05f
        when {
            roll < idleW -> {
                val pool = filtered(AnimationCatalog.idlePool)
                switchTo(AnimationCatalog.randomFrom(pool, currentAnim), once = true)
            }
            roll < idleW + turnW -> {
                val pool = filtered(AnimationCatalog.turnPool)
                switchTo(AnimationCatalog.randomFrom(pool, currentAnim), once = true)
            }
            roll < idleW + turnW + moveW -> {
                if (!tryStartRandomMove()) {
                    switchTo(AnimationCatalog.randomCategoryAction(context, facingRight, currentAnim), once = true)
                }
            }
            else -> {
                switchTo(AnimationCatalog.randomCategoryAction(context, facingRight, currentAnim), once = true)
            }
        }
    }

    fun playAction(name: String) {
        if (!started || name.isBlank()) return
        val resolved = if (AnimationCatalog.exists(context, name)) name else AnimationCatalog.IDLE
        if (AnimationCatalog.isNoMirror(resolved) && facingRight) {
            facingRight = false
            applyFacing()
        }
        stopAllMotion()
        if (AnimationCatalog.isMove(resolved)) {
            playMove(resolved)
            return
        }
        switchTo(resolved, once = true, event = AnimationCatalog.isEvent(resolved))
    }

    fun playMove(name: String) {
        if (!started) return
        if (spec.fixedEnabled) {
            // 固定时走不开，用原地动作代替
            switchTo(AnimationCatalog.IDLE, once = true)
            return
        }
        stopAllMotion()
        val action = AnimationCatalog.moveAction(name)
        val dir = if ((facingRight) != AnimationCatalog.isTurn(currentAnim)) 1 else -1
        val b = bounds()
        val distScale = widthPx / 462f
        val minD = action.minDist * distScale
        val maxD = action.maxDist * distScale
        val dist = minD + Math.random().toFloat() * (maxD - minD)
        val margin = dp(action.margin)
        val center = winX() + widthPx / 2f
        val targetCenter = center + dir * dist
        val bodyHalf = hitWidth() / 2f
        if (targetCenter < b.left + margin + bodyHalf ||
            targetCenter > b.right - margin - bodyHalf
        ) {
            switchTo(AnimationCatalog.IDLE, once = true)
            return
        }
        val targetX = (targetCenter - bodyHalf - hitOffsetX()).toInt()
        pendingMove = PendingMove(action.name, targetX, dir, action.leadSec, action.tailSec)
        val chosen = if (AnimationCatalog.exists(context, name)) name
        else AnimationCatalog.moveActions.map { it.name }.firstOrNull { AnimationCatalog.exists(context, it) }
        if (chosen == null) {
            pendingMove = null
            switchTo(AnimationCatalog.IDLE, once = true)
            return
        }
        switchTo(chosen, once = true)
    }

    private fun tryStartRandomMove(): Boolean {
        val action = AnimationCatalog.moveActions.random()
        playMove(action.name)
        return pendingMove != null && currentAnim == action.name
    }

    private fun stopAllMotion() {
        flying = false
        velocityX = 0f
        velocityY = 0f
        dragging = false
        dragVelX = 0f
        dragVelY = 0f
        stopMoveDrive()
    }

    private fun stopMoveDrive() {
        moving = false
        pendingMove = null
    }

    private fun updateMove() {
        params ?: return
        val now = SystemClock.uptimeMillis()
        if (moveEndAt <= moveStartAt) {
            moving = false
            return
        }
        val f = ((now - moveStartAt).toFloat() / (moveEndAt - moveStartAt)).coerceIn(0f, 1f)
        val x = moveStartX + (moveTargetX - moveStartX) * f
        setWindowPosF(x, posYf)
        if (now >= moveEndAt) {
            setWindowPosF(moveTargetX.toFloat(), posYf)
            moving = false
        }
    }

    private data class PendingMove(
        val anim: String,
        val targetX: Int,
        val dir: Int,
        val leadSec: Float,
        val tailSec: Float,
    )

    // ================= 播放器 =================

    /** JS -> Kotlin 的回调：动画开始 / 播完 / 素材读不出来。 */
    private inner class PlayerBridge {
        @android.webkit.JavascriptInterface
        fun onStarted(name: String, durationMs: Long) {
            handler.post {
                if (started && name == currentAnim) onAnimationStarted(name, durationMs)
            }
        }

        @android.webkit.JavascriptInterface
        fun onEnded(name: String) {
            handler.post {
                android.util.Log.d("WhalePet", "ended $name")
                if (started && name == currentAnim && currentOnce) handleEnded()
            }
        }

        @android.webkit.JavascriptInterface
        fun onError(name: String) {
            handler.post {
                android.util.Log.w("WhalePet", "error loading $name")
                if (!started || name != currentAnim) return@post
                showBubble("这段动画素材读不出来…")
                if (name != AnimationCatalog.IDLE) {
                    switchTo(AnimationCatalog.IDLE, once = true)
                }
            }
        }
    }

    /** 用户导入的素材在私有目录，WebView 的 file:// 读不到，走请求拦截按流返回。 */
    private fun serveCustomAnimation(url: String?): android.webkit.WebResourceResponse? {
        if (url == null || !url.startsWith(CUSTOM_HOST)) return null
        val raw = url.removePrefix(CUSTOM_HOST).substringBefore('?')
        val name = runCatching {
            java.net.URLDecoder.decode(raw, "UTF-8")
        }.getOrDefault(raw)
        val file = File(File(context.filesDir, "custom_animations"), name)
        if (!file.isFile) {
            return android.webkit.WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(ByteArray(0))
            )
        }
        return android.webkit.WebResourceResponse(
            "video/webm", null, 200, "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Content-Length" to file.length().toString(),
                "Accept-Ranges" to "bytes",
            ),
            file.inputStream()
        )
    }

    private fun switchTo(name: String, once: Boolean, event: Boolean = false) {
        if (!started) return
        currentAnim = name
        currentOnce = once
        currentEvent = event
        if (!playerReady) {
            pendingPlay = PendingPlay(name, once, event)
            return
        }
        val custom = AnimationCatalog.isCustom(name)
        android.util.Log.d("WhalePet", "switch -> $name once=$once")
        player?.evaluateJavascript(
            "petPlay(${org.json.JSONObject.quote(name)}, $once, $custom)", null
        )
    }

    private fun onAnimationStarted(name: String, durationMs: Long) {
        if (currentAnim != name) return
        if (name == AnimationCatalog.DRAG) return
        if (pendingMove != null && pendingMove?.anim == name) {
            val pm = pendingMove!!
            moveStartX = winX()
            moveTargetX = pm.targetX
            val now = SystemClock.uptimeMillis()
            moveStartAt = now + (pm.leadSec * 1000).toLong()
            moveEndAt = (now + durationMs - (pm.tailSec * 1000).toLong())
                .coerceAtLeast(now + 400L)
            moving = true
            scheduleFrame()
        }
    }

    private fun handleEnded() {
        if (dragging || flying) return
        if (AnimationCatalog.isTurn(currentAnim)) {
            facingRight = !facingRight
            applyFacing()
        }
        if (moving) {
            moving = false
            pendingMove = null
            savePosition()
        }
        pendingMove = null
        savePosition()
        playNextAfterAnimation()
    }

    private fun playNextAfterAnimation() {
        if (!started) return
        if (currentEvent || AnimationCatalog.isClick(currentAnim) ||
            AnimationCatalog.isDrag(currentAnim) || AnimationCatalog.isWhisper(currentAnim) ||
            currentAnim in AnimationCatalog.balancePool
        ) {
            switchTo(
                filtered(AnimationCatalog.idlePool).random(),
                once = true
            )
        } else {
            pickNext()
        }
    }

    private fun applyFacing() {
        stage?.scaleX = if (facingRight) -1f else 1f
        stage?.scaleY = if (squashActive) stage?.scaleY ?: 1f else 1f
    }

    // ================= Q 弹 =================

    private fun startSquash(depth: Float) {
        squashDepth = depth
        squashStart = SystemClock.uptimeMillis()
        squashActive = true
        scheduleFrame()
    }

    private fun updateSquash() {
        val u = ((SystemClock.uptimeMillis() - squashStart) / 220f).coerceIn(0f, 1f)
        val scale = squashScale(u)
        stage?.scaleY = scale
        if (u >= 1f) {
            squashActive = false
            stage?.scaleY = 1f
            applyFacing()
        }
    }

    private fun squashScale(u: Float): Float {
        if (u < 0.45f) {
            val p = u / 0.45f
            return 1f - (1f - squashDepth) * p * p
        }
        val p = (u - 0.45f) / 0.55f
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val f = 1f + c3 * Math.pow((p - 1f).toDouble(), 3.0).toFloat() +
            c1 * Math.pow((p - 1f).toDouble(), 2.0).toFloat()
        return min(1.12f, squashDepth + (1f - squashDepth) * max(f, 0f))
    }

    // ================= 互动 / 养成 =================

    private fun onPat() {
        if (!started) return
        if (dragging || flying) return
        val reaction = filtered(AnimationCatalog.clicks).random()
        switchTo(reaction, once = true, event = true)
        startSquash(0.55f)
        if (RuntimeStore.canPat(petId)) {
            RuntimeStore.markPat(petId)
            val beforeRank = RuntimeStore.rankIndex(RuntimeStore.affinity(petId))
            RuntimeStore.addAffinity(petId, 1)
            showBubble(PetLines.PET.random())
            val afterRank = RuntimeStore.rankIndex(RuntimeStore.affinity(petId))
            if (afterRank > beforeRank) {
                handler.postDelayed({
                    if (!started) return@postDelayed
                    switchTo("工作状态-雀跃庆祝", once = true, event = true)
                    showBubble(PetLines.levelUp(RuntimeStore.rankName(RuntimeStore.affinity(petId))), 6000L)
                }, 1600L)
            }
        } else {
            showBubble(PetLines.PET_COOLDOWN.random())
        }
    }

    fun feed() {
        if (!started) return
        when {
            !RuntimeStore.canFeed(petId) -> showBubble(PetLines.FEED_COOLDOWN.random())
            RuntimeStore.treats(petId) <= 0 -> showBubble(PetLines.NO_TREATS.random())
            else -> {
                RuntimeStore.consumeTreat(petId)
                RuntimeStore.markFeed(petId)
                switchTo(filtered(AnimationCatalog.feedAnims).random(), once = true, event = true)
                showBubble(PetLines.FEED.random())
                val beforeRank = RuntimeStore.rankIndex(RuntimeStore.affinity(petId))
                RuntimeStore.addAffinity(petId, 5)
                val afterRank = RuntimeStore.rankIndex(RuntimeStore.affinity(petId))
                if (afterRank > beforeRank) {
                    handler.postDelayed({
                        if (!started) return@postDelayed
                        switchTo("工作状态-雀跃庆祝", once = true, event = true)
                        showBubble(PetLines.levelUp(RuntimeStore.rankName(RuntimeStore.affinity(petId))), 6000L)
                    }, 2200L)
                }
            }
        }
    }

    fun showBalance() {
        AiClient.fetchBalance(ConfigStore.get().ai) { result ->
            if (!started) return@fetchBalance
            showBubble(result.text, null, 10_000L)
            if (result.ok && spec.balanceEnabled) {
                val anim = AnimationCatalog.balancePool[result.tier.coerceIn(0, 5)]
                switchTo(anim, once = true, event = true)
            }
        }
    }

    fun triggerWhisper(manual: Boolean) {
        if (!started) return
        if (!manual && !spec.whisperEnabled) return
        if (dragging || flying) {
            if (manual) handler.postDelayed({ triggerWhisper(true) }, 500L)
            return
        }
        ChatManager.whisper(context, petId) { result ->
            if (!started) return@whisper
            showSay(result.text, result.image)
        }
    }

    fun showSay(text: String, image: String? = null) {
        if (!started || text.isBlank() || appHidden) return
        showBubble(text, image, 10_000L)
        if (!dragging && !flying && pendingMove == null) {
            val pool = filtered(AnimationCatalog.whisperPool)
            switchTo(pool.random(), once = true, event = true)
        }
    }

    fun showWorkStatus(index: Int, customText: String? = null) {
        if (!started || appHidden) return
        val i = index.coerceIn(0, 5)
        val text = customText ?: AnimationCatalog.workStatusTexts[i].random()
        showBubble(text, null, if (i >= 4) 8000L else 20_000L)
        val loop = i <= 3
        switchTo(AnimationCatalog.workStatusPool[i], once = !loop, event = true)
    }

    private fun showBubble(text: String, image: String? = null, durationMs: Long = 10_000L) {
        if (appHidden) return
        bubble.show(text, image, durationMs)
    }

    private fun showBubble(text: String, durationMs: Long) {
        if (appHidden) return
        bubble.show(text, null, durationMs)
    }

    fun openMenu() {
        if (!started || appHidden) return
        menu?.dismiss()
        menu = PetMenuWindow(service, this)
        menu?.show()
        service.onMenuOpened(this)
    }

    fun dismissMenu() {
        menu?.dismiss()
        menu = null
    }

    fun closeMenuFromService() {
        menu?.dismiss()
        menu = null
    }

    fun goHome() {
        if (!started) return
        stopAllMotion()
        val b = bounds()
        val g = anchorGeometry(b)
        if (spec.fixedEnabled) {
            val spanX = (maxWinX(b) - minWinX(b)).coerceAtLeast(1)
            val spanY = (maxWinY(b) - minWinY(b)).coerceAtLeast(1)
            val rx = ((g.x - minWinX(b)).toFloat() / spanX).coerceIn(0f, 1f)
            val ry = ((g.y - minWinY(b)).toFloat() / spanY).coerceIn(0f, 1f)
            // 固定时「回原位」= 把新的锚点写进配置，否则下一次定位又会被拉回旧位置
            ConfigStore.updatePet(petId) {
                it.xRatio = rx
                it.yRatio = ry
            }
            geometry = g
        }
        setWindowPos(g.x, g.y)
        savePosition()
        bubble.updatePosition()
        switchTo(AnimationCatalog.IDLE, once = true)
    }

    // ================= 外部物理接口 =================

    fun windowRect(): Rect? {
        if (params == null) return null
        return Rect(
            winX() + hitOffsetX(),
            winY() + hitOffsetY(),
            winX() + hitOffsetX() + hitWidth(),
            winY() + hitOffsetY() + hitHeight()
        )
    }

    fun velocity(): Pair<Float, Float> = Pair(velocityX, velocityY)

    fun isFlying(): Boolean = flying

    fun isFixed(): Boolean = spec.fixedEnabled

    /** 菜单里的「固定在此处 / 解除固定」：固定时把当前位置写成归一化坐标，重启后仍在这个点。 */
    fun toggleFixed() {
        if (!started) return
        if (spec.fixedEnabled) {
            ConfigStore.updatePet(petId) { it.fixedEnabled = false }
            showBubble("又可以到处跑啦～")
            switchTo(AnimationCatalog.IDLE, once = true)
        } else {
            val b = bounds()
            val spanX = (maxWinX(b) - minWinX(b)).coerceAtLeast(1)
            val spanY = (maxWinY(b) - minWinY(b)).coerceAtLeast(1)
            val rx = ((winX() - minWinX(b)).toFloat() / spanX).coerceIn(0f, 1f)
            val ry = ((winY() - minWinY(b)).toFloat() / spanY).coerceIn(0f, 1f)
            ConfigStore.updatePet(petId) {
                it.fixedEnabled = true
                it.xRatio = rx
                it.yRatio = ry
            }
            showBubble("好呀，就固定在这里～")
        }
    }

    fun receiveCollision(vx: Float, vy: Float) {
        if (!started || spec.fixedEnabled) return
        velocityX = vx
        velocityY = vy
        grounded = false
        bounceCount = 0
        flying = true
        lastFrameNs = 0L
        if (currentAnim != AnimationCatalog.DRAG) switchTo(AnimationCatalog.DRAG, once = false)
        scheduleFrame()
    }

    fun setVelocity(vx: Float, vy: Float) {
        if (!started || spec.fixedEnabled) return
        velocityX = vx
        velocityY = vy
        grounded = false
        bounceCount = 0
        if (!flying) {
            flying = true
            lastFrameNs = 0L
            scheduleFrame()
        }
    }

    fun stopForRemoval() {
        stop()
    }

    // ================= 碎碎念调度 =================

    private fun scheduleWhisper(delayOverrideMs: Long? = null) {
        if (whisperScheduled) {
            handler.removeCallbacks(whisperRunnable)
            whisperScheduled = false
        }
        if (!started || !spec.whisperEnabled) return
        val delay = delayOverrideMs
            ?: ((ConfigStore.get().whisperIntervalSec * 1000L) + (0..15_000L).random())
        handler.postDelayed(whisperRunnable, delay.coerceAtLeast(10_000L))
        whisperScheduled = true
    }

    private fun cancelWhisper() {
        whisperScheduled = false
        handler.removeCallbacks(whisperRunnable)
    }

    // 拖拽抓起的动画循环要在松手时立刻停止
    private fun playDragLoopStop() {
        if (currentAnim == AnimationCatalog.DRAG) {
            switchTo(AnimationCatalog.IDLE, once = true)
        }
    }

    private data class TrailSample(val t: Long, val x: Float, val y: Float)
}
