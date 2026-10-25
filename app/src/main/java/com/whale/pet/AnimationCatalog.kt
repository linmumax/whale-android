package com.whale.pet

import android.content.Context
import java.io.File

data class ActionCategory(
    val id: String,
    val weight: Int,
    val noMirror: Boolean = false,
    val actions: List<String>,
)

data class MoveAction(
    val name: String,
    val minDist: Float = 60f,
    val maxDist: Float = 240f,
    val margin: Float = 20f,
    val leadSec: Float = 2f,
    val tailSec: Float = 2f,
)

/**
 * 与 dsh-pet 0.3.7 内置 config 对齐的 106 个动画池。
 * 自定义动画（导入的 .webm）会追加到「自定义」分类，并参与随机链。
 */
object AnimationCatalog {
    const val IDLE = "待机呼吸休闲"
    const val TURN = "东张西望"
    const val DRAG = "被鼠标拖拽悬空反馈"

    val clicks = listOf(
        "点击回应-开心跃动",
        "点击回应-害羞惊讶",
        "点击回应-傲娇生气",
        "点击回应-挠痒咯咯笑",
        "点击回应-元气挥手",
    )

    val idlePool = listOf(IDLE)
    val turnPool = listOf(TURN)
    val dragPool = listOf(DRAG)

    val categories = listOf(
        ActionCategory("小动作", 20, actions = listOf(
            "悠闲哼歌", "超大伸懒腰", "原地敲击桌面互动", "原地重力下蹲压缩", "哈欠连天",
            "原地小憩沉眠", "女仆屈膝礼仪", "被吓一跳", "小幅度原地360度旋转展示",
            "偷吃零食被抓住", "用鲸鱼尾巴拍打地面", "打瞌睡被惊醒", "照镜子", "整体换装试色",
            "轻快记录", "写代码", "摇扇纳凉", "晨间刷牙",
        )),
        ActionCategory("玩耍", 20, actions = listOf(
            "原地专心玩魔方", "原地蹲下玩玩具汽车", "鲸鱼吐泡泡特效", "原地跳跃抓碎头顶物品",
            "玩游戏气急败坏", "玩水枪", "小提琴演奏", "蓝鲸现世", "优雅女仆舞", "轻快摇摆舞",
            "可爱宅舞", "吹气球", "动物环绕", "放风筝", "拆礼物", "变鸽子", "扑克魔术",
            "抽陀螺", "吹笛子", "蝴蝶蜜蜂环绕头顶开花", "撸猫", "凭空生花", "骑木马",
            "三球抛接", "踢毽子", "下五子棋", "荡秋千",
        )),
        ActionCategory("吃什么", 16, actions = listOf(
            "吃白饭", "大口吃零食", "吃Token", "吃早餐", "吃午餐", "吃晚餐", "吃冰淇淋融化",
            "吃大闸蟹", "吃糖葫芦", "吃长寿面", "吃西瓜", "涮火锅",
        )),
        ActionCategory("时节", 14, actions = listOf(
            "被落叶淹没", "中秋赏月吃月饼", "堆雪人", "放烟花", "吃粽子", "吃年糕", "吃青团",
            "吃腊八粥", "吃重阳糕", "收红包", "写福字", "穿针乞巧", "舞狮头", "讨糖南瓜灯",
            "插茱萸赏菊", "放河灯", "萌化小幽灵", "装点圣诞树", "放孔明灯", "吃汤圆", "吃饺子",
        )),
        ActionCategory("文字", 10, noMirror = true, actions = listOf(
            "是啊，吃什么", "深度思考碎碎念",
        )),
    )

    val moveActions = listOf(
        MoveAction("螃蟹走路"),
        MoveAction("原地漂浮踏步", minDist = 40f, maxDist = 120f),
        MoveAction("原地左转奔跑", minDist = 120f, maxDist = 320f, leadSec = 1.75f, tailSec = 4.8f),
    )

    val balancePool = listOf(
        "余额-钱袋满溢", "余额-金袋叮当", "余额-钱袋如常",
        "余额-数金皱眉", "余额-袋空如洗", "余额-分文不剩",
    )
    val whisperPool = listOf("碎碎念-擦桌碎碎念", "碎碎念-发呆碎碎念", "碎碎念-对屏碎碎念")
    val workStatusPool = listOf(
        "工作状态-思考冒泡", "工作状态-忙碌点按", "工作状态-清点归档",
        "工作状态-原地踱步张望", "工作状态-雀跃庆祝", "工作状态-垂头叹气冒汗",
    )
    val workStatusTexts = listOf(
        listOf("正在认真想下一步呢", "让我整理一下思路~", "正在梳理接下来要做什么"),
        listOf("正在处理任务呢", "这一步正在进行中哦", "正在继续干活呢，别打扰我~"),
        listOf("这一步处理好啦，接着看下一步", "搞定一步，继续看看哦~"),
        listOf("需要你确认一下呢", "这里要等你看一下哦", "轮到你来决定啦，等你~"),
        listOf("这一轮搞定啦，真棒！", "任务完成咯，可以摸鱼啦~"),
        listOf("这一步好像没跑通呢", "刚才的操作遇到点问题哦", "这里卡了一下，再等等你呢"),
    )

    val feedAnims = listOf("吃白饭", "大口吃零食", "吃糖葫芦", "吃年糕", "吃青团")

    private val builtInAll: Set<String> by lazy {
        (idlePool + turnPool + dragPool + clicks + moveActions.map { it.name } +
            categories.flatMap { it.actions } + balancePool + whisperPool + workStatusPool).toSet()
    }

    private val noMirrorSet: Set<String> by lazy {
        categories.filter { it.noMirror }.flatMap { it.actions }.toSet()
    }

    private fun customDir(context: Context): File = File(context.filesDir, "custom_animations")

    fun customNames(context: Context): List<String> {
        val dir = customDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".webm") }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.name }
            ?: emptyList()
    }

    fun customCategory(context: Context): ActionCategory? {
        val names = customNames(context)
        return if (names.isEmpty()) null else ActionCategory("自定义", 10, actions = names)
    }

    fun allCategories(context: Context): List<ActionCategory> =
        categories + listOfNotNull(customCategory(context))

    fun builtInAnimationNames(): Set<String> = builtInAll

    fun isCustom(name: String): Boolean = name.lowercase().endsWith(".webm")

    fun displayName(name: String): String =
        if (isCustom(name)) name.removeSuffix(".webm").removeSuffix(".WEBM") else name

    fun isNoMirror(name: String): Boolean = name in noMirrorSet

    fun isTurn(name: String): Boolean = name in turnPool

    fun isDrag(name: String): Boolean = name in dragPool

    fun isClick(name: String): Boolean = name in clicks

    fun isEvent(name: String): Boolean =
        name in balancePool || name in whisperPool || name in workStatusPool

    fun isMove(name: String): Boolean = moveActions.any { it.name == name }

    fun isWhisper(name: String): Boolean = name in whisperPool

    /** 是否存在对应素材（包内或用户导入）。 */
    fun exists(context: Context, name: String): Boolean {
        if (isCustom(name)) return File(customDir(context), name).isFile
        return runCatching {
            context.assets.openFd("pet/$name.webm").use { true }
        }.getOrDefault(false)
    }

    fun randomCategory(context: Context, facingRight: Boolean): ActionCategory? {
        val cats = allCategories(context).filter { it.actions.isNotEmpty() }
        if (cats.isEmpty()) return null
        val filtered = cats.filter { !(it.noMirror && facingRight) }
        val eligible = filtered.ifEmpty { cats }
        val total = eligible.sumOf { it.weight }.coerceAtLeast(1)
        var t = Math.random() * total
        for (c in eligible) {
            t -= c.weight
            if (t <= 0) return c
        }
        return eligible.last()
    }

    fun randomCategoryAction(context: Context, facingRight: Boolean, current: String?): String {
        val cat = randomCategory(context, facingRight) ?: return IDLE
        return randomFrom(cat.actions, current)
    }

    fun randomFrom(pool: List<String>, exclude: String? = null): String {
        if (pool.isEmpty()) return IDLE
        val filtered = if (exclude == null) pool else pool.filter { it != exclude }
        val src = filtered.ifEmpty { pool }
        return src[(Math.random() * src.size).toInt().coerceIn(0, src.size - 1)]
    }

    fun moveAction(name: String): MoveAction =
        moveActions.firstOrNull { it.name == name } ?: moveActions.first()

    /** 菜单里的动作分类（含事件与自定义，顺序与 dsh-pet 右键菜单一致）。 */
    fun menuSections(context: Context): List<Pair<String, List<String>>> {
        val sections = mutableListOf<Pair<String, List<String>>>()
        if (idlePool.isNotEmpty()) sections.add("待机" to idlePool)
        if (turnPool.isNotEmpty()) sections.add("转向" to turnPool)
        if (dragPool.isNotEmpty()) sections.add("拖拽" to dragPool)
        if (clicks.isNotEmpty()) sections.add("点击回应" to clicks)
        if (moveActions.isNotEmpty()) sections.add("移动" to moveActions.map { it.name })
        allCategories(context).forEach { sections.add(it.id to it.actions) }
        sections.add("余额档位" to balancePool)
        sections.add("碎碎念" to whisperPool)
        sections.add("工作状态" to workStatusPool)
        return sections
    }
}
