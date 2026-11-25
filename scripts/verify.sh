#!/usr/bin/env bash
# 等待服务就绪后执行端到端回放。用法：scripts/verify.sh [base_url]
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

exec python3 "$(dirname "$0")/replay.py"
