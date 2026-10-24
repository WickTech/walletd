package com.wicktech.walletd;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Minimal JSON-over-HTTP client for the integration tests. */
final class TestApi {

    record Res(int status, Map<?, ?> body, HttpHeaders headers) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient http;

    TestApi(int port) {
        this.http = RestClient.create("http://localhost:" + port);
    }

    Res call(String method, String path, Object body) {
        return call(method, path, body, Map.of());
    }

    Res call(String method, String path, Object body, Map<String, String> headers) {
        var spec = http.method(HttpMethod.valueOf(method)).uri(path);
        for (var h : headers.entrySet()) {
            spec = spec.header(h.getKey(), h.getValue());
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON)
                    .body(body instanceof String s ? s : MAPPER.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            var text = new String(res.getBody().readAllBytes());
            Map<?, ?> parsed = text.startsWith("{") ? MAPPER.readValue(text, Map.class) : Map.of();
            return new Res(res.getStatusCode().value(), parsed, res.getHeaders());
        });
    }
}
