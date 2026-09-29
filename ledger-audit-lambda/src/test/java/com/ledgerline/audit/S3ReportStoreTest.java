package com.ledgerline.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3ReportStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void putsJsonIntoTheAuditBucket() throws Exception {
        S3Client s3 = mock(S3Client.class);

        new S3ReportStore(s3, "ledgerline-audit-123").put("audits/2026-09-29.json", "{\"passed\":true}");

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> request = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        request.getValue().accept(builder);
        PutObjectRequest built = builder.build();
        assertThat(built.bucket()).isEqualTo("ledgerline-audit-123");
        assertThat(built.key()).isEqualTo("audits/2026-09-29.json");
        assertThat(built.contentType()).isEqualTo("application/json");
        try (var in = body.getValue().contentStreamProvider().newStream()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("{\"passed\":true}");
        }
    }
}
