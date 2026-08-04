package io.github.aresprojects.local.runtime.service.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.aresprojects.local.dynamodb.DynamoDbJsonAdapter;
import io.github.aresprojects.local.runtime.LocalAwsServer;
import io.github.aresprojects.local.runtime.LocalAwsServerConfig;
import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

class DynamoDbJsonIntegrationTest {

    @Test
    void awsSdkV2SupportsTableAndItemLifecycle() {
        try (LocalAwsServer server = server()) {
            InetSocketAddress address = server.start();
            try (DynamoDbClient client = client(address)) {
                assertEquals(
                        0,
                        client.listTables(ListTablesRequest.builder().build())
                                .tableNames()
                                .size());

                client.createTable(CreateTableRequest.builder()
                        .tableName("orders")
                        .keySchema(KeySchemaElement.builder()
                                .attributeName("id")
                                .keyType(KeyType.HASH)
                                .build())
                        .attributeDefinitions(AttributeDefinition.builder()
                                .attributeName("id")
                                .attributeType(ScalarAttributeType.S)
                                .build())
                        .provisionedThroughput(ProvisionedThroughput.builder()
                                .readCapacityUnits(1L)
                                .writeCapacityUnits(1L)
                                .build())
                        .build());

                Map<String, AttributeValue> item = Map.of(
                        "id", AttributeValue.builder().s("order-1").build(),
                        "status", AttributeValue.builder().s("created").build());
                client.putItem(
                        PutItemRequest.builder().tableName("orders").item(item).build());

                var stored = client.getItem(GetItemRequest.builder()
                                .tableName("orders")
                                .key(Map.of(
                                        "id",
                                        AttributeValue.builder().s("order-1").build()))
                                .build())
                        .item();
                assertEquals("created", stored.get("status").s());

                client.deleteItem(DeleteItemRequest.builder()
                        .tableName("orders")
                        .key(Map.of("id", AttributeValue.builder().s("order-1").build()))
                        .build());
                assertFalse(client.getItem(GetItemRequest.builder()
                                .tableName("orders")
                                .key(Map.of(
                                        "id",
                                        AttributeValue.builder().s("order-1").build()))
                                .build())
                        .hasItem());

                client.deleteTable(
                        DeleteTableRequest.builder().tableName("orders").build());
                assertEquals(0, client.listTables().tableNames().size());
            }
        }
    }

    private static LocalAwsServer server() {
        return new LocalAwsServer(
                new LocalAwsServerConfig("127.0.0.1", 0, 10 * 1024 * 1024),
                AwsServiceRegistry.builder().register(new DynamoDbJsonAdapter()).build());
    }

    private static DynamoDbClient client(InetSocketAddress address) {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + address.getHostString() + ":" + address.getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("accesskey", "secretkey")))
                .build();
    }
}
