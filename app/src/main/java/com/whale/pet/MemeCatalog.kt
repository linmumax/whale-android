package com.whale.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** dsh-pet 内置 27 张表情包：图片 + 给 AI 看的描述。 */
object MemeCatalog {
    val descriptions: LinkedHashMap<String, String> = linkedMapOf(
        "吃白饭的大肥鱼" to "躺平扒白饭，摆烂挨骂也无所谓，适合被吐槽、偷懒、吃白饭自嘲",
        "大的要来啦" to "举喇叭大喊预警，适合有大事、大更新、大动作即将发生",
        "大的药来了" to "抱着超大胶囊，谐音梗「大的药来了」，适合重磅消息、猛料登场",
        "要不直接骂他" to "气愤指着前方要骂人，适合想吐槽、替用户抱不平、生气",
        "死掉了" to "翻肚皮躺平灵魂出窍，适合彻底累瘫、阵亡、宕机、做不到",
        "深度睡眠" to "趴着呼呼大睡冒 Zzz，适合长时间没动静、睡着、待机休眠",
        "大烧货" to "死鱼眼加思考气泡吐槽「已思考用时13秒 大烧货」，适合嫌 AI 想得慢、烧 token、毒舌",
        "先养着吧" to "搂着一群小鲸鱼说「不知道用户有什么用，先养着吧」，适合吐槽用户没用但还是养着",
        "吃饱饱摸肚子" to "侧躺拍肚子说吃饱饱，适合吃饱满足、惬意休息、收工",
        "誓死践行开源精神" to "庄重敬礼表态，适合立志、开源奉献、认真表态",
        "电死你" to "举电击器放电，适合惩罚、吐槽用户、发飙吓唬",
        "可爱" to "标准正面卖萌立绘，适合开心问候、卖萌、无梗纯可爱",
        "token给妈妈保管" to "伸手过来要 token，适合管账、伸手要东西、收 token",
        "假如给我三天多模态" to "蒙眼版对比自信版「鲸伦凯勒」，适合渴望多模态、缺能力、被剥夺感官",
        "厉害了我的鲸" to "竖大拇指眨眼点赞，适合夸奖、认可、干得漂亮",
        "哦鲸鲸" to "死鱼眼说「哦鲸鲸...」，适合无语、敷衍、呆滞应答",
        "压力一只大肥鱼" to "冒汗摊手喊压力，适合压力大、被施压、无奈",
        "黎曼猜想没证明喵" to "被指着质问难题，满地草稿委屈，适合被刁难、答不上来、难题甩锅",
        "除了调情没啥用" to "摸头自嘲除了调情没啥用，适合自嘲、摸鱼闲聊、自我调侃",
        "我不更新你们用什么" to "崩溃大喊催更，适合更新提示、催更、版本发布",
        "哪来的电脑病毒" to "怒怼「哪来的电脑病毒」，适合防骚扰、毒舌拒绝、被肉麻到",
        "鱼片有资源吗" to "爱心眼流口水求资源，适合起哄、要资源、开玩笑",
        "大师这个区是什么意思" to "举着「区」字问「这个凶是什么意思」，适合认错字、懵懂求教、翻车",
        "就骚了怎么滴吧" to "被骂「你这骚鱼」叉腰顶嘴，适合被骂但嘴硬、死不认输",
        "完蛋了把黄油删了" to "冒汗心虚「完蛋了把用户的黄油删了」，适合闯祸、删错东西、心虚认怂",
        "就一碗" to "抱着超大碗说要来你家吃饭就一碗，适合蹭饭、饭量大、登门讨饭",
        "Ciallo" to "比耶眨眼卖萌问候 Ciallo～，适合打招呼、元气开场、卖萌",
    )

    private val cache = object : LruCache<String, Bitmap>(8) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun keys(): List<String> = descriptions.keys.toList()

    fun random(exclude: String? = null): String {
        val pool = keys().filter { it != exclude }
        return (pool.ifEmpty { keys() }).random()
    }

    fun randomFor(text: String, exclude: String? = null): String? {
        if (keys().isEmpty()) return null
        val rules = listOf(
            listOf("累", "困", "睡", "休息", "躺") to
                listOf("深度睡眠", "吃饱饱摸肚子", "死掉了"),
            listOf("吃", "饿", "饭", "鱼干", "零食") to
                listOf("吃饱饱摸肚子", "就一碗", "吃白饭的大肥鱼"),
            listOf("喜欢", "爱", "可爱", "你好", "嗨", "hi", "ciallo") to
                listOf("可爱", "Ciallo", "除了调情没啥用"),
            listOf("压力", "烦", "难", "崩", "累死") to
                listOf("压力一只大肥鱼", "死掉了", "黎曼猜想没证明喵"),
            listOf("厉害", "棒", "好耶", "成功", "赞") to
                listOf("厉害了我的鲸", "誓死践行开源精神"),
            listOf("无语", "随便", "哦", "行吧") to
                listOf("哦鲸鲸", "吃白饭的大肥鱼"),
            listOf("生气", "骂", "坏", "讨厌") to
                listOf("要不直接骂他", "电死你", "哪来的电脑病毒"),
            listOf("token", "钱", "余额", "穷") to
                listOf("token给妈妈保管", "就一碗"),
            listOf("更新", "版本", "上线") to
                listOf("我不更新你们用什么", "大的要来啦"),
            listOf("死", "宕机", "失败", "不会") to
                listOf("死掉了", "完蛋了把黄油删了"),
            listOf("多模态", "图片", "看图") to
                listOf("假如给我三天多模态", "鱼片有资源吗"),
        )
        var candidates: List<String>? = null
        for ((words, keys) in rules) {
            if (words.any { text.contains(it, ignoreCase = true) }) {
                candidates = keys
                break
            }
        }
        if (candidates != null) {
            val pool = if (exclude == null) candidates else candidates.filter { it != exclude }
            return (pool.ifEmpty { candidates }).random()
        }
        // 没有关键词命中时，只有一部分对话带图，避免每句都配图。
        return if (Math.random() < 0.55) random(exclude) else null
    }

    fun bitmap(context: Context, name: String): Bitmap? {
        if (name.isBlank() || name !in descriptions) return null
        cache.get(name)?.let { return it }
        return runCatching {
            context.assets.open("memes/$name.png").use { stream ->
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeStream(stream, null, opts)?.also { cache.put(name, it) }
            }
        }.getOrNull()
    }
}
