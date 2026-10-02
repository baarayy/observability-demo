# Query cheatsheet

Paste these in Grafana → **Explore** (or Prometheus at http://localhost:9090).

## PromQL (metrics)

```promql
# --- RED -------------------------------------------------------------------
# Rate: requests per second per service and route
sum by (service_name, http_route) (rate(http_server_request_duration_seconds_count[1m]))

# Errors: 5xx ratio per service
sum by (service_name) (rate(http_server_request_duration_seconds_count{http_response_status_code=~"5.."}[1m]))
/
sum by (service_name) (rate(http_server_request_duration_seconds_count[1m]))

# Duration: p95 per service (sum the buckets FIRST, then compute the quantile)
histogram_quantile(0.95, sum by (le, service_name) (rate(http_server_request_duration_seconds_bucket[1m])))

# Average latency (compare with p95/p99 to see the tail)
sum by (service_name) (rate(http_server_request_duration_seconds_sum[1m]))
/
sum by (service_name) (rate(http_server_request_duration_seconds_count[1m]))

# % of requests faster than 500ms (an SLI!)
sum(rate(http_server_request_duration_seconds_bucket{le="0.5", http_route="/api/orders"}[5m]))
/
sum(rate(http_server_request_duration_seconds_count{http_route="/api/orders"}[5m]))

# --- Dependencies ------------------------------------------------------------
histogram_quantile(0.95, sum by (le, server_address) (rate(http_client_request_duration_seconds_bucket[1m])))
db_client_connections_usage{service_name="order-service"}
db_client_connections_pending_requests

# --- JVM ---------------------------------------------------------------------
jvm_memory_used_bytes{jvm_memory_type="heap"}
sum by (service_name) (rate(jvm_gc_duration_seconds_sum[1m]))
jvm_cpu_recent_utilization_ratio
jvm_thread_count

# --- Business ------------------------------------------------------------------
sum by (order_result) (rate(orders_created_total[5m]))
sum by (order_sku) (increase(orders_created_total{order_result="confirmed"}[1h]))
histogram_quantile(0.5, sum by (le) (rate(order_amount_bucket[5m])))
inventory_stock_level

# --- Derived from traces (Tempo metrics-generator) --------------------------------
sum by (client, server) (rate(traces_service_graph_request_total[1m]))
histogram_quantile(0.95, sum by (le, service, span_name) (rate(traces_spanmetrics_latency_bucket[1m])))

# --- k6 (client side) ------------------------------------------------------------
k6_http_req_duration_p95
sum(rate(k6_http_reqs_total[1m]))

# --- Meta: the telemetry pipeline itself ------------------------------------------
sum by (receiver) (rate(otelcol_receiver_accepted_spans[1m]))
sum by (exporter) (rate(otelcol_exporter_sent_log_records[1m]))
otelcol_exporter_queue_size
prometheus_tsdb_head_series                       # total active series = cardinality
topk(10, count by (__name__) ({__name__=~".+"}))  # which metrics have the most series
```

**Rules of thumb:** `rate()` before `sum()`; `sum by (le)` before `histogram_quantile()`; never `rate()` a gauge.

## LogQL (logs)

```logql
# all logs of a service
{service_name="order-service"}

# line filter (fast, substring) + structured metadata filter
{service_name="order-service"} |= "Order" | severity_text="ERROR"

# everything for one request, across services
{service_name=~".+"} | trace_id="<paste trace id>"

# regex line filter, case-insensitive
{service_name="inventory-service"} |~ "(?i)insufficient|unknown"

# extract fields with pattern and filter on them
{service_name="order-service"} |= "confirmed" | pattern "Order <id> confirmed sku=<sku> total=<total>" | total > 1000

# metric queries
sum by (service_name) (count_over_time({service_name=~".+"} | severity_text="ERROR" [1m]))
sum by (service_name, severity_text) (rate({service_name=~".+"}[1m]))
topk(5, sum by (sku) (count_over_time({service_name="order-service"} |= "confirmed" | pattern "<_> sku=<sku> <_>" [10m])))
```

## TraceQL (traces)

```traceql
# by service
{ resource.service.name = "order-service" }

# slow requests
{ kind = server && duration > 1s }

# errors
{ status = error }

# a specific route
{ span.http.route = "/api/orders" && span.http.request.method = "POST" }

# by custom (business) attribute set in OrderService
{ span.order.sku = "LAPTOP" && span.order.quantity >= 10 }

# slow DB queries
{ span.db.system = "postgresql" && duration > 500ms }

# structural: order-service traces where an inventory-service span failed
{ resource.service.name = "order-service" } >> { resource.service.name = "inventory-service" && status = error }

# custom manual span
{ name = "calculate-total" }

# TraceQL metrics (needs local-blocks processor, enabled here)
{ resource.service.name = "order-service" } | rate()
{ kind = server } | quantile_over_time(duration, .95) by (resource.service.name)
```

## Useful raw endpoints

| URL | What |
|-----|------|
| http://localhost:8889/metrics | App metrics as Prometheus sees them (raw text, with exemplars) |
| http://localhost:8888/metrics | Collector's own metrics |
| http://localhost:55679/debug/tracez | Collector zpages |
| http://localhost:9090/targets | Prometheus scrape targets |
| http://localhost:9090/alerts | Alert rules & state |
| http://localhost:3100/loki/api/v1/labels | Loki labels |
| http://localhost:3200/api/search?q=%7B%7D | Tempo search API |
