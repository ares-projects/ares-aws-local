package io.github.aresprojects.local.cloudformation;

/** Reports invalid templates, assemblies, plans, or provisioning operations. */
public class CloudFormationException extends RuntimeException {
    public CloudFormationException(String message) {
        super(message);
    }

    public CloudFormationException(String message, Throwable cause) {
        super(message, cause);
    }
}
