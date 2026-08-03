package io.github.aresprojects.local.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CloudFormationTemplateParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudFormationTemplateParser parser = new CloudFormationTemplateParser();

    @Test
    void parsesParametersMappingsConditionsOutputsAndResourceMetadata() throws Exception {
        CloudFormationTemplate template = parser.parse(mapper.readTree("""
                {"Parameters":{"Name":{"Type":"String"}},"Mappings":{"Map":{"Key":{"Value":"x"}}},
                 "Conditions":{"Enabled":true},"Outputs":{"Value":{"Value":"x"}},"Resources":{
                 "Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"queue"},
                 "Metadata":{"info":"test"},"DependsOn":["Other"],"Condition":"Enabled"},
                 "Other":{"Type":"AWS::CDK::Metadata"}}}
                """), Path.of("template.json"));

        assertEquals(1, template.parameters().size());
        assertEquals(1, template.mappings().size());
        assertEquals(1, template.conditions().size());
        assertEquals(1, template.outputs().size());
        assertEquals("Other", template.resources().getFirst().dependsOn().getFirst());
        assertEquals("Enabled", template.resources().getFirst().condition());
        assertEquals(0, template.resources().getFirst().declarationOrder());
    }

    @Test
    void rejectsMalformedTemplatesAndDependsOnValues() throws Exception {
        assertThrows(
                CloudFormationException.class, () -> parser.parse(mapper.readTree("[]"), Path.of("template.json")));
        assertThrows(
                CloudFormationException.class, () -> parser.parse(mapper.readTree("{}"), Path.of("template.json")));
        assertThrows(
                CloudFormationException.class,
                () -> parser.parse(
                        mapper.readTree("{\"Resources\":{\"Queue\":{\"Properties\":{}}}}"), Path.of("template.json")));
        assertThrows(
                CloudFormationException.class,
                () -> parser.parse(
                        mapper.readTree("{\"Resources\":{\"Queue\":{\"Type\":\"Test\",\"DependsOn\":true}}}"),
                        Path.of("template.json")));
        assertThrows(
                CloudFormationException.class,
                () -> parser.parse(
                        mapper.readTree("{\"Resources\":{\"Queue\":{\"Type\":\"Test\",\"DependsOn\":[1]}}}"),
                        Path.of("template.json")));
    }

    @Test
    void readsJsonFilesAndReportsFileErrors(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path template = directory.resolve("template.json");
        Files.writeString(template, "{\"Resources\":{}}");
        assertEquals(0, parser.parse(template).resources().size());
        Files.writeString(template, "not-json");
        assertThrows(CloudFormationException.class, () -> parser.parse(template));
        assertThrows(NullPointerException.class, () -> parser.parse((Path) null));
    }
}
