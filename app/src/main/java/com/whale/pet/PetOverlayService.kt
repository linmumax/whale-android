package com.whale.pet

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** 多宠物悬浮窗总控：前台服务、配置热更新、多开碰撞、通知与动作路由。 */
class PetOverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.whale.pet.STOP"
        const val ACTION_REBUILD = "com.whale.pet.REBUILD"

        @Volatile
        var instance: PetOverlayService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pets = LinkedHashMap<String, PetWindow>()
    private lateinit var effects: ScoreEffects
    private var activeMenuPetId: String? = null
    private var cfgListener: ((AppConfig) -> Unit)? = null
    private var lastCollisionAt = 0L
    private var lastBalanceScheduleKey: String? = null

    @Volatile
    var isChatOpen: Boolean = false

    @Volatile
    private var appForeground = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        ConfigStore.init(this)
        RuntimeStore.init(this)
        NotificationHelper.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1,
                NotificationHelper.serviceNotification(this, ConfigStore.get().pets.count { it.visible }),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                1,
                NotificationHelper.serviceNotification(this, ConfigStore.get().pets.count { it.visible })
            )
        }
        effects = ScoreEffects(this)
        cfgListener = { cfg -> onConfigChanged(cfg) }
        ConfigStore.addListener(cfgListener!!)
        rebuildAllPets()
        syncBalanceSchedule()
        if (!Settings.canDrawOverlays(this)) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REBUILD -> rebuildAllPets()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cfgListener?.let { ConfigStore.removeListener(it) }
        cfgListener = null
        handler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(balanceRunnable)
        pets.values.toList().forEach { it.stop() }
        pets.clear()
        effects.release()
        instance = null
        isChatOpen = false
        super.onDestroy()
    }

    /**
     * 拖拽/甩飞时每帧都要用它做边界钳制，而 currentWindowMetrics 是一次 Binder 调用，
     * 逐帧取会明显拖慢高刷屏的跟手感，所以缓存一份，屏幕配置变化时再失效。
     */
    private var cachedBounds: Rect? = null

    fun contentBounds(): Rect {
        cachedBounds?.let { return Rect(it) }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = wm.currentWindowMetrics
        val full = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val bounds = Rect(
            full.left + insets.left,
            full.top + insets.top,
            full.right - insets.right,
            full.bottom - insets.bottom,
        )
        cachedBounds = bounds
        return Rect(bounds)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cachedBounds = null
    }

    fun rebuildAllPets() {
        activeMenuPetId = null
        val cfg = ConfigStore.get()
        val ids = cfg.pets.filter { it.visible }.map { it.id }.toSet()
        pets.keys.filter { it !in ids }.forEach { id ->
            pets.remove(id)?.stop()
        }
        cfg.pets.filter { it.visible }.forEach { spec ->
            val existing = pets[spec.id]
            if (existing == null) {
                val win = PetWindow(this, spec)
                pets[spec.id] = win
                win.start()
                win.setAppHidden(appForeground)
            } else {
                existing.updateSpec(spec)
            }
        }
        // 删除宠物后，再清理不可见的窗口
        pets.entries.removeAll { (id, _) -> id !in ids }
        refreshForegroundNotification()
    }

    private fun onConfigChanged(cfg: AppConfig) {
        val ids = cfg.pets.filter { it.visible }.map { it.id }.toSet()
        pets.keys.filter { it !in ids }.forEach { id ->
            pets.remove(id)?.let {
                if (activeMenuPetId == id) activeMenuPetId = null
                it.stop()
            }
        }
        cfg.pets.filter { it.visible }.forEach { spec ->
            val existing = pets[spec.id]
            if (existing == null) {
                val win = PetWindow(this, spec)
                pets[spec.id] = win
                win.start()
                win.setAppHidden(appForeground)
            } else {
                existing.updateSpec(spec)
            }
        }
        refreshForegroundNotification()
        syncBalanceSchedule()
    }

    private val balanceRunnable = object : Runnable {
        override fun run() {
            val cfg = ConfigStore.get()
            if (cfg.ai.usable() && !appForeground) {
                pets.values.filter { it.spec.visible && it.spec.balanceEnabled }
                    .forEach { it.showBalance() }
            }
            handler.postDelayed(this, cfg.balanceIntervalSec.coerceAtLeast(300) * 1000L)
        }
    }

    private fun syncBalanceSchedule() {
        val cfg = ConfigStore.get()
        val key = "${cfg.ai.apiKey}|${cfg.ai.baseUrl}|${cfg.balanceIntervalSec}"
        if (key == lastBalanceScheduleKey) return
        lastBalanceScheduleKey = key
        handler.removeCallbacks(balanceRunnable)
        if (cfg.ai.usable()) {
            handler.postDelayed(balanceRunnable, cfg.balanceIntervalSec.coerceAtLeast(300) * 1000L)
        }
    }

    fun setUiHidden(hidden: Boolean) {
        appForeground = hidden
        pets.values.forEach { it.setAppHidden(hidden) }
    }

    fun refreshForegroundNotification() {
        val count = pets.values.count { it.isVisible() }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    1,
                    NotificationHelper.serviceNotification(this, max(0, count)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, NotificationHelper.serviceNotification(this, max(0, count)))
            }
        }
    }

    fun showScore(x: Float, y: Float, score: Int, speed: Float, size: Int) {
        if (score > 0) effects.burst(x, y, score, speed, size)
    }

    fun onMenuOpened(pet: PetWindow) {
        pets.values.forEach { if (it !== pet) it.closeMenuFromService() }
        activeMenuPetId = pet.petId
    }

    fun openChat(pet: PetWindow) {
        val i = Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_PET_ID, pet.petId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(i) }
    }

    fun openDeepSeek() {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.deepseek.com"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
    }

    /** 多宠物碰撞：飞行中的宠物撞到另一只时按动量守恒弹开。 */
    fun resolveCollision(pet: PetWindow) {
        val cfg = ConfigStore.get()
        if (!cfg.physics.petCollision) return
        if (pet.isFixed()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCollisionAt < 90L) return
        val r1 = pet.windowRect() ?: return
        val otherEntry = pets.entries.firstOrNull { (id, w) ->
            id != pet.petId && w.isVisible() && !w.isFixed() && w.windowRect()?.let { r2 ->
                Rect.intersects(r1, r2)
            } == true
        } ?: return
        val other = otherEntry.value
        val r2 = other.windowRect() ?: return
        val w1 = r1.width().toFloat()
        val w2 = r2.width().toFloat()
        val c1x = r1.centerX().toFloat()
        val c1y = r1.centerY().toFloat()
        val c2x = r2.centerX().toFloat()
        val c2y = r2.centerY().toFloat()
        var nx = c2x - c1x
        var ny = c2y - c1y
        val len = hypot(nx, ny).coerceAtLeast(0.001f)
        nx /= len; ny /= len
        val (v1x, v1y) = pet.velocity()
        val (v2x, v2y) = other.velocity()
        val rel = (v1x - v2x) * nx + (v1y - v2y) * ny
        if (rel <= 0f) return
        val m1 = w1 * w1
        val m2 = w2 * w2
        val e = 0.995f
        val j = -(1f + e) * rel / (1f / m1 + 1f / m2)
        val nv1x = v1x + j / m1 * nx
        val nv1y = v1y + j / m1 * ny
        val nv2x = v2x - j / m2 * nx
        val nv2y = v2y - j / m2 * ny
        pet.setVelocity(nv1x, nv1y)
        other.receiveCollision(nv2x, nv2y)
        lastCollisionAt = now
    }

    fun startChatWork(petId: String) {
        pets[petId]?.showWorkStatus(0)
    }

    fun finishChatWork(petId: String, success: Boolean) {
        pets[petId]?.showWorkStatus(if (success) 4 else 5)
    }

    fun showReplyBubble(petId: String, text: String, image: String?) {
        pets[petId]?.showSay(text, image)
    }

    fun triggerWhisper(petId: String) {
        pets[petId]?.triggerWhisper(true)
    }

    fun feed(petId: String) {
        pets[petId]?.feed()
    }

    fun openMenu(petId: String) {
        pets[petId]?.openMenu()
    }

    fun notifyCompanion(petId: String, text: String) {
        if (!ConfigStore.get().notificationsEnabled) return
        val name = ConfigStore.get().pets.firstOrNull { it.id == petId }?.name ?: "鲸鱼娘"
        NotificationHelper.showChatReply(this, petId, name, text)
    }

    fun updateChatOpen(open: Boolean) {
        isChatOpen = open
    }

    fun setPetSizeRatio(petId: String, ratio: Float) {
        ConfigStore.updatePet(petId) { it.sizeRatio = ratio.coerceIn(0.22f, 0.90f) }
    }

    fun dismissAllMenus() {
        pets.values.forEach { it.closeMenuFromService() }
        activeMenuPetId = null
    }

}
