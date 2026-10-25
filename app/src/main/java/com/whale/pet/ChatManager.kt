package com.whale.pet

import android.content.Context
import android.os.Handler
import android.os.Looper

/** 对话统一入口：本地人格 / 可选 AI，记忆按宠物隔离。 */
object ChatManager {
    private val main = Handler(Looper.getMainLooper())

    fun send(
        context: Context,
        petId: String,
        message: String,
        callback: (AiChatResult) -> Unit,
    ) {
        val cfg = ConfigStore.get()
        val pet = cfg.pets.firstOrNull { it.id == petId } ?: cfg.pets.firstOrNull()
        if (pet == null) {
            callback(AiChatResult(false, "宠物已经不在了。", error = "pet-missing"))
            return
        }
        val history = RuntimeStore.history(petId)
        val lastImage = RuntimeStore.lastMeme(petId)
        val lastReply = history.lastOrNull { it.role == "assistant" }?.text
        RuntimeStore.appendHistory(petId, "user", message)
        AiClient.chat(
            context.applicationContext, pet, cfg, history, message,
            avoidImage = lastImage, avoidText = lastReply,
        ) { result ->
            var image = if (cfg.ai.chatImageEnabled) result.image else null
            if (image != null && image == lastImage) {
                image = MemeCatalog.random(exclude = image)
            }
            if (image == null && cfg.ai.chatImageEnabled && !result.usedAi && result.ok) {
                image = MemeCatalog.randomFor(result.text, exclude = lastImage)
            }
            // 无论回复来自 AI 还是本地人格，都不允许和上一句逐字重复。
            var text = result.text
            if (!lastReply.isNullOrBlank() && text == lastReply) {
                text = AiClient.localReply(pet, message, lastReply).text
            }
            val finalResult = result.copy(text = text, image = image)
            if (finalResult.ok) {
                RuntimeStore.appendHistory(
                    petId, "assistant", finalResult.text,
                    if (cfg.ai.chatImageEnabled) finalResult.image else null
                )
                if (image != null) RuntimeStore.setLastMeme(petId, image)
            }
            main.post { callback(finalResult) }
        }
    }

    fun whisper(
        context: Context,
        petId: String,
        callback: (AiChatResult) -> Unit,
    ) {
        val cfg = ConfigStore.get()
        val pet = cfg.pets.firstOrNull { it.id == petId } ?: return
        val lastImage = RuntimeStore.lastMeme(petId)
        AiClient.whisper(context.applicationContext, pet, cfg) { result ->
            var image = if (cfg.ai.whisperImageEnabled) result.image else null
            if (image != null && image == lastImage) {
                image = MemeCatalog.random(exclude = image)
            }
            if (image != null) RuntimeStore.setLastMeme(petId, image)
            main.post { callback(result.copy(image = image)) }
        }
    }

    fun history(petId: String): List<ChatTurn> = RuntimeStore.history(petId)

    fun clear(petId: String) = RuntimeStore.clearHistory(petId)
}
