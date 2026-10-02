package com.demo.inventory.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/admin/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final ChaosSettings settings;

    public ChaosController(ChaosSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public ChaosSettings.Values get() {
        return settings.get();
    }

    @PostMapping
    public ChaosSettings.Values set(@RequestBody ChaosSettings.Values values) {
        settings.set(values);
        log.warn("Chaos settings changed: latencyMs={} jitterMs={} errorRate={}",
                values.latencyMs(), values.jitterMs(), values.errorRate());
        return values;
    }

    @DeleteMapping
    public ChaosSettings.Values reset() {
        settings.set(ChaosSettings.Values.NONE);
        log.info("Chaos settings reset");
        return ChaosSettings.Values.NONE;
    }

    @RestControllerAdvice
    static class InjectedFailureHandler {

        @ExceptionHandler(ChaosInterceptor.InjectedFailure.class)
        ProblemDetail handle(ChaosInterceptor.InjectedFailure e) {
            log.error("Request failed: {}", e.getMessage(), e);
            return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
