#!/usr/bin/env bash
# Quick health check of every component + proof that each signal reaches its backend.
set -uo pipefail
cd "$(dirname "$0")/.."

ok()   { printf "  \033[32m✔\033[0m %s\n" "$*"; }
fail() { printf "  \033[31m✘\033[0m %s\n" "$*"; }
check() { if curl -fs -o /dev/null "$2"; then ok "$1"; else fail "$1 ($2)"; fi; }

echo "Components"
check "order-service      :8080" http://localhost:8080/actuator/health
check "inventory-service  :8081" http://localhost:8081/actuator/health
check "otel-collector     :13133" http://localhost:13133/
check "prometheus         :9090" http://localhost:9090/-/ready
check "loki               :3100" http://localhost:3100/ready
check "tempo              :3200" http://localhost:3200/ready
check "grafana            :3000" http://localhost:3000/api/health

echo
echo "Signals (send a few orders first: scripts/chaos.sh orders)"
metrics=$(curl -fs 'http://localhost:9090/api/v1/query' --data-urlencode 'query=count(http_server_request_duration_seconds_count)' \
  | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else 0)' 2>/dev/null)
[[ "${metrics:-0}" != "0" ]] && ok "metrics: $metrics HTTP series in Prometheus" || fail "metrics: no HTTP series in Prometheus yet"

logs=$(curl -fs -G 'http://localhost:3100/loki/api/v1/labels' \
  | python3 -c 'import sys,json; print("service_name" in json.load(sys.stdin).get("data",[]))' 2>/dev/null)
[[ "$logs" == "True" ]] && ok "logs: Loki has service_name streams" || fail "logs: nothing in Loki yet"

traces=$(curl -fs -G 'http://localhost:3200/api/search' --data-urlencode 'q={resource.service.name="order-service"}' --data-urlencode 'limit=1' \
  | python3 -c 'import sys,json; print(len(json.load(sys.stdin).get("traces",[])))' 2>/dev/null)
[[ "${traces:-0}" != "0" ]] && ok "traces: Tempo has order-service traces" || fail "traces: no traces in Tempo yet"

echo
echo "Firing alerts"
curl -fs http://localhost:9090/api/v1/alerts \
  | python3 -c 'import sys,json
a=[x for x in json.load(sys.stdin)["data"]["alerts"] if x["state"]=="firing"]
print("\n".join("  ! "+x["labels"]["alertname"]+" "+x["labels"].get("service_name","") for x in a) or "  none")' 2>/dev/null
