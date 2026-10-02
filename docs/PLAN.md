# Learning plan

This plan takes you from "I've heard of Prometheus" to "I can instrument a Java service, run a full
OpenTelemetry + Grafana LGTM-style stack, and debug an incident with metrics, logs and traces".

The repo already has a **reference implementation** of every phase. You can use it in two ways:

- **Guided tour:** run the finished stack and work through each phase's *Explore* and *Exercises*.
- **Build it yourself (recommended for deep learning):** create a fresh branch, delete `services/` and
  `infra/`, and rebuild phase by phase. Peek at the reference only when you're stuck.

Each phase ends with a **checkpoint**, a concrete thing you should be able to do or explain.

| Phase | Topic | Est. time |
|------:|-------|-----------|
| 0 | Concepts & vocabulary | 2–3 h |
| 1 | Build the two Spring Boot services | 3–4 h |
| 2 | Containerize everything with Docker Compose | 1–2 h |
| 3 | Add the OTel Java agent + Collector (debug exporter only) | 2 h |
| 4 | Metrics → Prometheus | 3–4 h |
| 5 | Traces → Tempo | 3 h |
| 6 | Logs → Loki | 2–3 h |
| 7 | Grafana: dashboards & correlation | 3–4 h |
| 8 | Custom (manual) instrumentation | 2 h |
| 9 | Load testing & chaos: incident drills | 3–4 h |
| 10 | Alerting & SLOs | 2–3 h |
| 11 | Stretch goals | open-ended |

---

## Phase 0: Concepts & vocabulary

**Goal:** know the words before you touch the tools.

Read [`CONCEPTS.md`](CONCEPTS.md). Make sure you can answer:

- What's the difference between monitoring and observability?
- What are the three signals (metrics, logs, traces), and what question is each best at answering?
- What are RED, USE and the Four Golden Signals?
- What is OpenTelemetry, and what is it *not*? (It is not a backend. It stores nothing.)
- Push vs pull for metrics. Which one does Prometheus use?
- Counter vs gauge vs histogram. Why can't you average percentiles?
- What is cardinality, and why does putting `user_id` in a metric label get you yelled at?

**Checkpoint:** explain, without notes, how a trace ID ends up in a log line.

## Phase 1: Build the services

**Goal:** a small but realistic distributed system to observe. No observability code yet.

- `order-service` (Spring Boot, port 8080): REST API for orders, stores them in Postgres,
  calls `inventory-service` to reserve stock.
- `inventory-service` (port 8081): in-memory stock, `POST /api/inventory/{sku}/reserve`.
- Error handling: unknown SKU → 404, no stock → 409, downstream failure → 503.
- Chaos endpoints (`/api/chaos/*`, `/admin/chaos`) that are slow, fail, burn CPU, leak memory or hold DB connections.

Reference: `services/order-service`, `services/inventory-service`.

**Checkpoint:** `curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' -d '{"sku":"MOUSE","quantity":2}'` works.

## Phase 2: Containerize

**Goal:** one command to bring the whole system up.

- Multi-stage `Dockerfile` (Maven build → slim JRE image), so nobody needs a local JDK.
- `docker-compose.yml` with the 2 services + Postgres, healthchecks, `depends_on`.
- Memory limits (`mem_limit`) + `-XX:MaxRAMPercentage`. You'll need these to observe a realistic OOM later.

**Checkpoint:** `docker compose up --build` and the curl from Phase 1 works against the containers.

## Phase 3: OpenTelemetry agent + Collector

**Goal:** get telemetry *out* of the apps and see it raw before any backend is involved.

1. Download the OTel Java agent in the Dockerfile and start the JVM with `-javaagent:...`.
2. Configure it purely through env vars: `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, ...
3. Add an `otel-collector` container with **only** the `otlp` receiver and the `debug` exporter (`verbosity: detailed`).
4. Watch `docker compose logs -f otel-collector` while sending requests.

**Explore:**
- Find a span with `http.route`, `http.response.status_code`, `db.statement`.
- Find the `traceparent` header propagation: the inventory span has the same TraceId as the order span.
- Find a metric data point for `http.server.request.duration` and look at its histogram buckets.
- Find a log record and notice it already has a TraceId/SpanId.

**Checkpoint:** you can explain receivers → processors → exporters → pipelines, and why
the apps should talk to a collector instead of directly to each backend.

## Phase 4: Metrics → Prometheus

**Goal:** store metrics and learn PromQL.

1. Add the `prometheus` exporter to the collector (exposes `:8889/metrics`).
2. Add Prometheus and scrape the collector. Open http://localhost:8889/metrics and read the raw text format.
3. Learn how OTel names become Prometheus names: `http.server.request.duration` (unit `s`) →
   `http_server_request_duration_seconds_{bucket,sum,count}`.

**Exercises** (at http://localhost:9090/graph, cheatsheet in [`QUERIES.md`](QUERIES.md)):
- Requests per second per route.
- Error ratio (5xx / all).
- p95 latency per service using `histogram_quantile`.
- Why is `rate()` needed on a counter? What happens if you graph the raw counter?
- Compare `avg` latency (`_sum / _count`) with p99 while running `scripts/chaos.sh random`.
- Find the JVM heap, GC and thread metrics the agent gives you for free.

**Checkpoint:** you can write the RED queries for any service from memory.

## Phase 5: Traces → Tempo

**Goal:** follow a single request across services.

1. Add Tempo and the `otlp/tempo` exporter to the collector's traces pipeline.
2. Add the Tempo data source in Grafana and open **Explore → Tempo**.

**Exercises:**
- Find a `POST /api/orders` trace and identify: the SERVER span, the custom `calculate-total` span,
  the HTTP CLIENT span, the inventory SERVER span, and the `INSERT` DB span.
- TraceQL: all traces slower than 1s; all traces with errors; all orders for SKU `LAPTOP`
  (`{ span.order.sku = "LAPTOP" }`).
- Run `scripts/chaos.sh downstream-latency` and prove *from a trace* which service is slow.
- Enable the metrics-generator and look at the **Service Graph** tab.

**Checkpoint:** explain context propagation: what is in the `traceparent` header, and who injects/extracts it?

## Phase 6: Logs → Loki

**Goal:** centralized, queryable logs that are linked to traces.

1. Add Loki and the `otlphttp/loki` exporter (Loki ≥ 3 has a native OTLP endpoint).
2. Notice: no log shipper (Promtail/Alloy) and no file parsing. The agent captures Logback events and sends them as OTLP.

**Exercises:**
- `{service_name="order-service"}` → then add `|= "Order"` → then `| severity_text="ERROR"`.
- Count errors per minute per service with `count_over_time`.
- Expand a log line: find `trace_id` in its structured metadata. Why isn't it a *label*? (cardinality!)
- `scripts/chaos.sh log-burst` → use `pattern` or `json`/`logfmt`-style parsing to extract `user=`.

**Checkpoint:** you can explain labels vs structured metadata vs log line content in Loki.

## Phase 7: Grafana dashboards & correlation

**Goal:** one place to see everything, with one-click jumps between signals.

- Provision data sources and dashboards from files (`infra/grafana/provisioning`): dashboards as code.
- Wire correlations:
  - Prometheus **exemplars** → Tempo (`exemplarTraceIdDestinations`)
  - Loki `trace_id` → Tempo (`derivedFields`)
  - Tempo → Loki (`tracesToLogsV2`) and → Prometheus (`tracesToMetrics`, `serviceMap`)
- Build (or study) the **Service Overview** dashboard: golden-signal stats, RED per route, dependencies, JVM, business metrics, logs.

**Exercises:**
- From a latency spike, click an exemplar → open the trace → jump to its logs. Do the full loop.
- Add a panel yourself (e.g. "orders per minute for LAPTOP").
- Add a dashboard variable for `http_route`.

**Checkpoint:** you can go metric → trace → log → back to metric for an incident, in under a minute.

## Phase 8: Custom instrumentation

**Goal:** go beyond what auto-instrumentation gives you.

Reference: `OrderService.java`, `StockStore.java`, `ChaosController.java`.

- Add attributes to the current span (`Span.current().setAttribute("order.sku", ...)`).
- Create a child span manually (`calculate-total`).
- Create metrics with the OTel API: a counter (`orders.created`), a histogram (`order.amount`),
  observable gauges (`inventory.stock.level`, `chaos.memory.held`).
- Record exceptions on spans (`span.recordException`).
- Return the trace id to clients (`X-Trace-Id` header). Support teams love this.

**Exercises:**
- Add an `order.channel` attribute (web/mobile) and break down orders by it. Keep it low-cardinality!
- What would happen if you added `order.id` as a *metric* attribute? (Try it, then watch the series count: `count({__name__=~"orders_.*"})`.)
- Start the stack with `OTEL_JAVAAGENT_ENABLED=false` on one service: what still works, what disappears?

**Checkpoint:** you know when to use auto-instrumentation, when to add manual instrumentation, and what never to put in a label.

## Phase 9: Load & chaos: incident drills

**Goal:** practice the actual job: something is wrong, find out what and why.

Follow [`SCENARIOS.md`](SCENARIOS.md). For each scenario:
1. Start background traffic: `scripts/load.sh steady`.
2. Have a friend (or future you) run a random `scripts/chaos.sh` scenario *without telling you which*.
3. Using only Grafana, write a 3-line incident note: **symptom**, **cause**, **evidence** (with links).

Also run `scripts/load.sh spike` and `scripts/load.sh stress` and find which resource saturates first.
Compare k6's client-side p95 (`k6_http_req_duration_p95`) with the server-side p95. Why do they differ?

**Checkpoint:** you can diagnose every scenario in `SCENARIOS.md` from telemetry alone.

## Phase 10: Alerting & SLOs

**Goal:** turn signals into actionable alerts.

- Study `infra/prometheus/alert-rules.yml`: error rate, p95 latency, heap, DB pool, pipeline health.
- Watch them fire at http://localhost:9090/alerts during chaos scenarios. Note `for:` and why it exists.
- Define an SLO: *"99% of `POST /api/orders` succeed and 95% complete under 500 ms over 30 days"*.
  Write the SLI queries, then an **error budget burn-rate** alert (multi-window: 5m & 1h).
- Symptom-based vs cause-based alerts: which of the provided rules page a human, which are just warnings?

**Checkpoint:** you can explain why "CPU > 80%" is usually a bad paging alert, and what to alert on instead.

## Phase 11: Stretch goals

Pick what matches the jobs you're aiming for:

- **Sampling:** add the `tail_sampling` processor (keep all errors + slow traces, 10% of the rest). Observe the effect on Tempo and on span metrics.
- **Alertmanager:** route alerts to Slack/email, grouping, silences, inhibition.
- **Grafana Alloy:** replace the OTel Collector with Alloy (Grafana's OTel Collector distribution).
- **Profiles:** add Pyroscope (the 4th signal) and the Pyroscope Java agent; link profiles to traces.
- **Infrastructure metrics:** cAdvisor (containers), node-exporter (host), postgres-exporter (DB).
- **Micrometer path:** instrument one service with Spring Boot Actuator + Micrometer + `micrometer-tracing-bridge-otel` instead of the agent and compare.
- **OTel Spring Boot starter:** instrumentation without a `-javaagent` (good for native images).
- **Scale it:** Mimir instead of Prometheus, object storage (MinIO) for Loki/Tempo, the microservices deployment modes.
- **Kubernetes:** move to kind/k3d, use the OpenTelemetry Operator for auto-injection, the `k8sattributes` processor, Helm charts for the LGTM stack.
- **Cost & cardinality:** use `prometheus_tsdb_head_series`, Loki's volume API and `otelcol_*` metrics to reason about data volume.

---

## Skills checklist for observability roles

- [ ] Explain the three signals, their trade-offs and how they correlate
- [ ] OpenTelemetry: API vs SDK vs agent vs Collector, OTLP, resources, semantic conventions, context propagation
- [ ] Collector config: receivers/processors/exporters/pipelines, batching, memory limiting, sampling
- [ ] PromQL: `rate`, `sum by`, `histogram_quantile`, ratios, `absent`, recording rules
- [ ] LogQL: stream selectors, line filters, parsers, metric queries
- [ ] TraceQL: span/resource attributes, duration, status, structural queries
- [ ] Grafana: provisioning, variables, exemplars, correlations, alerting
- [ ] Cardinality management and telemetry cost control
- [ ] SLIs/SLOs/error budgets and burn-rate alerting
- [ ] Incident investigation workflow: from symptom to root cause with evidence
