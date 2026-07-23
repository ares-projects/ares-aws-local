package io.github.aresprojects.local.runtime.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TriggerValueTest {
    @Test
    void validatesResourceReferencesAndMappings() {
        AwsResourceReference source = new AwsResourceReference("sqs", "queue-arn");
        AwsResourceReference target = new AwsResourceReference("lambda", "function");
        TriggerSettings settings = new TestSettings();
        TriggerMapping mapping = new TriggerMapping("mapping", "driver", source, target, false, settings);

        assertEquals("mapping", mapping.id());
        assertFalse(mapping.enabled());
        assertThrows(NullPointerException.class, () -> new AwsResourceReference(null, "id"));
        assertThrows(IllegalArgumentException.class, () -> new AwsResourceReference(" ", "id"));
        assertThrows(IllegalArgumentException.class, () -> new AwsResourceReference("sqs", ""));
        assertThrows(
                IllegalArgumentException.class, () -> new TriggerMapping("", "driver", source, target, true, settings));
        assertThrows(
                IllegalArgumentException.class, () -> new TriggerMapping("id", " ", source, target, true, settings));
        assertThrows(
                NullPointerException.class, () -> new TriggerMapping("id", "driver", null, target, true, settings));
        assertThrows(
                NullPointerException.class, () -> new TriggerMapping("id", "driver", source, null, true, settings));
        assertThrows(NullPointerException.class, () -> new TriggerMapping("id", "driver", source, target, true, null));
    }

    @Test
    void snapshotsIntegrationAndLambdaPayloads() {
        byte[] eventPayload = {1, 2};
        IntegrationEvent event = new IntegrationEvent(
                "event", new AwsResourceReference("sns", "topic"), "published", Instant.EPOCH, eventPayload);
        eventPayload[0] = 9;
        byte[] returnedEventPayload = event.payload();
        returnedEventPayload[1] = 9;

        byte[] lambdaPayload = {3, 4};
        LambdaInvocationResult result = LambdaInvocationResult.success(lambdaPayload);
        lambdaPayload[0] = 9;
        byte[] returnedLambdaPayload = result.payload();
        returnedLambdaPayload[1] = 9;

        assertEquals("event", event.id());
        assertEquals("published", event.eventType());
        assertArrayEquals(new byte[] {1, 2}, event.payload());
        assertArrayEquals(new byte[] {3, 4}, result.payload());
        assertEquals(Optional.empty(), result.functionError());
        assertEquals(
                "Unhandled",
                LambdaInvocationResult.functionError(new byte[0], "Unhandled")
                        .functionError()
                        .orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> LambdaInvocationResult.functionError(new byte[0], " "));
        assertThrows(NullPointerException.class, () -> LambdaInvocationResult.success(null));
    }

    @Test
    void validatesIntegrationEventFields() {
        AwsResourceReference source = new AwsResourceReference("sns", "topic");

        assertThrows(
                IllegalArgumentException.class,
                () -> new IntegrationEvent("", source, "published", Instant.EPOCH, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntegrationEvent("id", source, " ", Instant.EPOCH, new byte[0]));
        assertThrows(
                NullPointerException.class,
                () -> new IntegrationEvent("id", null, "published", Instant.EPOCH, new byte[0]));
        assertThrows(
                NullPointerException.class, () -> new IntegrationEvent("id", source, "published", null, new byte[0]));
        assertThrows(
                NullPointerException.class, () -> new IntegrationEvent("id", source, "published", Instant.EPOCH, null));
    }

    private record TestSettings() implements TriggerSettings {}
}
