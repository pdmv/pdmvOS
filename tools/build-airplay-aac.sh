#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
toolchain=$1
root="$PWD/rust/target/airplay-aac"
version=7.1.5
digest=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
cached="$root/ffmpeg-$version.tar.xz"
bundled="$PWD/third_party/ffmpeg-$version.tar.xz"
archive="${PDMVOS_FFMPEG_ARCHIVE:-$cached}"
if [[ -z "${PDMVOS_FFMPEG_ARCHIVE:-}" && -f "$bundled" ]]; then
    archive="$bundled"
fi
source="$root/ffmpeg-$version"
mkdir -p "$root"
if [[ ! -f "$archive" ]]; then
    curl -fsSL "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz" -o "$archive.tmp"
    mv "$archive.tmp" "$archive"
fi
printf '%s  %s\n' "$digest" "$archive" | shasum -a 256 -c -
if [[ "$archive" != "$cached" && ! -f "$cached" ]]; then
    cp "$archive" "$cached"
fi
if [[ ! -d "$source" ]]; then
    tar -xJf "$archive" -C "$root"
fi
shim="$PWD/rust/src/airplay/eld.c"
build_target() {
    local target=$1 arch=$2 prefix=$3
    local build="$root/$target" cc="$toolchain/${prefix}26-clang"
    mkdir -p "$build"
    (
        cd "$build"
        if [[ ! -f libavcodec/libavcodec.a || ! -f libavutil/libavutil.a ]]; then
            "$source/configure" --enable-cross-compile --target-os=android --arch="$arch" \
                --cc="$cc" --ar="$toolchain/llvm-ar" --nm="$toolchain/llvm-nm" \
                --ranlib="$toolchain/llvm-ranlib" --strip="$toolchain/llvm-strip" \
                --disable-everything --enable-decoder=aac --disable-autodetect \
                --disable-programs --disable-doc --disable-network --disable-debug \
                --disable-avdevice --disable-avfilter --disable-avformat \
                --disable-swresample --disable-swscale --disable-x86asm --enable-pic
            make -j "${PDMVOS_CODEC_JOBS:-4}" libavcodec/libavcodec.a libavutil/libavutil.a
        fi
        "$cc" -O2 -fPIC -I"$source" -I"$build" -c "$shim" -o eld.o
        "$toolchain/llvm-ar" rcs libpdmvos_eld.a eld.o
    ) > "$build/build.log" 2>&1 || { tail -50 "$build/build.log" >&2; return 1; }
}
build_target aarch64-linux-android aarch64 aarch64-linux-android &
pid_arm64=$!
build_target armv7-linux-androideabi arm armv7a-linux-androideabi &
pid_arm=$!
build_target x86_64-linux-android x86_64 x86_64-linux-android &
pid_x86=$!
result=0
for pid in "$pid_arm64" "$pid_arm" "$pid_x86"; do
    wait "$pid" || result=1
done
exit "$result"
