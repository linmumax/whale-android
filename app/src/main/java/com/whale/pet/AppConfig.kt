package com.whale.pet

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全部可持久化配置。字段语义对齐 dsh-pet 的 main-config.jsonc，
 * 手机端把 web/desktop 合并为「是否在手机桌面显示」。
 */
data class PhysicsConfig(
    var gravity: Float = 1400f,
    var restitution: Float = 0.78f,
    var groundFriction: Float = 2.5f,
    var ceilingBounce: Boolean = true,
    var throwPower: Float = 1f,
    var petCollision: Boolean = false,
)

data class AiConfig(
    var baseUrl: String = "https://api.deepseek.com",
    var apiKey: String = "",
    var model: String = "deepseek-chat",
    var systemPrompt: String = DEFAULT_PERSONA,
    var memoryRounds: Int = 5,
    var whisperImageEnabled: Boolean = true,
    var chatImageEnabled: Boolean = true,
    var chatImageLimit: Int = 10,
) {
    fun usable(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_PERSONA =
            "你是主人手机上的Q版蓝发小女仆鲸鱼娘。说话自然、短短一句，温柔乖巧带点俏皮，" +
                "说人话不啰嗦，不解释自己，不要提你是AI。"
    }
}

data class PetSpec(
    var id: String,
    var name: String,
    /** 宠物窗口宽度占屏幕宽度的比例，0.25 ~ 0.85 */
    var sizeRatio: Float = 0.55f,
    /** top-left / top-right / bottom-left / bottom-right */
    var corner: String = "top-right",
    var marginX: Float = 20f,
    var marginY: Float = 100f,
    /** 用户拖拽后的归一化坐标；null = 使用角落锚点 */
    var xRatio: Float? = null,
    var yRatio: Float? = null,
    var balanceEnabled: Boolean = true,
    var whisperEnabled: Boolean = true,
    /** 宠物固定：位置锁死，不漫游、不拖拽、不甩飞，但动画与互动照常。 */
    var fixedEnabled: Boolean = false,
    var visible: Boolean = true,
)

data class AppConfig(
    var pets: MutableList<PetSpec> = mutableListOf(defaultPet()),
    var physics: PhysicsConfig = PhysicsConfig(),
    var ai: AiConfig = AiConfig(),
    var notificationsEnabled: Boolean = true,
    var whisperIntervalSec: Int = 300,
    var balanceIntervalSec: Int = 1800,
    var insetLeft: Int = 0,
    var insetRight: Int = 0,
    var insetTop: Int = 0,
    var insetBottom: Int = 0,
    var version: Int = 1,
) {
    companion object {
        fun defaultPet(): PetSpec = PetSpec(
            id = "main",
            name = "蓝毛小女仆",
            sizeRatio = 0.55f,
            corner = "top-right",
            marginX = 18f,
            marginY = 104f,
        )
    }
}

/**
 * 配置仓库：JSON 持久化 + 进程内监听，修改后悬浮窗与页面即时刷新。
 */
object ConfigStore {
    private const val PREF = "whale_config_v2"
    private const val KEY_CONFIG = "config_json"

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(AppConfig) -> Unit>()
    private var cached: AppConfig? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        migrateIfNeeded(context.applicationContext)
        cached = read()
    }

    fun get(): AppConfig {
        cached?.let { return it }
        val loaded = read()
        cached = loaded
        return loaded
    }

    fun update(block: (AppConfig) -> Unit): AppConfig {
        val cfg = get()
        block(cfg)
        cached = cfg
        prefs.edit().putString(KEY_CONFIG, toJson(cfg).toString()).apply()
        main.post { listeners.toList().forEach { it(get()) } }
        return cfg
    }

    fun updatePet(id: String, block: (PetSpec) -> Unit) {
        update { cfg -> cfg.pets.firstOrNull { it.id == id }?.let(block) }
    }

    fun savePosition(id: String, xRatio: Float, yRatio: Float) {
        updatePet(id) {
            it.xRatio = xRatio.coerceIn(0f, 1f)
            it.yRatio = yRatio.coerceIn(0f, 1f)
        }
    }

    fun addListener(listener: (AppConfig) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (AppConfig) -> Unit) {
        listeners.remove(listener)
    }

    fun reset(context: Context) {
        val defaults = AppConfig()
        cached = defaults
        prefs.edit().putString(KEY_CONFIG, toJson(defaults).toString()).apply()
        RuntimeStore.resetAll(context)
        main.post { listeners.toList().forEach { it(defaults) } }
    }

    fun saveNow() {
        prefs.edit().putString(KEY_CONFIG, toJson(get()).toString()).apply()
    }

    fun newPet(cfg: AppConfig): PetSpec {
        val n = cfg.pets.size + 1
        val corners = listOf("top-left", "bottom-left", "bottom-right", "top-right")
        return PetSpec(
            id = "pet_" + UUID.randomUUID().toString().take(8),
            name = if (n <= 2) "另一只小鲸鱼" else "小鲸鱼 $n",
            sizeRatio = 0.40f,
            corner = corners[(n - 1) % corners.size],
            marginX = 18f,
            marginY = 120f,
        )
    }

    private fun read(): AppConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return AppConfig()
        return runCatching { fromJson(JSONObject(raw)) }.getOrElse { AppConfig() }
    }

    private fun migrateIfNeeded(context: Context) {
        if (prefs.contains(KEY_CONFIG)) return
        val old = context.getSharedPreferences("whale_pet", Context.MODE_PRIVATE)
        val initial = AppConfig()
        if (old.contains("petName") || old.contains("petWidth") || old.contains("petX")) {
            val dm = context.resources.displayMetrics
            val pet = initial.pets[0]
            pet.name = old.getString("petName", pet.name) ?: pet.name
            val w = old.getInt("petWidth", (dm.widthPixels * pet.sizeRatio).toInt())
            pet.sizeRatio = (w.toFloat() / dm.widthPixels).coerceIn(0.25f, 0.85f)
            val x = old.getInt("petX", Int.MIN_VALUE)
            val y = old.getInt("petY", Int.MIN_VALUE)
            if (x != Int.MIN_VALUE && y != Int.MIN_VALUE) {
                pet.xRatio = (x.toFloat() / dm.widthPixels).coerceIn(0f, 1f)
                pet.yRatio = (y.toFloat() / dm.heightPixels).coerceIn(0f, 1f)
            }
            initial.insetLeft = old.getInt("insetLeft", 0)
            initial.insetRight = old.getInt("insetRight", 0)
            initial.insetTop = old.getInt("insetTop", 0)
            initial.insetBottom = old.getInt("insetBottom", 0)
            RuntimeStore.migrateLegacy(context, "main", old)
        }
        prefs.edit().putString(KEY_CONFIG, toJson(initial).toString()).apply()
    }

    // ---------------- JSON ----------------

    private fun toJson(cfg: AppConfig): JSONObject = JSONObject().apply {
        put("version", cfg.version)
        put("notificationsEnabled", cfg.notificationsEnabled)
        put("whisperIntervalSec", cfg.whisperIntervalSec)
        put("balanceIntervalSec", cfg.balanceIntervalSec)
        put("insetLeft", cfg.insetLeft)
        put("insetRight", cfg.insetRight)
        put("insetTop", cfg.insetTop)
        put("insetBottom", cfg.insetBottom)
        put("physics", JSONObject().apply {
            put("gravity", cfg.physics.gravity.toDouble())
            put("restitution", cfg.physics.restitution.toDouble())
            put("groundFriction", cfg.physics.groundFriction.toDouble())
            put("ceilingBounce", cfg.physics.ceilingBounce)
            put("throwPower", cfg.physics.throwPower.toDouble())
            put("petCollision", cfg.physics.petCollision)
        })
        put("ai", JSONObject().apply {
            put("baseUrl", cfg.ai.baseUrl)
            put("apiKey", cfg.ai.apiKey)
            put("model", cfg.ai.model)
            put("systemPrompt", cfg.ai.systemPrompt)
            put("memoryRounds", cfg.ai.memoryRounds)
            put("whisperImageEnabled", cfg.ai.whisperImageEnabled)
            put("chatImageEnabled", cfg.ai.chatImageEnabled)
            put("chatImageLimit", cfg.ai.chatImageLimit)
        })
        put("pets", JSONArray().apply {
            cfg.pets.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("sizeRatio", p.sizeRatio.toDouble())
                    put("corner", p.corner)
                    put("marginX", p.marginX.toDouble())
                    put("marginY", p.marginY.toDouble())
                    put("xRatio", p.xRatio?.toDouble() ?: JSONObject.NULL)
                    put("yRatio", p.yRatio?.toDouble() ?: JSONObject.NULL)
                    put("balanceEnabled", p.balanceEnabled)
                    put("whisperEnabled", p.whisperEnabled)
                    put("fixedEnabled", p.fixedEnabled)
                    put("visible", p.visible)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): AppConfig {
        val cfg = AppConfig()
        cfg.version = o.optInt("version", 1)
        cfg.notificationsEnabled = o.optBoolean("notificationsEnabled", true)
        cfg.whisperIntervalSec = o.optInt("whisperIntervalSec", 300).coerceIn(30, 3600)
        cfg.balanceIntervalSec = o.optInt("balanceIntervalSec", 1800).coerceIn(300, 86400)
        cfg.insetLeft = o.optInt("insetLeft", 0)
        cfg.insetRight = o.optInt("insetRight", 0)
        cfg.insetTop = o.optInt("insetTop", 0)
        cfg.insetBottom = o.optInt("insetBottom", 0)
        o.optJSONObject("physics")?.let { p ->
            cfg.physics = PhysicsConfig(
                gravity = p.optDouble("gravity", 1400.0).toFloat(),
                restitution = p.optDouble("restitution", 0.78).toFloat(),
                groundFriction = p.optDouble("groundFriction", 2.5).toFloat(),
                ceilingBounce = p.optBoolean("ceilingBounce", true),
                throwPower = p.optDouble("throwPower", 1.0).toFloat(),
                petCollision = p.optBoolean("petCollision", false),
            )
        }
        o.optJSONObject("ai")?.let { a ->
            cfg.ai = AiConfig(
                baseUrl = a.optString("baseUrl", "https://api.deepseek.com"),
                apiKey = a.optString("apiKey", ""),
                model = a.optString("model", "deepseek-chat"),
                systemPrompt = a.optString("systemPrompt", AiConfig.DEFAULT_PERSONA),
                memoryRounds = a.optInt("memoryRounds", 5).coerceIn(0, 30),
                whisperImageEnabled = a.optBoolean("whisperImageEnabled", true),
                chatImageEnabled = a.optBoolean("chatImageEnabled", true),
                chatImageLimit = a.optInt("chatImageLimit", 10).coerceIn(0, 99),
            )
        }
        val pets = mutableListOf<PetSpec>()
        o.optJSONArray("pets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = p.optString("id", "").trim()
                if (id.isBlank()) continue
                pets.add(
                    PetSpec(
                        id = id,
                        name = p.optString("name", id).ifBlank { id },
                        sizeRatio = p.optDouble("sizeRatio", 0.47).toFloat().coerceIn(0.22f, 0.9f),
                        corner = p.optString("corner", "top-right")
                            .takeIf { it in setOf("top-left", "top-right", "bottom-left", "bottom-right") }
                            ?: "top-right",
                        marginX = p.optDouble("marginX", 18.0).toFloat(),
                        marginY = p.optDouble("marginY", 100.0).toFloat(),
                        xRatio = if (p.isNull("xRatio")) null else p.optDouble("xRatio", 0.0).toFloat(),
                        yRatio = if (p.isNull("yRatio")) null else p.optDouble("yRatio", 0.0).toFloat(),
                        balanceEnabled = p.optBoolean("balanceEnabled", true),
                        whisperEnabled = p.optBoolean("whisperEnabled", true),
                        // 旧版的「钉在桌面」已并入「宠物固定」，读档时一并迁移
                        fixedEnabled = p.optBoolean("fixedEnabled", false) ||
                            p.optBoolean("pinned", false),
                        visible = p.optBoolean("visible", true),
                    )
                )
            }
        }
        if (pets.isNotEmpty()) cfg.pets = pets
        return cfg
    }
}
