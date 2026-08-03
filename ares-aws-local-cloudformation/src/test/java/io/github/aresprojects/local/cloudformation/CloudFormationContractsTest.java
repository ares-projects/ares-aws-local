package io.github.aresprojects.local.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CloudFormationContractsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void immutableModelsCopyCollectionsAndExposeStackArtifacts() {
        CloudAssemblyArtifact stack = new CloudAssemblyArtifact(
                "Stack",
                "aws:cloudformation:stack",
                Path.of("template.json"),
                "",
                List.of("Asset"),
                mapper.createObjectNode());
        CloudAssemblyArtifact asset = new CloudAssemblyArtifact(
                "Asset", "aws:cdk:asset", Path.of("asset.zip"), "", List.of(), mapper.createObjectNode());
        CloudAssembly assembly = new CloudAssembly(Path.of("."), "36.0.0", List.of(stack, asset));
        ProvisionedResource resource = new ProvisionedResource(
                "Queue", "AWS::SQS::Queue", "physical", "reference", Map.of("Arn", "arn"), mapper.createObjectNode());
        ResourceOperationContext context = new ResourceOperationContext(
                "Stack",
                "000000000000",
                "us-east-1",
                URI.create("http://localhost:4566"),
                Clock.systemUTC(),
                Map.of(),
                ignored -> Optional.empty());
        StackState state = new StackState("Stack", Map.of("Queue", resource), Map.of("Output", "value"));
        StackDeploymentResult result = new StackDeploymentResult(
                "Stack", StackDeploymentStatus.COMPLETE, List.of(), Map.of("Output", "value"), state);

        assertEquals(1, assembly.stacks().size());
        assertFalse(stack.cloudFormationStack() == asset.cloudFormationStack());
        assertEquals("Stack", context.stackName());
        assertEquals("reference", state.resources().get("Queue").referenceValue());
        assertEquals(StackDeploymentStatus.COMPLETE, result.status());
        assertThrows(
                UnsupportedOperationException.class, () -> state.resources().put("Other", resource));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProvisionedResource(
                        "", "Type", "physical", "reference", Map.of(), mapper.createObjectNode()));
    }

    @Test
    void resourcePlanAndStackPlanReportOptionalStateAndPartialActions() {
        TemplateResource resource = new TemplateResource(
                "Queue", "AWS::SQS::Queue", mapper.createObjectNode(), mapper.createObjectNode(), List.of(), null, 0);
        ResourcePlan create = ResourcePlan.withHandler(
                resource, ResourceAction.CREATE, resource.properties(), List.of(), "new", null, null);
        ResourcePlan partial = ResourcePlan.withHandler(
                resource, ResourceAction.BLOCKED, resource.properties(), List.of(), "blocked", null, null);

        assertTrue(create.existingOptional().isEmpty());
        assertFalse(new StackPlan("Stack", List.of(create), List.of()).partial());
        assertTrue(new StackPlan("Stack", List.of(partial), List.of()).partial());
    }

    @Test
    void handlerRegistryRejectsDuplicateAndInvalidTypes() {
        FakeHandler handler = new FakeHandler("Test::Queue", false, new AtomicInteger());
        assertThrows(
                NullPointerException.class,
                () -> CloudFormationResourceHandlerRegistry.builder().register(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> CloudFormationResourceHandlerRegistry.builder()
                        .register(new FakeHandler("", false, new AtomicInteger())));
        CloudFormationResourceHandlerRegistry.Builder builder =
                CloudFormationResourceHandlerRegistry.builder().register(handler);
        assertThrows(IllegalArgumentException.class, () -> builder.register(handler));
        assertEquals(List.of("Test::Queue"), builder.build().resourceTypes());
    }

    @Test
    void inMemoryStateStoreReplacesAndFindsSnapshots() {
        InMemoryStackStateStore store = new InMemoryStackStateStore();
        StackState state = new StackState("Stack", Map.of(), Map.of());
        assertTrue(store.find("Stack").isEmpty());
        store.save(state);
        assertEquals(state, store.find("Stack").orElseThrow());
        store.save(new StackState("Stack", Map.of(), Map.of("Status", "done")));
        assertEquals("done", store.find("Stack").orElseThrow().outputs().get("Status"));
        assertThrows(NullPointerException.class, () -> store.find(null));
    }

    @Test
    void provisionsOutputsAndRollsBackOnlyResourcesFromFailedAttempt() throws Exception {
        AtomicInteger deletes = new AtomicInteger();
        FakeHandler good = new FakeHandler("Test::Good", false, deletes);
        FakeHandler failing = new FakeHandler("Test::Failing", true, deletes);
        CloudFormationResourceHandlerRegistry registry = CloudFormationResourceHandlerRegistry.builder()
                .register(good)
                .register(failing)
                .build();
        CloudFormationTemplate template =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Resources":{"Good":{"Type":"Test::Good"},"Failing":{"Type":"Test::Failing","DependsOn":"Good"}},
                 "Outputs":{"QueueRef":{"Value":{"Ref":"Good"}},"Arn":{"Value":{"Fn::GetAtt":["Good","Arn"]}}}}
                """), Path.of("template.json"));
        ResourceOperationContext context = context();
        InMemoryStackStateStore store = new InMemoryStackStateStore();
        StackPlan plan = new StackPlanner(registry).plan(template, context, Map.of());

        StackDeploymentResult failed = new StackProvisioner(store).provision(template, plan, context);

        assertEquals(StackDeploymentStatus.ROLLBACK_COMPLETE, failed.status());
        assertEquals(1, deletes.get());
        assertTrue(store.find("Stack").isEmpty());
    }

    @Test
    void provisionsACompleteStackAndResolvesOutputs() throws Exception {
        CloudFormationResourceHandlerRegistry registry = CloudFormationResourceHandlerRegistry.builder()
                .register(new FakeHandler("Test::Good", false, new AtomicInteger()))
                .build();
        CloudFormationTemplate template =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Resources":{"Good":{"Type":"Test::Good"}},
                 "Outputs":{"Ref":{"Value":{"Ref":"Good"}},"Arn":{"Value":{"Fn::GetAtt":["Good","Arn"]}}}}
                """), Path.of("template.json"));
        ResourceOperationContext context = context();
        InMemoryStackStateStore store = new InMemoryStackStateStore();
        StackPlan plan = new StackPlanner(registry).plan(template, context, Map.of());

        StackDeploymentResult result = new StackProvisioner(store).provision(template, plan, context);

        assertEquals(StackDeploymentStatus.COMPLETE, result.status());
        assertEquals("reference", result.outputs().get("Ref"));
        assertEquals("arn:test", result.outputs().get("Arn"));
        assertEquals(result.state(), store.find("Stack").orElseThrow());
    }

    @Test
    void preservesExistingResourcesForNoOpPlansAndReportsRollbackFailures() throws Exception {
        AtomicInteger deletes = new AtomicInteger();
        FakeHandler good = new FakeHandler("Test::Good", false, false, deletes);
        ProvisionedResource existing = good.create(
                context(),
                new TemplateResource(
                        "Good", "Test::Good", mapper.createObjectNode(), mapper.createObjectNode(), List.of(), null, 0),
                mapper.createObjectNode());
        InMemoryStackStateStore store = new InMemoryStackStateStore();
        store.save(new StackState("Stack", Map.of("Good", existing), Map.of()));
        TemplateResource resource = new TemplateResource(
                "Good", "Test::Good", mapper.createObjectNode(), mapper.createObjectNode(), List.of(), null, 0);
        CloudFormationTemplate template = new CloudFormationTemplate(
                mapper.createObjectNode(), Map.of(), Map.of(), Map.of(), List.of(resource), Map.of());
        StackPlan noOp = new StackPlan(
                "Stack",
                List.of(ResourcePlan.withHandler(
                        resource, ResourceAction.NO_OP, resource.properties(), List.of(), "unchanged", good, existing)),
                List.of());
        StackDeploymentResult result = new StackProvisioner(store).provision(template, noOp, context());
        assertEquals(StackDeploymentStatus.COMPLETE, result.status());
        assertTrue(result.outputs().isEmpty());

        StackPlan partialPlan = new StackPlan(
                "Stack",
                List.of(
                        ResourcePlan.withHandler(
                                resource,
                                ResourceAction.NO_OP,
                                resource.properties(),
                                List.of(),
                                "unchanged",
                                good,
                                existing),
                        ResourcePlan.withHandler(
                                resource,
                                ResourceAction.SKIPPED_UNSUPPORTED,
                                resource.properties(),
                                List.of(),
                                "unsupported",
                                null,
                                null)),
                List.of());
        StackDeploymentResult partial = new StackProvisioner(store).provision(template, partialPlan, context());
        assertEquals(StackDeploymentStatus.PARTIAL, partial.status());
        assertEquals(1, partial.diagnostics().size());

        InMemoryStackStateStore rollbackStore = new InMemoryStackStateStore();
        CloudFormationResourceHandlerRegistry registry = CloudFormationResourceHandlerRegistry.builder()
                .register(new FakeHandler("Test::Good", false, true, deletes))
                .register(new FakeHandler("Test::Failing", true, false, deletes))
                .build();
        CloudFormationTemplate failingTemplate =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Resources":{"Good":{"Type":"Test::Good"},"Failing":{"Type":"Test::Failing","DependsOn":"Good"}}}
                """), Path.of("template.json"));
        StackPlan failingPlan = new StackPlanner(registry).plan(failingTemplate, context(), Map.of());

        StackDeploymentResult rollback =
                new StackProvisioner(rollbackStore).provision(failingTemplate, failingPlan, context());

        assertEquals(StackDeploymentStatus.ROLLBACK_FAILED, rollback.status());
        assertTrue(
                rollback.diagnostics().stream().anyMatch(item -> item.message().contains("Rollback failed")));
    }

    private static ResourceOperationContext context() {
        return new ResourceOperationContext(
                "Stack",
                "000000000000",
                "us-east-1",
                URI.create("http://localhost:4566"),
                Clock.systemUTC(),
                Map.of(),
                ignored -> Optional.empty());
    }

    private static final class FakeHandler implements CloudFormationResourceHandler {
        private final String type;
        private final boolean fail;
        private final boolean failDelete;
        private final AtomicInteger deletes;

        private FakeHandler(String type, boolean fail, AtomicInteger deletes) {
            this(type, fail, false, deletes);
        }

        private FakeHandler(String type, boolean fail, boolean failDelete, AtomicInteger deletes) {
            this.type = type;
            this.fail = fail;
            this.failDelete = failDelete;
            this.deletes = deletes;
        }

        @Override
        public String resourceType() {
            return type;
        }

        @Override
        public void validate(ResourceOperationContext context, TemplateResource resource, JsonNode properties) {}

        @Override
        public ProvisionedResource create(
                ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
            if (fail) {
                throw new CloudFormationException("intentional create failure");
            }
            return new ProvisionedResource(
                    resource.logicalId(), type, "physical", "reference", Map.of("Arn", "arn:test"), properties);
        }

        @Override
        public Optional<ProvisionedResource> read(ResourceOperationContext context, ProvisionedResource resource) {
            return Optional.of(resource);
        }

        @Override
        public ProvisionedResource update(
                ResourceOperationContext context,
                TemplateResource resource,
                JsonNode properties,
                ProvisionedResource current) {
            return current;
        }

        @Override
        public void delete(ResourceOperationContext context, ProvisionedResource resource) {
            if (failDelete) {
                throw new CloudFormationException("intentional delete failure");
            }
            deletes.incrementAndGet();
        }
    }
}
