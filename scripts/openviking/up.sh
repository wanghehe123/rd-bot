#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/deploy/openviking"

# 嵌入档：mock（默认，离线、32 维哈希向量，仅够跑协议合同）或 dashscope（真实语义嵌入）。
# 检索质量相关的评测必须用 dashscope 档，mock 档的召回数字没有决策效力。
PROFILE="${OPENVIKING_EMBEDDING_PROFILE:-mock}"

cd "${COMPOSE_DIR}"

case "${PROFILE}" in
  mock)
    export OPENVIKING_CONF_FILE="./ov.conf"
    ;;
  dashscope)
    if [[ -z "${DASHSCOPE_API_KEY:-}" ]]; then
      echo "OPENVIKING_EMBEDDING_PROFILE=dashscope 需要 DASHSCOPE_API_KEY，未设置。" >&2
      exit 1
    fi
    # 渲染文件含真实密钥，已 gitignore，权限收到 600。
    umask 077
    DASHSCOPE_API_KEY="${DASHSCOPE_API_KEY}" \
      python3 -c 'import os,sys;sys.stdout.write(open("ov.conf.dashscope.template").read().replace("${DASHSCOPE_API_KEY}", os.environ["DASHSCOPE_API_KEY"]))' \
      > ov.conf.rendered
    export OPENVIKING_CONF_FILE="./ov.conf.rendered"
    ;;
  *)
    echo "未知 OPENVIKING_EMBEDDING_PROFILE=${PROFILE}，只支持 mock 或 dashscope。" >&2
    exit 1
    ;;
esac

echo "embedding profile: ${PROFILE} (${OPENVIKING_CONF_FILE})"
docker compose up -d --wait
curl -fsS http://127.0.0.1:1933/health
echo
