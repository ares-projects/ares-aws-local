package io.github.aresprojects.local.runtime;

import io.github.aresprojects.local.runtime.cloudformation.LocalCloudFormationController;
import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.http.AwsRequestHandler;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Keeps Ares control routes separate from AWS service protocol dispatch. */
final class LocalRequestRouter implements AwsRequestHandler {
    private final AwsRequestHandler awsServices;
    private final LocalCloudFormationController cloudFormation;

    LocalRequestRouter(AwsRequestHandler awsServices, LocalCloudFormationController cloudFormation) {
        this.awsServices = Objects.requireNonNull(awsServices, "awsServices");
        this.cloudFormation = Objects.requireNonNull(cloudFormation, "cloudFormation");
    }

    @Override
    public CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
        return cloudFormation.supports(request) ? cloudFormation.handle(request) : awsServices.handle(request);
    }
}
