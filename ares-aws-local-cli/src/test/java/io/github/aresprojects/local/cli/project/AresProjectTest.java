package io.github.aresprojects.local.cli.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AresProjectTest {

    @Test
    void treatsMissingEnvironmentAsAnEmptyNameList() {
        assertEquals(List.of(), AresProject.sortedEnvironmentNames(null));
    }
}
