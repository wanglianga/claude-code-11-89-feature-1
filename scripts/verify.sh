#!/usr/bin/env bash
# 等待服务就绪后执行端到端回放（抢修调度主线 + 道路施工许可审查）。
# 用法：scripts/verify.sh [base_url]
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
export BASE_URL

echo "等待 ${BASE_URL}/api/health ..."
for _ in $(seq 1 60); do
  if curl -fsS --max-time 3 "${BASE_URL}/api/health" >/dev/null 2>&1; then
    echo "服务已就绪。"
    break
  fi
  sleep 2
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
python3 "${SCRIPT_DIR}/replay.py"
python3 "${SCRIPT_DIR}/replay_construction.py"
