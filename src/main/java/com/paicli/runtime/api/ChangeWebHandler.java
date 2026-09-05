package com.paicli.runtime.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/** Public, credential-free shell. All task data and decisions use authenticated same-origin APIs. */
final class ChangeWebHandler implements HttpHandler {
    private static final Map<String, String> FILES = Map.of(
            "/changes", "index.html", "/changes/", "index.html",
            "/changes/app.js", "app.js", "/changes/style.css", "style.css");

    @Override public void handle(HttpExchange exchange) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        String file = FILES.get(exchange.getRequestURI().getPath());
        if (!"GET".equals(exchange.getRequestMethod()) || file == null || exchange.getRequestURI().getRawQuery() != null) {
            exchange.sendResponseHeaders(404, -1); exchange.close(); return;
        }
        try (var input = getClass().getResourceAsStream("/paichange-web/" + file)) {
            if (input == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            byte[] content = input.readAllBytes();
            headers.set("Content-Type", file.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, content.length);
            try (var out = exchange.getResponseBody()) { out.write(content); }
        }
    }
}
