package com.demo.order.chaos;

import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Endpoints that misbehave on purpose so you can see what each failure mode looks like
 * in metrics, logs and traces. Driven by scripts/chaos.sh.
 */
@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);
    private static final int MB = 1024 * 1024;

    private static volatile double sink;

    private final JdbcTemplate jdbc;
    private final List<byte[]> leakedMemory = new CopyOnWriteArrayList<>();

    public ChaosController(JdbcTemplate jdbc, OpenTelemetry otel) {
        this.jdbc = jdbc;
        // Observable gauge: read by the SDK on every metric export -> chaos_memory_held_bytes
        otel.getMeter("com.demo.order")
                .gaugeBuilder("chaos.memory.held")
                .setDescription("Memory intentionally leaked by /api/chaos/memory")
                .setUnit("By")
                .buildWithCallback(m -> m.record(heldBytes()));
    }

    /** Request that simply takes a long time. */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "2000") long ms) throws InterruptedException {
        log.warn("Simulating slow request: sleeping {} ms", ms);
        Thread.sleep(ms);
        return Map.of("sleptMs", ms);
    }

    /** Request that returns an error status without throwing. */
    @GetMapping("/error")
    public ResponseEntity<Map<String, Object>> error(@RequestParam(defaultValue = "500") int status) {
        log.error("Simulated failure, returning HTTP {}", status);
        return ResponseEntity.status(status).body(Map.of("error", "simulated", "status", status));
    }

    /** Unhandled exception -> 500, stack trace in the logs, exception event on the span. */
    @GetMapping("/exception")
    public void exception() {
        throw new IllegalStateException("Simulated unhandled exception: payment provider returned malformed response");
    }

    /** Realistic "noisy" endpoint: long-tail latency and ~10% errors. Great for histograms/percentiles. */
    @GetMapping("/random")
    public ResponseEntity<Map<String, Object>> random() throws InterruptedException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // mostly fast, sometimes very slow (long tail)
        long latency = rnd.nextDouble() < 0.9 ? rnd.nextLong(10, 200) : rnd.nextLong(500, 3000);
        Thread.sleep(latency);
        if (rnd.nextDouble() < 0.1) {
            log.error("Random failure after {} ms", latency);
            return ResponseEntity.internalServerError().body(Map.of("error", "random failure", "latencyMs", latency));
        }
        return ResponseEntity.ok(Map.of("latencyMs", latency));
    }

    /** Burn CPU on all cores for N seconds. */
    @GetMapping("/cpu")
    public Map<String, Object> cpu(@RequestParam(defaultValue = "10") int seconds) throws InterruptedException {
        int threads = Runtime.getRuntime().availableProcessors();
        log.warn("Burning CPU on {} threads for {} s", threads, seconds);
        long until = System.currentTimeMillis() + seconds * 1000L;
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = Thread.ofPlatform().name("cpu-burner-" + i).start(() -> {
                double x = 0;
                while (System.currentTimeMillis() < until) {
                    x += Math.sqrt(ThreadLocalRandom.current().nextDouble());
                }
                sink = x; // keep the JIT from optimising the loop away
            });
            workers.add(t);
        }
        for (Thread t : workers) {
            t.join();
        }
        return Map.of("threads", threads, "seconds", seconds);
    }

    /** Leak memory (kept until DELETE /api/chaos/memory). Call repeatedly to approach OutOfMemoryError. */
    @GetMapping("/memory")
    public Map<String, Object> leak(@RequestParam(defaultValue = "50") int mb) {
        leakedMemory.add(new byte[mb * MB]);
        log.warn("Leaked {} MB, now holding {} MB", mb, heldBytes() / MB);
        return Map.of("heldMb", heldBytes() / MB);
    }

    @DeleteMapping("/memory")
    public Map<String, Object> releaseMemory() {
        long released = heldBytes() / MB;
        leakedMemory.clear();
        System.gc();
        log.info("Released {} MB of leaked memory", released);
        return Map.of("releasedMb", released);
    }

    /** Slow SQL query (pg_sleep). Run many in parallel to exhaust the 5-connection Hikari pool. */
    @GetMapping("/slow-query")
    public Map<String, Object> slowQuery(@RequestParam(defaultValue = "2") double seconds) {
        log.warn("Running slow query: pg_sleep({})", seconds);
        jdbc.queryForList("SELECT pg_sleep(?)", seconds);
        return Map.of("querySeconds", seconds);
    }

    /** Emit a burst of log lines at mixed levels (useful for LogQL practice). */
    @GetMapping("/log-burst")
    public Map<String, Object> logBurst(@RequestParam(defaultValue = "200") int count) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String[] users = {"alice", "bob", "carol", "dave"};
        for (int i = 0; i < count; i++) {
            String user = users[rnd.nextInt(users.length)];
            double p = rnd.nextDouble();
            if (p < 0.6) {
                log.info("Cache lookup user={} hit={}", user, rnd.nextBoolean());
            } else if (p < 0.85) {
                log.warn("Slow cache response user={} latencyMs={}", user, rnd.nextInt(200, 900));
            } else if (p < 0.97) {
                log.error("Failed to refresh recommendations user={}", user);
            } else {
                log.error("Unexpected failure user={}", user, new RuntimeException("Simulated failure #" + i));
            }
        }
        return Map.of("logged", count);
    }

    private long heldBytes() {
        return leakedMemory.stream().mapToLong(b -> b.length).sum();
    }
}
