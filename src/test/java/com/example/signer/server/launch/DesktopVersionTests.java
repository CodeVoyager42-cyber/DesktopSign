package com.example.signer.server.launch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopVersionTests {

    private static int cmp(String a, String b) {
        return Integer.signum(DesktopVersion.parse(a).compareTo(DesktopVersion.parse(b)));
    }

    @Test
    void comparesNumericallyAndPutsPreReleasesFirst() {
        assertThat(cmp("1.10.0", "1.2.0")).isEqualTo(1);
        assertThat(cmp("1.2.0", "1.2.0")).isZero();
        assertThat(cmp("1.2.0-beta", "1.2.0")).isEqualTo(-1);
        assertThat(cmp("1.2.0", "1.2.0-rc1")).isEqualTo(1);
        assertThat(cmp("1.2.1-beta", "1.2.0")).isEqualTo(1);
        assertThat(cmp("2.0.0", "1.99.99")).isEqualTo(1);
    }

    @Test
    void theCurrentBuildMeetsTheDefaultMinimum() {
        // pom.xml version (jar manifest) vs the default in application.yml.
        assertThat(cmp("0.1.0-SNAPSHOT", "0.1.0-SNAPSHOT")).isZero();
        assertThat(cmp("0.1.0", "0.1.0-SNAPSHOT")).isEqualTo(1);
    }

    @Test
    void rejectsAnythingElse() {
        for (String bad : new String[] {null, "", "1.2", "1.2.3.4", "v1.2.3", "1.2.3-",
                "1.2.3-<script>", "1234.0.0", "1.2.3-ninechars", "999.999.999-12345678x"}) {
            assertThat(DesktopVersion.parse(bad)).as(String.valueOf(bad)).isNull();
        }
        assertThat(DesktopVersion.parse(" 1.2.3-rc.1 ")).hasToString("1.2.3-rc.1");
        // Always fits the 20-character columns of launch_codes.
        assertThat(DesktopVersion.parse("999.999.999-12345678").toString()).hasSize(20);
    }

    @Test
    void preReleasesFollowSemanticVersioningOrder() {
        // SemVer 2.0.0 example ordering, within our 8-character limit.
        String[] ordered = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.b", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"};
        for (int i = 0; i + 1 < ordered.length; i++) {
            assertThat(cmp(ordered[i], ordered[i + 1])).as(ordered[i] + " < " + ordered[i + 1])
                    .isEqualTo(-1);
        }
        assertThat(cmp("1.0.0-rc.10", "1.0.0-rc.2")).isEqualTo(1);
        assertThat(cmp("1.0.0-2", "1.0.0-rc")).as("numeric before alphanumeric").isEqualTo(-1);
        // ASCII order: upper case first, so SNAPSHOT sorts before alpha.
        assertThat(cmp("1.0.0-SNAPSHOT", "1.0.0-alpha")).isEqualTo(-1);
    }

    @Test
    void emptyPreReleaseIdentifiersAreRefused() {
        for (String bad : new String[] {"1.2.3-rc..1", "1.2.3-.rc", "1.2.3-rc.", "1.2.3--"}) {
            assertThat(DesktopVersion.parse(bad)).as(bad).isNull();
        }
    }
}
