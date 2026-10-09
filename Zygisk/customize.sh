#!/system/bin/sh
MODDIR=${0%/*}
ui_print "- LivePhotoUnlock Zygisk 安装中..."
APP_DIR=/data/data/com.tencent.mm/files
mkdir -p "$APP_DIR" 2>/dev/null
cp -f "$MODDIR/lib/arm64-v8a/libpine.so" "$APP_DIR/libpine.so" 2>/dev/null
cp -f "$MODDIR/livephoto_module.dex" "$APP_DIR/livephoto_module.dex" 2>/dev/null
chmod 755 "$APP_DIR/libpine.so" 2>/dev/null
chmod 644 "$APP_DIR/livephoto_module.dex" 2>/dev/null
# v2.1.1: 动态获取微信 UID（原来写死的 u0_a281 只是作者设备的 UID，换机失效）
WX_OWNER=""
for d in /data/data/com.tencent.mm /data/user/0/com.tencent.mm; do
  if [ -d "$d" ]; then
    WX_OWNER=$(stat -c %u:%g "$d" 2>/dev/null)
    [ -n "$WX_OWNER" ] && break
  fi
done
if [ -n "$WX_OWNER" ]; then
  chown "$WX_OWNER" "$APP_DIR/libpine.so" "$APP_DIR/livephoto_module.dex" 2>/dev/null
fi
ui_print "- 部署完成"
