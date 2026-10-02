# Observability concepts: a primer

## Monitoring vs observability

- **Monitoring** answers questions you knew to ask in advance: *"Is CPU > 80%?"*, *"Is the site up?"*
- **Observability** is the ability to answer questions you *didn't* anticipate, from the telemetry the
  system already emits: *"Why are LAPTOP orders from the last 10 minutes slow, but only some of them?"*

You get observability by emitting rich, correlated telemetry and having tools to slice it.

## The three signals (+1)

| Signal | What it is | Best at answering | Cost profile | Here stored in |
|--------|-----------|-------------------|--------------|----------------|
| **Metrics** | Numbers aggregated over time (counters, gauges, histograms) | *Is something wrong? How much? Since when?* | Cheap, fixed size regardless of traffic | Prometheus |
| **Logs** | Timestamped text/structured events | *What exactly happened at this moment?* | Grows with traffic, can get expensive | Loki |
| **Traces** | The path of one request through all services, as a tree of **spans** | *Where is the time spent? Which service failed?* | Grows with traffic; usually sampled | Tempo |
| *Profiles* | CPU/memory usage per function | *Which line of code burns the CPU?* | Moderate | (stretch goal: Pyroscope) |

The real power comes from **correlation**: going from a spike on a metric graph, to an example
trace of a slow request, to the logs of that exact request.

## Methods: what to measure

- **RED** (for request-driven services): **R**ate, **E**rrors, **D**uration.
- **USE** (for resources: CPU, memory, pools, queues): **U**tilization, **S**aturation, **E**rrors.
- **Four Golden Signals** (Google SRE book): Latency, Traffic, Errors, Saturation.

The Service Overview dashboard is organized exactly like this.

## OpenTelemetry (OTel)

A CNCF project that standardizes **how telemetry is produced and transported**. It is vendor neutral and
does **not** store or visualize anything.

| Piece | What it is | In this demo |
|-------|-----------|--------------|
| **API** | Interfaces your code calls (`Tracer`, `Meter`, `Span`). No-op without an SDK. | `opentelemetry-api` dependency, used in `OrderService` |
| **SDK** | Implementation: sampling, batching, exporting | Bundled inside the Java agent |
| **Instrumentation** | Code that creates telemetry for libraries (Spring MVC, JDBC, HTTP clients, Logback…) | Java agent, zero code changes |
| **Java agent** | A `-javaagent` jar that bytecode-instruments 100+ libraries at startup | `opentelemetry-javaagent.jar` in the Dockerfiles |
| **OTLP** | The OpenTelemetry Protocol (gRPC :4317 / HTTP :4318) | Apps → Collector, Collector → Tempo/Loki |
| **Collector** | A standalone pipeline: receive → process → export | `otel-collector` container |
| **Semantic conventions** | Standard attribute names: `http.route`, `http.response.status_code`, `db.system`, `service.name` | Why dashboards can be generic |
| **Resource** | Attributes describing *who* produced the telemetry: `service.name`, `service.version`, `host.name` | Become labels `service_name`, … in every backend |

### Context propagation

When `order-service` calls `inventory-service`, the agent injects a W3C header:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             │  │                                │                └ flags (01 = sampled)
             │  │                                └ parent span id
             │  └ trace id (shared by every span of the request)
             └ version
```

`inventory-service`'s agent extracts it and creates its spans with the same trace id. The same
trace id is put into the logging MDC, which is how logs and traces get linked.

### Spans

A span has: name, trace id, span id, parent span id, start/end time, **kind**
(SERVER, CLIENT, INTERNAL, PRODUCER, CONSUMER), **status** (UNSET/OK/ERROR), **attributes** and **events**
(e.g. an `exception` event with the stack trace).

> HTTP 4xx responses do **not** mark a SERVER span as error (the client made the mistake); 5xx do.

## Prometheus

- **Pull model:** Prometheus *scrapes* `/metrics` endpoints on an interval. (Here it scrapes the
  collector on :8889, which re-exposes what the apps pushed via OTLP.)
- **Data model:** a time series = metric name + set of labels, e.g.
  `http_server_request_duration_seconds_count{service_name="order-service", http_route="/api/orders", http_response_status_code="201"}`.
- **Metric types:**
  - **Counter**: only goes up (resets on restart). Always use `rate()`/`increase()` on it.
  - **Gauge**: goes up and down (heap used, stock level, queue size).
  - **Histogram**: counts observations in buckets (`_bucket{le="0.5"}`), plus `_sum` and `_count`.
    Percentiles are *estimated* server-side with `histogram_quantile()`. You **cannot average percentiles**
    across instances, but you *can* sum buckets and then compute the percentile, which is why histograms win.
- **Exemplars:** a histogram sample can carry a `trace_id` of one real request that fell into that bucket.
  Grafana shows them as dots you can click.
- **Cardinality:** every unique label combination is a new series held in memory.
  `http_route="/api/orders/{id}"` is fine; `order_id="12345"` is a disaster.

## Loki

"Like Prometheus, but for logs." It indexes **labels only**, not the log content.

- **Stream** = unique label set, e.g. `{service_name="order-service"}`. Keep labels few and low-cardinality.
- **Structured metadata** (Loki 3): per-line key/values that are *not* indexed as labels, which is perfect
  for `trace_id`, `span_id`, `severity_text`. Filterable, but they don't create streams.
- **LogQL** = stream selector + pipeline: `{service_name="order-service"} |= "timeout" | severity_text="ERROR"`.
  Metric queries: `sum by (service_name) (count_over_time({...}[1m]))`.
- Here logs arrive through **OTLP** directly from the agent → collector → Loki's `/otlp` endpoint.
  The classic alternative is the app writing to stdout and an agent (Promtail/Alloy) tailing files.

## Tempo

A trace store that only indexes trace ids, so it's cheap. Search works by scanning blocks.

- **TraceQL** query language: `{ resource.service.name = "order-service" && duration > 1s && status = error }`.
- **metrics-generator** derives metrics *from* traces and remote-writes them to Prometheus:
  - span metrics (`traces_spanmetrics_calls_total`, `..._latency_bucket`)
  - service graph (`traces_service_graph_request_total{client, server}`), which powers the Service Graph view.

## Grafana

The UI on top of all backends. Key features used here:

- **Data source provisioning** and **dashboard provisioning** from files (everything as code).
- **Explore** for ad-hoc queries in PromQL / LogQL / TraceQL.
- **Correlations**: exemplars, derived fields, traces-to-logs, traces-to-metrics, service map.
- **Alerting** UI (also shows Prometheus-evaluated rules).

## SLI / SLO / error budget

- **SLI** (indicator): a ratio of good events, e.g. *requests < 500 ms / all requests*.
- **SLO** (objective): a target for the SLI over a window, e.g. *99% over 30 days*.
- **Error budget**: `1 - SLO` = how much failure you're allowed (1% ≈ 7.2 h/month).
- **Burn rate**: how fast you're spending the budget. Alert on fast burn (page) and slow burn (ticket),
  rather than on raw thresholds.

## Glossary

| Term | Meaning |
|------|---------|
| Cardinality | Number of unique time series (label combinations) / streams |
| Exemplar | A sample trace id attached to a metric data point |
| Instrumentation | Code that produces telemetry |
| Auto- vs manual instrumentation | Agent/library does it for you vs you call the API yourself |
| Sampling | Keeping only some traces (head: decided at start; tail: decided after the trace completes) |
| Scrape | Prometheus pulling metrics over HTTP |
| Remote write | Pushing samples into Prometheus (or Mimir) over HTTP |
| OTLP | OpenTelemetry's wire protocol |
| LGTM | Loki, Grafana, Tempo, Mimir: Grafana Labs' open-source stack (Prometheus plays Mimir's role here) |
