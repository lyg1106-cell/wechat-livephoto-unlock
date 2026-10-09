package me.livephoto.zygisk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行时 dex 结构探测：不依赖混淆类名，按方法签名特征定位关键类。
 * 从 Kotlin 版 DexProbe 翻译，用于微信进程内动态定位：
 *   - remux worker（聊天 + 朋友圈转码直通入口）
 *   - 实况包装类（持有 LivePhotoCore 类型字段）
 *   - 聊天查看门控（a()Z + b(msg)Z 同类）
 */
public final class DexProbe {

    public static class Remux {
        public String worker;
        public String chat;
        public String sns;
        public String result;
        public Remux(String w, String c, String s, String r) { worker = w; chat = c; sns = s; result = r; }
    }

    private static final String CONT = "Lkotlin/coroutines/Continuation;";
    private static final String PROVIDER = "Lcom/tencent/mm/plugin/recordvideo/jumper/RecordConfigProvider;";
    private static final String MSG = "Lcom/tencent/mm/storage/e9;";
    private static final String CORE = "Lcom/motion/core/LivePhotoCore;";

    /** 找微信主 APK 路径（支持 split）
     *  v2.1.1：删除已废弃的 candidates 数组（路径曾误写模块 ID，且从未被使用）；
     *  调用方（LivePhotoHooks）会优先用 Context.getApplicationInfo().sourceDir，
     *  这里保留 /data/app 扫描作为兜底。 */
    public static String findWeChatApkPath() {
        try {
            // 从系统找到微信的 split base apk
            File dataDir = new File("/data/user/0/com.tencent.mm");
            if (dataDir.isDirectory()) {
                // 尝试常见 split apk 路径
                File appDir = new File("/data/app");
                File[] apps = appDir.listFiles();
                if (apps != null) {
                    for (File app : apps) {
                        String name = app.getName();
                        if (name.startsWith("com.tencent.mm") || name.contains("tencent.mm")) {
                            String[] parts = app.list();
                            if (parts != null) {
                                for (String p : parts) {
                                    File apk = new File(app, p + "/base.apk");
                                    if (apk.isFile()) return apk.getAbsolutePath();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 结构探测 remux worker */
    public static Remux findRemux(String apkPath) {
        if (apkPath == null) return null;
        try (ZipFile zip = new ZipFile(apkPath)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                byte[] bytes = readEntry(zip, e);
                Remux r = findInDex(bytes);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 结构探测实况包装类 */
    public static String findWrapper(String apkPath) {
        if (apkPath == null) return null;
        try (ZipFile zip = new ZipFile(apkPath)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                byte[] bytes = readEntry(zip, e);
                String w = findWrapperInDex(bytes);
                if (w != null) return w;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 结构探测聊天查看门控 */
    public static String findViewGate(String apkPath) {
        if (apkPath == null) return null;
        try (ZipFile zip = new ZipFile(apkPath)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                byte[] bytes = readEntry(zip, e);
                String g = findViewGateInDex(bytes);
                if (g != null) return g;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry e) {
        try (InputStream is = zip.getInputStream(e); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            return bos.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    // ==================== 最小 dex 解析器 ====================

    private static final class R {
        byte[] b;
        R(byte[] x) { b = x; }
        int u4(int p) { return (b[p]&0xff)|((b[p+1]&0xff)<<8)|((b[p+2]&0xff)<<16)|((b[p+3]&0xff)<<24); }
        int u2(int p) { return (b[p]&0xff)|((b[p+1]&0xff)<<8); }
        int[] uleb(int p0) { int p=p0,res=0,sh=0; while(true){int x=b[p]&0xff;p++;res|=((x&0x7f)<<sh);if((x&0x80)==0)break;sh+=7;} return new int[]{res,p}; }
        String mutf8(int strOff, int[] strData, int idx) {
            int[] v = uleb(strData[idx]);
            int len = v[0], p = v[1];
            StringBuilder sb = new StringBuilder(len);
            for (int k=0;k<len;k++) {
                int x = b[p]&0xff; p++;
                if (x<0x80) { if (x!=0) sb.append((char)x); }
                else if ((x&0xe0)==0xc0) { int y=b[p]&0xff; p++; sb.append((char)(((x&0x1f)<<6)|(y&0x3f))); }
                else { int y=b[p]&0xff; p++; int z=b[p]&0xff; p++; sb.append((char)(((x&0x0f)<<12)|((y&0x3f)<<6)|(z&0x3f))); }
            }
            return sb.toString();
        }
    }

    private static String dotted(String s) {
        return s.substring(1, s.length()-1).replace('/', '.');
    }

    static Remux findInDex(byte[] b) {
        if (b.length < 0x70) return null;
        R r = new R(b);
        int strSize = r.u4(0x38), strOff = r.u4(0x3c);
        int typeSize = r.u4(0x40), typeOff = r.u4(0x44);
        int protoSize = r.u4(0x48), protoOff = r.u4(0x4c);
        int methodSize = r.u4(0x58), methodOff = r.u4(0x5c);
        if (strSize<=0||strSize>5000000||typeSize>1000000||protoSize>1000000||methodSize>5000000) return null;
        int[] strData = new int[strSize];
        for (int k=0;k<strSize;k++) strData[k] = r.u4(strOff+4*k);
        String[] types = new String[typeSize];
        for (int k=0;k<typeSize;k++) types[k] = r.mutf8(strOff, strData, r.u4(typeOff+4*k));
        // proto: shorty_idx(4) return_type_idx(4) parameters_off(4)
        List<String>[] protoParams = new List[protoSize];
        for (int k=0;k<protoSize;k++) {
            int listOff = r.u4(protoOff+12*k+8);
            if (listOff == 0) protoParams[k] = new ArrayList<>();
            else {
                List<String> l = new ArrayList<>();
                int n = r.u4(listOff);
                for (int j=0;j<n;j++) l.add(types[r.u2(listOff+4+2*j)]);
                protoParams[k] = l;
            }
        }
        Map<String, String[]> sigs = new HashMap<>();
        for (int k=0;k<methodSize;k++) {
            int o = methodOff+8*k;
            int ci = r.u2(o);
            if (ci>=typeSize) continue;
            String cls = types[ci];
            if (cls==null || !cls.startsWith("L") || !cls.contains("/")) continue;
            int pi = r.u2(o+2);
            if (pi>=protoSize) continue;
            List<String> params = protoParams[pi];
            int retIdx = r.u4(protoOff+12*pi+4);
            String ret = retIdx<typeSize ? types[retIdx] : null;
            String name = r.mutf8(strOff, strData, r.u4(o+4));
            String[] s = sigs.get(cls);
            if (s==null) { s = new String[2]; sigs.put(cls, s); }
            if (!"Ljava/lang/Object;".equals(ret)) continue;
            if (params.size()>=4 && "Ljava/lang/String;".equals(params.get(0)) &&
                "Ljava/lang/String;".equals(params.get(1)) && "Ljava/lang/String;".equals(params.get(2)) &&
                CONT.equals(params.get(params.size()-1))) {
                if (s[0]==null) s[0] = name;
            }
            if (params.size()==2 && PROVIDER.equals(params.get(0)) && CONT.equals(params.get(1))) {
                s[1] = name;
            }
        }
        String hitCls=null, chat=null, sns=null;
        for (Map.Entry<String,String[]> e : sigs.entrySet()) {
            if (e.getValue()[0]!=null && e.getValue()[1]!=null) { hitCls=e.getKey(); chat=e.getValue()[0]; sns=e.getValue()[1]; break; }
        }
        if (hitCls==null) return null;
        // 结果类 (ZI) 构造器
        List<String> zi = new ArrayList<>();
        for (int k=0;k<methodSize;k++) {
            int o = methodOff+8*k;
            if (!"<init>".equals(r.mutf8(strOff, strData, r.u4(o+4)))) continue;
            int pi = r.u2(o+2);
            if (pi>=protoSize) continue;
            List<String> pp = protoParams[pi];
            if (pp.size()==2 && "Z".equals(pp.get(0)) && "I".equals(pp.get(1))) {
                int ci = r.u2(o);
                if (ci<typeSize && types[ci]!=null) zi.add(types[ci]);
            }
        }
        String result = "";
        for (String z : zi) { if (z.endsWith("/e;")) { result = z; break; } }
        if (result.isEmpty() && !zi.isEmpty()) result = zi.get(0);
        if (!result.isEmpty()) result = dotted(result);
        return new Remux(dotted(hitCls), chat, sns, result);
    }

    static String findViewGateInDex(byte[] b) {
        if (b.length < 0x70) return null;
        R r = new R(b);
        int strSize = r.u4(0x38), strOff = r.u4(0x3c);
        int typeSize = r.u4(0x40), typeOff = r.u4(0x44);
        int protoSize = r.u4(0x48), protoOff = r.u4(0x4c);
        int methodSize = r.u4(0x58), methodOff = r.u4(0x5c);
        if (strSize<=0||strSize>5000000||typeSize>1000000||protoSize>1000000||methodSize>5000000) return null;
        int[] strData = new int[strSize];
        for (int k=0;k<strSize;k++) strData[k] = r.u4(strOff+4*k);
        String[] types = new String[typeSize];
        for (int k=0;k<typeSize;k++) types[k] = r.mutf8(strOff, strData, r.u4(typeOff+4*k));
        List<String>[] protoParams = new List[protoSize];
        for (int k=0;k<protoSize;k++) {
            int listOff = r.u4(protoOff+12*k+8);
            if (listOff == 0) protoParams[k] = new ArrayList<>();
            else { List<String> l = new ArrayList<>(); int n = r.u4(listOff); for (int j=0;j<n;j++) l.add(types[r.u2(listOff+4+2*j)]); protoParams[k] = l; }
        }
        Set<String> hasA = new HashSet<>(), hasB = new HashSet<>();
        for (int k=0;k<methodSize;k++) {
            int o = methodOff+8*k;
            int ci = r.u2(o);
            if (ci>=typeSize) continue;
            String cls = types[ci];
            if (cls==null || !cls.startsWith("L") || !cls.contains("/")) continue;
            int pi = r.u2(o+2);
            if (pi>=protoSize) continue;
            List<String> params = protoParams[pi];
            int retIdx = r.u4(protoOff+12*pi+4);
            String ret = retIdx<typeSize ? types[retIdx] : null;
            if (!"Z".equals(ret)) continue;
            String name = r.mutf8(strOff, strData, r.u4(o+4));
            if ("a".equals(name) && params.isEmpty()) hasA.add(cls);
            if ("b".equals(name) && params.size()==1 && MSG.equals(params.get(0))) hasB.add(cls);
        }
        hasA.retainAll(hasB);
        String pick = null;
        for (String c : hasA) { if (countSlash(c)==1 && c.length()<10) { pick = c; break; } }
        if (pick==null && !hasA.isEmpty()) pick = hasA.iterator().next();
        return pick==null ? null : dotted(pick);
    }

    static String findWrapperInDex(byte[] b) {
        if (b.length < 0x70) return null;
        R r = new R(b);
        int strSize = r.u4(0x38), strOff = r.u4(0x3c);
        int typeSize = r.u4(0x40), typeOff = r.u4(0x44);
        int fieldSize = r.u4(0x50), fieldOff = r.u4(0x54);
        if (strSize<=0||strSize>5000000||typeSize>1000000||fieldSize>3000000) return null;
        int[] strData = new int[strSize];
        for (int k=0;k<strSize;k++) strData[k] = r.u4(strOff+4*k);
        String[] types = new String[typeSize];
        for (int k=0;k<typeSize;k++) types[k] = r.mutf8(strOff, strData, r.u4(typeOff+4*k));
        for (int k=0;k<fieldSize;k++) {
            int o = fieldOff+8*k;
            int own = r.u2(o);
            int typ = r.u2(o+2);
            if (own<typeSize && typ<typeSize && CORE.equals(types[typ]) && types[own]!=null && types[own].startsWith("L")) {
                return dotted(types[own]);
            }
        }
        return null;
    }

    private static int countSlash(String s) { int c=0; for (int i=0;i<s.length();i++) if (s.charAt(i)=='/') c++; return c; }
}