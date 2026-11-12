#!/bin/sh
# 每晚用本机模型跑一轮真实场景（agent-eval 需求 7），和前一晚比。不花钱：只用本机 OpenAI 兼容模型。
#
#   TANTALUM_NIGHTLY_REPEAT     每个场景跑几次，默认 3
#   TANTALUM_NIGHTLY_LOCAL_URL  本机模型的地址，默认 http://127.0.0.1:8090/v1
#   TANTALUM_NIGHTLY_START      模型没响应时用来启动它的命令（在后台起，脚本最多等 5 分钟），不设就不启动
#
# 结果在 tantalum-examples/scenarios/results/nightly/：每晚一份 .json 和 .md，日志 nightly.log。
# 用 launchd 每天跑：见同目录的 dev.tantalum.nightly.plist.template 开头的说明。
set -u
REPO=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$REPO/tantalum-examples/scenarios/results/nightly"
URL=${TANTALUM_NIGHTLY_LOCAL_URL:-http://127.0.0.1:8090/v1}
mkdir -p "$OUT"
exec >> "$OUT/nightly.log" 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') nightly"
cd "$REPO" || exit 1

# 有更新就拉，拉不下来（本地有改动、没网）就用现在的代码
git pull --ff-only -q || echo "git pull failed; using the current checkout"

if ! curl -sf -m 5 "$URL/models" > /dev/null; then
  if [ -n "${TANTALUM_NIGHTLY_START:-}" ]; then
    echo "local model not answering; starting it"
    sh -c "$TANTALUM_NIGHTLY_START" &
    for i in $(seq 1 60); do curl -sf -m 5 "$URL/models" > /dev/null && break; sleep 5; done
  fi
  curl -sf -m 5 "$URL/models" > /dev/null || { echo "local model at $URL is not answering; giving up"; exit 1; }
fi

mvn -o -q package -DskipTests -Djacoco.skip=true || { echo "build failed"; exit 1; }

PREVIOUS=$(ls -t "$OUT"/*.json 2>/dev/null | head -1)
STAMP=$(date +%Y%m%d)
set -- --model qwen --repeat "${TANTALUM_NIGHTLY_REPEAT:-3}" --local-url "$URL" --tag "nightly-$STAMP"
[ -n "$PREVIOUS" ] && set -- "$@" --compare "$PREVIOUS"
python3 tantalum-examples/scenarios/run.py "$@"
CODE=$?
# run.py 把结果写在 results/ 下，挪进 nightly/
for f in tantalum-examples/scenarios/results/*-nightly-"$STAMP".*; do [ -e "$f" ] && mv "$f" "$OUT/"; done
echo "=== done, exit $CODE"
exit $CODE
