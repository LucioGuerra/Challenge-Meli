package com.meli.challenge.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void missingHeader_generatesNewUuidAndPropagates() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(req, res, captureMdc(mdcDuringChain));

        String headerValue = res.getHeader(HEADER);
        assertThat(headerValue).isNotBlank();
        assertThat(UUID.fromString(headerValue)).isNotNull(); // valid UUID
        assertThat(mdcDuringChain.get()).isEqualTo(headerValue);
    }

    @Test
    void existingHeader_isReusedAcrossRequest() throws ServletException, IOException {
        String incoming = "trace-abc-123";
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HEADER, incoming);
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(req, res, captureMdc(mdcDuringChain));

        assertThat(res.getHeader(HEADER)).isEqualTo(incoming);
        assertThat(mdcDuringChain.get()).isEqualTo(incoming);
    }

    @Test
    void mdcIsClearedAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (request, response) -> {});

        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    private FilterChain captureMdc(AtomicReference<String> sink) {
        return (request, response) -> sink.set(MDC.get(MDC_KEY));
    }
}
