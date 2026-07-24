package io.github.aresprojects.local.runtime.trigger.lambda;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LambdaInvocationResultTest {

    @Test
    void snapshotsPayloadAndReportsSuccess() {
        byte[] payload = {1, 2};
        LambdaInvocationResult result = LambdaInvocationResult.success(payload);

        payload[0] = 9;

        assertArrayEquals(new byte[] {1, 2}, result.payload());
        assertEquals(Optional.empty(), result.functionError());
    }

    @Test
    void rejectsBlankFunctionError() {
        assertThrows(IllegalArgumentException.class, () -> LambdaInvocationResult.functionError(new byte[0], " "));
    }
}
