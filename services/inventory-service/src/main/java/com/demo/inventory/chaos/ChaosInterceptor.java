package com.demo.inventory.chaos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Injects latency / errors before the controller runs. Implemented as a HandlerInterceptor
 * (not a servlet filter) so Spring has already resolved the route: the failing requests still
 * get a proper `http.route` attribute on their spans and metrics.
 */
@Configuration
public class ChaosInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static class InjectedFailure extends RuntimeException {
        InjectedFailure(String message) {
            super(message);
        }
    }

    private final ChaosSettings settings;

    public ChaosInterceptor(ChaosSettings settings) {
        this.settings = settings;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ChaosSettings.Values chaos = settings.get();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (chaos.latencyMs() > 0 || chaos.jitterMs() > 0) {
            long delay = chaos.latencyMs() + (chaos.jitterMs() > 0 ? rnd.nextLong(chaos.jitterMs()) : 0);
            Thread.sleep(delay);
        }
        if (chaos.errorRate() > 0 && rnd.nextDouble() < chaos.errorRate()) {
            throw new InjectedFailure("Injected failure (errorRate=" + chaos.errorRate() + ") on " + request.getRequestURI());
        }
        return true;
    }
}
