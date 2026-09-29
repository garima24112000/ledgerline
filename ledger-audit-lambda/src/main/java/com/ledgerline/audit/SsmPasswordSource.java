package com.ledgerline.audit;

import java.util.function.Supplier;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Reads the database password from an SSM SecureString parameter once, then caches it for the life
 * of the Lambda execution environment (warm invocations don't call SSM again).
 * The password is never in the function's environment variables or in Terraform state.
 */
final class SsmPasswordSource implements Supplier<String> {

    private final SsmClient ssm;
    private final String parameterName;
    private String cached;

    SsmPasswordSource(SsmClient ssm, String parameterName) {
        this.ssm = ssm;
        this.parameterName = parameterName;
    }

    @Override
    public synchronized String get() {
        if (cached == null) {
            cached = ssm.getParameter(request -> request.name(parameterName).withDecryption(true))
                    .parameter()
                    .value();
        }
        return cached;
    }
}
