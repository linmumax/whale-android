# 鲸鱼娘 WhalePet · Android 桌面宠物

一只住在手机桌面上的 Q 版鲸鱼娘。透明视频播放、漫游、拖拽甩飞、AI 对话与养成，全部离线可跑，填上 API Key 后变成真 AI 女仆。

| 桌面效果 | 主页 | 设置 |
| --- | --- | --- |
| ![桌面](docs/screenshots/desktop.png) | ![主页](docs/screenshots/home.png) | ![设置](docs/screenshots/settings.png) |

## 功能

- **真透明动画**：106 个带 alpha 通道的 VP9 WebM 动作，WebView 双 `<video>` 交替播放 + canvas 冻结帧 + 首帧上屏后再切换，切换动作不闪黑也不闪灰白。
- **多宠同屏**：最多 5 只，每只独立悬浮窗、独立尺寸/位置/开关，互不遮挡触控。
- **手感**：拖拽弹簧跟手、松手惯性甩飞、落地挤压回弹、左右贴边，窗口只覆盖角色本身，不会整屏吃触摸。
- **互动**：点按摸头回应、双击/长按打开动作菜单（对话、碎碎念、喂食、余额、回原位、固定在此处，分类标签自动换行）。
- **宠物固定**：位置锁死、不漫游不甩飞，动画与点按照常；重启后仍停在原地。
- **AI 陪伴**：DeepSeek API 对话、上下文记忆、对话配图、联网碎碎念、余额查询与档位动画；不填 Key 时使用本地人格聊天，不消耗 token。
- **可调性**：体型、角落/自由位置、边距、物理参数、碎碎念间隔、自定义 WebM 动画导入、边界校准。

## 环境要求

- Android 13（API 33）及以上
- Android Studio Ladybug 或更新版本，JDK 17+
- 首次安装需授予「显示在其他应用上层」权限，Android 13+ 还需通知权限

## 构建

```bash
git clone https://github.com/<你的用户名>/<仓库名>.git
cd <仓库名>
./gradlew assembleRelease
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。产物在 `app/build/outputs/apk/release/app-release.apk`，也可以直接用 Android Studio 打开本目录后点击 Run。

> release 目前复用 debug 签名，方便侧载安装；如果要上架或长期维护，请替换成自己的 keystore。

## 使用

1. 打开 App，点「放出鲸鱼娘」，桌面即出现角色。
2. 长按（或双击）宠物打开菜单，可以对话、换动作、喂食、固定位置。
3. 设置页里调整体型/位置、添加第二只、填 DeepSeek API Key、导入自定义动画。

## 技术栈

- Kotlin + AppCompat，单 Activity + 悬浮窗（`TYPE_APPLICATION_OVERLAY`）
- WebView 内 `player.html` 播放 `assets/pet/*.webm`（VP9 + alpha）
- 配置 JSON 持久化（`SharedPreferences` + `ConfigStore` 单例，变更即时同步到悬浮窗）
- `Choreographer` 驱动的弹簧拖拽、抛掷物理、漫游与挤压动画

## 目录结构

```
app/src/main/
├── java/com/whale/pet/    # 悬浮窗、菜单、设置、AI、养成等
├── assets/pet/            # 角色动画（透明 WebM）
├── assets/memes/          # 表情包
├── assets/fonts/          # 字体
└── res/                   # 图标与主题
```

## 素材与版权

- **代码**：MIT License（见 [LICENSE](LICENSE)）。
- **角色动画、表情包、字体**：来自网络的同人/网络素材，仅用于学习与个人使用，版权归原作者所有；本仓库的 MIT 协议不覆盖这些素材。若要商用或再分发，请替换为你自己拥有授权的资源（字体「上首软糖体」请自行确认授权范围）。

## 已知限制

- 动画依赖 WebView 的 VP9 alpha 解码，极少数老旧机型可能显示黑底。
- 悬浮窗按角色矩形完全拦截触摸（不在矩形透明处穿透），以免误触下层桌面图标。
- 未配置 API Key 时没有联网碎碎念与余额查询。
