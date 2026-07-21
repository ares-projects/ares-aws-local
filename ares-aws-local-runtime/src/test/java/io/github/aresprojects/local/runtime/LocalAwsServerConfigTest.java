package io.github.aresprojects.local.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalAwsServerConfigTest {
    @Test
    void defaultsUseLoopbackAndLocalstackCompatiblePort() {
        LocalAwsServerConfig config = LocalAwsServerConfig.defaults();

        assertEquals("127.0.0.1", config.host());
        assertEquals(4566, config.port());
        assertEquals(16 * 1024 * 1024, config.maxRequestBytes());
    }

    @Test
    void environmentValuesOverrideDefaults() {
        Map<String, String> environment = Map.of(
                "ARES_AWS_LOCAL_HOST", "0.0.0.0",
                "ARES_AWS_LOCAL_PORT", "9000",
                "ARES_AWS_LOCAL_MAX_REQUEST_BYTES", "2048");

        assertEquals(
                new LocalAwsServerConfig("0.0.0.0", 9000, 2048), LocalAwsServerConfig.fromEnvironment(environment));
    }

    @Test
    void blankEnvironmentValuesUseDefaults() {
        assertEquals(
                LocalAwsServerConfig.defaults(),
                LocalAwsServerConfig.fromEnvironment(Map.of(
                        "ARES_AWS_LOCAL_HOST", " ",
                        "ARES_AWS_LOCAL_PORT", "",
                        "ARES_AWS_LOCAL_MAX_REQUEST_BYTES", "\t")));
    }

    @Test
    void missingEnvironmentValuesUseDefaults() {
        assertEquals(LocalAwsServerConfig.defaults(), LocalAwsServerConfig.fromEnvironment(new HashMap<>()));
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalAwsServerConfig.fromEnvironment(Map.of("ARES_AWS_LOCAL_PORT", "not-a-port")));
        assertThrows(IllegalArgumentException.class, () -> new LocalAwsServerConfig("127.0.0.1", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new LocalAwsServerConfig("127.0.0.1", 80_000, 10));
        assertThrows(IllegalArgumentException.class, () -> new LocalAwsServerConfig("127.0.0.1", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LocalAwsServerConfig(" ", 0, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalAwsServerConfig.fromEnvironment(Map.of("ARES_AWS_LOCAL_MAX_REQUEST_BYTES", "not-a-size")));
        assertThrows(NullPointerException.class, () -> LocalAwsServerConfig.fromEnvironment(null));
    }
}
