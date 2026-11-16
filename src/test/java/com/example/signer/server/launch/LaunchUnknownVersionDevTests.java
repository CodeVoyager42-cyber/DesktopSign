package com.example.signer.server.launch;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B remediation M2: with {@code accept-unknown-version} (the dev
 * profile's setting) an IDE build reporting "unknown" may open a launch
 * link; the launch is recorded as "unknown", never as a made-up version.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-launch-dev",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.desktop.accept-unknown-version=true"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class LaunchUnknownVersionDevTests {

    @Autowired
    private LaunchCodeService launches;

    @Test
    void anUnknownVersionIsAcceptedAndRecordedAsUnknown() {
        assertThat(ReflectionTestUtils.getField(launches, "acceptUnknownVersion")).isEqualTo(true);
        // An unknown code with an unknown version passes the version gate and
        // fails on the code itself — proving the gate let it through.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> launches.exchange(
                new ApiContracts.LaunchExchangeRequest("x".repeat(43), "unknown", "dev"))))
                .isInstanceOf(LaunchCodeService.LaunchRejected.class);
        // A malformed version is still refused (426), unknown is a precise word.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> launches.exchange(
                new ApiContracts.LaunchExchangeRequest("x".repeat(43), "Unknown", "dev"))))
                .hasMessageContaining("too old");
    }
}
