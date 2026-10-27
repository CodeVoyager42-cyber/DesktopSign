package com.example.signer.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-address ceiling on the two public download routes.
 *
 * <p>Open and unmetered are different things: the installer is the largest
 * object this server hands out, so a loop fetching it is the cheapest way to
 * spend a deployment's bandwidth. What this proves is that the ceiling exists,
 * that it is one budget across both routes, and that it says nothing about
 * which limit was reached.
 */
class DownloadRateLimitFilterTests {

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

    private MockHttpServletResponse send(DownloadRateLimitFilter filter, String method,
                                         String path, String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        // A spoofed proxy header must not change the counted address.
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void theThirdRequestInTheWindowIsRefused() throws Exception {
        DownloadRateLimitFilter filter =
                new DownloadRateLimitFilter(2, Duration.ofMinutes(1), this.clock);

        assertThat(send(filter, "GET", "/downloads/SignFlow-0.1.0.msi", "10.0.0.1")
                .getStatus()).isEqualTo(200);
        assertThat(send(filter, "GET", "/downloads/SignFlow-0.1.0.msi", "10.0.0.1")
                .getStatus()).isEqualTo(200);

        MockHttpServletResponse refused =
                send(filter, "GET", "/downloads/SignFlow-0.1.0.msi", "10.0.0.1");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isNotNull();
        // The same wording as every other 429 here, so it reveals nothing
        // about which limit was hit.
        assertThat(refused.getContentAsString()).contains("TOO_MANY_REQUESTS");
        assertThat(refused.getContentAsString()).doesNotContain("download");
    }

    @Test
    void theFileAndTheInfoShareOneBudget() throws Exception {
        DownloadRateLimitFilter filter =
                new DownloadRateLimitFilter(2, Duration.ofMinutes(1), this.clock);

        send(filter, "GET", "/downloads/SignFlow-0.1.0.msi", "10.0.0.2");
        send(filter, "GET", "/web/api/download-info", "10.0.0.2");

        // Two routes, one count: alternating between them must not buy twice
        // the requests, which is why the filter is a shared singleton.
        assertThat(send(filter, "GET", "/web/api/download-info", "10.0.0.2").getStatus())
                .isEqualTo(429);
    }

    @Test
    void eachAddressHasItsOwn() throws Exception {
        DownloadRateLimitFilter filter =
                new DownloadRateLimitFilter(1, Duration.ofMinutes(1), this.clock);

        assertThat(send(filter, "GET", "/downloads/x", "10.0.0.3").getStatus()).isEqualTo(200);
        assertThat(send(filter, "GET", "/downloads/x", "10.0.0.3").getStatus()).isEqualTo(429);
        // A neighbour is not punished for it.
        assertThat(send(filter, "GET", "/downloads/x", "10.0.0.4").getStatus()).isEqualTo(200);
    }

    @Test
    void theBudgetComesBackWhenTheWindowPasses() throws Exception {
        DownloadRateLimitFilter filter =
                new DownloadRateLimitFilter(1, Duration.ofMinutes(1), this.clock);

        send(filter, "GET", "/downloads/x", "10.0.0.5");
        assertThat(send(filter, "GET", "/downloads/x", "10.0.0.5").getStatus()).isEqualTo(429);

        this.now.addAndGet(Duration.ofMinutes(1).toMillis() + 1);

        assertThat(send(filter, "GET", "/downloads/x", "10.0.0.5").getStatus()).isEqualTo(200);
    }

    @Test
    void itCountsNothingElse() throws Exception {
        DownloadRateLimitFilter filter =
                new DownloadRateLimitFilter(1, Duration.ofMinutes(1), this.clock);

        // The limiter is mounted on both chains, so it sees every request.
        // Only its own two routes may spend the budget.
        for (String path : new String[] {"/", "/documents", "/web/api/auth/me",
                                         "/api/health", "/download"}) {
            assertThat(send(filter, "GET", path, "10.0.0.6").getStatus())
                    .as(path).isEqualTo(200);
        }
        // The budget of 1 is still unspent.
        assertThat(send(filter, "GET", "/downloads/x", "10.0.0.6").getStatus())
                .isEqualTo(200);
    }

    @Test
    void aHeadOfTheFileCountsToo() throws Exception {
        DownloadRateLimitFilter filter =
                new DownloadRateLimitFilter(1, Duration.ofMinutes(1), this.clock);

        // HEAD returns the same headers and costs the same lookup; leaving it
        // unmetered would be a hole in the ceiling.
        assertThat(send(filter, "HEAD", "/downloads/x", "10.0.0.7").getStatus())
                .isEqualTo(200);
        assertThat(send(filter, "HEAD", "/downloads/x", "10.0.0.7").getStatus())
                .isEqualTo(429);
    }
}
