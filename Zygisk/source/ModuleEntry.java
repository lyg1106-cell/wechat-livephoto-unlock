package me.livephoto.zygisk;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;

/**
 * 微信实况照片解锁 - Zygisk 版入口
 *
 * native 层（zygisk_main.cpp）在微信进程内通过 JNI 调用 {@link #init(ClassLoader)}，
 * 初始化 Pine Hook 引擎并注册全部 Hook。
 */
public class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TAG = "LivePhotoZygisk";
    private static final String WECHAT = "com.tencent.mm";
    // libpine.so 部署路径（post-fs-data.sh 复制到微信私有目录）
    private static final String PINE_PATH = "/data/data/com.tencent.mm/files/libpine.so";

    /** native 注入后调用的静态入口 */
    public static void init(ClassLoader loader) {
        try {
            // 1. 手动加载 libpine.so（Pine 默认 System.loadLibrary 找不到私有目录的库）
            System.load(PINE_PATH);
            Log.i(TAG, "libpine.so loaded via System.load: " + PINE_PATH);

            // 2. 配置 Pine：已手动加载，跳过其内部 loadLibrary
            PineConfig.libLoader = new Pine.LibLoader() {
                @Override public void loadLib() { /* 已手动加载，跳过 */ }
            };
            PineConfig.debug = true;
            PineConfig.debuggable = true;

            // 3. 初始化 Pine
            Pine.ensureInitialized();
            Log.i(TAG, "Pine initialized, installing hooks");

            // 4. 注册 Hook
            LivePhotoHooks.install(loader);
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
        }
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!WECHAT.equals(lpparam.packageName)) return;
        init(lpparam.classLoader);
    }
}
