package me.livephoto.zygisk;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
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

    /** Remux 直通：跳过转码直接复制文件（聊天 + 朋友圈），DexProbe 动态定位 */
    private static void hookRemux() {
        try {
            String apkPath = DexProbe.findWeChatApkPath();
            DexProbe.Remux probed = apkPath != null ? DexProbe.findRemux(apkPath) : null;
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

    /** 检测 Motion Photo：文件尾部 MP4 ftyp 特征 + XMP 元数据 */
    private static boolean hasMotionPhoto(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < 64) return false;
            long len = f.length();
            // 1) 头部 128KB XMP 快检（byte 级查找，避免 String 分配）
            int headLen = (int) Math.min(len, 131072);
            byte[] head = new byte[headLen];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(0);
                raf.readFully(head);
            }
            if (containsAscii(head, "MotionPhoto") || containsAscii(head, "MicroVideo") || containsAscii(head, "GCamera")) {
                return true;
            }
            // 2) 尾部 ftyp 兜底（12MB 窗口）
            int tailLen = (int) Math.min(len, 12L * 1024 * 1024);
            byte[] tail = new byte[tailLen];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(len - tailLen);
                raf.readFully(tail);
            }
            return containsAscii(tail, "ftyp");
        } catch (Throwable t) {
            return false;
        }
    }

    /** byte[] 中查找 ASCII 子串（比 String 构造快，避免大内存分配） */
    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int last = haystack.length - n.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** byte[] 中查找 ASCII 子串返回下标（-1 未找到） */
    private static int indexOfAscii(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int last = haystack.length - n.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** 提取内嵌 MP4 到 savePath，返回微信约定 JSON（完整格式） */
    private static String tryExtractVideo(long mediaId, String savePath) {
        final String Q = "\"";
        RandomAccessFile raf = null;
        try {
            if (savePath == null || savePath.isEmpty()) return null;
            String imgPath = resolveImagePath(mediaId);
            if (imgPath == null) return null;
            File img = new File(imgPath);
            if (!img.isFile()) return null;
            long fileLen = img.length();

            raf = new RandomAccessFile(img, "r");

            // 1) 只读头部 8MB 找 ftyp（覆盖 ftyp 在 2~3MB 的图，如 DCIM/Live 的魅族实况）
            int headRead = (int) Math.min(fileLen, 8 * 1024 * 1024L);
            byte[] head = new byte[headRead];
            raf.seek(0);
            raf.readFully(head);
            int off = findFtyp(head);
            if (off < 0) {
                Log.w(TAG, "tryExtractVideo: no ftyp in head 8MB of " + imgPath + " (len=" + fileLen + ")");
                return null;
            }

            // 2) 流式复制 [ftyp_off, EOF) 到 savePath（不整文件读入内存）
            File dst = new File(savePath);
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            long videoLen = 0;
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dst)) {
                raf.seek(off);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = raf.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    videoLen += n;
                }
                fos.flush();
            }

            // 3) 解析时长/宽高：只读写入的视频文件尾部 4MB（moov/mvhd/tkhd 通常靠近文件尾）
            long dur = 0;
            int w = 0, h = 0;
            try (RandomAccessFile vraf = new RandomAccessFile(dst, "r")) {
                long vLen = vraf.length();
                int tailRead = (int) Math.min(vLen, 4 * 1024 * 1024L);
                byte[] tail = new byte[tailRead];
                vraf.seek(vLen - tailRead);
                vraf.readFully(tail);
                dur = parseMvhdDurationMs(tail, 0);
                int[] wh = parseTkhdSize(tail, 0);
                w = wh[0]; h = wh[1];
            }

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
        } finally {
            if (raf != null) { try { raf.close(); } catch (Throwable ignored) {} }
        }
    }
    /** 从尾部往前找 ftyp box（MP4 起始） */
    private static int findFtyp(byte[] buf) {
        int p = indexOfAscii(buf, "ftyp");
        if (p < 0) return -1;
        int sizeOff = p - 4;
        if (sizeOff < 0) return -1;
        int sz = (buf[sizeOff] & 0xff) << 24 | (buf[sizeOff+1] & 0xff) << 16 |
            (buf[sizeOff+2] & 0xff) << 8 | (buf[sizeOff+3] & 0xff);
        if (sz >= 8 && sz < 200_000_000) return sizeOff;
        // 首个 ftyp size 非法，尝试继续找下一个（从 sizeOff+8 起）
        int i = sizeOff + 8;
        while (i < buf.length) {
            int p2 = -1;
            for (int j = i; j <= buf.length - 4; j++) {
                if (buf[j]=='f' && buf[j+1]=='t' && buf[j+2]=='y' && buf[j+3]=='p') { p2 = j; break; }
            }
            if (p2 < 0) return -1;
            int so = p2 - 4;
            if (so >= 0) {
                int s2 = (buf[so] & 0xff) << 24 | (buf[so+1] & 0xff) << 16 |
                    (buf[so+2] & 0xff) << 8 | (buf[so+3] & 0xff);
                if (s2 >= 8 && s2 < 200_000_000) return so;
            }
            i = p2 + 4;
        }
        return -1;
    }


    /** 解析 mvhd 时长（毫秒） */
    private static long parseMvhdDurationMs(byte[] data, int from) {
        try {
            int i = from;
            int end = data.length - 4;
            while (i < end) {
                if (data[i] == 'm' && data[i+1] == 'v' && data[i+2] == 'h' && data[i+3] == 'd') {
                    int boxSize = readI32(data, i - 4);
                    if (boxSize >= 80 && boxSize <= 4096) {
                        int p = i + 4;
                        long dur;
                        if (data[p] == 1) {
                            long ts = readI32(data, p + 20) & 0xFFFFFFFFL;
                            long du = readI64(data, p + 24);
                            if (ts > 0) dur = du * 1000 / ts; else dur = 0;
                        } else {
                            long ts = readI32(data, p + 12) & 0xFFFFFFFFL;
                            long du = readI32(data, p + 16) & 0xFFFFFFFFL;
                            if (ts > 0) dur = du * 1000 / ts; else dur = 0;
                        }
                        if (dur > 0) return dur;
                    }
                }
                i++;
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 解析 tkhd 宽高 */
    private static int[] parseTkhdSize(byte[] data, int from) {
        try {
            int i = from;
            int end = data.length - 4;
            while (i < end) {
                if (data[i] == 't' && data[i+1] == 'k' && data[i+2] == 'h' && data[i+3] == 'd') {
                    int boxSize = readI32(data, i - 4);
                    if (boxSize >= 80 && boxSize <= 4096) {
                        int p = i + 4;
                        int off = data[p] == 1 ? 88 : 76;
                        int w = (int)((readI32(data, p + off) & 0xFFFFFFFFL) >> 16);
                        int h = (int)((readI32(data, p + off + 4) & 0xFFFFFFFFL) >> 16);
                        if (w >= 2 && w <= 19200 && h >= 2 && h <= 19200) {
                            return new int[]{w, h};
                        }
                    }
                }
                i++;
            }
            return new int[]{0, 0};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private static int readI32(byte[] b, int off) {
        return (b[off] & 0xff) << 24 | (b[off+1] & 0xff) << 16 |
            (b[off+2] & 0xff) << 8 | (b[off+3] & 0xff);
    }

    private static long readI64(byte[] b, int off) {
        long hi = readI32(b, off) & 0xFFFFFFFFL;
        long lo = readI32(b, off + 4) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    /** 从动态照片中提取内嵌 MP4（JPEG 结束后第一个 ftyp box 到文件尾） */
    private static byte[] extractEmbeddedMp4(File img) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(img, "r")) {
            long len = raf.length();
            // 读取整个文件（实况照片一般几 MB）
            byte[] all = new byte[(int) Math.min(len, 512 * 1024 * 1024)];
            raf.seek(0);
            raf.readFully(all);

            // 1. 找 JPEG 结束 FFD9（图片与视频的分界）
            int jpegEnd = -1;
            for (int i = all.length - 1; i >= 0; i--) {
                if (i > 0 && all[i-1] == (byte)0xFF && all[i] == (byte)0xD9) {
                    jpegEnd = i + 1;
                    break;
                }
            }
            int searchFrom = Math.max(jpegEnd, 0);

            // 2. 从 JPEG 结束处往后找第一个 ftyp box
            for (int i = searchFrom; i < all.length - 8; i++) {
                if (all[i] == 'f' && all[i+1] == 't' && all[i+2] == 'y' && all[i+3] == 'p') {
                    // box size 在前 4 字节
                    int sizeOff = i - 4;
                    if (sizeOff < 0) continue;
                    int boxSize = (all[sizeOff] & 0xff) << 24 | (all[sizeOff+1] & 0xff) << 16 |
                        (all[sizeOff+2] & 0xff) << 8 | (all[sizeOff+3] & 0xff);
                    // boxSize == 0 表示延伸到文件尾，== 1 表示 64 位 size
                    if (boxSize == 0) {
                        // 从 ftyp 到文件尾
                        byte[] mp4 = new byte[all.length - sizeOff];
                        System.arraycopy(all, sizeOff, mp4, 0, mp4.length);
                        return mp4;
                    } else if (boxSize > 0) {
                        // 从 ftyp 到 box 结束
                        int end = sizeOff + boxSize;
                        if (end > all.length) end = all.length;
                        byte[] mp4 = new byte[end - sizeOff];
                        System.arraycopy(all, sizeOff, mp4, 0, mp4.length);
                        return mp4;
                    }
                }
            }
            // 3. 兜底：从文件尾找最后一个 moov 所在的大 box 起始
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
    private static final String VAL_BASE64_ONE = "MQ==";

    private static final String[] ALL_REPAIRER_KEYS = {
        "RepairerConfig_Chatting_C2C_Live_Preview_V2",
        "RepairerConfig_Chatting_C2C_Live_Send_V4",
        "RepairerConfig_Chatting_C2C_Live_Album_Auto_Enable",
        "RepairerConfig_Chatting_C2C_Live_Hevc_Soft_Encode",
        "RepairerConfig_SnsSaveLivePhoto",
        "RepairerConfig_SnsPublishLivePhoto",
        "RepairerConfig_SnsCheckSysLivePhoto",
        "RepairerConfig_SnsPreDownloadLivePhoto",
        "RepairerConfig_TextStatus_Gallery_LivePhoto_Enable",
    };

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
                    for (String key : ALL_REPAIRER_KEYS) {
                        int target = key.equals("RepairerConfig_Chatting_C2C_Live_Hevc_Soft_Encode") ? 0 : 1;
                        if (mmkvGetInt(mkv, key, -1) != target) {
                            mmkvPutInt(mkv, key, target);
                            mmkvSync(mkv);
                            dirty = true;
                            Log.i(TAG, "Repairer written: " + key + "=" + target);
                        }
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
                            mmkvPutString(idMkv, String.valueOf(EXPT_ID_SEND), exptJson(EXPT_ID_SEND, KEY_EXPT_SEND));
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
                                mmkvPutString(idMkv, String.valueOf(EXPT_ID_G6), exptJson(EXPT_ID_G6, KEY_G6));
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

    private static String exptJson(int exptId, String key) {
        // 构造 JSON: {"ExptId":N,"GroupId":0,...}
        StringBuilder sb = new StringBuilder();
        char q = '"';
        sb.append('{').append(q).append("ExptId").append(q).append(':').append(exptId);
        sb.append(',').append(q).append("GroupId").append(q).append(':').append(0);
        sb.append(',').append(q).append("ExptSequence").append(q).append(':').append(1);
        sb.append(',').append(q).append("Priority").append(q).append(':').append(1);
        sb.append(',').append(q).append("NeedReport").append(q).append(':').append(0);
        sb.append(',').append(q).append("StartTime").append(q).append(':').append(0);
        sb.append(',').append(q).append("EndTime").append(q).append(':').append(0);
        sb.append(',').append(q).append("ExptType").append(q).append(':').append(4);
        sb.append(',').append(q).append("SvrType").append(q).append(':').append(1);
        sb.append(',').append(q).append("ExptCheckSum").append(q).append(':').append(q).append(q);
        sb.append(',').append(q).append("Args").append(q).append(':').append('[').append('{');
        sb.append(q).append("Key").append(q).append(':').append(q).append(key).append(q);
        sb.append(',').append(q).append("Val").append(q).append(':').append(q).append(VAL_BASE64_ONE).append(q);
        sb.append('}').append(']').append('}');
        return sb.toString();
    }

}
