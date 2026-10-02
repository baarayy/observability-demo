package com.demo.inventory.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TelemetryConfig {

    /**
     * When the OTel Java agent is attached it registers itself as the global instance,
     * so custom spans/metrics created through this bean flow through the same pipeline
     * as the auto-instrumentation. Without the agent this is a harmless no-op.
     */
    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
