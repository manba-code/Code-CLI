package com.paicli.runtime.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runtime.auth.AuthenticationException;
import com.paicli.runtime.auth.AuthenticationRequest;
import com.paicli.runtime.auth.LocalApiKeyPrincipalAdapter;
import com.paicli.runtime.auth.Principal;
import com.paicli.runtime.auth.PrincipalAdapter;
import com.paicli.runtime.task.TaskRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RuntimeApiServer implements AutoCloseable {
    public static final String PRINCIPAL_ATTRIBUTE = RuntimeApiServer.class.getName() + ".principal";
    public static final String AUTH_MODE_ATTRIBUTE = RuntimeApiServer.class.getName() + ".authMode";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RuntimeThreadStore store;
    private final TaskRunner runner;
    private final PrincipalAdapter identities;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "paicli-runtime-api");
        thread.setDaemon(true);
        return thread;
    });

    public RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port, String apiKey) throws IOException {
        this(store, runner, port, apiKey, null);
    }

    public RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port,
                            PrincipalAdapter identities) throws IOException {
        this(store, runner, port, identities, null);
    }

    public RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port, String apiKey,
                            ChangeApiHandler changes) throws IOException {
        this(store, runner, port, new LocalApiKeyPrincipalAdapter(apiKey), changes);
    }

    public RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port,
                            PrincipalAdapter identities, ChangeApiHandler changes) throws IOException {
        this.store = store;
        this.runner = runner;
        this.identities = java.util.Objects.requireNonNull(identities, "identities");
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/v1/threads", this::handleThreads);
        if (changes != null) {
            this.server.createContext("/changes", new ChangeWebHandler());
            this.server.createContext("/v1/changes", exchange -> {
                if (!authenticate(exchange)) {
                    return;
                }
                changes.handle(exchange);
            });
        }
        this.server.setExecutor(executor);
    }

    public static String configuredApiKey() {
        String configured = System.getProperty("paicli.runtime.api.key");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_RUNTIME_API_KEY");
        }
        return configured;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleThreads(HttpExchange exchange) throws IOException {
        try {
            if (!authenticate(exchange)) return;
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/v1/threads".equals(path)) {
                String id = store.createThread();
                writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"object\":\"thread\"}");
                return;
            }
            if ("POST".equals(method) && path.matches("/v1/threads/[^/]+/turns")) {
                handleTurn(exchange, threadId(path));
                return;
            }
            if ("GET".equals(method) && path.matches("/v1/threads/[^/]+/events")) {
                handleEvents(exchange, threadId(path));
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"not_found\"}");
        } catch (Exception e) {
            writeJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleTurn(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        String input = body.path("input").asText("");
        if (input.isBlank()) {
            writeJson(exchange, 400, "{\"error\":\"input_required\"}");
            return;
        }
        String turnId = "turn_" + Long.toHexString(System.nanoTime());
        store.appendEvent(threadId, "turn.started",
                "{\"turn_id\":\"" + turnId + "\",\"input\":\"" + escape(input) + "\"}");
        executor.submit(() -> runTurn(threadId, turnId, input));
        writeJson(exchange, 202, "{\"id\":\"" + turnId + "\",\"object\":\"turn\",\"status\":\"running\"}");
    }

    private void runTurn(String threadId, String turnId, String input) {
        try {
            String result = runner.run(input);
            store.appendEvent(threadId, "message.delta",
                    "{\"turn_id\":\"" + turnId + "\",\"content\":\"" + escape(result) + "\"}");
            store.appendEvent(threadId, "turn.completed",
                    "{\"turn_id\":\"" + turnId + "\",\"status\":\"completed\"}");
        } catch (Exception e) {
            store.appendEvent(threadId, "turn.failed",
                    "{\"turn_id\":\"" + turnId + "\",\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleEvents(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        long after = parseAfter(exchange.getRequestURI().getQuery());
        List<RuntimeEvent> events = store.events(threadId, after);
        byte[] body = formatSse(events).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private boolean authenticate(HttpExchange exchange) throws IOException {
        try {
            Principal principal = identities.authenticate(new AuthenticationRequest(
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-PaiCLI-API-Key")));
            exchange.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            exchange.setAttribute(AUTH_MODE_ATTRIBUTE, identities.mode());
            return true;
        } catch (AuthenticationException error) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"PaiCLI Runtime API\"");
            writeJson(exchange, 401, "{\"error\":\"unauthorized\",\"message\":\"认证失败或登录态已过期\"}");
            return false;
        }
    }

    private static String threadId(String path) {
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : "";
    }

    private static long parseAfter(String query) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("after=")) {
                try {
                    return Long.parseLong(part.substring("after=".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String formatSse(List<RuntimeEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (RuntimeEvent event : events) {
            sb.append("id: ").append(event.id()).append('\n');
            sb.append("event: ").append(event.type()).append('\n');
            sb.append("data: ").append(event.data()).append("\n\n");
        }
        return sb.toString();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
