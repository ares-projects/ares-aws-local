package io.github.aresprojects.local.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.cli.testing.AresCli;
import io.github.aresprojects.local.cli.testing.AresCli.ProcessResult;
import io.github.aresprojects.local.cli.testing.AresCliExtension;
import io.github.aresprojects.local.cli.testing.Cli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "ARES_RUN_DYNAMODB_CLI_E2E", matches = "true")
@ExtendWith(AresCliExtension.class)
class AresDynamoDbCliE2eTest {
    @Cli
    private AresCli cli;

    @Test
    void awsCliSupportsDynamoDbTableAndItemLifecycle(@TempDir Path tempDirectory) throws Exception {
        Path createTable = tempDirectory.resolve("create-table.json");
        Files.writeString(createTable, """
                {
                  "TableName": "cli-orders",
                  "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                  "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                  "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1}
                }
                """);
        Path putItem = tempDirectory.resolve("put-item.json");
        Files.writeString(putItem, """
                {
                  "TableName": "cli-orders",
                  "Item": {"id": {"S": "order-1"}, "status": {"S": "created"}}
                }
                """);

        cli.start();

        ProcessResult listedBefore = cli.awsDynamoDb(List.of("list-tables"));
        assertSuccess(listedBefore);
        assertTrue(listedBefore.stdout().contains("TableNames"), listedBefore.stdout());

        ProcessResult created = cli.awsDynamoDb(List.of("create-table", "--cli-input-json", "file://" + createTable));
        assertSuccess(created);
        assertTrue(created.stdout().contains("cli-orders"), created.stdout());

        assertSuccess(cli.awsDynamoDb(List.of("put-item", "--cli-input-json", "file://" + putItem)));

        ProcessResult read = cli.awsDynamoDb(
                List.of("get-item", "--table-name", "cli-orders", "--key", "{\"id\":{\"S\":\"order-1\"}}"));
        assertSuccess(read);
        assertTrue(read.stdout().contains("created"), read.stdout());

        assertSuccess(cli.awsDynamoDb(
                List.of("delete-item", "--table-name", "cli-orders", "--key", "{\"id\":{\"S\":\"order-1\"}}")));
        assertSuccess(cli.awsDynamoDb(List.of("delete-table", "--table-name", "cli-orders")));

        ProcessResult listedAfter = cli.awsDynamoDb(List.of("list-tables"));
        assertSuccess(listedAfter);
        assertTrue(!listedAfter.stdout().contains("cli-orders"), listedAfter.stdout());
    }

    private static void assertSuccess(ProcessResult result) {
        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
    }
}
