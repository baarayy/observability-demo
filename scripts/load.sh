#!/usr/bin/env bash
# Run a k6 load profile inside Docker (no local k6 install needed).
#
#   scripts/load.sh steady [duration] [rate]   realistic background traffic (default 10m @ 5 rps)
#   scripts/load.sh spike                      5 -> 150 -> 5 rps
#   scripts/load.sh stress                     ramp up to 300 virtual users to find the breaking point
#
# k6 also pushes its client-side metrics to Prometheus (k6_* metrics).
set -euo pipefail
cd "$(dirname "$0")/.."

profile=${1:-steady}
duration=${2:-10m}
rate=${3:-5}

if [[ ! -f "load/${profile}.js" ]]; then
  echo "Unknown profile '${profile}'. Available: $(ls load | sed 's/\.js//' | tr '\n' ' ')"
  exit 1
fi

echo "Running k6 profile '${profile}' against order-service (Ctrl+C to stop)..."
docker compose --profile tools run --rm \
  -e DURATION="$duration" -e RATE="$rate" \
  k6 run -o experimental-prometheus-rw --tag testid="${profile}-$(date +%H%M%S)" "/scripts/${profile}.js"
