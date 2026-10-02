package com.demo.inventory.chaos;

import org.springframework.stereotype.Component;

/**
 * Runtime-tunable fault injection for every /api/** request of this service.
 * Changed through /admin/chaos (see scripts/chaos.sh downstream-*).
 */
@Component
public class ChaosSettings {

    public record Values(long latencyMs, long jitterMs, double errorRate) {
        public static final Values NONE = new Values(0, 0, 0.0);
    }

    private volatile Values values = Values.NONE;

    public Values get() {
        return values;
    }

    public void set(Values values) {
        this.values = values;
    }
}
