#include <android/log.h>
#include <jni.h>
#include <string>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <vector>
#include "zygisk.hpp"

#define LOG_TAG "LivePhotoZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr const char* TARGET_PKG = "com.tencent.mm";
// 微信应用私有目录（post-fs-data.sh 已把资源复制到这里，app 进程可读写）
static constexpr const char* APP_PINE = "/data/data/com.tencent.mm/files/libpine.so";
static constexpr const char* APP_DEX = "/data/data/com.tencent.mm/files/livephoto_module.dex";

using namespace zygisk;

static bool FileExists(const char* path) {
    struct stat st{};
    return stat(path, &st) == 0;
}

class LivePhotoModule : public ModuleBase {
public:
    void onLoad(Api* api, JNIEnv* env) override {
        this->api = api;
        this->env = env;
        LOGI("LivePhoto Zygisk module loaded");
    }

    void preAppSpecialize(AppSpecializeArgs* args) override {
        if (args->nice_name != nullptr && env != nullptr) {
            const char* name = env->GetStringUTFChars(args->nice_name, nullptr);
            if (name != nullptr) {
                std::string sname(name);
                env->ReleaseStringUTFChars(args->nice_name, name);
                if (sname == TARGET_PKG) {
                    shouldInject = true;
                    LOGI("Target matched: %s", sname.c_str());
                }
            }
        }
    }

    void postAppSpecialize(const AppSpecializeArgs* args) override {
        if (!shouldInject) return;
        injectAndRun();
    }

    void preServerSpecialize(ServerSpecializeArgs* args) override {}
    void postServerSpecialize(const ServerSpecializeArgs* args) override {}

private:
    Api* api = nullptr;
    JNIEnv* env = nullptr;
    bool shouldInject = false;

    void injectAndRun() {
        LOGI("Injecting LivePhoto runtime into WeChat process");

        // 资源由 post-fs-data.sh 预部署到微信私有目录，这里直接检查
        if (!FileExists(APP_PINE)) {
            LOGE("libpine.so not found at %s (post-fs-data.sh should have deployed)", APP_PINE);
        } else {
            // 预加载 libpine.so（app 可读的私有目录）
            void* h = dlopen(APP_PINE, RTLD_NOW);
            if (h == nullptr) {
                LOGE("dlopen libpine.so failed: %s", dlerror());
            } else {
                LOGI("libpine.so preloaded ok");
            }
        }

        if (!FileExists(APP_DEX)) {
            LOGE("dex not found at %s", APP_DEX);
            return;
        }
        loadDexAndStart();
    }

    void loadDexAndStart() {
        FILE* f = fopen(APP_DEX, "rb");
        if (!f) { LOGE("open dex failed: %s", APP_DEX); return; }
        fseek(f, 0, SEEK_END);
        long sz = ftell(f);
        fseek(f, 0, SEEK_SET);
        std::vector<uint8_t> buf(sz);
        if (fread(buf.data(), 1, sz, f) != (size_t)sz) { fclose(f); LOGE("read dex failed"); return; }
        fclose(f);

        jbyteArray dexBytes = env->NewByteArray(sz);
        env->SetByteArrayRegion(dexBytes, 0, sz, (jbyte*)buf.data());

        jclass inMemCls = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (!inMemCls) { LOGE("InMemoryDexClassLoader not found"); return; }
        jclass byteBufferCls = env->FindClass("java/nio/ByteBuffer");
        jmethodID wrapMethod = env->GetStaticMethodID(byteBufferCls, "wrap", "([B)Ljava/nio/ByteBuffer;");
        jobject bb = env->CallStaticObjectMethod(byteBufferCls, wrapMethod, dexBytes);

        jclass clCls = env->FindClass("java/lang/ClassLoader");
        jmethodID getSys = env->GetStaticMethodID(clCls, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject sysLoader = env->CallStaticObjectMethod(clCls, getSys);

        jmethodID ctor = env->GetMethodID(inMemCls, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        jobject loader = env->NewObject(inMemCls, ctor, bb, sysLoader);
        if (!loader) { LOGE("create InMemoryDexClassLoader failed"); return; }
        LOGI("InMemoryDexClassLoader created");

        // 加载 ModuleEntry 并调用 init
        jmethodID loadClass = env->GetMethodID(clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring entryName = env->NewStringUTF("me.livephoto.zygisk.ModuleEntry");
        jobject entryObj = env->CallObjectMethod(loader, loadClass, entryName);
        env->DeleteLocalRef(entryName);
        if (entryObj == nullptr) {
            LOGE("ModuleEntry not found via loadClass");
            if (env->ExceptionCheck()) env->ExceptionDescribe();
            return;
        }
        LOGI("ModuleEntry loaded via loadClass");

        jclass entryCls = (jclass)entryObj;
        jmethodID initMethod = env->GetStaticMethodID(entryCls, "init", "(Ljava/lang/ClassLoader;)V");
        if (!initMethod) { LOGE("ModuleEntry.init not found"); return; }
        env->CallStaticVoidMethod(entryCls, initMethod, sysLoader);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        LOGI("ModuleEntry.init called");
    }
};

REGISTER_ZYGISK_MODULE(LivePhotoModule)
