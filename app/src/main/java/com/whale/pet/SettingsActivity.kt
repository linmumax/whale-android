package com.whale.pet

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.util.concurrent.Executors

/** 全量设置页：多开、物理、AI、配图、通知、自定义动画、边界校准与存储。 */
class SettingsActivity : AppCompatActivity() {
    private val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val importAnimations =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            importFiles(uris)
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与聊天页一致：不依赖 adjustResize，改用 insets 自适应键盘高度
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Palette.bgTop
        window.navigationBarColor = Palette.bgBottom
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ConfigStore.init(this)
        RuntimeStore.init(this)
        buildPage()
    }

    private fun buildPage() {
        val root = FrameLayout(this)
        root.addView(OceanBackgroundView(this), FrameLayout.LayoutParams(-1, -1))
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(36))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // 键盘弹起时给滚动区留出底部空间，并把正在输入的控件滚到可见区域
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            scroll.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            if (ime.bottom > 0) {
                val focused = currentFocus
                if (focused != null && focused !== scroll && focused !== root) {
                    scroll.post {
                        // 用内容坐标手动计算滚动距离，避免嵌套子 View 调用
                        // requestChildRectangleOnScreen 时坐标偏移错误导致整页跳到顶部
                        val rect = Rect()
                        focused.getDrawingRect(rect)
                        content.offsetDescendantRectToMyCoords(focused, rect)
                        val visibleBottom = scroll.height - scroll.paddingBottom
                        val delta = rect.bottom + dp(56) - (scroll.scrollY + visibleBottom)
                        if (delta > 0) scroll.smoothScrollBy(0, delta)
                    }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)

        setContentView(root)
        rebuildContent()
    }

    private fun rebuildContent() {
        content.removeAllViews()
        addTopBar()
        addIntroCard()
        addPetsSection()
        addPhysicsSection()
        addAiSection()
        addCustomAnimationSection()
        addAdvancedSection()
        addAboutSection()
    }

    private fun addTopBar() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(8))
        }
        row.addView(UiKit.textView(this, "←", 22f, Palette.text, gravity = Gravity.CENTER).apply {
            background = UiKit.ripple(this@SettingsActivity, UiKit.rounded(0x22152E49, 999, this@SettingsActivity))
            setPadding(dp(12), dp(4), dp(12), dp(6))
            setOnClickListener { finish() }
        })
        row.addView(UiKit.textView(this, "设置", 23f, Palette.text, true).apply {
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(UiKit.pill(this, if (PetOverlayService.isRunning()) "桌宠运行中" else "桌宠已休息", true))
        content.addView(row)
    }

    private fun addIntroCard() {
        val card = UiKit.card(this) {
            addView(UiKit.textView(
                this@SettingsActivity,
                "所有修改即时保存并同步到桌面上的鲸鱼娘。手机端不需要打开 DeepSeek Harness 即可独立运行。",
                12.5f, Palette.sub
            ).apply { setLineSpacing(0f, 1.35f) })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun addSectionTitle(title: String, desc: String? = null) {
        content.addView(UiKit.textView(this, title, 16f, Palette.accent, true).apply {
            setPadding(dp(4), dp(22), 0, dp(3))
        })
        if (!desc.isNullOrBlank()) {
            content.addView(UiKit.textView(this, desc, 11.5f, Palette.muted).apply {
                setPadding(dp(4), 0, dp(4), dp(8))
                setLineSpacing(0f, 1.25f)
            })
        } else {
            content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(7)) })
        }
    }

    // ================= 桌宠列表 =================

    private fun addPetsSection() {
        val cfg = ConfigStore.get()
        addSectionTitle("桌宠管理", "可多开最多 5 只；每只独立名字、大小、位置与功能开关。")
        cfg.pets.forEachIndexed { index, pet ->
            addPetCard(pet, index)
        }
        val add = UiKit.button(this, "＋ 添加一只桌宠", primary = false).apply {
            setOnClickListener {
                val current = ConfigStore.get()
                if (current.pets.size >= 5) {
                    toast("最多同时养 5 只，先把不想要的收起来吧")
                    return@setOnClickListener
                }
                val newPet = ConfigStore.newPet(current)
                ConfigStore.update { it.pets.add(newPet) }
                toast("${newPet.name} 加入了")
                rebuildContent()
            }
        }
        content.addView(add, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) })
    }

    private fun addPetCard(pet: PetSpec, index: Int) {
        val cfg = ConfigStore.get()
        val affinity = RuntimeStore.affinity(pet.id)
        val card = UiKit.card(this) {
            val head = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val avatar = ImageView(this@SettingsActivity).apply {
                runCatching {
                    setImageDrawable(
                        android.graphics.drawable.BitmapDrawable(
                            resources,
                            android.graphics.BitmapFactory.decodeStream(assets.open("ui/pet_hero.png"))
                        )
                    )
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = UiKit.rounded(0xFF1B3957.toInt(), 14, this@SettingsActivity)
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            head.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))
            val titles = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            titles.addView(UiKit.textView(
                this@SettingsActivity,
                "${pet.name}  ·  ${RuntimeStore.rankName(affinity)}",
                15f, Palette.text, true
            ))
            titles.addView(UiKit.textView(
                this@SettingsActivity,
                "id: ${pet.id}  ·  亲密度 $affinity  ·  小鱼干 ${RuntimeStore.treats(pet.id)}/${RuntimeStore.TREAT_CAP}",
                10.5f, Palette.muted
            ))
            head.addView(titles)
            val visibleSwitch = compactSwitch(pet.visible)
            head.addView(UiKit.textView(this@SettingsActivity, "显示", 10.5f, Palette.sub).apply {
                setPadding(0, 0, dp(4), 0)
            })
            head.addView(visibleSwitch)
            visibleSwitch.setOnCheckedChangeListener { _, checked ->
                ConfigStore.updatePet(pet.id) { it.visible = checked }
            }
            addView(head)

            // 名字
            val nameRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(2))
            }
            val nameInput = EditText(this@SettingsActivity).apply {
                setText(pet.name)
                setTextColor(Palette.text)
                textSize = 14f
                typeface = UiKit.typeface(this@SettingsActivity)
                background = UiKit.rounded(0xFF10273E.toInt(), 13, this@SettingsActivity, 0x2A5BD8FF, 1)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setSingleLine(true)
            }
            nameRow.addView(nameInput, LinearLayout.LayoutParams(0, -2, 1f))
            nameRow.addView(UiKit.button(this@SettingsActivity, "改名", false, 12f).apply {
                setOnClickListener {
                    val name = nameInput.text.toString().trim()
                    if (name.isBlank()) {
                        toast("名字不能为空")
                        return@setOnClickListener
                    }
                    ConfigStore.updatePet(pet.id) { it.name = name.take(24) }
                    toast("好名字，她记住了")
                    rebuildContent()
                }
                layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) }
            })
            addView(nameRow)

            // 大小
            val sizePct = (pet.sizeRatio * 100).toInt()
            val sizeValue = UiKit.textView(this@SettingsActivity, "体型 $sizePct%", 12f, Palette.sub)
            addView(sizeValue, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            // SeekBar 的 progress 是 0..(90-22)，必须加回最小值才是真实百分比，
            // 否则滑到最左会存成 1%、最右也只到 68%。
            val sizeSeek = SeekBar(this@SettingsActivity).apply {
                max = 90 - 22
                progress = (sizePct - 22).coerceIn(0, 90 - 22)
                progressTintList = ColorStateList.valueOf(Palette.accent)
                progressBackgroundTintList = ColorStateList.valueOf(0xFF173753.toInt())
                thumbTintList = ColorStateList.valueOf(Palette.accent)
                setPadding(0, 0, 0, 0)
                setOnSeekBarChangeListener(object : SimpleSeekListener() {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) sizeValue.text = "体型 ${(progress + 22).coerceIn(22, 90)}%"
                    }

                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        val v = ((sb?.progress ?: 0) + 22).coerceIn(22, 90)
                        sizeValue.text = "体型 $v%"
                        ConfigStore.updatePet(pet.id) { it.sizeRatio = v / 100f }
                    }
                })
            }
            addView(sizeSeek, LinearLayout.LayoutParams(-1, dp(32)))
            addView(UiKit.textView(this@SettingsActivity, "高度自动保持 16:9；也可以直接在桌面上双指捏合。", 10.5f, Palette.muted))

            // 角落位置
            addView(UiKit.textView(this@SettingsActivity, "默认停靠角落", 12f, Palette.sub).apply {
                setPadding(0, dp(12), 0, dp(5))
            })
            val corners = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                "top-left" to "左上", "top-right" to "右上",
                "bottom-left" to "左下", "bottom-right" to "右下"
            ).forEach { (value, label) ->
                val selected = pet.corner == value
                corners.addView(UiKit.textView(this@SettingsActivity, label, 11.5f,
                    if (selected) 0xFF071425.toInt() else Palette.sub,
                    bold = selected, gravity = Gravity.CENTER).apply {
                    background = if (selected) UiKit.rounded(Palette.accent, 999, this@SettingsActivity)
                    else UiKit.rounded(0xFF173753.toInt(), 999, this@SettingsActivity, 0x226E91B3, 1)
                    setPadding(dp(4), dp(7), dp(4), dp(7))
                    setOnClickListener {
                        ConfigStore.updatePet(pet.id) {
                            it.corner = value
                            it.xRatio = null
                            it.yRatio = null
                        }
                        rebuildContent()
                    }
                }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(5) })
            }
            addView(corners)
            addView(UiKit.button(this@SettingsActivity, "回到这个角落", false, 11.5f).apply {
                setOnClickListener {
                    ConfigStore.updatePet(pet.id) {
                        it.xRatio = null
                        it.yRatio = null
                    }
                    toast("已让她回到${cornerName(pet.corner)}")
                }
                layoutParams = LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(6) }
            })
            addSliderBlock(
                "水平边距", "按角色身体计算；0 = 身体正好贴边",
                pet.marginX.toInt().coerceIn(0, 300), 0, 300, "${pet.marginX.toInt()} dp",
                onCommit = { v ->
                    ConfigStore.updatePet(pet.id) {
                        it.marginX = v.toFloat()
                        it.xRatio = null
                        it.yRatio = null
                    }
                }
            ) { v, label ->
                label.text = "$v dp"
            }
            addSliderBlock(
                "垂直边距", "按角色身体计算；0 = 身体正好贴边",
                pet.marginY.toInt().coerceIn(0, 400), 0, 400, "${pet.marginY.toInt()} dp",
                onCommit = { v ->
                    ConfigStore.updatePet(pet.id) {
                        it.marginY = v.toFloat()
                        it.xRatio = null
                        it.yRatio = null
                    }
                }
            ) { v, label ->
                label.text = "$v dp"
            }

            addView(UiKit.divider(this@SettingsActivity))

            // 开关
            val toggleGrid = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            fun toggleRow(label: String, hint: String, key: String, checked: Boolean) {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                }
                val labels = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                labels.addView(UiKit.textView(this@SettingsActivity, label, 12.5f, Palette.text))
                labels.addView(UiKit.textView(this@SettingsActivity, hint, 9.5f, Palette.muted))
                row.addView(labels)
                val sw = compactSwitch(checked)
                sw.setOnCheckedChangeListener { _, c ->
                    ConfigStore.updatePet(pet.id) {
                        when (key) {
                            "whisper" -> it.whisperEnabled = c
                            "balance" -> it.balanceEnabled = c
                            "fixed" -> it.fixedEnabled = c
                        }
                    }
                }
                row.addView(sw)
                toggleGrid.addView(row)
            }
            toggleRow("自动碎碎念", "定时冒一句，可配表情包", "whisper", pet.whisperEnabled)
            toggleRow("余额展示", "查看余额时播档位动画", "balance", pet.balanceEnabled)
            toggleRow(
                "宠物固定",
                "位置锁死：不漫游、不能拖动或甩飞；动画、点按与长按菜单照常",
                "fixed",
                pet.fixedEnabled,
            )
            addView(toggleGrid)

            if (cfg.pets.size > 1) {
                addView(UiKit.button(this@SettingsActivity, "移出这只桌宠", false, 11.5f).apply {
                    setTextColor(Palette.danger)
                    setOnClickListener {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("移出「${pet.name}」？")
                            .setMessage("会同时移除她的位置与专属聊天记录。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("移出") { _, _ ->
                                ConfigStore.update { c -> c.pets.removeAll { it.id == pet.id } }
                                RuntimeStore.clearHistory(pet.id)
                                rebuildContent()
                            }.show()
                    }
                }, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(8) })
            }
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun cornerName(corner: String): String = when (corner) {
        "top-left" -> "左上角"
        "top-right" -> "右上角"
        "bottom-left" -> "左下角"
        else -> "右下角"
    }

    // ================= 物理 =================

    private fun addPhysicsSection() {
        addSectionTitle("物理手感", "全局生效：拖拽弹簧、甩抛、碰壁、地面摩擦与多宠物碰撞。")
        val cfg = ConfigStore.get()
        val physics = cfg.physics
        val card = UiKit.card(this) {
            addSliderBlock(
                "重力", "px/s²；0 = 无重力漂浮",
                physics.gravity.toInt().coerceIn(0, 3000), 0, 3000,
                "${physics.gravity.toInt()}",
                onCommit = { value -> ConfigStore.update { it.physics.gravity = value.toFloat() } }
            ) { value, label ->
                label.text = if (value == 0) "重力 0 · 无重力漂浮" else "重力 $value px/s²"
            }
            addSliderBlock(
                "弹性", "0% = 撞上即停；78% = 默认 Q 弹",
                (physics.restitution * 100).toInt().coerceIn(0, 100), 0, 100,
                "${(physics.restitution * 100).toInt()}%",
                onCommit = { value -> ConfigStore.update { it.physics.restitution = value / 100f } }
            ) { value, label ->
                label.text = "弹性 $value%"
            }
            addSliderBlock(
                "地面摩擦", "落地后水平速度衰减；0 = 冰面",
                (physics.groundFriction * 100).toInt().coerceIn(0, 800), 0, 800,
                "${"%.1f".format(physics.groundFriction)}/s",
                onCommit = { value -> ConfigStore.update { it.physics.groundFriction = value / 100f } }
            ) { value, label ->
                label.text = "地面摩擦 ${"%.1f".format(value / 100f)}/s"
            }
            addSliderBlock(
                "抛掷力度", "跟手程度与甩出初速的整体倍率",
                (physics.throwPower * 100).toInt().coerceIn(20, 300), 20, 300,
                "${"%.2f".format(physics.throwPower)}×",
                onCommit = { value -> ConfigStore.update { it.physics.throwPower = value / 100f } }
            ) { value, label ->
                label.text = "抛掷力度 ${"%.2f".format(value / 100f)}×"
            }
            addSwitchRow("顶部反弹", "手机端始终保留上边界，避免宠物飞出视野", physics.ceilingBounce) { c ->
                ConfigStore.update { it.physics.ceilingBounce = c }
            }
            addSwitchRow("宠物互撞", "飞行中的宠物撞到另一只时按动量守恒弹开", physics.petCollision) { c ->
                ConfigStore.update { it.physics.petCollision = c }
            }
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    // ================= AI =================

    private fun addAiSection() {
        addSectionTitle("AI 对话", "不填 Key 也能用本地人格聊天；填 DeepSeek API Key 后启用真 AI、余额查询与联网碎碎念。")
        val cfg = ConfigStore.get()
        val card = UiKit.card(this) {
            addView(fieldLabel("API 地址"))
            val baseUrl = editText(cfg.ai.baseUrl, "https://api.deepseek.com", false)
            addView(baseUrl)
            addView(fieldLabel("模型名"))
            val model = editText(cfg.ai.model, "deepseek-chat", false)
            addView(model)
            addView(fieldLabel("API Key（仅保存在本机）"))
            val key = editText(cfg.ai.apiKey, "sk-…", true)
            addView(key)
            addView(fieldLabel("人设 / 系统提示词"))
            val persona = editText(cfg.ai.systemPrompt, "她的人设…", false).apply {
                minLines = 3
                maxLines = 6
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            addView(persona)

            val saveAi = UiKit.button(this@SettingsActivity, "保存 AI 配置", primary = true).apply {
                setOnClickListener {
                    val url = baseUrl.text.toString().trim().trimEnd('/')
                    val m = model.text.toString().trim()
                    if (!url.startsWith("http")) {
                        toast("API 地址需要以 http:// 或 https:// 开头")
                        return@setOnClickListener
                    }
                    if (m.isBlank()) {
                        toast("模型名不能为空")
                        return@setOnClickListener
                    }
                    ConfigStore.update {
                        it.ai.baseUrl = url
                        it.ai.model = m
                        it.ai.apiKey = key.text.toString().trim()
                        it.ai.systemPrompt = persona.text.toString().trim()
                            .ifBlank { AiConfig.DEFAULT_PERSONA }
                    }
                    toast("AI 配置已保存")
                }
            }
            addView(saveAi, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(9) })

            val status = UiKit.textView(this@SettingsActivity, "", 10.5f, Palette.muted)
            addView(status)
            addView(UiKit.button(this@SettingsActivity, "测试连接", false, 12f).apply {
                setOnClickListener {
                    ConfigStore.update {
                        it.ai.baseUrl = baseUrl.text.toString().trim().trimEnd('/')
                        it.ai.model = model.text.toString().trim().ifBlank { "deepseek-chat" }
                        it.ai.apiKey = key.text.toString().trim()
                    }
                    status.text = "正在连接…"
                    AiClient.testConnection(ConfigStore.get().ai) { ok, msg ->
                        status.setTextColor(if (ok) Palette.mint else Palette.danger)
                        status.text = msg
                    }
                }
                layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(6) }
            })

            addView(UiKit.divider(this@SettingsActivity))

            val rounds = cfg.ai.memoryRounds
            addSliderBlock("对话记忆轮数", "每次请求携带最近几轮问答；0 = 不带上下文",
                rounds.coerceIn(0, 20), 0, 20, "$rounds 轮",
                onCommit = { v -> ConfigStore.update { it.ai.memoryRounds = v } }
            ) { v, label ->
                label.text = "对话记忆 $v 轮"
            }
            val imgLimit = if (cfg.ai.chatImageLimit == 0) 27 else cfg.ai.chatImageLimit.coerceIn(1, 27)
            addSliderBlock("对话配图上限", "最多把前 N 张表情包交给模型挑选；0 = 全部",
                imgLimit, 0, 27, if (cfg.ai.chatImageLimit == 0) "全部" else "$imgLimit 张",
                onCommit = { v -> ConfigStore.update { it.ai.chatImageLimit = v } }
            ) { v, label ->
                label.text = if (v == 0) "对话配图 · 不限制" else "对话配图最多 $v 张"
            }
            addSwitchRow("碎碎念配图", "随机抽一张表情包一起出现", cfg.ai.whisperImageEnabled) { c ->
                ConfigStore.update { it.ai.whisperImageEnabled = c }
            }
            addSwitchRow("对话配图", "由 AI 按语境挑一张（会多消耗少量 token）", cfg.ai.chatImageEnabled) { c ->
                ConfigStore.update { it.ai.chatImageEnabled = c }
            }

            addView(UiKit.divider(this@SettingsActivity))

            addSliderBlock("碎碎念间隔", "自动冒一句话的周期；手动菜单不受限",
                cfg.whisperIntervalSec.coerceIn(30, 1800), 30, 1800,
                if (cfg.whisperIntervalSec >= 60) "${cfg.whisperIntervalSec / 60} 分钟" else "${cfg.whisperIntervalSec} 秒",
                onCommit = { v -> ConfigStore.update { it.whisperIntervalSec = v } }
            ) { v, label ->
                label.text = if (v >= 60) "碎碎念间隔 ${v / 60} 分钟" else "碎碎念间隔 $v 秒"
            }
            addSwitchRow("对话回复通知", "AI 回复时在通知栏提醒（需系统通知权限）", cfg.notificationsEnabled) { c ->
                ConfigStore.update { it.notificationsEnabled = c }
            }
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    // ================= 自定义动画 =================

    private fun addCustomAnimationSection() {
        val custom = AnimationCatalog.customNames(this)
        addSectionTitle("自定义动画", "导入 VP9 Alpha 的 .webm 动画，自动加入随机动作和「自定义」菜单分类。")
        val card = UiKit.card(this) {
            addView(UiKit.textView(
                this@SettingsActivity,
                "模型或素材生成工具产出的透明 WebM 都可以。建议 640×360、10 秒左右，动画脚底线尽量一致。",
                11f, Palette.sub
            ).apply { setLineSpacing(0f, 1.3f) })
            addView(UiKit.button(this@SettingsActivity, "导入 .webm 动画", primary = true).apply {
                setOnClickListener {
                    // 部分 OEM 文件选择器会忽略 MIME 白名单，直接放开后再按扩展名校验，
                    // 避免用户明明有 webm 却选不到。
                    importAnimations.launch(arrayOf("*/*"))
                }
                layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(9) }
            })
            if (custom.isEmpty()) {
                addView(UiKit.textView(this@SettingsActivity, "还没有导入自定义动画。", 11f, Palette.muted).apply {
                    setPadding(0, dp(9), 0, 0)
                })
            } else {
                custom.forEach { name ->
                    val row = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, dp(2))
                    }
                    row.addView(UiKit.textView(
                        this@SettingsActivity, AnimationCatalog.displayName(name), 12f, Palette.text
                    ).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                    row.addView(UiKit.textView(this@SettingsActivity, "删除", 11f, Palette.danger).apply {
                        background = UiKit.rounded(0x22FF8194, 999, this@SettingsActivity)
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        setOnClickListener {
                            File(File(filesDir, "custom_animations"), name).delete()
                            toast("已删除")
                            rebuildContent()
                        }
                    })
                    addView(row)
                }
            }
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun importFiles(uris: List<Uri>) {
        toast("正在导入 ${uris.size} 个动画…")
        io.execute {
            val dir = File(filesDir, "custom_animations").apply { mkdirs() }
            var ok = 0
            var skipped = 0
            uris.forEach { uri ->
                runCatching {
                    val name = queryDisplayName(uri) ?: "custom_${System.currentTimeMillis()}.webm"
                    if (!name.lowercase().endsWith(".webm")) {
                        skipped++
                        return@forEach
                    }
                    val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    val target = File(dir, safe)
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取文件")
                    ok++
                }.onFailure { skipped++ }
            }
            main.post {
                val extra = if (skipped > 0) "，跳过 $skipped 个非 WebM 文件" else ""
                toast(
                    if (ok > 0) "成功导入 $ok 个动画$extra"
                    else "没有导入成功，请选择 VP9 Alpha 的 .webm 文件"
                )
                rebuildContent()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name
    }

    // ================= 高级 =================

    private fun addAdvancedSection() {
        val cfg = ConfigStore.get()
        addSectionTitle("边界与存储", "碰撞边界校准、聊天记忆与恢复默认。")
        val card = UiKit.card(this) {
            addSliderBlock(
                "左边界", "正值向内收；负值向外扩",
                cfg.insetLeft, -120, 120, "${cfg.insetLeft} px",
                onCommit = { v -> ConfigStore.update { it.insetLeft = v } }
            ) { v, label ->
                label.text = "左边界 $v px"
            }
            addSliderBlock(
                "右边界", "正值向内收；负值向外扩",
                cfg.insetRight, -120, 120, "${cfg.insetRight} px",
                onCommit = { v -> ConfigStore.update { it.insetRight = v } }
            ) { v, label ->
                label.text = "右边界 $v px"
            }
            addSliderBlock(
                "上边界", "正值向内收；负值向外扩",
                cfg.insetTop, -120, 120, "${cfg.insetTop} px",
                onCommit = { v -> ConfigStore.update { it.insetTop = v } }
            ) { v, label ->
                label.text = "上边界 $v px"
            }
            addSliderBlock(
                "下边界（地板）", "正值抬高地板；负值向下扩",
                cfg.insetBottom, -120, 120, "${cfg.insetBottom} px",
                onCommit = { v -> ConfigStore.update { it.insetBottom = v } }
            ) { v, label ->
                label.text = "下边界 $v px"
            }
            addView(UiKit.button(this@SettingsActivity, "清空所有聊天记忆", false).apply {
                setOnClickListener {
                    RuntimeStore.clearAllHistory()
                    toast("聊天记忆已清空")
                }
                layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) }
            })
            addView(UiKit.button(this@SettingsActivity, "让所有桌宠休息", false).apply {
                setOnClickListener {
                    stopService(Intent(this@SettingsActivity, PetOverlayService::class.java))
                    toast("已经让她们休息了")
                }
                layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) }
            })
            addView(UiKit.button(this@SettingsActivity, "恢复默认设置", false).apply {
                setTextColor(Palette.danger)
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("恢复默认设置？")
                        .setMessage("会清空桌宠配置、亲密度、小鱼干与聊天记忆，并恢复一只默认的蓝毛小女仆。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复") { _, _ ->
                            ConfigStore.reset(this@SettingsActivity)
                            rebuildContent()
                            toast("已恢复默认设置")
                        }.show()
                }
                layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) }
            })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun addAboutSection() {
        content.addView(UiKit.textView(
            this,
            "鲸鱼娘 · WhalePet 1.0\n独立手机版 · 复刻 dsh-pet 的全部桌宠能力\n动画素材 © PC2005-cloud/dsh-pet（禁止商用）",
            10.5f, Palette.muted, gravity = Gravity.CENTER
        ).apply {
            setPadding(dp(8), dp(24), dp(8), dp(10))
            setLineSpacing(0f, 1.35f)
        })
    }

    // ================= 小工具 =================

    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    /**
     * 带标题和数值的滑杆。保存时机 = 松手，避免拖动过程中反复重建悬浮窗。
     */
    private fun LinearLayout.addSliderBlock(
        title: String,
        hint: String,
        value: Int,
        min: Int,
        max: Int,
        initialLabel: String,
        onCommit: (Int) -> Unit,
        onChange: (Int, TextView) -> Unit,
    ) {
        val head = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(1))
        }
        val left = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        left.addView(UiKit.textView(this@SettingsActivity, title, 12.5f, Palette.text))
        left.addView(UiKit.textView(this@SettingsActivity, hint, 9.5f, Palette.muted))
        head.addView(left)
        val label = UiKit.textView(this@SettingsActivity, initialLabel, 11.5f, Palette.accent, true)
        head.addView(label)
        addView(head)
        val seek = SeekBar(this@SettingsActivity).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = ColorStateList.valueOf(Palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF173753.toInt())
            thumbTintList = ColorStateList.valueOf(Palette.accent)
            setPadding(0, 0, 0, 0)
        }
        addView(seek, LinearLayout.LayoutParams(-1, dp(30)))
        seek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress + min, label)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                onCommit((sb?.progress ?: 0) + min)
            }
        })
    }

    private fun LinearLayout.addSwitchRow(
        title: String,
        hint: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        val labels = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        labels.addView(UiKit.textView(this@SettingsActivity, title, 12.5f, Palette.text))
        labels.addView(UiKit.textView(this@SettingsActivity, hint, 9.5f, Palette.muted))
        row.addView(labels)
        val sw = compactSwitch(checked)
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        row.addView(sw)
        addView(row)
    }

    private fun compactSwitch(checked: Boolean): SwitchCompat = SwitchCompat(this).apply {
        isChecked = checked
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_checked), intArrayOf(android.R.attr.state_checked)),
            intArrayOf(0xFFE6EFF8.toInt(), Palette.accent)
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_checked), intArrayOf(android.R.attr.state_checked)),
            intArrayOf(0xFF28445F.toInt(), 0x665BD8FF)
        )
    }

    private fun fieldLabel(text: String): TextView = UiKit.textView(this, text, 11f, Palette.sub).apply {
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun editText(value: String, hint: String, password: Boolean): EditText =
        EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Palette.muted)
            setTextColor(Palette.text)
            textSize = 13f
            typeface = UiKit.typeface(this@SettingsActivity)
            background = UiKit.rounded(0xFF10273E.toInt(), 13, this@SettingsActivity, 0x2A5BD8FF, 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setSingleLine(!password)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
