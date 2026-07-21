package io.github.aresprojects.local.runtime.service;

import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import java.util.concurrent.CompletionStage;

/**
 * Defines the protocol-aware boundary for one local AWS service implementation.
 *
 * <p>An adapter claims requests in the wire format it understands, then translates the request and response at
 * this boundary. For example, a future SQS Query adapter could look like this:
 *
 * <pre>{@code
 * final class SqsQueryAdapter implements AwsServiceAdapter {
 *     public String serviceName() {
 *         return "sqs";
 *     }
 *
 *     public boolean supports(AwsRequestContext request) {
 *         return request.rawTarget().contains("Action=");
 *     }
 *
 *     public CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
 *         return sqsService.handleQuery(request);
 *     }
 * }
 * }</pre>
 *
 * <p>The registry uses {@link #supports(AwsRequestContext)} for routing, so the adapter can evolve with the AWS
 * protocol without making the HTTP transport aware of service-specific details.
 */
public interface AwsServiceAdapter {

    /**
     * Returns the stable service identifier used in diagnostics and registration errors.
     *
     * @return a non-blank service identifier
     */
    String serviceName();

    /**
     * Determines whether this adapter owns the request according to its wire protocol.
     *
     * @param request the immutable request context
     * @return whether this adapter can process the request
     */
    boolean supports(AwsRequestContext request);

    /**
     * Processes a request without coupling the service implementation to the transport.
     *
     * @param request the immutable request context
     * @return a future containing the response
     */
    CompletionStage<AwsHttpResponse> handle(AwsRequestContext request);
}
