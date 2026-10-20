package com.example.signer.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase B remediation M2: where the desktop's version comes from. */
class AppVersionTests {

    private static InputStream props(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void aFilteredBuildFileGivesItsVersion() {
        assertThat(WorkflowApiClient.readBuildVersion(props("version=0.1.0-SNAPSHOT\n")))
                .isEqualTo("0.1.0-SNAPSHOT");
        assertThat(WorkflowApiClient.readBuildVersion(props("version = 1.4.2 \n")))
                .isEqualTo("1.4.2");
    }

    @Test
    void anythingElseIsReportedAsUnknownNeverAsARelease() {
        assertThat(WorkflowApiClient.readBuildVersion(null)).isEqualTo("unknown");
        for (String text : new String[] {"version=@project.version@", "version=${project.version}",
                "", "version=", "version=1.0", "version=v1.2.3", "version=1.2.3-<x>",
                "version=1.2.3-averyveryverylongsuffix"}) {
            assertThat(WorkflowApiClient.readBuildVersion(props(text))).as(text)
                    .isEqualTo(WorkflowApiClient.UNKNOWN_VERSION);
        }
    }

    @Test
    void theRunningBuildReportsWhatItWasBuiltAs() {
        String expected = System.getProperty("signflow.expected.version");
        if (expected != null && !expected.isBlank()) {
            // Maven (surefire passes the project version): filtered resource.
            assertThat(WorkflowApiClient.appVersion()).isEqualTo(expected);
        } else {
            // Unfiltered build (IDE, plain javac): explicitly unknown.
            assertThat(WorkflowApiClient.appVersion()).isEqualTo("unknown");
        }
    }
}
