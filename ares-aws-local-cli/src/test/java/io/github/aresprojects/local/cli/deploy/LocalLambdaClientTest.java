package io.github.aresprojects.local.cli.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.aresprojects.local.cli.builder.DeploymentResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLambdaClientTest {

    @Test
    void callsTheLambdaRestPathsAndMapsRemoteConfiguration(@TempDir Path directory) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(
                        response(200, wrappedConfiguration("not-base64")),
                        response(200, wrappedConfiguration("not-base64")),
                        response(200, configuration("not-base64")),
                        response(200, configuration("not-base64")),
                        response(200, configuration("not-base64")),
                        response(200, configuration("not-base64")))
                .when(httpClient)
                .send(any(), any());
        Path artifact = directory.resolve("function.zip");
        Files.write(artifact, new byte[] {1, 2, 3});
        DeploymentResult result = result(artifact);
        LocalLambdaClient client = new LocalLambdaClient(
                URI.create("http://127.0.0.1:4566"), httpClient, new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals("hello", client.getFunction("hello").orElseThrow().functionName());
        assertEquals(
                Map.of("MODE", "test"),
                client.getFunction("hello").orElseThrow().environment());
        assertEquals("not-base64", client.createFunction(result).codeSha256());
        assertEquals("not-base64", client.updateFunctionCode(result).codeSha256());
        assertEquals("not-base64", client.updateFunctionConfiguration(result).codeSha256());
        client.deleteFunction("hello");
        assertEquals("http://127.0.0.1:4566", client.endpointDescription());
    }

    @Test
    void distinguishesNotFoundRemoteErrorsAndMalformedResponses() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(
                        response(
                                404,
                                "{\"__type\":\"com.amazonaws.lambda#ResourceNotFoundException\","
                                        + "\"message\":\"missing\"}"),
                        response(500, "{\"__type\":\"InternalFailure\",\"message\":\"failed\"}"),
                        response(200, "not-json"),
                        response(200, "{\"Configuration\":{}}"))
                .when(httpClient)
                .send(any(), any());
        LocalLambdaClient client = client(httpClient);

        assertFalse(client.getFunction("missing").isPresent());
        LambdaClientException remote = assertThrows(LambdaClientException.class, () -> client.getFunction("broken"));
        assertEquals("InternalFailure", remote.errorCode());
        LambdaClientException invalidJson =
                assertThrows(LambdaClientException.class, () -> client.getFunction("bad-json"));
        assertEquals("LocalResponseInvalid", invalidJson.errorCode());
        LambdaClientException incomplete =
                assertThrows(LambdaClientException.class, () -> client.getFunction("incomplete"));
        assertEquals("LocalResponseInvalid", incomplete.errorCode());

        HttpClient boundaryClient = mock(HttpClient.class);
        doReturn(response(400, "{\"__type\":\"#Failure\",\"message\":\"bad\"}"))
                .when(boundaryClient)
                .send(any(), any());
        LambdaClientException boundary = assertThrows(
                LambdaClientException.class, () -> client(boundaryClient).getFunction("boundary"));
        assertEquals("Failure", boundary.errorCode());
    }

    @Test
    void reportsTransportAndArtifactFailures(@TempDir Path directory) throws Exception {
        HttpClient unavailable = mock(HttpClient.class);
        doThrow(new IOException("offline")).when(unavailable).send(any(), any());
        LambdaClientException unreachable = assertThrows(
                LambdaClientException.class, () -> client(unavailable).getFunction("hello"));
        assertEquals("LocalEndpointUnavailable", unreachable.errorCode());

        HttpClient interrupted = mock(HttpClient.class);
        doThrow(new InterruptedException()).when(interrupted).send(any(), any());
        LambdaClientException stopped = assertThrows(
                LambdaClientException.class, () -> client(interrupted).getFunction("hello"));
        assertEquals("LocalEndpointInterrupted", stopped.errorCode());
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();

        DeploymentResult missingArtifact = result(directory.resolve("missing.zip"));
        LambdaClientException missing = assertThrows(
                LambdaClientException.class,
                () -> client(mock(HttpClient.class)).createFunction(missingArtifact));
        assertEquals("LocalArtifactUnavailable", missing.errorCode());
        assertThrows(IllegalArgumentException.class, () -> new LocalLambdaClient(URI.create("file:///tmp")));
        assertEquals(
                "http://127.0.0.1:4566",
                new LocalLambdaClient(URI.create("http://127.0.0.1:4566/")).endpointDescription());
    }

    private static LocalLambdaClient client(HttpClient httpClient) {
        return new LocalLambdaClient(
                URI.create("http://127.0.0.1:4566"), httpClient, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static DeploymentResult result(Path artifact) {
        return new DeploymentResult(
                1,
                "hello",
                "java21",
                "arm64",
                "Handler",
                Path.of("project"),
                artifact,
                "sha",
                3,
                List.of(),
                Map.of("MODE", "test"));
    }

    private static HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return response;
    }

    private static String wrappedConfiguration(String sha256) {
        return "{\"Configuration\":" + configuration(sha256) + "}";
    }

    private static String configuration(String sha256) {
        return "{\"FunctionName\":\"hello\",\"Runtime\":\"java21\","
                + "\"Architectures\":[\"arm64\"],\"Handler\":\"Handler\",\"Environment\":"
                + "{\"Variables\":{\"MODE\":\"test\"}},\"CodeSha256\":\""
                + sha256
                + "\",\"RevisionId\":\"revision\"}";
    }
}
