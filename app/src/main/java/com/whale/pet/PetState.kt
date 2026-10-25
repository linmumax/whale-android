package com.whale.pet

import android.content.SharedPreferences

/**
 * 鲸鱼娘养成状态：亲密度、小鱼干库存（懒恢复）、冷却、名字、位置记忆
 */
class PetState(private val prefs: SharedPreferences) {

    companion object {
        private const val TREAT_CAP = 20
        private const val TREAT_INTERVAL = 5 * 3600_000L // 每 5 小时 +1（原版节奏）
        private const val FEED_COOLDOWN = 30_000L        // 喂食冷却 30s
        private const val PAT_COOLDOWN = 10_000L         // 摸头冷却 10s

        // 亲密度 9 级头衔（对齐原版：幼鲸 → 鲸生共渡）
        private val RANKS = listOf(
            0 to "幼鲸",
            10 to "伙伴",
            50 to "挚友",
            150 to "深海羁绊",
            400 to "心有灵犀",
            1000 to "传说羁绊",
            2500 to "神话羁绊",
            6000 to "永恒之契",
            15000 to "鲸生共渡"
        )

        fun rankName(points: Int): String {
            var name = RANKS.first().second
            for ((threshold, n) in RANKS) if (points >= threshold) name = n
            return name
        }
    }

    // ---------- 亲密度 ----------

    fun affinity(): Int = prefs.getInt("affinity", 0)

    fun addAffinity(n: Int): Int {
        val v = affinity() + n
        prefs.edit().putInt("affinity", v).apply()
        return v
    }

    // ---------- 小鱼干（每 5 小时自动 +1，上限 20，懒计算） ----------

    fun treats(): Int {
        val now = System.currentTimeMillis()
        val stock = prefs.getInt("treatsStock", 5)
        val last = prefs.getLong("treatsTime", now)
        val gained = ((now - last) / TREAT_INTERVAL).toInt()
        if (gained <= 0) return stock
        val newStock = (stock + gained).coerceAtMost(TREAT_CAP)
        prefs.edit()
            .putInt("treatsStock", newStock)
            .putLong("treatsTime", last + gained * TREAT_INTERVAL)
            .apply()
        return newStock
    }

    fun consumeTreat(): Boolean {
        if (treats() <= 0) return false
        prefs.edit().putInt("treatsStock", treats() - 1).apply()
        return true
    }

    // ---------- 冷却 ----------

    fun canFeed(): Boolean =
        System.currentTimeMillis() - prefs.getLong("lastFeed", 0L) >= FEED_COOLDOWN

    fun markFeed() =
        prefs.edit().putLong("lastFeed", System.currentTimeMillis()).apply()

    fun canPat(): Boolean =
        System.currentTimeMillis() - prefs.getLong("lastPat", 0L) >= PAT_COOLDOWN

    fun markPat() =
        prefs.edit().putLong("lastPat", System.currentTimeMillis()).apply()

    // ---------- 名字 ----------

    fun name(): String = prefs.getString("petName", "蓝毛小女仆") ?: "蓝毛小女仆"

    fun rename(n: String) {
        if (n.isNotBlank()) prefs.edit().putString("petName", n.trim()).apply()
    }

    // ---------- 位置记忆 ----------

    fun posX(def: Int): Int = prefs.getInt("petX", def)
    fun posY(def: Int): Int = prefs.getInt("petY", def)

    fun savePos(x: Int, y: Int) =
        prefs.edit().putInt("petX", x).putInt("petY", y).apply()
}
