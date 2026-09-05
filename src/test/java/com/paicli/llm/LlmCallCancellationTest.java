package com.paicli.llm;

import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmCallCancellationTest {
    @Test void cancelTerminatesOnlyScopedHttpCallAndDoesNotPoisonNextCall() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
            server.start(java.net.InetAddress.getLoopbackAddress(), 0);
            var client = new DeepSeekClient("test-key", "test-model", server.url("/").toString());
            var executor = Executors.newSingleThreadExecutor();
            var cancellation = new LlmCallCancellation();
            try {
                var first = executor.submit(() -> {
                    cancellation.enter();
                    try { return client.chat(List.of(LlmClient.Message.user("test")), List.of()); }
                    finally { cancellation.close(); }
                });
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                cancellation.cancel();
                var error = assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
                assertInstanceOf(java.io.IOException.class, error.getCause());
                var next = executor.submit(() -> client.chat(List.of(LlmClient.Message.user("next")), List.of()));
                assertNotNull(next.get(2, TimeUnit.SECONDS));
            } finally { cancellation.cancel(); executor.shutdownNow(); }
        }
    }

    @Test void statusClassificationDoesNotParseOrExposeProviderBodies() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(java.net.InetAddress.getLoopbackAddress(), 0);
            var client = new DeepSeekClient("test-key", "test-model", server.url("/").toString());
            for (int status : new int[]{400, 401, 403, 404, 408, 429, 500, 503}) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("provider diagnostic"));
                try (var scope = new LlmCallCancellation()) {
                scope.enter();
                var error = assertThrows(LlmHttpException.class, () -> client.chat(List.of(), List.of()));
                assertEquals(status, error.status());
                assertEquals(status == 408 || status == 429 || status >= 500, error.retryable());
                }
            }
        }
    }
}
