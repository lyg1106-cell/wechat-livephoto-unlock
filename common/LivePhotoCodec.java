package me.livephoto.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * LivePhoto 编解码共享核心（纯 JVM，无 Android 依赖）。
 *
 * LSPosed（Kotlin）与 Zygisk（Java）两版共用：MP4 box 解析、实况文件检测、
 * 内嵌视频定位与流式拷贝、expt JSON 构造、Repairer 配置键表。
 *
 * 接入方式（gradle）：sourceSets.main.java.srcDir("../../common")
 * 即可把本目录编入各自模块；Kotlin 侧直接 import 调用。
 */
public final class LivePhotoCodec {

    private LivePhotoCodec() {}

    /** 实况判定：头部 XMP 快检窗口（XMP 位于 JPEG 头部 APP1 段） */
    public static final int HEAD_XMP_WINDOW = 131072;
    /** 实况判定：尾部 ftyp 扫描窗口（内嵌 MP4 一般 1~3 秒，几 MB 内） */
    public static final int TAIL_WINDOW = 12 * 1024 * 1024;
    /** 视频元数据解析：头部读取量（faststart 的 moov/mvhd/tkhd 在视频起始处） */
    public static final int VIDEO_HEAD_PARSE = 1048576;
    /** 视频元数据解析：尾部读取量（moov 在尾部的 MP4 兜底） */
    public static final int VIDEO_TAIL_PARSE = 4 * 1024 * 1024;
    /** 流式拷贝缓冲区 */
    public static final int COPY_BUF = 65536;

    // ==================== 基础读写 ====================

    public static int readI32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    public static long readI64(byte[] b, int o) {
        long hi = readI32(b, o) & 0xFFFFFFFFL;
        long lo = readI32(b, o + 4) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    /** byte[] 中 ASCII 子串查找（避免大 String 分配） */
    public static boolean containsAscii(byte[] haystack, String needle) {
        return indexOfAscii(haystack, needle, 0) >= 0;
    }

    public static int indexOfAscii(byte[] haystack, String needle, int from) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int last = haystack.length - n.length;
        outer:
        for (int i = Math.max(from, 0); i <= last; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ==================== ftyp 定位 ====================

    /** 正向找第一个合法 ftyp box，返回 box 起始偏移（即 size 字段位置），-1 未找到 */
    public static int findFtypForward(byte[] buf) {
        int p = indexOfAscii(buf, "ftyp", 0);
        while (p >= 4) {
            int sizeOff = p - 4;
            int sz = readI32(buf, sizeOff);
            if (sz >= 8 && sz < 200_000_000) return sizeOff;
            p = indexOfAscii(buf, "ftyp", p + 4);
        }
        return -1;
    }

    /** 逆向找最后一个合法 ftyp box（视频在文件尾部时用），返回 box 起始偏移，-1 未找到 */
    public static int findFtypReverse(byte[] buf) {
        int i = buf.length - 8;
        while (i >= 0) {
            if (buf[i + 4] == 'f' && buf[i + 5] == 't'
                    && buf[i + 6] == 'y' && buf[i + 7] == 'p') {
                int sz = readI32(buf, i);
                if (sz >= 8 && sz < 200_000_000) return i;
            }
            i--;
        }
        return -1;
    }

    // ==================== MP4 box 解析 ====================

    /** 在视频数据里找 mvhd 解析时长毫秒；失败返回 0 */
    public static long parseMvhdDurationMs(byte[] data, int from) {
        try {
            int i = from;
            int end = data.length - 4;
            while (i < end) {
                if (data[i] == 'm' && data[i + 1] == 'v'
                        && data[i + 2] == 'h' && data[i + 3] == 'd') {
                    int boxSize = readI32(data, i - 4);
                    if (boxSize >= 80 && boxSize <= 4096) {
                        int p = i + 4; // version 字节
                        long dur;
                        if (data[p] == 1) {
                            // v1: creation(8) modification(8) timescale(4)@p+20 duration(8)@p+24
                            long ts = readI32(data, p + 20) & 0xFFFFFFFFL;
                            long du = readI64(data, p + 24);
                            dur = ts > 0 ? du * 1000 / ts : 0;
                        } else {
                            // v0: creation(4) modification(4) timescale(4)@p+12 duration(4)@p+16
                            long ts = readI32(data, p + 12) & 0xFFFFFFFFL;
                            long du = readI32(data, p + 16) & 0xFFFFFFFFL;
                            dur = ts > 0 ? du * 1000 / ts : 0;
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

    /** 在视频数据里找 tkhd 解析宽高（16.16 定点数→整数）；失败返回 {0,0} */
    public static int[] parseTkhdSize(byte[] data, int from) {
        try {
            int i = from;
            int end = data.length - 4;
            while (i < end) {
                if (data[i] == 't' && data[i + 1] == 'k'
                        && data[i + 2] == 'h' && data[i + 3] == 'd') {
                    int boxSize = readI32(data, i - 4);
                    if (boxSize >= 80 && boxSize <= 4096) {
                        int p = i + 4; // version 字节
                        int off = data[p] == 1 ? 88 : 76;
                        int w = (int) ((readI32(data, p + off) & 0xFFFFFFFFL) >>> 16);
                        int h = (int) ((readI32(data, p + off + 4) & 0xFFFFFFFFL) >>> 16);
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

    // ==================== 实况检测 ====================

    /**
     * 运动照片检测：头部 XMP 快检 + 尾部 ftyp 兜底。
     * 全程只读头部 128KB 与尾部 12MB，不整文件加载。
     */
    public static boolean isMotionPhotoFile(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < 64L) return false;
            long len = f.length();
            // 1) 头部 XMP 快速检测
            int headLen = (int) Math.min(len, (long) HEAD_XMP_WINDOW);
            byte[] head = new byte[headLen];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(0);
                raf.readFully(head);
            }
            if (containsAscii(head, "MotionPhoto")
                    || containsAscii(head, "MicroVideo")
                    || containsAscii(head, "GCamera")) {
                return true;
            }
            // 2) 尾部 ftyp 兜底检测（内嵌式 MP4：小米/魅族/Google 等）
            return findVideoOffsetInFile(f) >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在文件尾部窗口内逆向定位内嵌视频起始偏移（ftyp box 起始）。
     * @return 视频在文件中的绝对偏移；-1 表示未找到
     */
    public static long findVideoOffsetInFile(File f) {
        try {
            long len = f.length();
            if (len < 64L) return -1;
            int tailLen = (int) Math.min(len, (long) TAIL_WINDOW);
            byte[] tail = new byte[tailLen];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(len - tailLen);
                raf.readFully(tail);
            }
            int offInTail = findFtypReverse(tail);
            if (offInTail < 0) return -1;
            return len - tailLen + offInTail;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ==================== 流式拷贝 ====================

    /**
     * 流式复制 [off, EOF) 到目标文件，不整文件读入内存。
     * @return 复制的字节数；-1 表示失败
     */
    public static long streamCopyRange(File src, long off, File dst) {
        try {
            long len = src.length();
            if (off < 0 || off >= len) return -1;
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            long copied = 0;
            try (RandomAccessFile raf = new RandomAccessFile(src, "r");
                 FileOutputStream fos = new FileOutputStream(dst)) {
                raf.seek(off);
                byte[] buf = new byte[COPY_BUF];
                int n;
                while ((n = raf.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    copied += n;
                }
                fos.flush();
            }
            return copied;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 流式复制整个文件（大文件不进内存） */
    public static boolean streamCopyFile(File src, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(dst)) {
                byte[] buf = new byte[COPY_BUF];
                int n;
                while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解析视频文件的时长与宽高：先读头部 1MB（faststart），
     * 找不到再读尾部 4MB（moov 在尾部）。全程不整文件加载。
     * @return {durationMs, width, height}
     */
    public static long[] parseVideoMeta(File videoFile) {
        long[] out = new long[]{0, 0, 0};
        try {
            long len = videoFile.length();
            if (len < 64L) return out;
            int headLen = (int) Math.min(len, (long) VIDEO_HEAD_PARSE);
            byte[] head = new byte[headLen];
            try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                raf.seek(0);
                raf.readFully(head);
            }
            out[0] = parseMvhdDurationMs(head, 0);
            int[] wh = parseTkhdSize(head, 0);
            out[1] = wh[0];
            out[2] = wh[1];
            if (out[0] <= 0 || out[1] <= 0) {
                int tailLen = (int) Math.min(len, (long) VIDEO_TAIL_PARSE);
                byte[] tail = new byte[tailLen];
                try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                    raf.seek(len - tailLen);
                    raf.readFully(tail);
                }
                if (out[0] <= 0) out[0] = parseMvhdDurationMs(tail, 0);
                if (out[1] <= 0) {
                    int[] wh2 = parseTkhdSize(tail, 0);
                    out[1] = wh2[0];
                    out[2] = wh2[1];
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 检查文件是否为 JPEG（SOI 标记），只读 2 字节 */
    public static boolean isJpegFile(File f) {
        try {
            if (!f.isFile() || f.length() < 4L) return false;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                int b0 = raf.read();
                int b1 = raf.read();
                return b0 == 0xFF && b1 == 0xD8;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 微信 expt / Repairer 配置 ====================

    public static final String VAL_BASE64_ONE = "MQ==";

    public static final String[] ALL_REPAIRER_KEYS = {
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

    /** Hevc_Soft_Encode 置 0（禁用软编，走硬件硬编通道），其余置 1 */
    public static int repairerTargetValue(String key) {
        return "RepairerConfig_Chatting_C2C_Live_Hevc_Soft_Encode".equals(key) ? 0 : 1;
    }

    /** 构造微信 expt JSON（与原模块格式一致） */
    public static String buildExptJson(int exptId, String key) {
        return "{\"ExptId\":" + exptId + ",\"GroupId\":0,\"ExptSequence\":1,\"Priority\":1,\"NeedReport\":0,"
                + "\"StartTime\":0,\"EndTime\":0,\"ExptType\":4,\"SvrType\":1,\"ExptCheckSum\":\"\","
                + "\"Args\":[{\"Key\":\"" + key + "\",\"Val\":\"" + VAL_BASE64_ONE + "\"}]}";
    }
}
