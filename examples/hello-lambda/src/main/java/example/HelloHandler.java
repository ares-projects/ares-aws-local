package example;

import java.util.Map;

/** Provides the small deterministic handler package used by CLI acceptance tests. */
public final class HelloHandler {

    /** Returns the greeting payload used by the later local invocation milestone. */
    public Map<String, String> handleRequest(Map<String, String> event, Object context) {
        String name = event == null ? "Ares" : event.getOrDefault("name", "Ares");
        return Map.of("message", "Hello, " + name);
    }
}
