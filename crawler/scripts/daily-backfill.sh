#!/bin/sh
# 일일 무료 한도(20요청) backfill 래퍼. 등록 방법은 ../SCHEDULE_PROPOSAL.md (사용자 승인 전 등록 금지).
# API 키는 인자/환경에 넣지 않는다. pyeonit-crawler가 crawler/.env에서 읽는다.
set -u
cd "$(dirname "$0")/.." || exit 1
mkdir -p artifacts/logs
log="artifacts/logs/backfill-$(date +%Y%m%d).log"
{
  date -u +"== %Y-%m-%dT%H:%M:%SZ 시작"
  .venv/bin/pyeonit-crawler backfill --year 2026 --output artifacts/t6-chain --daily-request-limit 20 --model-chain gemini-3.8-flash --env-file .env "$@"
  status=$?
  .venv/bin/pyeonit-crawler backfill --next-run
  date -u +"== %Y-%m-%dT%H:%M:%SZ 종료 (backfill 종료 코드 $status)"
} >>"$log" 2>&1
exit "$status"
