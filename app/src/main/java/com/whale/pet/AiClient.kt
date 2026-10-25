package com.whale.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class AiChatResult(
    val ok: Boolean,
    val text: String,
    val image: String? = null,
    val error: String? = null,
    val usedAi: Boolean = false,
)

data class BalanceResult(
    val ok: Boolean,
    val text: String,
    val tier: Int = 2,
    val error: String? = null,
)

/**
 * OpenAI 兼容接口客户端（默认 DeepSeek 官方）。
 * 未填 API Key 时全部走本地人格回复，保证 App 离线也能完整玩。
 */
object AiClient {
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun fillMemeInstruction(cfg: AiConfig, avoidImage: String? = null): String {
        if (!cfg.chatImageEnabled) return ""
        val limit = if (cfg.chatImageLimit <= 0) MemeCatalog.keys().size else cfg.chatImageLimit
        val rows = MemeCatalog.keys().take(limit).joinToString("\n") { "「$it」：${MemeCatalog.descriptions[it]}" }
        val avoid = if (avoidImage.isNullOrBlank()) ""
        else "\n上一次回复已经用过「$avoidImage」，这一次不要再用它，换一张更合适的。"
        return "\n你可以在回复最末尾附加一个表情包标记，格式严格为 [img:图片名]，图片名必须来自下面的清单；" +
            "不合适可以不加。不要解释这个标记。$avoid\n可用表情包：\n$rows"
    }

    fun chat(
        context: Context,
        pet: PetSpec,
        cfg: AppConfig,
        history: List<ChatTurn>,
        message: String,
        avoidImage: String? = null,
        avoidText: String? = null,
        callback: (AiChatResult) -> Unit,
    ) {
        if (!cfg.ai.usable()) {
            val local = localReply(pet, message, avoidText)
            main.post { callback(local) }
            return
        }
        executor.execute {
            val result = runCatching {
                val messages = JSONArray()
                val persona = buildString {
                    append(cfg.ai.systemPrompt.trim())
                    append("\n你的名字是“${pet.name}”。主人在手机上和你聊天，回复尽量简短、自然、口语化。")
                    if (!avoidText.isNullOrBlank()) {
                        append("\n你上一句回复是「$avoidText」，这一次不要重复同样的句子。")
                    }
                    if (cfg.ai.chatImageEnabled) append(fillMemeInstruction(cfg.ai, avoidImage))
                }
                messages.put(JSONObject().put("role", "system").put("content", persona))
                val rounds = cfg.ai.memoryRounds.coerceAtLeast(0)
                val recent = if (rounds == 0) emptyList() else history.takeLast(rounds * 2)
                recent.forEach { t ->
                    messages.put(JSONObject().put("role", t.role).put("content", t.text))
                }
                messages.put(JSONObject().put("role", "user").put("content", message))
                val content = requestChatCompletion(cfg.ai, messages, 520)
                val (text, image) = parseImageTag(content, cfg.ai.chatImageEnabled)
                AiChatResult(
                    ok = true,
                    text = text.ifBlank { "……嗯？我刚刚走神了，你再说一遍嘛。" },
                    image = image,
                    usedAi = true,
                )
            }.getOrElse { err ->
                val local = localReply(pet, message, avoidText)
                local.copy(error = err.message ?: "网络请求失败")
            }
            main.post { callback(result) }
        }
    }

    fun whisper(
        context: Context,
        pet: PetSpec,
        cfg: AppConfig,
        callback: (AiChatResult) -> Unit,
    ) {
        val image = if (cfg.ai.whisperImageEnabled) MemeCatalog.random() else null
        if (!cfg.ai.usable()) {
            main.post {
                callback(AiChatResult(true, localWhisper(), image, usedAi = false))
            }
            return
        }
        executor.execute {
            val result = runCatching {
                val messages = JSONArray()
                val persona = buildString {
                    append(cfg.ai.systemPrompt.trim())
                    append("\n你的名字是“${pet.name}”。")
                    if (image != null && cfg.ai.whisperImageEnabled) {
                        append("\n这一句可以呼应这张表情包：${MemeCatalog.descriptions[image]}")
                    }
                }
                messages.put(JSONObject().put("role", "system").put("content", persona))
                messages.put(
                    JSONObject().put("role", "user")
                        .put("content", "现在小声说一句此刻的碎碎念，20字以内，只输出这一句话。")
                )
                val content = requestChatCompletion(cfg.ai, messages, 90)
                AiChatResult(
                    ok = true,
                    text = content.trim().trim('「', '」', '"').take(60),
                    image = image,
                    usedAi = true,
                )
            }.getOrElse {
                AiChatResult(
                    ok = true,
                    text = localWhisper(),
                    image = image,
                    error = it.message,
                    usedAi = false,
                )
            }
            main.post { callback(result) }
        }
    }

    fun testConnection(cfg: AiConfig, callback: (Boolean, String) -> Unit) {
        if (!cfg.usable()) {
            callback(false, "请先填写 API 地址、模型和 API Key")
            return
        }
        executor.execute {
            val result = runCatching {
                val messages = JSONArray().put(
                    JSONObject().put("role", "user").put("content", "只回复两个字：你好")
                )
                requestChatCompletion(cfg, messages, 20)
            }
            main.post {
                if (result.isSuccess) callback(true, "连接成功：${result.getOrNull()?.take(30)}")
                else callback(false, result.exceptionOrNull()?.message ?: "连接失败")
            }
        }
    }

    fun fetchBalance(cfg: AiConfig, callback: (BalanceResult) -> Unit) {
        if (cfg.apiKey.isBlank()) {
            callback(
                BalanceResult(
                    false,
                    "还没有配置 API Key。去「设置 → AI 对话」填上 DeepSeek API Key，就能查看余额啦。",
                    2,
                    "missing-key"
                )
            )
            return
        }
        executor.execute {
            val r = runCatching {
                val base = cfg.baseUrl.trimEnd('/')
                val url = URL("$base/user/balance")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                val body = readAll(conn)
                if (code !in 200..299) error("HTTP $code ${body.take(120)}")
                val o = JSONObject(body)
                val available = o.optBoolean("is_available", true)
                val arr = o.optJSONArray("balance_infos")
                val info = arr?.optJSONObject(0)
                if (info == null) {
                    BalanceResult(
                        available,
                        if (available) "余额接口已连通，但没有返回余额明细。" else "当前账户余额不可用。",
                        4
                    )
                } else {
                    val total = info.optString("total_balance", "0").toDoubleOrNull() ?: 0.0
                    val granted = info.optDouble("granted_balance", 0.0)
                    val topped = info.optDouble("topped_up_balance", 0.0)
                    val currency = info.optString("currency", "CNY")
                    val tier = when {
                        total >= 100 -> 0
                        total >= 50 -> 1
                        total >= 20 -> 2
                        total >= 10 -> 3
                        total > 0 -> 4
                        else -> 5
                    }
                    BalanceResult(
                        true,
                        "余额 $total $currency\n充值 $topped · 赠送 $granted\n${if (available) "账户可用" else "账户不可用"}",
                        tier
                    )
                }
            }.getOrElse {
                BalanceResult(false, "余额查询失败：${it.message ?: "网络异常"}", 2, it.message)
            }
            main.post { callback(r) }
        }
    }

    private fun requestChatCompletion(cfg: AiConfig, messages: JSONArray, maxTokens: Int): String {
        val base = cfg.baseUrl.trimEnd('/')
        val url = URL("$base/chat/completions")
        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            put("temperature", 0.8)
            put("max_tokens", maxTokens)
            put("stream", false)
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = readAll(conn)
        if (code !in 200..299) {
            val msg = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message")
            }.getOrNull()
            error("HTTP $code ${msg ?: body.take(160)}")
        }
        val o = JSONObject(body)
        val choices = o.optJSONArray("choices") ?: error("接口没有返回 choices")
        val first = choices.optJSONObject(0) ?: error("接口没有返回内容")
        val messageObj = first.optJSONObject("message")
        val content = messageObj?.optString("content").orEmpty()
        if (content.isBlank()) error("模型返回了空内容")
        return content
    }

    private fun readAll(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun parseImageTag(content: String, imageEnabled: Boolean): Pair<String, String?> {
        if (!imageEnabled) return content.trim() to null
        val regex = Regex("""\[(?:img|image|图片)\s*[:：]\s*([^\]]+)]""", RegexOption.IGNORE_CASE)
        val match = regex.find(content)
        val image = match?.groupValues?.get(1)?.trim()
            ?.takeIf { it in MemeCatalog.descriptions }
        val text = content.replace(regex, "").trim()
        return text to image
    }

    fun localWhisper(): String = PetLines.WHISPER.random()

    fun localReply(pet: PetSpec, message: String, avoid: String? = null): AiChatResult {
        val m = message.trim()
        val lower = m.lowercase()
        val reply = when {
            m.isEmpty() -> "嗯？我在呢，你慢慢说。"
            listOf("你好", "嗨", "在吗", "hello", "hi", "ciallo").any { lower.contains(it) } ->
                listOf("在的在的，我一直在你旁边呀。", "嗨～今天也一起加油吧！", "Ciallo～被你叫到啦。").random()
            listOf("吃", "饿", "鱼干", "零食", "饭").any { lower.contains(it) } ->
                listOf("说到吃的我就精神了！有小鱼干吗？", "肚子已经开始咕噜咕噜了……", "想吃热乎乎的，火锅也可以哦。").random()
            listOf("累", "困", "困了", "睡", "休息", "加班").any { lower.contains(it) } ->
                listOf("辛苦啦，靠着我休息一小会儿吧。", "别硬撑，摸摸头，先喝口水。", "要不要先闭眼休息五分钟？我帮你看时间。").random()
            listOf("难过", "烦", "压力", "崩溃", "不开心").any { lower.contains(it) } ->
                listOf("先深呼吸一下，我在呢。", "不想做就先放一放，天不会塌下来的。", "抱抱你，今天已经很努力了。").random()
            listOf("喜欢", "爱你", "想你了", "可爱").any { lower.contains(it) } ->
                listOf("诶嘿嘿……我也最喜欢主人啦。", "突然说这个，我会害羞的。", "那、那今天多陪你一会儿。").random()
            listOf("在干嘛", "干嘛", "做什么").any { lower.contains(it) } ->
                listOf("在看着你呀，顺便想想下一顿吃什么。", "刚才在发呆，现在在认真听你说话。", "在等你来找我聊天呢。").random()
            listOf("名字", "叫什么").any { lower.contains(it) } ->
                "我叫${pet.name}，是住在你手机里的小女仆鲸鱼。"
            listOf("笑话", "讲个").any { lower.contains(it) } ->
                listOf("为什么鲸鱼不上岸？因为岸上没有小鱼干。", "我今天学了个魔术，把零食变没了……现在还没变回来。", "有一只鲸鱼很懒，但它不承认——它说这叫深海节能。").random()
            listOf("谢谢", "感谢").any { lower.contains(it) } ->
                "不用谢，能帮上忙我就很开心了。"
            else -> listOf(
                "唔……我听见啦。然后呢？",
                "嗯嗯，我在认真听。",
                "让我想想……你说的很有道理。",
                "原来是这样呀，我记住了。",
                "如果是我，大概会先摸摸尾巴冷静一下。",
            ).random()
        }
        // 同一句不连续重复，避免用户看到"永远同一句"的错觉。
        val finalText = if (avoid != null && reply == avoid) {
            listOf(
                "唔……这次让我换个说法。",
                "嗯，我在听，继续说吧。",
                "好呀，那我就这么做啦。",
                "我记住啦，还想聊点什么？",
            ).filter { it != avoid }.random()
        } else reply
        val image = MemeCatalog.randomFor("$m $finalText")
        return AiChatResult(true, finalText, image, usedAi = false)
    }
}
