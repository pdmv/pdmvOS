#!/usr/bin/env bash
# Cross-compile the Rust core library (libpdmvos_core.so) for Android TV ABIs.
set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" && -d "$SDK/ndk" ]]; then
  NDK=$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
fi
: "${NDK:?Set ANDROID_NDK_HOME or install NDK under ANDROID_HOME/ndk}"

HOST_TAG=""
for candidate in darwin-arm64 darwin-x86_64 linux-x86_64 linux-aarch64; do
  if [[ -d "$NDK/toolchains/llvm/prebuilt/$candidate/bin" ]]; then
    HOST_TAG="$candidate"
    break
  fi
done
: "${HOST_TAG:?Cannot locate NDK LLVM prebuilt toolchain in $NDK}"
TOOLCHAIN_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/aarch64-linux-android26-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOLCHAIN_BIN/armv7a-linux-androideabi26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/x86_64-linux-android26-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

cargo build --locked --manifest-path rust/Cargo.toml --release \
  --target aarch64-linux-android \
  --target armv7-linux-androideabi \
  --target x86_64-linux-android

JNI_LIBS="app/src/main/jniLibs"
mkdir -p "$JNI_LIBS/arm64-v8a" "$JNI_LIBS/armeabi-v7a" "$JNI_LIBS/x86_64"
cp rust/target/aarch64-linux-android/release/libpdmvos_core.so "$JNI_LIBS/arm64-v8a/libpdmvos_core.so"
cp rust/target/armv7-linux-androideabi/release/libpdmvos_core.so "$JNI_LIBS/armeabi-v7a/libpdmvos_core.so"
cp rust/target/x86_64-linux-android/release/libpdmvos_core.so "$JNI_LIBS/x86_64/libpdmvos_core.so"
