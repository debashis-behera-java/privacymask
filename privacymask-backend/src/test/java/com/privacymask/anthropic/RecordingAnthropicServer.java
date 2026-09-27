package com.privacymask.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Deterministic stand-in for the Anthropic HTTP API, built on the JDK's
 * embedded HTTP server - no new test dependencies, no network, no credentials.
 *
 * <p>Records every exchange (method, path, {@code x-api-key},
 * {@code anthropic-version}, parsed JSON body) for boundary assertions, counts
 * requests (exactly-once and no-retry proofs), and serves scripted or echo
 * responses with optional latency. Captured secrets stay inside the test that
 * owns this instance: never logged, only asserted.</p>
 */
final class RecordingAnthropicServer implements AutoCloseable {

    /** One captured inbound exchange. Test-only inspection, never logged. */
    record Exchange(String method, String path, String apiKey, String apiVersion, JsonNode body) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<Exchange> exchanges = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile String responseBody = envelope("default reply");
    private volatile long delayMillis;
    private volatile boolean echoInput;

    RecordingAnthropicServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode json;
                try {
                    json = MAPPER.readTree(body.isEmpty() ? "{}" : body);
                } catch (IOException e) {
                    json = MAPPER.createObjectNode();
                }
                exchanges.add(new Exchange(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("x-api-key"),
                        exchange.getRequestHeaders().getFirst("anthropic-version"),
                        json));
                if (delayMillis > 0) {
                    // Test scaffolding only: simulates upstream latency on the
                    // fake-server thread. Production code never sleeps.
                    Thread.sleep(delayMillis);
                }
                String reply = echoInput
                        ? envelope(messageText(json) + " done.")
                        : responseBody;
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "fake-anthropic");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    static String envelope(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + escaped + "\"}]}";
    }

    private static String messageText(JsonNode body) {
        JsonNode messages = body.path("messages");
        if (messages.isArray() && !messages.isEmpty()) {
            return messages.get(0).path("content").asText("");
        }
        return "";
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requestCount() {
        return exchanges.size();
    }

    List<Exchange> exchanges() {
        synchronized (exchanges) {
            return List.copyOf(exchanges);
        }
    }

    void setResponse(int status, String body) {
        this.status = status;
        this.responseBody = body;
        this.echoInput = false;
    }

    void setEchoInput(boolean echoInput) {
        this.echoInput = echoInput;
    }

    void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    void reset() {
        exchanges.clear();
        status = 200;
        responseBody = envelope("default reply");
        delayMillis = 0;
        echoInput = false;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}