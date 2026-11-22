#!/usr/bin/env bash
# Builds the 32-bit ARM (armeabi-v7a) libraries sm64coopdx links against, from source.
#
# Upstream ships these prebuilt for arm64-v8a and x86_64 only, but every Fire TV runs a 32-bit
# OS. Versions match what the game is built against:
#   SDL2    2.28.1  (lib/sdl2/include, and the SDLActivity.java in platform/)
#   Lua     5.3.5   (lib/lua/include; upstream's luaconf.h only adds extra module search paths)
#   coopnet master  (lib/coopnet/include/libcoopnet.h is identical to its common/libcoopnet.h)
#   libjuice 1.6.2  (coopnet's bundled juice headers)
# curl is not built: on 32-bit the game's own update checker is compiled out (NO_CURL).
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk scripts/build-armv7-deps.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK}"
ABI=armeabi-v7a
API=24
WORK="${WORK:-$ROOT/build/deps-$ABI}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/armv7a-linux-androideabi$API-clang"
CXX="$TC/armv7a-linux-androideabi$API-clang++"
AR="$TC/llvm-ar"
JOBS="$(nproc)"
mkdir -p "$WORK"

clone() { # url tag dir
  [ -d "$3/.git" ] || git clone -q --depth 1 -b "$2" "$1" "$3"
}

echo "== SDL2 2.28.1"
clone https://github.com/libsdl-org/SDL release-2.28.1 "$WORK/SDL"
# SDL's own Android.mk, like the upstream prebuilt (hidapi built in, c++_shared).
"$NDK/ndk-build" -C "$WORK/SDL" -j"$JOBS" \
  NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk \
  APP_ABI=$ABI APP_PLATFORM=android-$API APP_STL=c++_shared \
  NDK_OUT="$WORK/SDL/obj" NDK_LIBS_OUT="$WORK/SDL/libs" SDL2
install -D "$WORK/SDL/libs/$ABI/libSDL2.so" "$ROOT/lib/sdl2/android/$ABI/libSDL2.so"

echo "== Lua 5.3.5"
clone https://github.com/lua/lua v5.3.5 "$WORK/lua"
rm -rf "$WORK/lua/obj" && mkdir -p "$WORK/lua/obj"
for f in "$WORK"/lua/*.c; do
  case "$(basename "$f")" in lua.c|luac.c|onelua.c|ltests.c) continue ;; esac
  "$CC" -O2 -fPIC -DLUA_USE_POSIX -DLUA_USE_DLOPEN -I"$ROOT/lib/lua/include" -I"$WORK/lua" \
    -c "$f" -o "$WORK/lua/obj/$(basename "${f%.c}").o" &
  while [ "$(jobs -r | wc -l)" -ge "$JOBS" ]; do wait -n; done
done
wait
"$AR" rcs "$WORK/liblua.a" "$WORK"/lua/obj/*.o
install -D "$WORK/liblua.a" "$ROOT/lib/lua/android/$ABI/liblua.a"

echo "== libjuice 1.6.2"
clone https://github.com/paullouisageneau/libjuice v1.6.2 "$WORK/libjuice"
cmake -S "$WORK/libjuice" -B "$WORK/libjuice/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
  -DNO_TESTS=ON -DNO_SERVER=OFF
cmake --build "$WORK/libjuice/build" --target juice-static -j"$JOBS"
install -D "$(find "$WORK/libjuice/build" -name 'libjuice-static.a' -o -name 'libjuice.a' | head -1)" \
  "$ROOT/lib/coopnet/android/$ABI/libjuice.a"

echo "== coopnet"
clone https://github.com/coop-deluxe/coopnet master "$WORK/coopnet"
rm -rf "$WORK/coopnet/obj" && mkdir -p "$WORK/coopnet/obj"
for f in "$WORK"/coopnet/common/*.cpp; do
  "$CXX" -O2 -fPIC -std=c++11 -DJUICE_STATIC -fexceptions -frtti \
    -I"$WORK/coopnet/common" -I"$WORK/coopnet/lib/include" \
    -c "$f" -o "$WORK/coopnet/obj/$(basename "${f%.cpp}").o"
done
"$AR" rcs "$WORK/libcoopnet.a" "$WORK"/coopnet/obj/*.o
install -D "$WORK/libcoopnet.a" "$ROOT/lib/coopnet/android/$ABI/libcoopnet.a"

echo "== done"
ls -la "$ROOT"/lib/*/android/$ABI/
