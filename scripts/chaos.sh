#!/usr/bin/env bash
# Failure scenarios for the demo. Run `scripts/chaos.sh help` for the list.
# Tip: keep `scripts/load.sh steady` running in another terminal while you do this,
#      so you can see how failures affect "normal" traffic.
set -euo pipefail
cd "$(dirname "$0")/.."

ORDER=${ORDER_URL:-http://localhost:8080}
INVENTORY=${INVENTORY_URL:-http://localhost:8081}

say() { printf "\n\033[1;35m==> %s\033[0m\n" "$*"; }
hint() { printf "\033[0;36m    look at: %s\033[0m\n" "$*"; }

# curl that prints status, duration and the trace id (X-Trace-Id header) of each call
call() {
  local method=$1 url=$2; shift 2
  curl -s -o /dev/null -X "$method" "$url" "$@" \
    -D - -w "    %{http_code}  %{time_total}s  $method $url" \
    | awk '{ sub(/\r$/, "") } tolower($1) == "x-trace-id:" { tid = $2 } { last = $0 } END { print last "  trace_id=" tid }'
}

# run N calls with C in parallel
burst() {
  local n=$1 c=$2 method=$3 url=$4
  for i in $(seq 1 "$n"); do
    call "$method" "$url" &
    if (( i % c == 0 )); then wait; fi
  done
  wait
}

order() { # order <sku> <qty>
  call POST "$ORDER/api/orders" -H 'Content-Type: application/json' -d "{\"sku\":\"$1\",\"quantity\":$2}"
}

inventory_chaos() { # inventory_chaos <latencyMs> <jitterMs> <errorRate>
  curl -s -X POST "$INVENTORY/admin/chaos" -H 'Content-Type: application/json' \
    -d "{\"latencyMs\":$1,\"jitterMs\":$2,\"errorRate\":$3}"
  echo
}

usage() {
  cat <<'EOF'
Usage: scripts/chaos.sh <scenario> [args]

 Latency
   slow [ms] [count]             Slow endpoint in order-service           (default 3000 ms x 10)
   downstream-latency [ms]       Make inventory-service slow for ALL calls (default 1500 ms + jitter)
   db-slow [seconds] [parallel]  Slow SQL queries -> exhausts DB pool     (default 5 s x 15)

 Errors
   errors [count]                Endpoint returning HTTP 500               (default 20)
   exceptions [count]            Unhandled exceptions with stack traces   (default 10)
   downstream-errors [rate]      inventory-service fails a % of requests  (default 0.3 = 30%)
   outage                        Stop inventory-service container (recover with: recover)
   bad-requests [count]          4xx: unknown SKUs, invalid payloads, huge quantities
   random [count]                Long-tail latency + ~10% errors          (default 100)

 Saturation
   cpu [seconds]                 Burn all CPU cores in order-service      (default 30)
   memory [mb] [times]           Leak heap memory (try 5 x 60 MB to hit OOM)
   memory-release                Free the leaked memory
   log-burst [count]             Flood of mixed-level log lines           (default 500)

 Housekeeping
   recover                       Start inventory-service again
   reset                         Clear all injected chaos
   orders [count]                A few normal orders (sanity check)
EOF
}

scenario=${1:-help}
shift || true

case "$scenario" in
  slow)
    ms=${1:-3000}; n=${2:-10}
    say "Sending $n requests that take ${ms} ms each"
    burst "$n" 5 GET "$ORDER/api/chaos/slow?ms=$ms"
    hint "Service Overview -> p95/p99 latency, click an exemplar dot -> trace"
    ;;

  downstream-latency)
    ms=${1:-1500}
    say "inventory-service now adds ${ms} ms (+ up to 500 ms jitter) to every request"
    inventory_chaos "$ms" 500 0
    for _ in 1 2 3; do order KEYBOARD 1; done
    hint "order-service latency goes up although its own code is fine."
    hint "Tempo trace: the inventory-service span covers most of the time. Service graph edge turns slow."
    hint "Undo with: scripts/chaos.sh reset"
    ;;

  downstream-errors)
    rate=${1:-0.3}
    say "inventory-service now fails ${rate} of requests with HTTP 500"
    inventory_chaos 0 0 "$rate"
    for _ in $(seq 1 10); do order MOUSE 1; done
    hint "order-service answers 503 (inventory_unavailable). Logs: ERROR in BOTH services with the same trace_id."
    hint "Undo with: scripts/chaos.sh reset"
    ;;

  outage)
    say "Stopping inventory-service (simulated crash)"
    docker compose stop inventory-service
    for _ in $(seq 1 5); do order MONITOR 1; done
    hint "Connection refused errors; trace only has order-service spans; ServiceNotReporting alert after ~1-2 min"
    hint "Recover with: scripts/chaos.sh recover"
    ;;

  recover)
    say "Starting inventory-service"
    docker compose start inventory-service
    ;;

  db-slow)
    secs=${1:-5}; n=${2:-15}
    say "Running $n parallel slow queries (${secs}s each) against a pool of 5 connections"
    for i in $(seq 1 "$n"); do call GET "$ORDER/api/chaos/slow-query?seconds=$secs" & done
    sleep 1
    say "Meanwhile, normal orders also need a DB connection..."
    for _ in 1 2 3; do order HEADSET 1 & done
    wait
    hint "DB connection pool panel (used = max, waiting threads > 0), 500s after 3s connection-timeout,"
    hint "SQLTransientConnectionException in logs, long 'SELECT' spans in Tempo"
    ;;

  errors)
    n=${1:-20}
    say "Sending $n requests that return HTTP 500"
    burst "$n" 10 GET "$ORDER/api/chaos/error?status=500"
    hint "Error rate panel, Responses by status code, HighErrorRate alert (if sustained)"
    ;;

  exceptions)
    n=${1:-10}
    say "Triggering $n unhandled exceptions"
    burst "$n" 5 GET "$ORDER/api/chaos/exception"
    hint "Loki: {service_name=\"order-service\"} | severity_text=\"ERROR\" -> stack traces; Tempo: span events 'exception'"
    ;;

  bad-requests)
    n=${1:-10}
    say "Sending $n bad requests (4xx)"
    for _ in $(seq 1 "$n"); do
      order UNICORN 1                                                        # 404 unknown sku
      order LAPTOP 9999                                                      # 409 out of stock
      call POST "$ORDER/api/orders" -H 'Content-Type: application/json' -d '{"sku":"","quantity":0}'  # 400 validation
    done
    hint "4xx are NOT counted as server errors (span status stays unset), but show in 'Responses by status code'"
    hint "Business metric: orders_created_total{order_result=~\"unknown_product|out_of_stock\"}"
    ;;

  random)
    n=${1:-100}
    say "Sending $n requests to a noisy endpoint (long tail latency, ~10% errors)"
    burst "$n" 10 GET "$ORDER/api/chaos/random"
    hint "Compare average vs p99 latency - averages hide the tail"
    ;;

  cpu)
    secs=${1:-30}
    say "Burning CPU in order-service for ${secs}s"
    call GET "$ORDER/api/chaos/cpu?seconds=$secs" &
    sleep 2
    for _ in 1 2 3; do order KEYBOARD 1; done
    wait
    hint "CPU utilization panel, latency of OTHER endpoints during the burn"
    ;;

  memory)
    mb=${1:-60}; times=${2:-3}
    say "Leaking ${mb} MB x ${times} in order-service (heap max ~ 75% of 768 MB)"
    for _ in $(seq 1 "$times"); do call GET "$ORDER/api/chaos/memory?mb=$mb"; sleep 1; done
    hint "Heap used vs max, GC time, 'leaked MB' line; enough leaking -> OutOfMemoryError in logs"
    hint "Release with: scripts/chaos.sh memory-release"
    ;;

  memory-release)
    say "Releasing leaked memory"
    call DELETE "$ORDER/api/chaos/memory"
    ;;

  log-burst)
    n=${1:-500}
    say "Emitting $n log lines"
    call GET "$ORDER/api/chaos/log-burst?count=$n"
    hint "Loki: sum by (severity_text) (count_over_time({service_name=\"order-service\"}[1m]))"
    ;;

  orders)
    n=${1:-5}
    say "Placing $n normal orders"
    for _ in $(seq 1 "$n"); do order KEYBOARD 2; done
    ;;

  reset)
    say "Resetting chaos"
    curl -s -X DELETE "$INVENTORY/admin/chaos" && echo
    curl -s -X DELETE "$ORDER/api/chaos/memory" && echo
    docker compose start inventory-service >/dev/null 2>&1 || true
    ;;

  help|-h|--help)
    usage
    ;;

  *)
    echo "Unknown scenario: $scenario"; echo; usage; exit 1
    ;;
esac
