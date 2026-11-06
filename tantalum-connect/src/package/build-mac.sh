#!/bin/bash
# 打 macOS（Apple 芯片）安装包（local-workspace 任务 22，L16、需求 7.1）。由 tantalum-connect 的 mac profile 在 package 阶段调用：
#   mvn -o -pl tantalum-connect -am package -Pmac -DskipTests
# 参数：target 目录、项目版本、连接程序的 jar 名。运行时依赖已由 maven-dependency-plugin 复制到 target/package/input。
#
# 1. 取 MXC v1.0.1 的发布包：先看缓存（~/.cache/tantalum/mxc/1.0.1，或 TANTALUM_CACHE），再看环境变量 MXC_ZIP 给的本地文件，
#    都没有才从 GitHub 下载。整个包和取出的 mxc-exec-mac 都校验 SHA-256，对不上就停。MXC 程序不进 Git 仓库。
# 2. jlink 裁剪运行环境（jdeps 算出的模块）。
# 3. jpackage 做 “Tantalum Connect.app”（不签名、不公证），另加一个叫 tantalum-connect 的命令行启动器，和插件页给的命令同名。
#    启动器经系统属性 tantalum.mxc 告诉程序包里的 MXC 在哪。堆从 16 MB 起步（默认按内存的 1/64 起步，大内存的 Mac 上空闲就占
#    两三百 MB），最多 512 MB，空闲时整个进程约 115 MB（LinkBench 实测，要求 ≤ 150 MB）。
# 4. 用 ditto 打成 zip。
# 5. 自检：解压到临时目录，在没有 Java 的环境里跑 --help；再带 --allow-shell 启动一次，确认包里的 MXC 检查通过。
set -euo pipefail

TARGET=$1
VERSION=$2
JAR=$3
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)

MXC_VERSION=1.0.1
MXC_URL=https://github.com/microsoft/mxc/releases/download/v$MXC_VERSION/mxc-release-binaries.zip
MXC_ZIP_SHA256=0e832239454ba7fcd338ff56218a287eef4c0a01c1484084b869f55feff8abe9
MXC_BIN_SHA256=c54390e48a25187fdae10758c6a286bede49416cb610c88c6705fd8445e97f60
MODULES=java.base,java.desktop,java.net.http,java.sql
APP_NAME="Tantalum Connect"
# macOS 的包版本第一位不能是 0；连接程序自己的版本（启动说明第一行）来自 jar 的 Implementation-Version，不受影响
APP_VERSION=${VERSION%%-*}
if [ "${APP_VERSION%%.*}" = 0 ]; then
  APP_VERSION=1.0.0
fi

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

if [ "$(uname -s)" != Darwin ] || [ "$(uname -m)" != arm64 ]; then
  echo "安装包只能在 Apple 芯片的 Mac 上打" >&2
  exit 1
fi

# 1. MXC
CACHE=${TANTALUM_CACHE:-$HOME/.cache/tantalum}/mxc/$MXC_VERSION
mkdir -p "$CACHE"
ZIP=$CACHE/mxc-release-binaries.zip
BIN=$CACHE/mxc-exec-mac
if [ ! -f "$BIN" ] || [ "$(sha "$BIN")" != "$MXC_BIN_SHA256" ]; then
  if [ ! -f "$ZIP" ]; then
    if [ -n "${MXC_ZIP:-}" ]; then
      cp "$MXC_ZIP" "$ZIP.part"
    else
      echo "下载 $MXC_URL（约 540 MB）"
      curl -fL --retry 3 -o "$ZIP.part" "$MXC_URL"
    fi
    mv "$ZIP.part" "$ZIP"
  fi
  if [ "$(sha "$ZIP")" != "$MXC_ZIP_SHA256" ]; then
    echo "MXC 发布包的 SHA-256 不对，已删掉：$ZIP" >&2
    rm -f "$ZIP"
    exit 1
  fi
  unzip -p "$ZIP" arm64/mxc-exec-mac > "$BIN.part"
  if [ "$(sha "$BIN.part")" != "$MXC_BIN_SHA256" ]; then
    echo "取出的 mxc-exec-mac 的 SHA-256 不对" >&2
    rm -f "$BIN.part"
    exit 1
  fi
  chmod 755 "$BIN.part"
  mv "$BIN.part" "$BIN"
fi
codesign --verify --strict "$BIN"

# 2. 运行环境
PACKAGE=$TARGET/package
INPUT=$PACKAGE/input
rm -rf "$PACKAGE/runtime" "$PACKAGE/app"
cp "$TARGET/$JAR" "$INPUT/"
cp "$BIN" "$INPUT/mxc-exec-mac"
cp "$HERE/MXC-LICENSE.txt" "$ROOT/THIRD_PARTY_NOTICES.md" "$INPUT/"
jlink --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files --compress zip-6 --output "$PACKAGE/runtime"

# 3. .app
jpackage --type app-image --name "$APP_NAME" --app-version "$APP_VERSION" --vendor Tantalum \
  --mac-package-identifier dev.tantalum.connect \
  --input "$INPUT" --main-jar "$JAR" --main-class tantalum.connect.Main \
  --runtime-image "$PACKAGE/runtime" \
  --java-options '-Dtantalum.mxc=$APPDIR/mxc-exec-mac' \
  --java-options '-XX:+UseSerialGC' --java-options '-Xms16m' --java-options '-Xmx512m' \
  --add-launcher tantalum-connect="$HERE/tantalum-connect.properties" \
  --dest "$PACKAGE/app"
APP="$PACKAGE/app/$APP_NAME.app"
# jpackage 给包里每个程序都做了临时签名，MXC 也被改签了。换回微软签名的原文件，再给 .app 重新做一次临时签名（不加 --deep，
# 不碰里面的 MXC），让 .app 的封印记下原文件；最后连同 MXC 的微软签名一起验证
cp "$BIN" "$APP/Contents/app/mxc-exec-mac"
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"
SIGNER=$(codesign -dv --verbose=2 "$APP/Contents/app/mxc-exec-mac" 2>&1)
grep -q "Authority=Developer ID Application: Microsoft Corporation" <<< "$SIGNER"

# 4. zip
OUT=$TARGET/tantalum-connect-$VERSION-macos-arm64.zip
rm -f "$OUT"
(cd "$PACKAGE/app" && ditto -c -k --keepParent "$APP_NAME.app" "$OUT")

# 5. 自检
CHECK=$(mktemp -d)
trap 'rm -rf "$CHECK"' EXIT
ditto -x -k "$OUT" "$CHECK"
LAUNCHER="$CHECK/$APP_NAME.app/Contents/MacOS/tantalum-connect"
HELP=$(env -i HOME="$HOME" PATH=/usr/bin:/bin JAVA_HOME= "$LAUNCHER" --help)
for flag in --server --allow --allow-write --allow-shell --allow-read --env --no-network --name --mxc --help; do
  if ! grep -q -- "$flag" <<< "$HELP"; then
    echo "自检失败：--help 没有列出 $flag" >&2
    exit 1
  fi
done
mkdir "$CHECK/work"
# 假令牌只放在环境变量里；服务器地址连不上，只看启动说明里命令行是否可用
env -i HOME="$HOME" PATH=/usr/bin:/bin JAVA_HOME= TANTALUM_TOKEN="tc_$(printf 'A%.0s' {1..43})" \
  "$LAUNCHER" --server http://127.0.0.1:9 --allow "$CHECK/work" --allow-shell > "$CHECK/out.txt" 2>&1 &
PID=$!
for _ in $(seq 1 50); do
  grep -q "命令行：" "$CHECK/out.txt" 2>/dev/null && break
  sleep 0.2
done
kill "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true
if ! grep -q "命令行：可用" "$CHECK/out.txt"; then
  echo "自检失败：包里的 MXC 没有通过检查" >&2
  cat "$CHECK/out.txt" >&2
  exit 1
fi
echo "安装包：$OUT"
echo "自检通过：没有 Java 也能运行，包里的 MXC 检查通过（$(grep "命令沙箱：" "$CHECK/out.txt")）"
