#!/usr/bin/env bash
# Stop the stack.  Pass --clean to also delete volumes (database, stored telemetry).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--clean" ]]; then
  docker compose --profile tools down -v --remove-orphans
else
  docker compose --profile tools down --remove-orphans
fi
