package com.whale.pet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 每只宠物的养成状态与聊天记忆。
 * 亲密度 / 小鱼干 / 冷却与 dsh-pet 手机复刻版保持一致。
 */
object RuntimeStore {
    const val TREAT_CAP = 20
    private const val TREAT_INTERVAL_MS = 5 * 3600_000L
    private const val FEED_COOLDOWN_MS = 30_000L
    private const val PAT_COOLDOWN_MS = 10_000L

    private lateinit var prefs: SharedPreferences

    private val RANKS = listOf(
        0 to "幼鲸",
        10 to "伙伴",
        50 to "挚友",
        150 to "深海羁绊",
        400 to "心有灵犀",
        1000 to "传说羁绊",
        2500 to "神话羁绊",
        6000 to "永恒之契",
        15000 to "鲸生共渡",
    )

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("whale_runtime_v2", Context.MODE_PRIVATE)
    }

    fun rankName(points: Int): String {
        var name = RANKS.first().second
        for ((threshold, n) in RANKS) if (points >= threshold) name = n
        return name
    }

    fun rankIndex(points: Int): Int {
        var idx = 0
        RANKS.forEachIndexed { i, (threshold, _) -> if (points >= threshold) idx = i }
        return idx
    }

    fun nextRankThreshold(points: Int): Int? =
        RANKS.firstOrNull { it.first > points }?.first

    // ---------------- 亲密度 ----------------

    fun affinity(petId: String): Int = prefs.getInt("a.$petId", 0)

    fun addAffinity(petId: String, n: Int): Int {
        val v = (affinity(petId) + n).coerceAtLeast(0)
        prefs.edit().putInt("a.$petId", v).apply()
        return v
    }

    // ---------------- 小鱼干 ----------------

    fun treats(petId: String): Int {
        val now = System.currentTimeMillis()
        val stock = prefs.getInt("t.stock.$petId", 5)
        val last = prefs.getLong("t.time.$petId", now)
        if (last <= 0L) {
            prefs.edit().putLong("t.time.$petId", now).apply()
            return stock
        }
        if (stock >= TREAT_CAP) {
            prefs.edit().putLong("t.time.$petId", now).apply()
            return stock
        }
        val gained = ((now - last) / TREAT_INTERVAL_MS).toInt()
        if (gained <= 0) return stock
        val newStock = (stock + gained).coerceAtMost(TREAT_CAP)
        val newTime = if (newStock >= TREAT_CAP) now else last + gained * TREAT_INTERVAL_MS
        prefs.edit().putInt("t.stock.$petId", newStock).putLong("t.time.$petId", newTime).apply()
        return newStock
    }

    fun nextTreatInMs(petId: String): Long {
        if (treats(petId) >= TREAT_CAP) return 0L
        val last = prefs.getLong("t.time.$petId", System.currentTimeMillis())
        return (TREAT_INTERVAL_MS - (System.currentTimeMillis() - last)).coerceAtLeast(0L)
    }

    fun consumeTreat(petId: String): Boolean {
        val n = treats(petId)
        if (n <= 0) return false
        prefs.edit().putInt("t.stock.$petId", n - 1).apply()
        return true
    }

    fun canFeed(petId: String): Boolean =
        System.currentTimeMillis() - prefs.getLong("f.$petId", 0L) >= FEED_COOLDOWN_MS

    fun markFeed(petId: String) {
        prefs.edit().putLong("f.$petId", System.currentTimeMillis()).apply()
    }

    fun canPat(petId: String): Boolean =
        System.currentTimeMillis() - prefs.getLong("p.$petId", 0L) >= PAT_COOLDOWN_MS

    fun markPat(petId: String) {
        prefs.edit().putLong("p.$petId", System.currentTimeMillis()).apply()
    }

    // ---------------- 聊天记忆 ----------------

    fun history(petId: String): MutableList<ChatTurn> {
        val raw = prefs.getString("chat.$petId", null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<ChatTurn>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role", "")
                val text = o.optString("text", "")
                if (role.isBlank() || text.isBlank()) continue
                list.add(ChatTurn(role, text, o.optString("image", "").ifBlank { null }))
            }
            list
        }.getOrElse { mutableListOf() }
    }

    fun appendHistory(petId: String, role: String, text: String, image: String? = null) {
        val list = history(petId)
        list.add(ChatTurn(role, text, image))
        val arr = JSONArray()
        list.takeLast(200).forEach { t ->
            arr.put(JSONObject().apply {
                put("role", t.role)
                put("text", t.text)
                if (!t.image.isNullOrBlank()) put("image", t.image)
            })
        }
        prefs.edit().putString("chat.$petId", arr.toString()).apply()
    }

    fun clearHistory(petId: String) {
        prefs.edit().remove("chat.$petId").apply()
    }

    fun clearAllHistory() {
        prefs.edit().apply {
            for (key in prefs.all.keys.toList()) {
                if (key.startsWith("chat.")) remove(key)
            }
        }.apply()
    }

    fun lastMeme(petId: String): String? =
        prefs.getString("meme.$petId", null)?.takeIf { it.isNotBlank() }

    fun setLastMeme(petId: String, name: String?) {
        if (name.isNullOrBlank()) prefs.edit().remove("meme.$petId").apply()
        else prefs.edit().putString("meme.$petId", name).apply()
    }

    fun resetAll(context: Context) {
        prefs.edit().clear().apply()
    }

    /** 从旧版单宠物配置迁移养成数据。 */
    fun migrateLegacy(context: Context, petId: String, old: SharedPreferences) {
        init(context)
        if (old.contains("affinity")) {
            prefs.edit().putInt("a.$petId", old.getInt("affinity", 0)).apply()
        }
        if (old.contains("treatsStock")) {
            prefs.edit()
                .putInt("t.stock.$petId", old.getInt("treatsStock", 5))
                .putLong("t.time.$petId", old.getLong("treatsTime", System.currentTimeMillis()))
                .apply()
        }
        if (old.contains("lastFeed")) prefs.edit().putLong("f.$petId", old.getLong("lastFeed", 0)).apply()
        if (old.contains("lastPat")) prefs.edit().putLong("p.$petId", old.getLong("lastPat", 0)).apply()
    }
}

data class ChatTurn(val role: String, val text: String, val image: String? = null)
