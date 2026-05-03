#!/system/bin/sh
# engine_launcher.sh — JavaPro FPS Native Engine Launcher
# Deteksi arsitektur, jalankan fps_core dari /data/local/tmp/

BINARY_DIR="/data/local/tmp/javapro"
LOG_TAG="JavaPro_FpsEngine"

# Deteksi arsitektur
ARCH=$(uname -m 2>/dev/null)
case "$ARCH" in
    aarch64|arm64)    ABI="arm64-v8a"   ;;
    armv7l|armv8l)    ABI="armeabi-v7a" ;;
    x86_64)           ABI="x86_64"      ;;
    x86|i686)         ABI="x86"         ;;
    *)                ABI="arm64-v8a"   ;;  # default fallback
esac

BINARY="$BINARY_DIR/fps_core_$ABI"

# Fallback: kalau ABI-spesifik tidak ada, coba universal
if [ ! -f "$BINARY" ]; then
    BINARY="$BINARY_DIR/fps_core"
fi

if [ ! -f "$BINARY" ]; then
    echo "ERROR: fps_core binary not found at $BINARY" >&2
    exit 1
fi

# Pastikan executable
chmod +x "$BINARY" 2>/dev/null

# Jalankan — output langsung ke stdout (dibaca Kotlin via stream)
exec "$BINARY"
