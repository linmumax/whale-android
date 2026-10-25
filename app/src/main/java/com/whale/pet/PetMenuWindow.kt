package com.whale.pet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 手机端动作菜单：底部面板 + 分类标签。
 * 功能与 dsh-pet 右键级联菜单一致（全部 106 个动画、工具动作都可达），
 * 交互改成更适合触屏的底部面板。
 */
class PetMenuWindow(
    private val service: PetOverlayService,
    private val pet: PetWindow,
) {
    private val context: Context = service
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var categoryWrap: LinearLayout? = null
    private var actionContainer: LinearLayout? = null
    private var actionScroll: ScrollView? = null
    private var selectedCategory = ""
    private val sections = AnimationCatalog.menuSections(context)

    fun show() {
        dismiss()
        val cfg = ConfigStore.get()
        val spec = cfg.pets.firstOrNull { it.id == pet.petId } ?: pet.spec
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val overlay = FrameLayout(context).apply {
            isClickable = true
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
        }

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFFF8FBFF.toInt())
                setCornerRadii(
                    floatArrayOf(
                        dp(26).toFloat(), dp(26).toFloat(),
                        dp(26).toFloat(), dp(26).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                )
            }
            isClickable = true
            setOnTouchListener { _, _ -> true }
        }

        // 顶部拖拽条
        sheet.addView(View(context).apply {
            background = UiKit.rounded(0xFFD6E2EF.toInt(), 999, context)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(9)
            }
        })

        // 标题
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(14), dp(4))
        }
        val headText = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headText.addView(UiKit.textView(context, spec.name, 19f, 0xFF1A2C42.toInt(), true))
        headText.addView(UiKit.textView(
            context,
            "亲密度 ${RuntimeStore.affinity(spec.id)} · ${RuntimeStore.rankName(RuntimeStore.affinity(spec.id))}" +
                " · 小鱼干 ${RuntimeStore.treats(spec.id)}/${RuntimeStore.TREAT_CAP}",
            11.5f, 0xFF6884A0.toInt()
        ).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(headText)
        header.addView(closeButton())
        sheet.addView(header)

        // 快捷动作
        val quickGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(4))
        }
        quickGrid.addView(UiKit.textView(context, "快捷操作", 11f, 0xFF8AA0B7.toInt()).apply {
            setPadding(dp(3), 0, 0, dp(2))
        })
        val quick = listOf(
            QuickAction("💬 对话", true) { dismiss(); service.openChat(pet) },
            QuickAction("💭 碎碎念") { dismiss(); pet.triggerWhisper(true) },
            QuickAction("🐟 喂食") { dismiss(); pet.feed() },
            QuickAction("💰 余额") { dismiss(); pet.showBalance() },
            QuickAction("🏠 回原位") { dismiss(); pet.goHome() },
            QuickAction(if (spec.fixedEnabled) "📍 解除固定" else "📌 固定在此处") {
                dismiss()
                pet.toggleFixed()
            },
        )
        quick.chunked(3).forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowItems.forEach { qa ->
                val b = UiKit.button(context, qa.label, primary = qa.primary, textSizeSp = 13f).apply {
                    background = if (qa.primary) background else UiKit.ripple(
                        context, UiKit.rounded(0xFFEAF2FB.toInt(), 15, context, 0x1F17466D, 1)
                    )
                    setTextColor(if (qa.primary) 0xFF061425.toInt() else 0xFF25415F.toInt())
                    // 三列按钮宽度有限（如「📌 固定在此处」），内边距收窄 + 单行，
                    // 否则在密度 440 的机型上会被挤成两行
                    setPadding(dp(6), 0, dp(6), 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { qa.action() }
                }
                row.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    leftMargin = dp(3); rightMargin = dp(3); topMargin = dp(3); bottomMargin = dp(3)
                })
            }
            while (row.childCount < 3) {
                row.addView(View(context), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    leftMargin = dp(3); rightMargin = dp(3)
                })
            }
            quickGrid.addView(row)
        }
        sheet.addView(quickGrid)

        // 分类标签：按可用宽度自动换行，避免横向滚动把右侧标签裁掉半截
        val catWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        categoryWrap = catWrap
        sheet.addView(catWrap)

        // 动作列表
        actionScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110)
            )
        }
        actionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(20))
        }
        actionScroll?.addView(actionContainer)
        sheet.addView(actionScroll)

        // 底部提示
        sheet.addView(UiKit.textView(
            context,
            "长按桌宠随时打开 · 全部动作共 ${sections.sumOf { it.second.size }} 个",
            10.5f, 0xFF8AA0B7.toInt(), gravity = Gravity.CENTER
        ).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12))
        })

        selectedCategory = sections.firstOrNull()?.first ?: ""
        rebuildCategoryChips()
        rebuildActions()

        val sheetLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(7)
            rightMargin = dp(7)
        }
        // 压暗层自己画、跟着面板一起淡入：系统的 FLAG_DIM_BEHIND 是瞬间变暗的，
        // 面板还在淡入时背景已经黑了，视觉上就是一次闪。
        val scrim = View(context).apply {
            setBackgroundColor(0xFF061425.toInt())
            alpha = 0f
            isClickable = true
            setOnClickListener { dismiss() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 面板先设为不可见再挂到窗口上，否则窗口第一帧会以全不透明状态渲染出来，
        // 随后才被动画重置为透明，看起来就是打开瞬间闪一下。
        sheet.alpha = 0f
        sheet.translationY = dp(28).toFloat()
        overlay.addView(sheet, sheetLp)
        root = overlay
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { wm.addView(overlay, params) }
        overlay.requestFocus()
        overlay.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else false
        }
        overlay.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                true
            } else {
                false
            }
        }
        scrim.animate().alpha(0.52f).setDuration(200L).start()
        sheet.animate().alpha(1f).translationY(0f).setDuration(180L).start()
    }

    private fun closeButton(): TextView = UiKit.textView(
        context, "关闭", 12.5f, 0xFF5B7693.toInt(), gravity = Gravity.CENTER
    ).apply {
        background = UiKit.ripple(context, UiKit.rounded(0xFFEAF2FB.toInt(), 12, context))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        isClickable = true
        setOnClickListener { dismiss() }
    }

    private fun rebuildCategoryChips() {
        val wrap = categoryWrap ?: return
        wrap.removeAllViews()
        if (sections.isEmpty()) return
        // 每行最多 5 个、按行数把标签平均分配（例如 13 个 → 5/4/4），
        // 每个标签 weight=1 铺满整行，避免全部挤在左侧、右边留一大块空白
        val maxPerRow = 5
        val rowCount = (sections.size + maxPerRow - 1) / maxPerRow
        val base = sections.size / rowCount
        val extra = sections.size % rowCount
        var index = 0
        repeat(rowCount) { r ->
            val count = base + if (r < extra) 1 else 0
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (r == 0) 0 else dp(6), 0, 0)
            }
            repeat(count) {
                val name = sections[index++].first
                val selected = name == selectedCategory
                val tv = UiKit.textView(
                    context,
                    name,
                    12f,
                    if (selected) 0xFFF8FBFF.toInt() else 0xFF526E8A.toInt(),
                    bold = selected,
                    gravity = Gravity.CENTER
                ).apply {
                    background = if (selected) {
                        UiKit.rounded(0xFF2E79C8.toInt(), 999, context)
                    } else {
                        UiKit.rounded(0xFFE9F1FA.toInt(), 999, context)
                    }
                    setPadding(dp(4), dp(7), dp(4), dp(7))
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener {
                        if (selectedCategory != name) {
                            selectedCategory = name
                            rebuildCategoryChips()
                            rebuildActions()
                        }
                    }
                }
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(3); rightMargin = dp(3)
                })
            }
            wrap.addView(row)
        }
    }

    private fun rebuildActions() {
        val container = actionContainer ?: return
        container.removeAllViews()
        val actions = sections.firstOrNull { it.first == selectedCategory }?.second ?: emptyList()
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        titleRow.addView(UiKit.textView(
            context, "$selectedCategory · ${actions.size} 个动作", 11.5f, 0xFF8AA0B7.toInt()
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        titleRow.addView(UiKit.textView(context, "点击切换", 10.5f, 0xFF9AAEC2.toInt()))
        container.addView(titleRow)
        actions.chunked(2).forEach { rowActions ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowActions.forEach { name ->
                val label = AnimationCatalog.displayName(name)
                val b = UiKit.textView(
                    context,
                    label,
                    12.5f,
                    0xFF263F59.toInt(),
                    gravity = Gravity.CENTER
                ).apply {
                    background = UiKit.ripple(
                        context, UiKit.rounded(0xFFEFF5FC.toInt(), 13, context, 0x1427466E, 1)
                    )
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    isClickable = true
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener {
                        dismiss()
                        if (AnimationCatalog.isMove(name)) pet.playMove(name)
                        else pet.playAction(name)
                    }
                }
                // 单数行只剩一个动作时让它占满整行，否则按钮缩在左半边，看着偏一边
                row.addView(b, LinearLayout.LayoutParams(
                    0, dp(44), if (rowActions.size == 1) 2f else 1f
                ).apply {
                    leftMargin = dp(3); rightMargin = dp(3); topMargin = dp(3)
                })
            }
            container.addView(row)
        }
        val rows = (actions.size + 1) / 2
        val h = (dp(54) + rows * dp(50)).coerceIn(dp(100), dp(310))
        actionScroll?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h
        )
        actionScroll?.requestLayout()
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
        categoryWrap = null
        actionContainer = null
    }

    fun isShowing(): Boolean = root != null

    private data class QuickAction(
        val label: String,
        val primary: Boolean = false,
        val action: () -> Unit,
    )
}
