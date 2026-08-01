package example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;

/** Provides the small deterministic handler package used by CLI acceptance tests. */
public final class HelloHandler implements RequestHandler<Map<String, String>, Map<String, String>> {

    /** Returns the greeting payload used by the later local invocation milestone. */
    @Override
    public Map<String, String> handleRequest(Map<String, String> event, Context context) {
        String name = event == null ? "Ares" : event.getOrDefault("name", "Ares");
        return Map.of("message", "Hello, " + name);
    }
}
