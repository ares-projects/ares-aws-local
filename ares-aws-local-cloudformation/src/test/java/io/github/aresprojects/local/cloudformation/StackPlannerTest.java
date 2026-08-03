package io.github.aresprojects.local.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StackPlannerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ordersDependenciesAndReportsUnsupportedResources() throws Exception {
        CloudFormationTemplate template =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Resources":{
                  "Consumer":{"Type":"Test::Consumer","Properties":{"Source":{"Ref":"Producer"},
                  "Arn":{"Fn::GetAtt":["Producer","Arn"]},"Name":{"Fn::Sub":"${Producer}"}}},
                  "Producer":{"Type":"Test::Producer"},
                  "Unknown":{"Type":"AWS::S3::Bucket"}}}
                """), java.nio.file.Path.of("template.json"));
        CloudFormationResourceHandlerRegistry registry = CloudFormationResourceHandlerRegistry.builder()
                .register(new FakeHandler("Test::Producer"))
                .register(new FakeHandler("Test::Consumer"))
                .build();

        StackPlan plan = new StackPlanner(registry).plan(template, context(), Map.of());

        assertEquals("Producer", plan.resources().get(0).resource().logicalId());
        assertEquals(ResourceAction.CREATE, plan.resources().get(1).action());
        assertEquals(ResourceAction.SKIPPED_UNSUPPORTED, plan.resources().get(2).action());
    }

    @Test
    void rejectsDuplicateLogicalIdsInManuallyBuiltTemplates() {
        TemplateResource first = new TemplateResource(
                "Duplicate",
                "Test::One",
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                java.util.List.of(),
                null,
                0);
        TemplateResource second = new TemplateResource(
                "Duplicate",
                "Test::Two",
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                java.util.List.of(),
                null,
                1);
        CloudFormationTemplate template = new CloudFormationTemplate(
                mapper.createObjectNode(), Map.of(), Map.of(), Map.of(), java.util.List.of(first, second), Map.of());

        assertThrows(
                CloudFormationException.class,
                () -> new StackPlanner(
                                CloudFormationResourceHandlerRegistry.builder().build())
                        .plan(template, context(), Map.of()));
    }

    @Test
    void rejectsDependencyCycles() throws Exception {
        CloudFormationTemplate template =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Resources":{"A":{"Type":"Test::A","DependsOn":"B"},"B":{"Type":"Test::B","DependsOn":"A"}}}
                """), java.nio.file.Path.of("template.json"));
        CloudFormationResourceHandlerRegistry registry = CloudFormationResourceHandlerRegistry.builder()
                .register(new FakeHandler("Test::A"))
                .register(new FakeHandler("Test::B"))
                .build();

        assertThrows(
                CloudFormationException.class, () -> new StackPlanner(registry).plan(template, context(), Map.of()));
    }

    @Test
    void blocksTemplatesThatRequireTransforms() throws Exception {
        CloudFormationTemplate template =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{"Queue":{"Type":"Test::Queue"}}}
                """), java.nio.file.Path.of("template.json"));

        StackPlan plan = new StackPlanner(CloudFormationResourceHandlerRegistry.builder()
                        .register(new FakeHandler("Test::Queue"))
                        .build())
                .plan(template, context(), Map.of());

        assertEquals(ResourceAction.BLOCKED, plan.resources().getFirst().action());
        assertEquals(ResourceAction.BLOCKED, plan.diagnostics().getFirst().action());
    }

    @Test
    void reportsConditionsMissingDependenciesMetadataAndChangedResources() throws Exception {
        CloudFormationTemplate template =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Conditions":{"Disabled":false},"Resources":{
                  "Missing":{"Type":"Test::Producer","Properties":{"Source":{"Ref":"Unknown"}}},
                  "Dependent":{"Type":"Test::Consumer","DependsOn":"Missing"},
                  "Metadata":{"Type":"AWS::CDK::Metadata"},
                  "Disabled":{"Type":"Test::Producer","Condition":"Disabled"},
                  "BadCondition":{"Type":"Test::Producer","Condition":"UnknownCondition"},
                  "Unsupported":{"Type":"AWS::S3::Bucket"},
                  "Rejected":{"Type":"Test::Rejected"},
                  "Changed":{"Type":"Test::Producer","Properties":{"Value":"new"}}}}
                """), java.nio.file.Path.of("template.json"));
        CloudFormationResourceHandlerRegistry registry = CloudFormationResourceHandlerRegistry.builder()
                .register(new FakeHandler("Test::Producer"))
                .register(new FakeHandler("Test::Consumer"))
                .register(new FakeHandler("Test::Rejected", true))
                .build();
        ProvisionedResource existing = new ProvisionedResource(
                "Changed", "Test::Producer", "physical", "reference", Map.of(), mapper.createObjectNode());
        Map<String, ProvisionedResource> state = Map.of("Changed", existing, "Removed", existing);

        StackPlan plan = new StackPlanner(registry).plan(template, context(), state);

        assertEquals(ResourceAction.BLOCKED, action(plan, "Missing"));
        assertEquals(ResourceAction.BLOCKED, action(plan, "Dependent"));
        assertEquals(ResourceAction.NO_OP, action(plan, "Metadata"));
        assertEquals(ResourceAction.SKIPPED_CONDITION, action(plan, "Disabled"));
        assertEquals(ResourceAction.BLOCKED, action(plan, "BadCondition"));
        assertEquals(ResourceAction.SKIPPED_UNSUPPORTED, action(plan, "Unsupported"));
        assertEquals(ResourceAction.SKIPPED_UNSUPPORTED, action(plan, "Rejected"));
        assertEquals(ResourceAction.UPDATE_UNSUPPORTED, action(plan, "Changed"));
        assertTrue(plan.diagnostics().stream().anyMatch(item -> item.action() == ResourceAction.DELETE_UNSUPPORTED));
    }

    @Test
    void collectsOnlyValidImplicitReferencesAndTraversesNestedExpressions() throws Exception {
        CloudFormationTemplate template =
                new CloudFormationTemplateParser().parse(mapper.readTree("""
                {"Resources":{
                  "Consumer":{"Type":"Test::Consumer","Properties":{
                    "ref":{"Ref":"Producer"},
                    "getAtt":{"Fn::GetAtt":["Producer","Arn"]},
                    "sub":"${Producer}",
                    "subVariables":{"Fn::Sub":["${Value}",{"Value":{"Ref":"Producer"}}]},
                    "notARef":{"Ref":"Producer","Extra":true},
                    "notAGetAtt":{"Fn::GetAtt":["Producer","Arn"],"Extra":true},
                    "emptyGetAtt":{"Fn::GetAtt":[]},
                    "nonTextGetAtt":{"Fn::GetAtt":[1,"Arn"]}}},
                  "Producer":{"Type":"Test::Producer"}}}
                """), java.nio.file.Path.of("template.json"));
        CloudFormationResourceHandlerRegistry registry = CloudFormationResourceHandlerRegistry.builder()
                .register(new FakeHandler("Test::Producer"))
                .register(new FakeHandler("Test::Consumer"))
                .build();

        StackPlan plan = new StackPlanner(registry).plan(template, context(), Map.of());

        assertEquals("Producer", plan.resources().getFirst().resource().logicalId());
        ResourcePlan consumer = plan.resources().get(1);
        assertEquals(List.of("Producer"), consumer.dependencies());
        assertEquals(ResourceAction.CREATE, consumer.action());
    }

    private static ResourceAction action(StackPlan plan, String logicalId) {
        return plan.resources().stream()
                .filter(item -> item.resource().logicalId().equals(logicalId))
                .findFirst()
                .orElseThrow()
                .action();
    }

    private static ResourceOperationContext context() {
        return new ResourceOperationContext(
                "Stack",
                "000000000000",
                "us-east-1",
                URI.create("http://127.0.0.1:4566"),
                Clock.systemUTC(),
                Map.of(),
                ignored -> Optional.empty());
    }

    private static final class FakeHandler implements CloudFormationResourceHandler {
        private final String type;
        private final boolean reject;

        private FakeHandler(String type) {
            this(type, false);
        }

        private FakeHandler(String type, boolean reject) {
            this.type = type;
            this.reject = reject;
        }

        @Override
        public String resourceType() {
            return type;
        }

        @Override
        public void validate(ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
            if (reject) {
                throw new CloudFormationException("test handler rejected properties");
            }
        }

        @Override
        public ProvisionedResource create(
                ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
            return new ProvisionedResource(
                    resource.logicalId(), type, resource.logicalId(), resource.logicalId(), Map.of(), properties);
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
        public void delete(ResourceOperationContext context, ProvisionedResource resource) {}
    }
}
