# Observability Demo: OpenTelemetry + Prometheus + Loki + Tempo + Grafana

A hands-on playground for learning observability. You get two small **Spring Boot** microservices,
instrumented with **OpenTelemetry**, that send **metrics, logs and traces** through an **OTel Collector**
into **Prometheus**, **Loki** and **Tempo**. Everything is visualized and cross-linked in **Grafana**.
Scripts generate load and break things on purpose (latency, errors, outages, pool exhaustion, CPU, memory leaks)
so you can practice finding out *what* went wrong and *why*.

![High-level design](docs/architecture.svg)

<sub>Editable diagram: [`docs/architecture.excalidraw`](docs/architecture.excalidraw) (open at https://excalidraw.com)</sub>

## What's inside

| Part | What it does |
|------|--------------|
| `order-service` (:8080) | Orders REST API → calls inventory-service → stores in Postgres. Has chaos endpoints. |
| `inventory-service` (:8081) | Stock reservations. Fault injection is configurable at runtime (`/admin/chaos`). |
| OpenTelemetry Java agent | Zero-code instrumentation: HTTP, JDBC, logs, JVM, connection pool |
| OTel Collector | Receives OTLP from the apps and routes traces → Tempo, logs → Loki, metrics → Prometheus |
| Prometheus | Metrics + alert rules + exemplars |
| Loki | Logs (OTLP-native, with trace_id on every line) |
| Tempo | Traces + metrics-generator (service graph, span metrics) |
| Grafana | Pre-provisioned data sources, correlations and a **Service Overview** dashboard |
| k6 | Load profiles: steady, spike, stress |

## Prerequisites

- **Docker** with Docker Compose v2 (Docker Desktop, OrbStack, Colima…), with ~4 GB RAM allotted
- `curl`, `bash`, `python3` (for the helper scripts; preinstalled on macOS/Linux)
- **No** local Java, Maven or k6 needed: everything builds and runs in containers.

## Quick start

```bash
# 1. Build & start everything (first build takes a few minutes: Maven downloads + images)
scripts/up.sh

# 2. Generate realistic traffic (leave this running in its own terminal)
scripts/load.sh steady

# 3. Open Grafana: the Service Overview dashboard is the home page
open http://localhost:3000

# 4. Break something and investigate
scripts/chaos.sh help
scripts/chaos.sh downstream-latency
scripts/chaos.sh reset

# 5. Check that everything is healthy and every signal arrives
scripts/status.sh

# 6. Stop (add --clean to also delete data)
scripts/down.sh
```

Without the scripts: `docker compose up -d --build` and `docker compose down`.

## URLs

| What | URL |
|------|-----|
| Grafana (no login) | http://localhost:3000 |
| Prometheus | http://localhost:9090 (alerts: `/alerts`, targets: `/targets`) |
| order-service | http://localhost:8080/api/orders |
| inventory-service | http://localhost:8081/api/inventory |
| Collector: raw app metrics | http://localhost:8889/metrics |
| Collector zpages | http://localhost:55679/debug/tracez |
| Loki / Tempo APIs | http://localhost:3100 / http://localhost:3200 |

## Try the API

```bash
# place an order (watch the X-Trace-Id response header: paste it into Grafana → Explore → Tempo)
curl -i -X POST localhost:8080/api/orders -H 'Content-Type: application/json' -d '{"sku":"KEYBOARD","quantity":2}'

curl localhost:8080/api/orders            # latest 20 orders
curl localhost:8081/api/inventory         # stock levels

# SKUs: KEYBOARD, MOUSE, MONITOR, LAPTOP, HEADSET
```

## Scripts

| Script | Purpose |
|--------|---------|
| `scripts/up.sh` | Build, start, wait for health, print URLs |
| `scripts/down.sh [--clean]` | Stop (and optionally wipe volumes) |
| `scripts/status.sh` | Health of every component + proof that metrics/logs/traces arrive + firing alerts |
| `scripts/load.sh steady [duration] [rps]` | Realistic mixed traffic (default 10m @ 5 rps) incl. some 4xx/5xx noise |
| `scripts/load.sh spike` | 5 → 150 → 5 rps spike |
| `scripts/load.sh stress` | Ramp up to 300 virtual users to find the breaking point |
| `scripts/chaos.sh <scenario>` | Failure injection (see below) |

### Chaos scenarios

| Scenario | Simulates | Main signals to look at |
|----------|-----------|------------------------|
| `slow [ms] [n]` | Slow endpoint | p95/p99 by route, exemplars |
| `downstream-latency [ms]` | Slow dependency | client-call latency, trace waterfall, service graph |
| `downstream-errors [rate]` | Failing dependency | 5xx, error spans in 2 services, same trace_id in logs |
| `outage` / `recover` | Dependency down | connection refused, missing series, `ServiceNotReporting` alert |
| `db-slow [s] [n]` | Slow SQL → pool exhaustion | Hikari pool panel, `SQLTransientConnectionException` |
| `errors [n]` | Handled 500s | error rate, status codes |
| `exceptions [n]` | Unhandled exceptions | stack traces in Loki, exception events in spans |
| `bad-requests [n]` | 4xx client errors | status codes, *not* counted as server errors |
| `random [n]` | Long-tail latency + 10% errors | avg vs p99 |
| `cpu [s]` | CPU saturation | CPU panel, everything slower |
| `memory [mb] [times]` / `memory-release` | Memory leak → OOM | heap, GC time, OutOfMemoryError |
| `log-burst [n]` | Log flood | LogQL practice |
| `reset` | Undo all chaos | |

Step-by-step investigation guides for each one: **[docs/SCENARIOS.md](docs/SCENARIOS.md)**.

## What to look at in Grafana

1. **Dashboards → Observability Demo → Service Overview**: golden signals, RED per route, dependencies
   (HTTP client, DB pool, service graph), JVM, business metrics, logs, slowest traces.
2. **Click a dot** on the latency panel (an *exemplar*) → opens the exact trace in Tempo.
3. In a trace, click **Logs for this span** → Loki filtered by that trace id.
4. In Loki, expand a log line → **View trace** button.
5. **Explore → Tempo → Service Graph** for the live dependency map.
6. **Alerting → Alert rules** (or http://localhost:9090/alerts) during chaos.

## Documentation

| Doc | What's in it |
|-----|--------------|
| [docs/PLAN.md](docs/PLAN.md) | **Learning plan**: 11 phases with goals, exercises and checkpoints; build-it-yourself path |
| [docs/CONCEPTS.md](docs/CONCEPTS.md) | Observability primer: signals, RED/USE, OpenTelemetry, Prometheus, Loki, Tempo, SLOs, glossary |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, signal flows, request lifecycle, design decisions, naming |
| [docs/SCENARIOS.md](docs/SCENARIOS.md) | Incident drills: telemetry fingerprint of each failure mode |
| [docs/QUERIES.md](docs/QUERIES.md) | PromQL / LogQL / TraceQL cheatsheet |
| [docs/architecture.excalidraw](docs/architecture.excalidraw) | Editable high-level design diagram |

## Repository layout

```
.
├── docker-compose.yml           # the whole stack
├── services/
│   ├── order-service/           # Spring Boot app + Dockerfile (with OTel agent)
│   └── inventory-service/
├── infra/
│   ├── otel-collector/          # collector pipelines
│   ├── prometheus/              # scrape config + alert rules
│   ├── loki/  tempo/            # backend configs
│   └── grafana/                 # provisioned data sources + dashboards (as code)
├── load/                        # k6 scripts
├── scripts/                     # up / down / status / load / chaos
└── docs/                        # plan, concepts, architecture, scenarios, queries, diagram
```

## Troubleshooting

| Problem | Try |
|---------|-----|
| Dashboard is empty | Send traffic (`scripts/load.sh steady`), wait ~30 s; run `scripts/status.sh` |
| A service keeps restarting | `docker compose logs order-service`; give Docker more memory |
| Want to see raw telemetry | `docker compose logs -f otel-collector` (set `debug` exporter `verbosity: detailed`) |
| Port already in use | Change the left side of the port mapping in `docker-compose.yml` |
| No traces in Tempo | `docker compose logs tempo otel-collector`; check http://localhost:8888/metrics for `otelcol_exporter_send_failed_spans` |
| Changed a Java file | `docker compose up -d --build order-service` |
| Changed a config file | `docker compose restart <component>` |
| Start from zero | `scripts/down.sh --clean && scripts/up.sh` |

## Versions

Spring Boot 3.3.5 · Java 21 · OTel Java agent 2.10.0 · OTel Collector contrib 0.115.1 · Prometheus 2.55.1 ·
Loki 3.3.2 · Tempo 2.6.1 · Grafana 11.4.0 · k6 0.55.0 · Postgres 16.
All versions are pinned in `docker-compose.yml` and the Dockerfiles. Bump them deliberately and read the changelogs.
