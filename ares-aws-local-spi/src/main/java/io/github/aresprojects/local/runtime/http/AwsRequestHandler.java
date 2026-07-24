package io.github.aresprojects.local.runtime.http;

import java.util.concurrent.CompletionStage;

/** Keeps service handlers independent of Netty's channel lifecycle and buffer ownership. */
@FunctionalInterface
public interface AwsRequestHandler {

    /**
     * Completes a request asynchronously so service work does not block the event loop.
     *
     * @param request the immutable request context
     * @return a future containing the response
     */
    CompletionStage<AwsHttpResponse> handle(AwsRequestContext request);
}
