package com.example.signer.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2: per-address limit on sign-in requests. */
class LoginRateLimitFilterTests {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };

    private MockHttpServletResponse send(LoginRateLimitFilter filter, String method, String path,
                                         String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        // A spoofed proxy header must not change the counted address.
        request.addHeader("X-Forwarded-For", "203.0.113." + (int) (Math.random() * 250));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void theAttemptAfterTheLimitGets429WithRetryAfter() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter(3, 60, clock);
        for (int i = 0; i < 3; i++) {
            assertThat(send(filter, "POST", "/api/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = send(filter, "POST", "/api/auth/login", "10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("TOO_MANY_REQUESTS");
    }

    @Test
    void addressesAreCountedSeparatelyAndTheWindowSlides() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter(2, 60, clock);
        send(filter, "POST", "/api/auth/login", "10.0.0.1");
        send(filter, "POST", "/api/auth/login", "10.0.0.1");
        assertThat(send(filter, "POST", "/api/auth/login", "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(send(filter, "POST", "/api/auth/login", "10.0.0.2").getStatus()).isEqualTo(200);

        now.addAndGet(60_000);
        assertThat(send(filter, "POST", "/api/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void registrationIsLimitedButOtherRequestsAreNot() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter(1, 60, clock);
        send(filter, "POST", "/api/auth/register", "10.0.0.9");
        assertThat(send(filter, "POST", "/api/auth/register", "10.0.0.9").getStatus()).isEqualTo(429);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, "POST", "/api/auth/refresh", "10.0.0.9").getStatus()).isEqualTo(200);
            assertThat(send(filter, "GET", "/api/auth/login", "10.0.0.9").getStatus()).isEqualTo(200);
            assertThat(send(filter, "GET", "/api/workflows/my-tasks", "10.0.0.9").getStatus()).isEqualTo(200);
        }
    }
}
