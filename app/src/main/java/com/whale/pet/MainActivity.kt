package com.whale.pet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {
    private val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var selectedPetId: String? = null
    private var pendingStart = false

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Palette.bgTop
        window.navigationBarColor = Palette.bgBottom
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ConfigStore.init(this)
        RuntimeStore.init(this)
        NotificationHelper.ensureChannels(this)
        root = FrameLayout(this)
        setContentView(root)
        render()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        val cfg = ConfigStore.get()
        if (cfg.pets.none { it.id == selectedPetId }) {
            selectedPetId = cfg.pets.firstOrNull()?.id
        }
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false
            startPetService()
        }
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(OceanBackgroundView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val cfg = ConfigStore.get()
        if (cfg.pets.isEmpty()) {
            // 理论上不会发生；兜底恢复默认
            ConfigStore.update { it.pets.add(AppConfig.defaultPet()) }
            root.post { render() }
            return
        }
        if (cfg.pets.none { it.id == selectedPetId }) selectedPetId = cfg.pets.first().id
        val pet = cfg.pets.first { it.id == selectedPetId }

        addTopBar(content)
        addHero(content, pet, cfg)
        addSelector(content, cfg, pet)
        addStats(content, pet)
        addReleaseCard(content, cfg)
        addQuickActions(content, pet)
        addCapabilities(content)
        addTips(content)
    }

    private fun addTopBar(content: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            runCatching {
                setImageDrawable(
                    BitmapDrawable(resources, BitmapFactory.decodeStream(assets.open("ui/pet_hero.png")))
                )
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titles.addView(UiKit.textView(this, "鲸鱼娘", 28f, Palette.text, true))
        titles.addView(UiKit.textView(this, "WhalePet · 独立手机桌宠", 11.5f, Palette.sub).apply {
            setPadding(dp(2), 0, 0, 0)
        })
        row.addView(titles)
        row.addView(UiKit.textView(this, "设置", 12f, Palette.accent, true, Gravity.CENTER).apply {
            background = UiKit.ripple(this@MainActivity, UiKit.rounded(0x22204D77, 999, this@MainActivity, 0x2E5BD8FF, 1))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        content.addView(row)
    }

    private fun addHero(content: LinearLayout, pet: PetSpec, cfg: AppConfig) {
        val hero = FrameLayout(this).apply {
            background = UiKit.gradient(
                this@MainActivity,
                intArrayOf(0xFF183E64.toInt(), 0xFF102A48.toInt(), 0xFF0C2038.toInt()),
                26,
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            )
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(dp(210), -2).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        }
        text.addView(UiKit.pill(this, if (PetOverlayService.isRunning()) "陪伴中" else "待唤醒", PetOverlayService.isRunning()))
        text.addView(UiKit.textView(this, pet.name, 22f, Palette.text, true).apply {
            setPadding(0, dp(8), 0, dp(2))
        })
        text.addView(UiKit.textView(
            this,
            "吃白饭的蓝色大肥鱼，偶尔玩魔方、荡秋千，\n被你甩来甩去也照样赖着不走。",
            11f, Palette.sub
        ).apply { setLineSpacing(0f, 1.35f) })
        hero.addView(text)
        if (!PetOverlayService.isRunning()) {
            val img = ImageView(this).apply {
                runCatching {
                    setImageDrawable(
                        BitmapDrawable(resources, BitmapFactory.decodeStream(assets.open("ui/pet_hero.png")))
                    )
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                alpha = 0.98f
            }
            hero.addView(img, FrameLayout.LayoutParams(dp(158), dp(190)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(-6)
            })
            img.translationY = dp(4).toFloat()
            img.animate().translationY(-dp(2).toFloat()).setDuration(2200L)
                .setStartDelay(160L)
                .withEndAction {
                    img.animate().translationY(dp(4).toFloat()).setDuration(2200L).start()
                }.start()
        } else {
            val live = UiKit.textView(
                this,
                "正在桌面上",
                10.5f, Palette.mint,
                gravity = Gravity.CENTER
            ).apply {
                background = UiKit.rounded(0x1A66E6B8, 999, this@MainActivity)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            hero.addView(live, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dp(2)
                bottomMargin = dp(2)
            })
        }
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(204)).apply { topMargin = dp(18) })
    }

    private fun addSelector(content: LinearLayout, cfg: AppConfig, selected: PetSpec) {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, 0)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cfg.pets.forEach { p ->
            val active = p.id == selected.id
            val label = "${p.name}${if (!p.visible) " · 休息" else ""}"
            row.addView(UiKit.textView(this, label, 11.5f,
                if (active) 0xFF071425.toInt() else Palette.sub,
                bold = active, gravity = Gravity.CENTER).apply {
                background = if (active) UiKit.ripple(this@MainActivity, UiKit.rounded(Palette.accent, 999, this@MainActivity))
                else UiKit.ripple(this@MainActivity, UiKit.rounded(0x22152E49, 999, this@MainActivity, 0x336E91B3, 1))
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener {
                    selectedPetId = p.id
                    render()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
        row.addView(UiKit.textView(this, "＋", 12f, Palette.muted, gravity = Gravity.CENTER).apply {
            background = UiKit.ripple(this@MainActivity, UiKit.rounded(0x22152E49, 999, this@MainActivity))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        scroll.addView(row)
        content.addView(scroll)
    }

    private fun addStats(content: LinearLayout, pet: PetSpec) {
        val affinity = RuntimeStore.affinity(pet.id)
        val treats = RuntimeStore.treats(pet.id)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun statCard(
            label: String,
            value: String,
            detail: String,
            accent: Int,
        ): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(Palette.card, 18, this@MainActivity, 0x148FD9FF, 1)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            addView(UiKit.textView(this@MainActivity, label, 10.5f, Palette.muted))
            addView(UiKit.textView(this@MainActivity, value, 21f, accent, true).apply {
                setPadding(0, dp(2), 0, 0)
            })
            addView(UiKit.textView(this@MainActivity, detail, 10f, Palette.sub))
        }
        row.addView(statCard(
            "亲密度 · ${RuntimeStore.rankName(affinity)}",
            "$affinity",
            treats.let {
                val next = RuntimeStore.nextRankThreshold(affinity)
                if (next == null) "羁绊已满" else "距离下一阶段 ${next - affinity}"
            },
            Palette.accent
        ), LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(6) })
        row.addView(statCard(
            "小鱼干库存",
            "$treats / ${RuntimeStore.TREAT_CAP}",
            if (treats >= RuntimeStore.TREAT_CAP) "袋子装得满满的"
            else "${UiKit.formatRelativeMs(RuntimeStore.nextTreatInMs(pet.id))}后恢复 1 条",
            Palette.warm
        ), LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(6) })
        content.addView(row)
    }

    private fun addReleaseCard(content: LinearLayout, cfg: AppConfig) {
        val running = PetOverlayService.isRunning()
        val hasOverlay = Settings.canDrawOverlays(this)
        val visibleCount = cfg.pets.count { it.visible }
        val card = UiKit.card(this, 22) {
            val statusRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusRow.addView(View(this@MainActivity).apply {
                background = UiKit.rounded(if (running) Palette.mint else Palette.muted, 999, this@MainActivity)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) }
            })
            statusRow.addView(UiKit.textView(
                this@MainActivity,
                when {
                    running && visibleCount > 0 -> "她正在桌面陪你 · $visibleCount 只"
                    running -> "服务已启动，但所有桌宠都在休息"
                    !hasOverlay -> "需要悬浮窗权限，才能在桌面出现"
                    else -> "她现在在屏幕外等你"
                },
                11.5f, if (running) Palette.mint else Palette.sub
            ).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(statusRow)
            val btn = UiKit.button(
                this@MainActivity,
                when {
                    !hasOverlay -> "授权悬浮窗并放出"
                    running -> "让她休息"
                    else -> "放出鲸鱼娘"
                },
                primary = !running || !hasOverlay
            )
            btn.setOnClickListener {
                if (running) {
                    stopService(Intent(this@MainActivity, PetOverlayService::class.java))
                    main.postDelayed({ render() }, 250)
                } else {
                    if (!hasOverlay) {
                        pendingStart = true
                        Toast.makeText(this@MainActivity, "请在系统设置中允许「显示在其他应用上层」", Toast.LENGTH_LONG).show()
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                    } else {
                        startPetService()
                    }
                }
            }
            addView(btn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun addQuickActions(content: LinearLayout, pet: PetSpec) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun action(label: String, onClick: () -> Unit): TextView =
            UiKit.textView(this, label, 11.5f, Palette.text, true, Gravity.CENTER).apply {
                background = UiKit.ripple(this@MainActivity, UiKit.rounded(0xFF173753.toInt(), 16, this@MainActivity, 0x2A6E91B3, 1))
                setOnClickListener { onClick() }
            }
        row.addView(action("聊聊天") {
            startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_PET_ID, pet.id))
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
        row.addView(action("碎碎念") {
            val service = PetOverlayService.instance
            if (service == null) toast("先放出鲸鱼娘，才能听到她说话")
            else service.triggerWhisper(pet.id)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        row.addView(action("喂鱼干") {
            val service = PetOverlayService.instance
            if (service == null) toast("先放出鲸鱼娘，才能喂她")
            else service.feed(pet.id)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        row.addView(action("动作菜单") {
            val service = PetOverlayService.instance
            if (service == null) toast("先放出鲸鱼娘")
            else service.openMenu(pet.id)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
        content.addView(row)
    }

    private fun addCapabilities(content: LinearLayout) {
        val card = UiKit.card(this, 20) {
            addView(UiKit.textView(this@MainActivity, "她能做到的事", 13f, Palette.text, true))
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(9), 0, 0)
            }
            listOf("106 个透明动画", "多开同屏", "表情包对话").forEach { label ->
                row.addView(UiKit.pill(this@MainActivity, label, true), LinearLayout.LayoutParams(-2, -2).apply {
                    rightMargin = dp(6)
                })
            }
            addView(row)
            addView(UiKit.textView(
                this@MainActivity,
                "全部动作都能在长按菜单里点播；碎碎念、对话记忆、物理手感、自定义动画都可在设置里调整。",
                10.5f, Palette.sub
            ).apply {
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(0f, 1.3f)
            })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun addTips(content: LinearLayout) {
        content.addView(UiKit.textView(
            this,
            "单击摸头  ·  长按或双击打开动作菜单  ·  双指缩放\n拖动她搬家，快速甩出去会弹跳  ·  设置里可自由调整",
            10f, Palette.muted, gravity = Gravity.CENTER
        ).apply {
            setPadding(dp(6), dp(12), dp(6), dp(4))
            setLineSpacing(0f, 1.35f)
        })
    }

    private fun startPetService() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            return
        }
        ContextCompat.startForegroundService(
            this, Intent(this, PetOverlayService::class.java)
        )
        toast("鲸鱼娘出门啦")
        main.postDelayed({ render() }, 600)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

}


