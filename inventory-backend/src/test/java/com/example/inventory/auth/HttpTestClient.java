package com.example.inventory.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class HttpTestClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;

    public static final String BASE_PATH = "/api/v1";

    public HttpTestClient(int port) {
        this.baseUrl = "http://localhost:" + port + BASE_PATH;
    }

    public record Response(int status, String body, HttpHeadersView headers) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String at(String pointer) {
            return json().at(pointer).asString();
        }
    }

    public record HttpHeadersView(HttpResponse<String> raw) {
        public String first(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    public Response post(String path, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response postWithToken(String path, String jsonBody, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request);
    }

    public Response postWithHeaders(String path, String jsonBody, String bearerToken,
                                    java.util.Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        headers.forEach(request::header);
        return send(request);
    }

    public Response get(String path, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request);
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    new HttpHeadersView(response));
        } catch (Exception e) {
            throw new IllegalStateException("request failed", e);
        }
    }

    public static String json(Object... keyValuePairs) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(keyValuePairs[i]).append("\":");
            Object value = keyValuePairs[i + 1];
            if (value == null) {
                out.append("null");
            } else {
                out.append('"').append(value).append('"');
            }
        }
        return out.append('}').toString();
    }
}
