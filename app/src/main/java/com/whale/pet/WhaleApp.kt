package com.whale.pet

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * 仅聊天页暂时收起悬浮宠物；主页和设置页都能直接看到、拖动、点按桌宠。
 * 跨页面切换时做 250ms 防抖，避免闪烁。
 */
class WhaleApp : Application(), Application.ActivityLifecycleCallbacks {
    private val resumed = mutableSetOf<Activity>()
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    private fun scheduleUpdate() {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pending = null
            // 聊天页隐藏桌宠，避免挡聊天内容；主页和设置页都保留桌宠可见。
            val hide = resumed.any { it is ChatActivity }
            PetOverlayService.instance?.setUiHidden(hide)
        }
        pending = r
        main.postDelayed(r, 250L)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed.add(activity)
        scheduleUpdate()
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        scheduleUpdate()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
