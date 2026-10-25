package com.whale.pet

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 与桌宠聊天：本地人格即时可用，配了 API Key 则自动走 AI。 */
class ChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PET_ID = "pet_id"
    }

    private val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    private lateinit var petId: String
    private lateinit var pet: PetSpec
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private var sending = false
    private var typingView: View? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Palette.bgTop
        window.navigationBarColor = Palette.bgBottom
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        ConfigStore.init(this)
        RuntimeStore.init(this)
        val cfg = ConfigStore.get()
        petId = intent.getStringExtra(EXTRA_PET_ID)
            ?: cfg.pets.firstOrNull()?.id
            ?: "main"
        pet = cfg.pets.firstOrNull { it.id == petId } ?: cfg.pets.firstOrNull()
            ?: AppConfig.defaultPet()
        buildUi()
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        PetOverlayService.instance?.updateChatOpen(true)
    }

    override fun onPause() {
        super.onPause()
        PetOverlayService.instance?.updateChatOpen(false)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(OceanBackgroundView(this), FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // 顶栏
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
        }
        val back = UiKit.textView(this, "←", 22f, Palette.text, gravity = Gravity.CENTER).apply {
            background = UiKit.ripple(this@ChatActivity, UiKit.rounded(0x22152E49, 999, this@ChatActivity))
            setPadding(dp(12), dp(4), dp(12), dp(6))
            setOnClickListener { finish() }
        }
        top.addView(back)
        val avatar = ImageView(this).apply {
            setImageResource(0)
            runCatching { setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, android.graphics.BitmapFactory.decodeStream(assets.open("ui/pet_hero.png")))) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        top.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titles.addView(UiKit.textView(this@ChatActivity, pet.name, 16f, Palette.text, true))
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status.addView(View(this@ChatActivity).apply {
            background = UiKit.rounded(Palette.mint, 999, this@ChatActivity)
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(5) }
        })
        status.addView(UiKit.textView(
            this@ChatActivity,
            if (ConfigStore.get().ai.usable()) "AI 在线" else "本地模式 · 在线",
            10.5f, Palette.sub
        ))
        titles.addView(status)
        top.addView(titles)
        val clear = UiKit.textView(this, "清空", 12f, Palette.muted, gravity = Gravity.CENTER).apply {
            background = UiKit.ripple(this@ChatActivity, UiKit.rounded(0x22152E49, 999, this@ChatActivity))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { confirmClear() }
        }
        top.addView(clear)
        column.addView(top)
        column.addView(View(this).apply {
            background = UiKit.rounded(0x1A8FD9FF, 999, this@ChatActivity)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        })

        // 消息区
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
        }
        scroll.addView(messages)
        column.addView(scroll)

        // 快捷话题
        val quickScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), dp(2), dp(12), 0)
        }
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("摸摸头", "今天好累", "你在干嘛", "讲个笑话", "饿了吗", "喜欢我吗").forEach { q ->
            quickRow.addView(UiKit.textView(this, q, 12f, 0xFF3C5878.toInt(), gravity = Gravity.CENTER).apply {
                background = UiKit.ripple(
                    this@ChatActivity,
                    UiKit.rounded(0xFFEAF3FC.toInt(), 999, this@ChatActivity, 0x223C79C4, 1)
                )
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { if (!sending) sendMessage(q) }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    rightMargin = dp(7)
                }
            })
        }
        quickScroll.addView(quickRow)
        column.addView(quickScroll)

        // 输入栏
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(12), dp(14))
            background = UiKit.rounded(0xD90B1D31.toInt(), 0, this@ChatActivity)
        }
        input = EditText(this).apply {
            hint = "和${pet.name}说点什么…"
            setHintTextColor(Palette.muted)
            setTextColor(Palette.text)
            textSize = 14f
            typeface = UiKit.typeface(this@ChatActivity)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            minLines = 1
            background = UiKit.rounded(0xFF152F4A.toInt(), 20, this@ChatActivity, 0x335BD8FF, 1)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    sendCurrent()
                    true
                } else false
            }
        }
        inputBar.addView(input)
        send = UiKit.textView(this, "发送", 13.5f, 0xFF061425.toInt(), true, Gravity.CENTER).apply {
            background = UiKit.ripple(
                this@ChatActivity,
                UiKit.gradient(this@ChatActivity, intArrayOf(Palette.accent, Palette.accentDeep), 20)
            )
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { sendCurrent() }
        }
        inputBar.addView(send, LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(9) })
        column.addView(inputBar)

        setContentView(root)
    }

    private fun loadHistory() {
        val history = ChatManager.history(petId)
        if (history.isEmpty()) {
            addPetMessage("想聊点什么？我一直在你旁边哦。", MemeCatalog.randomFor("你好")?.takeIf { Math.random() < 0.4 })
        } else {
            history.takeLast(60).forEach { turn ->
                if (turn.role == "user") addUserMessage(turn.text)
                else addPetMessage(turn.text, turn.image)
            }
        }
        scrollToBottom()
    }

    private fun sendCurrent() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || sending) return
        sendMessage(text)
    }

    private fun sendMessage(text: String) {
        if (sending) return
        val cfg = ConfigStore.get()
        input.setText("")
        addUserMessage(text)
        sending = true
        send.alpha = 0.45f
        input.isEnabled = false
        typingView = addTyping()
        scrollToBottom()
        PetOverlayService.instance?.startChatWork(petId)

        ChatManager.send(this, petId, text) { result ->
            if (isFinishing || isDestroyed) return@send
            typingView?.let { messages.removeView(it) }
            typingView = null
            if (!result.ok) {
                addSystemNote(result.error ?: "回复失败，再试一次吧。")
                sending = false
                send.alpha = 1f
                input.isEnabled = true
                PetOverlayService.instance?.finishChatWork(petId, false)
                return@send
            }
            if (result.error != null) {
                addSystemNote("AI 连接失败，先切换到本地回复：${result.error.take(70)}")
            }
            addPetMessage(result.text, if (cfg.ai.chatImageEnabled) result.image else null)
            PetOverlayService.instance?.showReplyBubble(
                petId, result.text, if (cfg.ai.chatImageEnabled) result.image else null
            )
            PetOverlayService.instance?.finishChatWork(petId, result.error == null)
            if (!hasWindowFocus()) {
                PetOverlayService.instance?.notifyCompanion(petId, result.text)
            }
            sending = false
            send.alpha = 1f
            input.isEnabled = true
            scrollToBottom()
        }
        scrollToBottom()
    }

    private fun addUserMessage(text: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, dp(4))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(0xFF2F77C6.toInt(), 18, this@ChatActivity)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bubble.addView(UiKit.textView(this, text, 13.5f, Color.WHITE).apply {
            setLineSpacing(0f, 1.25f)
            setSingleLine(false)
            maxLines = 12
            maxWidth = (resources.displayMetrics.widthPixels * 0.70f).toInt()
        })
        row.addView(bubble)
        messages.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addPetMessage(text: String, image: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.TOP
            setPadding(0, dp(5), 0, dp(5))
        }
        val avatar = ImageView(this).apply {
            runCatching {
                setImageDrawable(
                    android.graphics.drawable.BitmapDrawable(
                        resources,
                        android.graphics.BitmapFactory.decodeStream(assets.open("ui/pet_hero.png"))
                    )
                )
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = UiKit.rounded(0xFF1B3957.toInt(), 999, this@ChatActivity)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            topMargin = dp(4); rightMargin = dp(7)
        })
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(0xFFF7FBFF.toInt(), 18, this@ChatActivity)
            setPadding(dp(14), dp(10), dp(14), dp(11))
        }
        if (!image.isNullOrBlank()) {
            val bmp = MemeCatalog.bitmap(this, image)
            if (bmp != null) {
                val img = ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply {
                        bottomMargin = dp(8)
                    }
                    roundedClip(this, 12)
                }
                bubble.addView(img)
            }
        }
        val body = UiKit.textView(this, text, 13.5f, Palette.bubbleText).apply {
            setLineSpacing(0f, 1.3f)
            setSingleLine(false)
            maxLines = 12
            maxWidth = (resources.displayMetrics.widthPixels * 0.68f).toInt()
        }
        if (!image.isNullOrBlank()) {
            body.layoutParams = LinearLayout.LayoutParams(dp(210), -2)
            bubble.minimumWidth = dp(228)
        }
        bubble.addView(body)
        row.addView(bubble)
        messages.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addSystemNote(text: String) {
        val tv = UiKit.textView(this, text, 10.5f, Palette.muted, gravity = Gravity.CENTER).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        messages.addView(tv, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addTyping(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(6), 0, dp(6))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiKit.rounded(0xFFF2F8FE.toInt(), 18, this@ChatActivity)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        repeat(3) { i ->
            bubble.addView(View(this).apply {
                background = UiKit.rounded(0xFF7CA3C7.toInt(), 999, this@ChatActivity)
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(4) }
                alpha = 0.45f
                animate().alpha(1f).setDuration(420L).setStartDelay((i * 140).toLong())
                    .withEndAction { animate().alpha(0.45f).setDuration(420L).start() }.start()
            })
        }
        bubble.addView(UiKit.textView(this, "正在想…", 12f, 0xFF6D8AA8.toInt()).apply {
            setPadding(dp(5), 0, 0, 0)
        })
        row.addView(bubble)
        messages.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空对话记忆？")
            .setMessage("只会清空与「${pet.name}」的聊天记录，亲密度和小鱼干都会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                ChatManager.clear(petId)
                messages.removeAllViews()
                addPetMessage("刚才聊的都忘掉啦…不过我还在这里。", "深度睡眠")
            }
            .show()
    }

    private fun hideKeyboard() {
        val imm = ContextCompat.getSystemService(this, InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onStop() {
        super.onStop()
        PetOverlayService.instance?.updateChatOpen(false)
    }
}


