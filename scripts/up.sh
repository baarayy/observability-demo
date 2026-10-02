#!/usr/bin/env bash
# Build and start the whole stack, then wait until the apps answer.
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose up -d --build

wait_for() {
  local name=$1 url=$2
  printf "Waiting for %-18s" "$name"
  for _ in $(seq 1 90); do
    if curl -fs -o /dev/null "$url"; then
      echo " ok"
      return 0
    fi
    printf "."
    sleep 2
  done
  echo " TIMEOUT (check: docker compose logs $name)"
  return 1
}

wait_for grafana           http://localhost:3000/api/health
wait_for prometheus        http://localhost:9090/-/ready
wait_for otel-collector    http://localhost:13133/
wait_for inventory-service http://localhost:8081/actuator/health
wait_for order-service     http://localhost:8080/actuator/health

cat <<'EOF'

Stack is up!
  Grafana            http://localhost:3000   (dashboards: Service Overview)
  Prometheus         http://localhost:9090   (alerts: /alerts)
  order-service      http://localhost:8080/api/orders
  inventory-service  http://localhost:8081/api/inventory
  Collector zpages   http://localhost:55679/debug/tracez

Next:
  scripts/load.sh steady      # generate background traffic
  scripts/chaos.sh help       # break things on purpose
EOF
