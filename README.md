# 📸 WeChat LivePhoto Unlock（微信实况照片解锁）

> 让**非白名单机型 / 旧系统**的 Android 微信完整支持**实况照片（Live Photo / Live 动图）**——相册 LIVE 角标识别、聊天预览发送、视频提取播放，全链路打通。

<p align="center">
  <b>LSPosed 版</b> · <b>Zygisk 版</b> 双方案任选
</p>

---

## ✅ 版本兼容性（重要）

| 状态 | 微信版本 | 说明 |
|---|---|---|
| ✅ **完整支持** | **8.0.69 ~ 8.0.79** | 相册 LIVE 角标、预览、发送全功能正常 |
| ⚠️ **部分支持** | 8.0.63 ~ 8.0.68 | 模块注入兼容（不崩溃），但早期版本实况识别走**系统探测**而非桩类，相册可能不显示 LIVE 角标 |
| ❌ **不支持** | < 8.0.63 | 微信尚未实装实况照片功能 |

**最低完整支持版本：8.0.69**

> 📌 已实测：8.0.69 / 8.0.70 / 8.0.71 / 8.0.72 / 8.0.74 / 8.0.76 / 8.0.77 / 8.0.78 / 8.0.79 相册 LIVE 角标与识别全部正常。

---

## ✨ 功能特性

- ✅ **相册 LIVE 角标识别** — 兼容小米 / 魅族 / 抖音 / Google Motion Photo 等主流实况格式
- ✅ **聊天选择实况并发送** — 直通秒发
- ✅ **预览动态画面** — 含 100MB+ 超大实况图，流式提取不卡死
- ✅ **接收查看实况** — 动图 + 声音完整播放
- ✅ **朋友圈发表实况**
- ✅ **增强检测** — 头部 XMP 标记（MotionPhoto / MicroVideo / GCamera）+ 尾部 ftyp 双通道
- ✅ **多版本自适应** — DexProbe 按方法签名结构动态探测关键类，微信混淆升级不失效

---

## 📦 两个版本

### 1️⃣ LSPosed 版（`LSPosed/`）

基于 **libxposed API 102** 的成熟方案。

**安装步骤**：
1. 安装 `LSPosed-backup.apk`
2. 打开 LSPosed 管理器 → 模块 → 启用 **LivePhotoUnlock**
3. 作用域勾选 **微信（com.tencent.mm）**
4. 强制停止微信并重新打开

**文件**：
- `LSPosed-backup.apk` / `LivePhotoUnlock-enhanced-v2.apk` — 模块 APK
- `LivePhotoUnlockHook-enhanced.kt` / `DexProbe.kt` — 源码

### 2️⃣ Zygisk 版（`Zygisk/`）

纯 Zygisk 方案，**无需 LSPosed**，通过 **Pine Hook 引擎**注入。

**安装步骤**：
1. KernelSU / Magisk → 安装模块 `LivePhotoUnlock-Zygisk-v2.0.zip`
2. 重启手机
3. 打开微信即可使用

**文件**：
- `LivePhotoUnlock-Zygisk-v2.0.zip` — 模块包
- `livephoto_module-v4.dex` — 业务 dex
- `source/` — 完整源码

---

## 🛠 技术原理

微信所有官方 APK 中的 `com.motion.core.LivePhotoCore` 都是**桩类**（initCore 返回 -1000、isLivePhoto 返回空表），厂商白名单只决定是否创建核心实例，不提供实现本身。本模块通过**模拟真核心**实现完整功能：

| 桩方法 | 模块实现 |
|---|---|
| `initCore(Context)` | 返回 0（成功标志）|
| `isSupport()` | 返回 true |
| `isLivePhoto(List)` | MediaStore 解析 + 文件头部 XMP 标记 + 尾部 ftyp 特征扫描 |
| `getVideoMetaData(id, path)` | 流式提取内嵌 MP4，返回完整 JSON |
| `exportLivePhoto(json)` | 图 + 视频合成动态 JPEG 保存到相册 |

**多版本自适应（DexProbe）**：按方法签名结构动态探测，不依赖固定混淆类名：

| 版本范围 | 包装类 | remux worker |
|---|---|---|
| 8.0.63 | `lo.b` | `rb4.b0` |
| 8.0.64 | `oo.b` | `oc4.b0` |
| 8.0.65 | `po.b` | `nd4.b0` |
| 8.0.66 | `xn.b` | `jf4.b0` |
| 8.0.67/68/69 | `zn.b` | `ei4.b0` / `xi4.b0` |
| 8.0.70 | `bp.b` | `wl4.b0` |
| 8.0.71 | `ep.b` | `in4.b0` |
| 8.0.72 | `gp.b` | `lo4.b0` |
| 8.0.74/76 | `qp.b` | `wp4.b0` / `ar4.b0` |
| 8.0.77 | `wp.b` | `yt4.b0` |
| 8.0.78/79 | `wp.b` | `ox4.b0` / `ky4.b0` |

---

## ⚠️ 注意事项

- ⚠️ **两版不能同时启用**（会冲突导致微信崩溃 SIGSEGV）
- ⚠️ 使用 Xposed / Zygisk 注入**可能触发微信风控**，**强烈建议先小号测试**
- ⚠️ **8.0.63~8.0.68** 早期版本实况识别走系统探测，模块兼容但不保证显示 LIVE 角标
- 微信升级后混淆类名会变化，Zygisk 版已内置 DexProbe 动态探测应对

---

## 📥 下载

前往 [**Releases**](https://github.com/lyg1106-cell/wechat-livephoto-unlock/releases) 下载最新版本。

---

## 📄 免责声明

本项目仅用于**技术研究和学习**。使用本模块可能触发微信风控（账号异常、功能受限、封号等），开发者不承担任何责任。请勿用于任何商业用途。

⭐ 如果这个项目对你有帮助，欢迎 Star！
