package com.demo.order.inventory;

import com.demo.order.domain.OrderException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;

/**
 * Calls inventory-service over HTTP. Nothing tracing-specific here: the OTel agent
 * instruments the HTTP client, creates a CLIENT span and injects the W3C `traceparent`
 * header, which is how the trace continues inside inventory-service.
 */
@Component
public class InventoryClient {

    public record Reservation(String sku, int reserved, int remaining, BigDecimal unitPrice) {
    }

    private final RestClient restClient;

    public InventoryClient(RestClient.Builder builder, @Value("${inventory.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(5_000);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public Reservation reserve(String sku, int quantity) {
        try {
            return restClient.post()
                    .uri("/api/inventory/{sku}/reserve?quantity={quantity}", sku, quantity)
                    .retrieve()
                    .body(Reservation.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new OrderException.UnknownProduct(sku);
        } catch (HttpClientErrorException.Conflict e) {
            throw new OrderException.OutOfStock(sku, quantity);
        } catch (RestClientException e) {
            // 5xx, timeouts, connection refused (service down) ...
            throw new OrderException.InventoryUnavailable(e);
        }
    }
}
