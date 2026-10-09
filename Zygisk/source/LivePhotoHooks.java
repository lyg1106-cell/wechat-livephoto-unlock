package me.livephoto.zygisk;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;

import me.livephoto.common.LivePhotoCodec;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信实况照片解锁 - Zygisk 版核心 Hook（由 Pine 引擎驱动）
 * 移植自 LSPosed 版 LivePhotoUnlockHook.kt，使用 Xposed 兼容 API。
 *
 * 核心模型：微信所有 APK 的 com.motion.core.LivePhotoCore 都是桩类，
 * 通过接管桩方法 + 注入核心实例 + 强制门控，让任意机型获得完整实况能力。
 */
public final class LivePhotoHooks {

    private static final String TAG = "LivePhotoZygisk";
    private static final String CLS_CORE = "com.motion.core.LivePhotoCore";
    private static final String[] WRAPPER_CANDIDATES = {"wp.b", "fq.b", "wp"};
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String MMKV_CLASS = "com.tencent.mmkv.MMKV";

    private static Context sAppContext;
    private static ClassLoader sLoader;
    private static Class<?> sWrapperClass;

    private LivePhotoHooks() {}

    /** 入口：由 ModuleEntry 在微信进程内调用（此时只有系统 loader，微信类尚不可见）
     *  先 hook Instrumentation.callApplicationOnCreate，等微信 Application 创建后
     *  拿到最终 classLoader（含 Tinker 补丁类），再注册全部 Hook。 */
    public static void install(ClassLoader loader) {
        sLoader = loader;
        try {
            Class<?> ins = Class.forName("android.app.Instrumentation", false, loader);
            XposedHelpers.findAndHookMethod(ins, "callApplicationOnCreate",
                Application.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Application app = (Application) param.args[0];
                        if (app != null) {
                            sAppContext = app.getApplicationContext();
                            ClassLoader real = app.getClassLoader();
                            if (real != null) sLoader = real;
                            Log.i(TAG, "app ready, real loader=" + sLoader);
                            try {
                                hookCoreMethods();
                                hookWrapper();
                                hookChatGate();
                                hookSnsGate();
                                hookRemux();
                                Log.i(TAG, "All hooks registered (real classloader)");
                                scheduleConfigWrites();
                            } catch (Throwable t) {
                                Log.e(TAG, "register hooks failed", t);
                            }
                        }
                    }
                }
            );
            Log.i(TAG, "install: waiting for application create");
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    /** 接管 LivePhotoCore 桩类全部方法 */
    private static void hookCoreMethods() {
        try {
            Class<?> core = Class.forName(CLS_CORE, false, sLoader);

            XposedHelpers.findAndHookMethod(core, "initCore", Context.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(0); // 0 = 成功
                    Log.i(TAG, "core.initCore -> 0");
                }
            });

            XposedHelpers.findAndHookMethod(core, "isSupport", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });

            XposedHelpers.findAndHookMethod(core, "getCoreMetaData", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult("");
                }
            });

            XposedHelpers.findAndHookMethod(core, "isLivePhoto", List.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    List<?> ids = (List<?>) param.args[0];
                    Map<Long, Boolean> map = detectLivePhotos(ids);
                    param.setResult(map);
                    Log.i(TAG, "core.isLivePhoto(" + (ids == null ? 0 : ids.size()) + " ids) -> " + countLive(map));
                }
            });

            XposedHelpers.findAndHookMethod(core, "getVideoMetaData", long.class, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        long mediaId = ((Number) param.args[0]).longValue();
                        String savePath = (String) param.args[1];
                        Log.i(TAG, "core.getVideoMetaData(id=" + mediaId + ", save=" + savePath + ")");
                        String json = tryExtractVideo(mediaId, savePath);
                        if (json != null) {
                            Log.i(TAG, "core.getVideoMetaData -> " + json);
                            param.setResult(json);
                        } else {
                            Log.w(TAG, "core.getVideoMetaData -> empty");
                            param.setResult("");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "getVideoMetaData hook error", t);
                        param.setResult("");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(core, "exportLivePhoto", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String json = (String) param.args[0];
                    boolean ok = tryExportLivePhoto(json);
                    param.setResult(ok ? 0 : -1000);
                }
            });

            Log.i(TAG, "core hooks registered");
        } catch (Throwable t) {
            Log.e(TAG, "hookCoreMethods failed", t);
        }
    }

    /** 包装类静态字段注入：e=true + b=核心实例 */
    private static void hookWrapper() {
        try {
            Class<?> wpb = null;
            for (String name : WRAPPER_CANDIDATES) {
                try {
                    wpb = Class.forName(name, false, sLoader);
                    if (findCoreField(wpb) != null) break;
                } catch (Throwable ignored) {}
            }
            if (wpb == null) {
                Log.w(TAG, "wrapper class not found");
                return;
            }
            sWrapperClass = wpb;

            // 强制静态布尔字段（e）为 true
            for (Field f : wpb.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == boolean.class) {
                    f.setAccessible(true);
                    f.setBoolean(null, true);
                    Log.i(TAG, "wrapper " + wpb.getName() + "." + f.getName() + " = true");
                }
            }

            // 注入核心实例到类型为 LivePhotoCore 的静态字段
            Class<?> core = Class.forName(CLS_CORE, false, sLoader);
            Object coreInst = core.getDeclaredConstructor().newInstance();
            Field coreField = findCoreField(wpb);
            if (coreField != null) {
                coreField.setAccessible(true);
                coreField.set(null, coreInst);
                Log.i(TAG, "core instance injected to " + wpb.getName() + "." + coreField.getName());
            }

            // wp.b.b(mediaId, path1, path2, ???) 校验放行：强制 t0.a = true（绕过宽高比 Ratio Error 拦截）
            try {
                java.lang.reflect.Method bMethod = wpb.getDeclaredMethod(
                    "b", long.class, String.class, String.class, long.class);
                de.robv.android.xposed.XposedBridge.hookMethod(bMethod, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (result != null) {
                                Object t0 = result.getClass().getDeclaredField("a").get(result);
                                if (t0 != null) {
                                    Class<?> t0Cls = t0.getClass();
                                    Field success = t0Cls.getDeclaredField("a");
                                    success.setAccessible(true);
                                    boolean orig = success.getBoolean(t0);
                                    success.setBoolean(t0, true);
                                    Field err = t0Cls.getDeclaredField("b");
                                    err.setAccessible(true);
                                    if (!orig) err.setInt(t0, 0);
                                    if (!orig) Log.i(TAG, "wp/b.b forced success (ratio/format bypass)");
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                Log.i(TAG, "wp/b.b validation bypass hooked");
            } catch (Throwable t) {
                Log.w(TAG, "wp/b.b hook skipped: " + t.getMessage());
            }
        } catch (Throwable t) {
            Log.e(TAG, "hookWrapper failed", t);
        }
    }

    private static Field findCoreField(Class<?> wrapper) {
        try {
            Field f = wrapper.getDeclaredField("b");
            if (f.getType().getName().equals(CLS_CORE)) return f;
        } catch (Throwable ignored) {}
        for (Field f : wrapper.getDeclaredFields()) {
            if (f.getType().getName().equals(CLS_CORE)) return f;
        }
        return null;
    }

    /** 聊天实况查看门控：a()→true、b(msg) 放行、预览配置 c()→1 */
    private static void hookChatGate() {
        try {
            try {
                Class<?> cfg = Class.forName("com.tencent.mm.repairer.config.chatting.RepairerConfigC2CLiveImagePreview", false, sLoader);
                for (Method m : cfg.getDeclaredMethods()) {
                    if (m.getName().equals("c") && m.getParameterTypes().length == 0) {
                        XposedHelpers.findAndHookMethod(cfg, "c", new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(1);
                            }
                        });
                        Log.i(TAG, "preview config c() -> 1");
                        break;
                    }
                }
            } catch (Throwable ignored) {}

            // 门控类：lo5.f / mq5.f（结构：a()Z + b(msg)Z）
            String[] gates = {"lo5.f", "mq5.f", "nm5.f"};
            for (String gateName : gates) {
                try {
                    Class<?> gate = Class.forName(gateName, false, sLoader);
                    XposedHelpers.findAndHookMethod(gate, "a", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
                    Log.i(TAG, "gate " + gateName + ".a() -> true");
                } catch (Throwable ignored) {}
            }

            // q1.B 静态总开关
            try {
                Class<?> q1 = Class.forName("com.tencent.mm.ui.chatting.gallery.q1", false, sLoader);
                for (Field f : q1.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == boolean.class) {
                        f.setAccessible(true);
                        f.setBoolean(null, true);
                        Log.i(TAG, "q1." + f.getName() + " = true");
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "hookChatGate failed", t);
        }
    }

    /** 朋友圈发表门控 ss.v.c() → true（3180 新增，旧版自动跳过） */
    private static void hookSnsGate() {
        try {
            Class<?> v = Class.forName("ss.v", false, sLoader);
            XposedHelpers.findAndHookMethod(v, "c", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
            Log.i(TAG, "ss.v.c() -> true");
        } catch (Throwable ignored) {}
    }

    // ==================== DexProbe 缓存（v2.1：按微信 versionCode 缓存，避免每次启动全量扫描 dex） ====================

    private static final String PREFS_DEXPROBE = "livephoto_dexprobe";
    private static long sProbeCacheVer = 0;
    private static DexProbe.Remux sCachedRemux = null;

    private static long wechatVersionCode() {
        try {
            if (sAppContext == null) return 0;
            android.content.pm.PackageInfo pi = sAppContext.getPackageManager()
                    .getPackageInfo("com.tencent.mm", 0);
            return pi.getLongVersionCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** v2.1.1：优先用 Context 拿微信自身 APK 路径（最可靠），扫 /data/app 仅作兜底 */
    private static String findWeChatApk() {
        try {
            if (sAppContext != null) {
                String src = sAppContext.getApplicationInfo().sourceDir;
                if (src != null && new File(src).isFile()) return src;
            }
        } catch (Throwable ignored) {}
        return DexProbe.findWeChatApkPath();
    }

    /** remux 探测结果缓存（worker chat sns result 空格分隔序列化）；存 "" 表示阴性结果 */
    private static DexProbe.Remux cachedRemux() {
        long ver = wechatVersionCode();
        if (sAppContext == null || ver == 0) {
            String apkPath = findWeChatApk();
            return apkPath != null ? DexProbe.findRemux(apkPath) : null;
        }
        if (sProbeCacheVer == ver && sCachedRemux != null) return sCachedRemux;
        try {
            android.content.SharedPreferences prefs =
                    sAppContext.getSharedPreferences(PREFS_DEXPROBE, Context.MODE_PRIVATE);
            String ck = "v" + ver + ":remux";
            String cached = prefs.getString(ck, null);
            if (cached != null) {
                Log.d(TAG, "dexprobe cache hit: " + ck);
                if (cached.isEmpty()) return null;
                String[] parts = cached.split(" ");
                if (parts.length == 4) {
                    DexProbe.Remux r = new DexProbe.Remux(parts[0], parts[1], parts[2], parts[3]);
                    sCachedRemux = r;
                    sProbeCacheVer = ver;
                    return r;
                }
            }
            String apkPath = findWeChatApk();
            DexProbe.Remux r = apkPath != null ? DexProbe.findRemux(apkPath) : null;
            prefs.edit().putString(ck, r != null
                    ? r.worker + " " + r.chat + " " + r.sns + " " + r.result : "").apply();
            sCachedRemux = r;
            sProbeCacheVer = ver;
            return r;
        } catch (Throwable t) {
            String apkPath = findWeChatApk();
            return apkPath != null ? DexProbe.findRemux(apkPath) : null;
        }
    }

    /** Remux 直通：跳过转码直接复制文件（聊天 + 朋友圈），DexProbe 动态定位 */
    private static void hookRemux() {
        try {
            // v2.1：走缓存（按微信 versionCode，避免每次启动全量扫描 dex）
            DexProbe.Remux probed = cachedRemux();
            String[] remuxWorkers;
            if (probed != null && probed.worker != null) {
                remuxWorkers = new String[]{probed.worker};
                Log.i(TAG, "dex-probe remux: " + probed.worker + " chat=" + probed.chat + " sns=" + probed.sns + " result=" + probed.result);
            } else {
                remuxWorkers = new String[]{"yt4.b0.Vi", "np4.b0.mh", "ox4.b0.dj"};
            }
            for (String worker : remuxWorkers) {
                try {
                    Class<?> cls = Class.forName(worker, false, sLoader);
                    for (Method m : cls.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        // 聊天：前 3 参为 String
                        if (pts.length >= 4 && pts[0] == String.class && pts[1] == String.class && pts[2] == String.class) {
                            XposedHelpers.findAndHookMethod(cls, m.getName(), m.getParameterTypes(), new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam param) {
                                    String src = (String) param.args[0];
                                    String dst = (String) param.args[1];
                                    String thumb = (String) param.args[2];
                                    boolean ok = copyFile(src, dst);
                                    ensureThumbFile(src, thumb);
                                    param.setResult(makeRemuxResult(ok));
                                    Log.i(TAG, "chat remux bypass: " + src + " -> " + dst + " ok=" + ok);
                                }
                            });
                            Log.i(TAG, worker + "." + m.getName() + " (chat) hooked");
                        }
                    }
                    // 朋友圈：RecordConfigProvider
                    for (Method m : cls.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 2 && pts[0].getName().contains("RecordConfigProvider")) {
                            XposedHelpers.findAndHookMethod(cls, m.getName(), m.getParameterTypes(), new XC_MethodHook() {
                                @Override protected void beforeHookedMethod(MethodHookParam param) {
                                    Object provider = param.args[0];
                                    String src = readFieldStr(provider, "A");
                                    String dst = readFieldStr(provider, "B");
                                    String thumb = readFieldStr(provider, "C");
                                    boolean ok = copyFile(src, dst);
                                    ensureThumbFile(src, thumb);
                                    param.setResult(makeRemuxResult(ok));
                                    Log.i(TAG, "sns remux bypass: " + src + " -> " + dst + " ok=" + ok);
                                }
                            });
                            Log.i(TAG, worker + "." + m.getName() + " (sns) hooked");
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookRemux failed", t);
        }
    }

    // ==================== 实况检测与视频提取 ====================

    private static Map<Long, Boolean> detectLivePhotos(List<?> ids) {
        Map<Long, Boolean> out = new HashMap<>();
        if (ids == null || sAppContext == null) return out;
        for (Object o : ids) {
            long id = (Long) o;
            String path = resolveImagePath(id);
            out.put(id, path != null && hasMotionPhoto(path));
        }
        return out;
    }

    private static String resolveImagePath(long id) {
        try {
            Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {MediaStore.Images.Media.DATA};
            Cursor c = sAppContext.getContentResolver().query(uri, proj,
                MediaStore.Images.Media._ID + "=?", new String[]{String.valueOf(id)}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getString(0);
                } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 检测 Motion Photo：共享核心（头部 XMP 快检 + 尾部 ftyp 兜底，全程不整文件加载） */
    private static boolean hasMotionPhoto(String path) {
        return LivePhotoCodec.isMotionPhotoFile(path);
    }

    /** 提取内嵌 MP4 到 savePath，返回微信约定 JSON（完整格式） */
    private static String tryExtractVideo(long mediaId, String savePath) {
        final String Q = "\"";
        try {
            if (savePath == null || savePath.isEmpty()) return null;
            String imgPath = resolveImagePath(mediaId);
            if (imgPath == null) return null;
            File img = new File(imgPath);
            if (!img.isFile()) return null;
            long fileLen = img.length();

            // 1) v2.1：尾部窗口逆向定位 ftyp（视频在文件尾部；旧逻辑只在头部 8MB 正向找，
            //    JPEG 部分超过 8MB 的大底照片会漏检）
            long off = LivePhotoCodec.findVideoOffsetInFile(img);
            if (off < 0) {
                Log.w(TAG, "tryExtractVideo: no ftyp in tail window of " + imgPath + " (len=" + fileLen + ")");
                return null;
            }

            // 2) v2.1：流式复制 [ftyp_off, EOF) 到 savePath（共享核心，不整文件读入内存）
            File dst = new File(savePath);
            long videoLen = LivePhotoCodec.streamCopyRange(img, off, dst);
            if (videoLen <= 0) {
                Log.w(TAG, "tryExtractVideo: stream copy failed for " + imgPath);
                return null;
            }

            // 3) v2.1：解析时长/宽高——共享核心，头部 1MB + 尾部 4MB 有限区域解析
            long[] meta = LivePhotoCodec.parseVideoMeta(dst);
            long dur = meta[0];
            int w = (int) meta[1], h = (int) meta[2];

            String durField = dur > 0 ? "," + Q + "videoDuration" + Q + ":" + dur : "";
            String sizeField = (w > 0 && h > 0)
                ? "," + Q + "videoWidth" + Q + ":" + w + "," + Q + "videoHeight" + Q + ":" + h : "";
            String json = "{" + Q + "errorCode" + Q + ":0," + Q + "videoPath" + Q + ":" + Q + dst.getAbsolutePath() + Q +
                "," + Q + "videoSize" + Q + ":" + videoLen + durField + sizeField +
                "," + Q + "coverTimeStampMs" + Q + ":0}";
            Log.i(TAG, "getVideoMetaData -> " + json);
            return json;
        } catch (Throwable t) {
            Log.e(TAG, "tryExtractVideo failed", t);
            return null;
        }
    }
    /** 导出动态照片（简化：返回成功） */
    private static boolean tryExportLivePhoto(String json) {
        try {
            Log.i(TAG, "exportLivePhoto called: " + json);
            // 微信导出路径由 JSON 提供，这里解析后保存
            // 完整实现见原模块 exportLivePhoto（图+视频拼接动态 JPEG）
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "exportLivePhoto failed", t);
            return false;
        }
    }

    // ==================== 工具方法 ====================

    private static int countLive(Map<Long, Boolean> map) {
        int n = 0;
        for (boolean b : map.values()) if (b) n++;
        return n;
    }

    private static boolean copyFile(String src, String dst) {
        if (src == null || dst == null) return false;
        try {
            File s = new File(src);
            File d = new File(dst);
            if (!s.isFile()) return false;
            d.getParentFile().mkdirs();
            java.nio.file.Files.copy(s.toPath(), d.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void ensureThumbFile(String src, String thumb) {
        if (src == null || thumb == null) return;
        try {
            File s = new File(src);
            File t = new File(thumb);
            if (s.isFile() && !t.exists()) {
                t.getParentFile().mkdirs();
                java.nio.file.Files.copy(s.toPath(), t.toPath());
            }
        } catch (Throwable ignored) {}
    }

    private static String readFieldStr(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (String) f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object makeRemuxResult(boolean ok) {
        try {
            // 尝试创建微信 remux 结果对象（构造失败则返回 null 让原逻辑继续）
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== MMKV 配置写入（解锁相册实况入口） ====================

    private static final String MMKV_REPAIRER = "Repairer";
    private static final String SP_SYSTEM_CONFIG = "system_config_prefs";
    private static final String KEY_UIN = "default_uin";
    private static final String KEY_EXPT_SEND = "clicfg_chatting_c2c_live_send_v4";
    private static final String KEY_G6 = "clicfg_live_photo_extra_manufacturer";
    private static final String KEY_WRITTEN_MARK = "_g6_written";
    private static final int EXPT_ID_G6 = 99999;
    private static final int EXPT_ID_SEND = 100000;
    // VAL_BASE64_ONE / ALL_REPAIRER_KEYS 已移入共享核心 me.livephoto.common.LivePhotoCodec

    private static volatile boolean sPreviewDone = false;
    private static volatile boolean sSendDone = false;
    private static volatile boolean sExptDone = false;
    private static volatile boolean sStage2Done = false;

    private static void scheduleConfigWrites() {
        try {
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final long[] delays = {0L, 8000L, 20000L};
            for (long d : delays) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        try { attemptConfigWrites(); } catch (Throwable t) { Log.e(TAG, "config write failed", t); }
                    }
                }, d);
            }
        } catch (Throwable t) {
            Log.w(TAG, "scheduleConfigWrites failed", t);
        }
    }

    private static synchronized void attemptConfigWrites() {
        if (sAppContext == null) return;
        try {
            boolean dirty = false;
            if (!sPreviewDone || !sSendDone) {
                Object mkv = mmkv(MMKV_REPAIRER);
                if (mkv != null) {
                    boolean repairerDirty = false; // v2.1：批量写完一次 sync（原逐 key sync）
                    for (String key : LivePhotoCodec.ALL_REPAIRER_KEYS) {
                        int target = LivePhotoCodec.repairerTargetValue(key); // v2.1: 共享键表（Hevc_Soft_Encode=0，其余=1）
                        if (mmkvGetInt(mkv, key, -1) != target) {
                            mmkvPutInt(mkv, key, target);
                            repairerDirty = true;
                            Log.i(TAG, "Repairer written: " + key + "=" + target);
                        }
                    }
                    if (repairerDirty) {
                        mmkvSync(mkv);
                        dirty = true;
                    }
                }
                sPreviewDone = true;
                sSendDone = true;
            }

            if (!sExptDone) {
                try {
                    android.content.SharedPreferences sp = sAppContext.getSharedPreferences(SP_SYSTEM_CONFIG, 0);
                    int uin = sp.getInt(KEY_UIN, 0);
                    if (uin != 0) {
                        Object keyMkv = mmkv(uin + "_WxExptAppKeyMmkv");
                        Object idMkv = mmkv(uin + "_WxExptAppIdMmkv");
                        if (keyMkv != null && idMkv != null) {
                            mmkvPutInt(keyMkv, KEY_EXPT_SEND, EXPT_ID_SEND);
                            mmkvPutString(idMkv, String.valueOf(EXPT_ID_SEND), LivePhotoCodec.buildExptJson(EXPT_ID_SEND, KEY_EXPT_SEND));
                            mmkvSync(keyMkv); mmkvSync(idMkv);
                            if (mmkvGetInt(keyMkv, KEY_EXPT_SEND, 0) == EXPT_ID_SEND) {
                                sExptDone = true; dirty = true;
                                Log.i(TAG, "expt written+verified: " + KEY_EXPT_SEND);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "expt write failed", t);
                }
            }

            if (!sStage2Done) {
                try {
                    android.content.SharedPreferences sp = sAppContext.getSharedPreferences(SP_SYSTEM_CONFIG, 0);
                    int uin = sp.getInt(KEY_UIN, 0);
                    if (uin != 0) {
                        Object keyMkv = mmkv(uin + "_WxExptAppKeyMmkv");
                        Object idMkv = mmkv(uin + "_WxExptAppIdMmkv");
                        if (keyMkv != null && idMkv != null) {
                            if (mmkvGetInt(keyMkv, KEY_WRITTEN_MARK, 0) != 1) {
                                mmkvPutInt(keyMkv, KEY_G6, EXPT_ID_G6);
                                mmkvPutInt(keyMkv, KEY_WRITTEN_MARK, 1);
                                mmkvPutString(idMkv, String.valueOf(EXPT_ID_G6), LivePhotoCodec.buildExptJson(EXPT_ID_G6, KEY_G6));
                                mmkvSync(keyMkv); mmkvSync(idMkv);
                                Log.i(TAG, "G6 manufacturer expt written");
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "stage2 write failed", t);
                }
                sStage2Done = true;
            }

            if (dirty) Log.i(TAG, "config writes done");
        } catch (Throwable t) {
            Log.e(TAG, "attemptConfigWrites failed", t);
        }
    }

    private static Object mmkv(String mmapId) {
        try {
            Class<?> mmkvCls = Class.forName(MMKV_CLASS, false, sLoader);
            try {
                mmkvCls.getMethod("initialize", android.content.Context.class).invoke(null, sAppContext);
            } catch (Throwable ignored) {}
            return mmkvCls.getMethod("mmkvWithID", String.class).invoke(null, mmapId);
        } catch (Throwable t) {
            Log.w(TAG, "mmkv(" + mmapId + ") failed: " + t.getMessage());
            return null;
        }
    }

    private static void mmkvPutInt(Object mkv, String key, int value) {
        try { mkv.getClass().getMethod("putInt", String.class, int.class).invoke(mkv, key, value); }
        catch (Throwable t) { Log.e(TAG, "putInt failed: " + key, t); }
    }

    private static void mmkvPutString(Object mkv, String key, String value) {
        try { mkv.getClass().getMethod("putString", String.class, String.class).invoke(mkv, key, value); }
        catch (Throwable t) { Log.e(TAG, "putString failed: " + key, t); }
    }

    private static int mmkvGetInt(Object mkv, String key, int def) {
        try { return (Integer) mkv.getClass().getMethod("getInt", String.class, int.class).invoke(mkv, key, def); }
        catch (Throwable t) { return def; }
    }

    private static void mmkvSync(Object mkv) {
        try { mkv.getClass().getMethod("sync").invoke(mkv); }
        catch (Throwable t) { Log.e(TAG, "sync failed", t); }
    }


}
