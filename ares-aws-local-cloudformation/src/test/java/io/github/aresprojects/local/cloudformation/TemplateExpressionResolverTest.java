package io.github.aresprojects.local.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateExpressionResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateExpressionResolver resolver = new TemplateExpressionResolver();

    @Test
    void resolvesSupportedIntrinsicsAndOrdinaryObjects() throws Exception {
        TemplateExpressionResolver.EvaluationContext context = context();
        JsonNode expression = mapper.readTree("""
                {
                  "parameter":{"Ref":"Name"},
                  "pseudo":{"Ref":"AWS::Region"},
                  "resource":{"Ref":"Queue"},
                  "attribute":{"Fn::GetAtt":["Queue","Arn"]},
                  "sub":{"Fn::Sub":"https://${AWS::URLSuffix}/${Name}/${Queue.Arn}"},
                  "subVariables":{"Fn::Sub":["${Value}",{"Value":{"Ref":"Name"}}]},
                  "join":{"Fn::Join":["-",["a",{"Ref":"Name"}]]},
                  "split":{"Fn::Split":[",","a,b"]},
                  "select":{"Fn::Select":[1,["a","b"]]},
                  "map":{"Fn::FindInMap":["RegionMap","us-east-1","Suffix"]},
                  "if":{"Fn::If":["Enabled","yes","no"]},
                  "equals":{"Fn::Equals":["a","a"]},
                  "and":{"Fn::And":[true,{"Fn::Not":[false]}]},
                  "or":{"Fn::Or":[false,true]},
                  "condition":{"Condition":"Enabled"},
                  "ordinary":{"Name":{"Ref":"Name"}}
                }
                """);

        JsonNode resolved = resolver.resolve(expression, context);

        assertEquals("Ada", resolved.path("parameter").asText());
        assertEquals("us-east-1", resolved.path("pseudo").asText());
        assertEquals("http://queue", resolved.path("resource").asText());
        assertEquals(
                "arn:aws:sqs:us-east-1:000000000000:queue",
                resolved.path("attribute").asText());
        assertEquals(
                "https://amazonaws.com/Ada/arn:aws:sqs:us-east-1:000000000000:queue",
                resolved.path("sub").asText());
        assertEquals("Ada", resolved.path("subVariables").asText());
        assertEquals("a-Ada", resolved.path("join").asText());
        assertEquals("b", resolved.path("split").get(1).asText());
        assertEquals("b", resolved.path("select").asText());
        assertEquals("amazonaws.com", resolved.path("map").asText());
        assertEquals("yes", resolved.path("if").asText());
        assertTrue(resolved.path("equals").asBoolean());
        assertTrue(resolved.path("and").asBoolean());
        assertTrue(resolved.path("or").asBoolean());
        assertTrue(resolved.path("condition").asBoolean());
        assertEquals("Ada", resolved.path("ordinary").path("Name").asText());
    }

    @Test
    void removesNoValueFromObjectsAndArrays() throws Exception {
        JsonNode expression = mapper.readTree("""
                {"keep":"value","remove":{"Ref":"AWS::NoValue"},"values":[{"Ref":"AWS::NoValue"},"kept"]}
                """);

        JsonNode resolved = resolver.removeNoValue(resolver.resolve(expression, context()));

        assertFalse(resolved.has("remove"));
        assertEquals(1, resolved.path("values").size());
        assertEquals("kept", resolved.path("values").get(0).asText());
    }

    @Test
    void evaluatesNamedConditionsAndDetectsCycles() throws Exception {
        TemplateExpressionResolver.EvaluationContext context = new TemplateExpressionResolver.EvaluationContext(
                Map.of(),
                Map.of(),
                Map.of("Enabled", mapper.readTree("true"), "Disabled", mapper.readTree("false")),
                Map.of(),
                Map.of());

        assertTrue(resolver.evaluateCondition("Enabled", context));
        assertFalse(resolver.evaluateCondition("Disabled", context));
        assertThrows(CloudFormationException.class, () -> resolver.evaluateCondition("Missing", context));

        TemplateExpressionResolver.EvaluationContext cycle = new TemplateExpressionResolver.EvaluationContext(
                Map.of(),
                Map.of(),
                Map.of("A", mapper.readTree("{\"Condition\":\"B\"}"), "B", mapper.readTree("{\"Condition\":\"A\"}")),
                Map.of(),
                Map.of());
        assertThrows(CloudFormationException.class, () -> resolver.evaluateCondition("A", cycle));
    }

    @Test
    void distinguishesDottedRefsAndBooleanAggregateResults() throws Exception {
        TemplateExpressionResolver.EvaluationContext context = context();

        assertEquals(
                "arn:aws:sqs:us-east-1:000000000000:queue",
                resolve("{\"Ref\":\"Queue.Arn\"}", context).asText());
        CloudFormationException trailingDot =
                assertThrows(CloudFormationException.class, () -> resolve("{\"Ref\":\"Queue.\"}", context));
        assertTrue(trailingDot.getMessage().startsWith("Ref references unknown"));
        CloudFormationException leadingDot =
                assertThrows(CloudFormationException.class, () -> resolve("{\"Ref\":\".Arn\"}", context));
        assertTrue(leadingDot.getMessage().startsWith("Ref references unknown"));

        assertFalse(resolve("{\"Fn::And\":[false,true]}", context).asBoolean());
        assertTrue(resolve("{\"Fn::Or\":[true,false]}", context).asBoolean());
        assertEquals("a", resolve("{\"Fn::Select\":[0,[\"a\",\"b\"]]}", context).asText());
        CloudFormationException pastEnd = assertThrows(
                CloudFormationException.class, () -> resolve("{\"Fn::Select\":[2,[\"a\",\"b\"]]}", context));
        assertTrue(pastEnd.getMessage().contains("outside the resolved list"));
    }

    @Test
    void rejectsMalformedExpressionsWithActionableErrors() throws Exception {
        TemplateExpressionResolver.EvaluationContext context = context();
        assertThrows(CloudFormationException.class, () -> resolve("{\"Ref\":1}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::GetAtt\":[\"Queue\"]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::GetAtt\":[\"Missing\",\"Arn\"]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Sub\":[]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Join\":[]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Split\":[]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Select\":[3,[\"a\"]]}", context));
        assertThrows(
                CloudFormationException.class, () -> resolve("{\"Fn::FindInMap\":[\"Missing\",\"a\",\"b\"]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::If\":[]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Equals\":[true]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::And\":[]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Or\":[]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Not\":[]}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Fn::Unknown\":true}", context));
        assertThrows(CloudFormationException.class, () -> resolve("{\"Ref\":\"Missing\"}", context));
        assertThrows(
                CloudFormationException.class,
                () -> resolve("{\"Fn::Join\":[\"-\",[{\"Fn::Split\":[\",\",\"a,b\"]}]]}", context));
    }

    private JsonNode resolve(String expression, TemplateExpressionResolver.EvaluationContext context) throws Exception {
        return resolver.resolve(mapper.readTree(expression), context);
    }

    private TemplateExpressionResolver.EvaluationContext context() throws Exception {
        return new TemplateExpressionResolver.EvaluationContext(
                Map.of("Name", "Ada"),
                Map.of("RegionMap", mapper.readTree("{\"us-east-1\":{\"Suffix\":\"amazonaws.com\"}}")),
                Map.of("Enabled", mapper.readTree("true")),
                Map.of(
                        "Queue",
                        new ProvisionedResource(
                                "Queue",
                                "AWS::SQS::Queue",
                                "http://queue",
                                "http://queue",
                                Map.of("Arn", "arn:aws:sqs:us-east-1:000000000000:queue"),
                                mapper.createObjectNode())),
                Map.of("AWS::Region", "us-east-1", "AWS::URLSuffix", "amazonaws.com"));
    }
}
