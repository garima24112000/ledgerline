package com.ledgerline.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

class SsmPasswordSourceTest {

    @Test
    @SuppressWarnings("unchecked")
    void decryptsTheParameterOnceAndCachesIt() {
        SsmClient ssm = mock(SsmClient.class);
        when(ssm.getParameter(any(Consumer.class))).thenReturn(
                GetParameterResponse.builder().parameter(Parameter.builder().value("s3cret").build()).build());
        SsmPasswordSource source = new SsmPasswordSource(ssm, "/ledgerline/db/password");

        assertThat(source.get()).isEqualTo("s3cret");
        assertThat(source.get()).isEqualTo("s3cret");

        ArgumentCaptor<Consumer<GetParameterRequest.Builder>> request = ArgumentCaptor.forClass(Consumer.class);
        verify(ssm, times(1)).getParameter(request.capture());
        GetParameterRequest.Builder builder = GetParameterRequest.builder();
        request.getValue().accept(builder);
        assertThat(builder.build().name()).isEqualTo("/ledgerline/db/password");
        assertThat(builder.build().withDecryption()).isTrue();
    }
}
