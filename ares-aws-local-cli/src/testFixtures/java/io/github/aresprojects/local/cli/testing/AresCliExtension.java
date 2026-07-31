package io.github.aresprojects.local.cli.testing;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/** Injects and cleans up an installed local CLI fixture for end-to-end tests. */
public final class AresCliExtension implements TestInstancePostProcessor, AfterEachCallback {

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        for (Field field : testInstance.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Cli.class)) {
                inject(field, testInstance);
            }
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        Object testInstance = context.getRequiredTestInstance();
        for (Field field : testInstance.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Cli.class)) {
                close(field, testInstance);
            }
        }
    }

    private static void inject(Field field, Object testInstance) throws IllegalAccessException {
        validate(field);
        try {
            field.set(testInstance, new AresCli());
        } catch (Exception exception) {
            throw new ExtensionConfigurationException(
                    "Could not create CLI fixture for field '" + field.getName()
                            + "'; run :ares-aws-local-cli:installDist first",
                    exception);
        }
    }

    private static void close(Field field, Object testInstance) throws IllegalAccessException {
        validate(field);
        AresCli cli = (AresCli) field.get(testInstance);
        if (cli != null) {
            try {
                cli.close();
            } catch (Exception exception) {
                throw new ExtensionConfigurationException(
                        "Could not clean up CLI fixture in field '" + field.getName() + "'", exception);
            }
        }
    }

    private static void validate(Field field) {
        if (field.getType() != AresCli.class) {
            throw new ExtensionConfigurationException("@Cli can only annotate an AresCli test fixture field; found "
                    + field.getType().getName());
        }
        if (Modifier.isStatic(field.getModifiers())) {
            throw new ExtensionConfigurationException("@Cli cannot annotate a static field");
        }
        if (!field.trySetAccessible()) {
            throw new ExtensionConfigurationException("@Cli could not access field '" + field.getName() + "'");
        }
    }
}
