package io.github.aresprojects.local.runtime.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AwsRequestContextTest {
    @Test
    void normalizesAndDefensivelyCopiesRequestData() {
        byte[] body = {1, 2};
        List<String> headerValues = new ArrayList<>(List.of("one"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Request-ID", headerValues);
        headers.put("x-request-id", List.of("two"));

        AwsRequestContext context = new AwsRequestContext(
                "request-1",
                Instant.EPOCH,
                "POST",
                "HTTP/1.1",
                "/queue?Action=SendMessage",
                headers,
                body,
                InetSocketAddress.createUnresolved("remote", 1000),
                InetSocketAddress.createUnresolved("local", 4566));

        body[0] = 9;
        headerValues.add("three");

        assertArrayEquals(new byte[] {1, 2}, context.body());
        assertEquals("request-1", context.requestId());
        assertEquals(Instant.EPOCH, context.receivedAt());
        assertEquals("POST", context.method());
        assertEquals("HTTP/1.1", context.protocolVersion());
        assertEquals("/queue?Action=SendMessage", context.rawTarget());
        assertEquals(List.of("one", "two"), context.headers().get("x-request-id"));
        assertEquals("one", context.firstHeader("X-REQUEST-ID").orElseThrow());
        assertEquals(java.util.Optional.empty(), context.firstHeader("missing"));
        assertEquals(InetSocketAddress.createUnresolved("remote", 1000), context.remoteAddress());
        assertEquals(InetSocketAddress.createUnresolved("local", 4566), context.localAddress());
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.headers().get("x-request-id").add("value"));
        assertThrows(
                UnsupportedOperationException.class, () -> context.headers().put("new", List.of("value")));
        assertThrows(
                NullPointerException.class,
                () -> new AwsRequestContext(
                        "request-2",
                        Instant.EPOCH,
                        "GET",
                        "HTTP/1.1",
                        "/",
                        Map.of("x-test", List.of("valid", null)),
                        new byte[0],
                        InetSocketAddress.createUnresolved("remote", 1),
                        InetSocketAddress.createUnresolved("local", 2)));
    }

    @Test
    void rejectsMissingRequiredValues() {
        assertThrows(
                NullPointerException.class,
                () -> new AwsRequestContext(
                        null,
                        Instant.EPOCH,
                        "GET",
                        "HTTP/1.1",
                        "/",
                        Map.of(),
                        new byte[0],
                        InetSocketAddress.createUnresolved("remote", 1),
                        InetSocketAddress.createUnresolved("local", 2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AwsRequestContext(
                        "id",
                        Instant.EPOCH,
                        " ",
                        "HTTP/1.1",
                        "/",
                        Map.of(),
                        new byte[0],
                        InetSocketAddress.createUnresolved("remote", 1),
                        InetSocketAddress.createUnresolved("local", 2)));
    }
}
