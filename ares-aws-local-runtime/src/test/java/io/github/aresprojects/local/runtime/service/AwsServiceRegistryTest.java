package io.github.aresprojects.local.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class AwsServiceRegistryTest {
    @Test
    void registerRejectsNullAdapter() {
        assertThrows(
                NullPointerException.class, () -> AwsServiceRegistry.builder().register(null));
    }

    @Test
    void registerRejectsNullServiceName() {
        AwsServiceAdapter adapter = mock();
        when(adapter.serviceName()).thenReturn(null);

        assertThrows(
                NullPointerException.class, () -> AwsServiceRegistry.builder().register(adapter));
    }

    @Test
    void registerRejectsBlankServiceName() {
        AwsServiceAdapter adapter = mock();
        when(adapter.serviceName()).thenReturn(" ");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AwsServiceRegistry.builder().register(adapter));

        assertEquals(
                "adapter.serviceName() must not be blank; provide a stable service identifier", exception.getMessage());
    }

    @Test
    void buildCopiesBuilderRegistrations() {
        AwsRequestContext request = mock();
        AwsServiceAdapter first = adapter("first", false, AwsHttpResponse.of(200, new byte[0]));
        AwsServiceAdapter addedAfterBuild = adapter("second", true, AwsHttpResponse.of(201, new byte[0]));
        AwsServiceRegistry.Builder builder = AwsServiceRegistry.builder().register(first);
        AwsServiceRegistry registry = builder.build();
        builder.register(addedAfterBuild);

        AwsHttpResponse response =
                registry.handle(request).toCompletableFuture().join();

        assertEquals(404, response.statusCode());
        verify(first).supports(request);
        verify(addedAfterBuild, never()).supports(any());
    }

    @Test
    void firstMatchingAdapterWins() {
        AwsRequestContext request = mock();
        AwsServiceAdapter first = adapter("first", true, AwsHttpResponse.of(201, new byte[0]));
        AwsServiceAdapter second = adapter("second", true, AwsHttpResponse.of(202, new byte[0]));
        AwsServiceRegistry registry =
                AwsServiceRegistry.builder().register(first).register(second).build();

        AwsHttpResponse response =
                registry.handle(request).toCompletableFuture().join();

        assertEquals(201, response.statusCode());
        verify(first).supports(request);
        verify(first).handle(request);
        verify(second, never()).supports(any());
        verify(second, never()).handle(any());
    }

    @Test
    void unmatchedRequestReturnsNotFoundResponse() {
        AwsRequestContext request = mock();

        AwsHttpResponse response = AwsServiceRegistry.builder()
                .build()
                .handle(request)
                .toCompletableFuture()
                .join();

        assertEquals(404, response.statusCode());
        assertEquals("application/json", response.headers().get("content-type").getFirst());
        assertEquals(
                "{\"error\":\"no AWS service adapter matched request\"}",
                new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void preservesAsynchronousAdapterResponse() {
        AwsRequestContext request = mock();
        AwsServiceAdapter adapter = mock();
        CompletableFuture<AwsHttpResponse> responseFuture = new CompletableFuture<>();
        when(adapter.serviceName()).thenReturn("async");
        when(adapter.supports(request)).thenReturn(true);
        when(adapter.handle(request)).thenReturn(responseFuture);
        AwsServiceRegistry registry =
                AwsServiceRegistry.builder().register(adapter).build();

        CompletionStage<AwsHttpResponse> responseStage = registry.handle(request);

        assertFalse(responseStage.toCompletableFuture().isDone());
        responseFuture.complete(AwsHttpResponse.of(204, new byte[0]));
        assertTrue(responseStage.toCompletableFuture().isDone());
        assertEquals(204, responseStage.toCompletableFuture().join().statusCode());
    }

    private static AwsServiceAdapter adapter(String name, boolean supports, AwsHttpResponse response) {
        AwsServiceAdapter adapter = mock();
        when(adapter.serviceName()).thenReturn(name);
        when(adapter.supports(any())).thenReturn(supports);
        when(adapter.handle(any())).thenReturn(CompletableFuture.completedFuture(response));
        return adapter;
    }
}
