package io.github.aresprojects.local.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class DynamoDbLocalServerTest {

    @Test
    void startsAnInMemoryBackendAndClosesIdempotently() {
        DynamoDbLocalServer server = new DynamoDbLocalServer();
        assertThrows(IllegalStateException.class, server::endpoint);

        URI endpoint = server.start();
        assertEquals(endpoint, server.endpoint());
        assertThrows(IllegalStateException.class, server::start);
        try (DynamoDbClient client = client(endpoint)) {
            assertEquals(0, client.listTables().tableNames().size());
        } finally {
            server.close();
            server.close();
        }

        assertThrows(IllegalStateException.class, server::endpoint);
        assertThrows(IllegalStateException.class, server::start);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();
        assertThrows(
                IOException.class,
                () -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()));
    }

    @Test
    void closingBeforeStartIsSafe() {
        DynamoDbLocalServer server = new DynamoDbLocalServer();

        server.close();
        server.close();

        assertThrows(IllegalStateException.class, server::endpoint);
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    void reportsPortConflictDuringStartup() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            DynamoDbLocalServer server = new DynamoDbLocalServer(socket.getLocalPort());

            IllegalStateException failure = assertThrows(IllegalStateException.class, server::start);
            assertEquals(
                    "Could not start the embedded DynamoDB Local backend on port " + socket.getLocalPort(),
                    failure.getMessage());
            server.close();
        }
    }

    @Test
    void rejectsInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> new DynamoDbLocalServer(0));
        assertThrows(IllegalArgumentException.class, () -> new DynamoDbLocalServer(65_536));

        try (DynamoDbLocalServer minimum = new DynamoDbLocalServer(1);
                DynamoDbLocalServer maximum = new DynamoDbLocalServer(65_535)) {
            assertThrows(IllegalStateException.class, minimum::endpoint);
            assertThrows(IllegalStateException.class, maximum::endpoint);
        }
    }

    private static DynamoDbClient client(URI endpoint) {
        return DynamoDbClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("accesskey", "secretkey")))
                .build();
    }
}
