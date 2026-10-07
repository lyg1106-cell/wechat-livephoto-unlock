# WeChat LivePhoto Unlock（微信实况照片解锁）

让非白名单机型 / 旧系统的 Android 微信（com.tencent.mm 8.0.78+）完整支持**实况照片（Live Photo / Live 动图）**：相册 LIVE 角标识别、聊天预览发送、视频提取播放。

## ✨ 特性

- ✅ 相册 LIVE 角标识别（兼容小米 / 魅族 / 抖音 / Google Motion Photo 等内嵌式实况格式）
- ✅ 聊天选择实况并发送
- ✅ 预览动态画面（含 100MB+ 超大实况图，流式提取不卡死）
- ✅ 接收查看实况（动图 + 声音）
- ✅ 朋友圈发表实况
- ✅ 增强检测：头部 XMP 标记（MotionPhoto/MicroVideo/GCamera）+ 尾部 ftyp 双通道
- ✅ 多版本自适应：DexProbe 按方法签名结构动态探测关键类（微信混淆升级不失效）

## 📦 两个版本

### 1. LSPosed 版（`LSPosed/`）

基于 libxposed API 102 的成熟方案，功能全通。

**安装**：
1. 安装 `LSPosed-backup.apk`
2. LSPosed 管理器 → 模块 → 启用 `LivePhotoUnlock`
3. 作用域勾选 `微信 (com.tencent.mm)`
4. 强制停止微信并重新打开

**文件**：
- `LSPosed-backup.apk` — 可直接安装的模块 APK
- `LivePhotoUnlockHook-enhanced.kt` — 增强检测核心源码
- `DexProbe.kt` — dex 结构探测引擎

### 2. Zygisk 版（`Zygisk/`）

纯 Zygisk 方案，**无需 LSPosed**，通过 Pine Hook 引擎注入。

**安装**：
1. KernelSU / Magisk → 安装模块 `LivePhotoUnlock-Zygisk-v2.0.zip`
2. 重启手机
3. 打开微信即可使用

**文件**：
- `LivePhotoUnlock-Zygisk-v2.0.zip` — 可安装的 Zygisk 模块包
- `livephoto_module-v4.dex` — 最新业务逻辑（可手动替换进模块）
- `source/` — 完整源码（Java Hook + C++ 注入器 + Gradle 配置）

## 🛠 技术原理

微信所有官方 APK 中的 `com.motion.core.LivePhotoCore` 都是**桩类**（initCore 返回 -1000、isLivePhoto 返回空表），厂商白名单只决定是否创建核心实例，不提供实现本身。模块通过**模拟真核心**实现：

| 桩方法 | 模块实现 |
|---|---|
| `initCore(Context)` | 返回 0（成功）|
| `isSupport()` | 返回 true |
| `isLivePhoto(List)` | MediaStore 解析 + 文件头部 XMP 标记 + 尾部 ftyp 特征扫描 |
| `getVideoMetaData(id, path)` | 流式提取内嵌 MP4，返回 `{errorCode,videoPath,videoSize,videoDuration,videoWidth,videoHeight,coverTimeStampMs}` |
| `exportLivePhoto(json)` | 图 + 视频合成动态 JPEG 保存到相册 |

关键门控：
- `wp.b.e = true` + 核心实例注入
- `wp/b.b` 校验放行（绕过宽高比 Ratio Error）
- 聊天实况门控 `mq5.f.a() -> true` / 预览配置 `c() -> 1`
- 9 项 Repairer 实况配置写入 MMKV
- expt 云控开关 + G6 厂商标记

## ⚠️ 注意事项

- **两版不能同时启用**（会冲突导致微信崩溃）
- 使用 Xposed/Zygisk 注入可能触发微信风控，**建议先小号测试，风险自负**
- 微信升级后混淆类名变化，Zygisk 版已内置 DexProbe 动态探测应对

## 📄 免责声明

本项目仅用于技术研究和学习。使用本模块可能触发微信风控（账号异常、功能受限、封号等），开发者不承担任何责任。
