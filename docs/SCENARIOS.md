# Failure scenarios: incident drills

Every scenario has a different **telemetry fingerprint**. Learn to recognize them.

**Setup for all drills**

```bash
scripts/load.sh steady          # terminal 1: realistic background traffic (leave it running)
scripts/chaos.sh <scenario>     # terminal 2: break something
scripts/chaos.sh reset          # undo everything
```

Open Grafana → **Service Overview** (refresh 10 s, last 15 min). Each `chaos.sh` call prints the
HTTP status, duration and **trace_id** of every request; paste that id into Explore → Tempo.

---

## 1. Slow endpoint: `chaos.sh slow 3000 10`

| Signal | What you see |
|--------|-------------|
| Metrics | p95/p99 jump for route `/api/chaos/slow`; other routes unaffected; average barely moves |
| Traces | One SERVER span of ~3 s with no children: the time is spent *inside* this service |
| Logs | `WARN Simulating slow request: sleeping 3000 ms` |

**Lesson:** filter latency by route; a single slow endpoint can hide in a service-wide average.
Click an exemplar dot on the latency panel to land on exactly one of these traces.

## 2. Slow dependency: `chaos.sh downstream-latency 1500`

| Signal | What you see |
|--------|-------------|
| Metrics | order-service `POST /api/orders` p95 ≈ 1.5–2 s; *Outgoing HTTP calls p95* rises the same amount; service graph edge order→inventory slows |
| Traces | order-service SERVER span is long, but almost all of it is the CLIENT span → inventory SERVER span |
| Logs | Nothing alarming in order-service (it's "just waiting") |

**Lesson:** the service that *alerts* is not always the service that's *broken*. Traces show where the time went.
Try TraceQL: `{ resource.service.name = "inventory-service" && duration > 1s }`.

## 3. Downstream errors: `chaos.sh downstream-errors 0.3`

| Signal | What you see |
|--------|-------------|
| Metrics | order-service 503s (`inventory_unavailable`); inventory-service 500s; `orders_created_total{order_result="inventory_unavailable"}` rises; **HighErrorRate** alert after ~1 min |
| Traces | Red spans in both services; inventory span has an `exception` event (`InjectedFailure`) |
| Logs | `ERROR Request failed: Injected failure` in inventory **and** `ERROR Order failed` in order, with the **same trace_id** |

**Lesson:** follow the error to its origin. In Loki: `{service_name=~".+"} | trace_id="<id>"` shows both services' logs for one request.

## 4. Outage: `chaos.sh outage` → `chaos.sh recover`

| Signal | What you see |
|--------|-------------|
| Metrics | inventory-service series disappear (after the collector's `metric_expiration`); order-service 503s; **ServiceNotReporting** alert |
| Traces | Only order-service spans; the CLIENT span has an error: `Connection refused` / `UnknownHostException` |
| Logs | `ResourceAccessException: I/O error on POST request ... Connection refused` |

**Lesson:** "no data" is also a signal. Alert on absence (`absent_over_time`), not just on bad values.

**Bonus:** after `recover`, order-service keeps failing for ~10 s even though inventory-service is healthy.
The error is `UnknownHostException: inventory-service`: the JVM caches *failed* DNS lookups
(`networkaddress.cache.negative.ttl`, default 10 s). Recovery is not instant, and the logs tell you why.

## 5. DB connection pool exhaustion: `chaos.sh db-slow 5 15`

| Signal | What you see |
|--------|-------------|
| Metrics | *DB connection pool* panel: `used` = max (5), `waiting threads` > 0; `POST /api/orders` latency jumps to ~3 s then fails; **DbConnectionPoolExhausted** alert |
| Traces | `SELECT pg_sleep` spans of 5 s; order requests that waited ~3 s and then errored *before* reaching the DB |
| Logs | `SQLTransientConnectionException: orders-pool - Connection is not available, request timed out after 3000ms` |

**Lesson:** classic **saturation**. CPU is low and the DB itself is fine, but a small shared resource is exhausted.
USE method: check utilization *and* saturation (waiters) of every pool/queue.

## 6. Errors: `chaos.sh errors 20` and `chaos.sh exceptions 10`

| Signal | What you see |
|--------|-------------|
| Metrics | 5xx in *Responses by status code*; error-ratio by route points at `/api/chaos/error` / `/api/chaos/exception` |
| Traces | `exceptions`: span status ERROR + `exception` event with type, message, stack trace |
| Logs | `exceptions`: full stack traces (`Servlet.service() ... threw exception`) |

**Lesson:** handled errors (returning 500) vs unhandled exceptions look different in traces and logs.

## 7. Client errors: `chaos.sh bad-requests`

| Signal | What you see |
|--------|-------------|
| Metrics | 400 / 404 / 409 in status-code panel; **no** increase in the 5xx error rate; business metric `orders_created_total{order_result=~"unknown_product|out_of_stock"}` |
| Traces | Spans are **not** marked as errors (4xx is the client's fault per OTel semantic conventions) |
| Logs | `WARN Order rejected: ...` (not ERROR) |

**Lesson:** separate "we're broken" (5xx) from "clients send bad data" (4xx). Only the former should page you.

## 8. Long-tail latency: `chaos.sh random 200`

| Signal | What you see |
|--------|-------------|
| Metrics | Average latency ≈ 300 ms but p99 ≈ 2.5 s+; ~10% errors |
| Traces | TraceQL `{ span.http.route = "/api/chaos/random" && duration > 1s }` returns the tail |

**Lesson:** never use averages for latency SLOs. Look at percentiles (and histograms / heatmaps).

## 9. CPU saturation: `chaos.sh cpu 30`

| Signal | What you see |
|--------|-------------|
| Metrics | *CPU utilization* → ~100%; latency of *all* endpoints in order-service rises; inventory-service unaffected |
| Traces | Normal orders' spans are slower everywhere (including `calculate-total`), with no single culprit span |
| Logs | `WARN Burning CPU on N threads` |

**Lesson:** when *everything* in one service gets slower at once, suspect a shared resource (CPU, GC, threads).
This is where continuous profiling (stretch goal) would show you the exact function.

## 10. Memory leak / OOM: `chaos.sh memory 60 5` → `chaos.sh memory-release`

| Signal | What you see |
|--------|-------------|
| Metrics | *Heap used* staircase towards max; `leaked MB` line; *GC time per second* spikes as the heap fills; **HighHeapUsage** alert |
| Logs | Eventually `java.lang.OutOfMemoryError: Java heap space` |
| Traces | Requests slow down during GC pauses |

**Lesson:** a sawtooth heap is healthy; a rising floor is a leak. GC time is often the first symptom.
(Container `mem_limit` is 768 MB, heap max ≈ 75% of that.)

## 11. Log flood: `chaos.sh log-burst 1000`

Practice LogQL:

```logql
# volume by level
sum by (severity_text) (count_over_time({service_name="order-service"}[1m]))

# who is affected?
{service_name="order-service"} |= "Failed to refresh" | pattern "<_> user=<user>" | line_format "{{.user}}"

# top users in error logs
topk(3, sum by (user) (count_over_time({service_name="order-service"} | severity_text="ERROR" | pattern "<_> user=<user>" [5m])))
```

## 12. Load: `scripts/load.sh spike` and `scripts/load.sh stress`

Questions to answer from the dashboards:
- At what request rate does p95 exceed 500 ms?
- What saturates first: DB pool (max 5), CPU, Tomcat threads (`jvm_thread_count`), or inventory-service?
- Compare server-side p95 with k6's client-side `k6_http_req_duration_p95` in Explore. Why are they different?
  (Hint: queueing before the request reaches Spring, network, connection setup.)

---

## Blind drill template

Have someone run a random scenario without telling you. Write:

```
Symptom : (what a user would notice + which alert/panel showed it)
Cause   : (the component and mechanism)
Evidence: (1 metric query, 1 trace id, 1 log line)
Fix     : (what would resolve or mitigate it)
```
