package io.github.aresprojects.local.runtime.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AwsHttpResponseTest {
    @Test
    void jsonCreatesUtf8ResponseWithContentType() {
        AwsHttpResponse response = AwsHttpResponse.json(200, "Olá");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("application/json"), response.headers().get("content-type"));
        assertArrayEquals("Olá".getBytes(java.nio.charset.StandardCharsets.UTF_8), response.body());

        AwsHttpResponse empty = AwsHttpResponse.of(100, new byte[0]);
        assertEquals(100, empty.statusCode());
        assertEquals(Map.of(), empty.headers());
        assertArrayEquals(new byte[0], empty.body());
        assertEquals(599, AwsHttpResponse.of(599, new byte[0]).statusCode());
    }

    @Test
    void responseDataIsImmutable() {
        byte[] body = {1, 2};
        List<String> values = new ArrayList<>(List.of("value"));
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("X-Test", values);

        AwsHttpResponse response = new AwsHttpResponse(201, headers, body);
        body[0] = 9;
        values.add("changed");

        assertArrayEquals(new byte[] {1, 2}, response.body());
        assertEquals(201, response.statusCode());
        assertEquals(List.of("value"), response.headers().get("x-test"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> response.headers().get("x-test").add("changed"));
        assertThrows(
                UnsupportedOperationException.class, () -> response.headers().put("new", List.of("value")));
    }

    @Test
    void invalidStatusAndHeaderValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AwsHttpResponse.of(99, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> AwsHttpResponse.of(600, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AwsHttpResponse(200, Map.of("x-test", List.of("bad\nvalue")), new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AwsHttpResponse(200, Map.of("x-test", List.of("bad\rvalue")), new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AwsHttpResponse(200, Map.of("x-test", List.of("\rvalue")), new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AwsHttpResponse(200, Map.of("x-test", List.of("\nvalue")), new byte[0]));
    }
}
