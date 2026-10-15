package com.example.signer.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.invites.InvitationLinkResolver;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

/**
 * The sender-facing "where are my invitation links sent from?" endpoint.
 * It exposes internal network topology, so it must never answer an
 * anonymous caller — and it must report a local-only base as such rather
 * than pretending the emailed links work remotely.
 */
class ServerInfoControllerTests {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hs256-0123456789";

    private final JwtService jwt = new JwtService(SECRET, 15, true);

    /** A resolver reporting a fixed decision, so no address is detected. */
    private static InvitationLinkResolver links(String baseUrl,
                                               boolean localOnly,
                                               String source) {
        return new InvitationLinkResolver("http://localhost:8080", "", 8080) {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public boolean localOnly() {
                return localOnly;
            }

            @Override
            public String source() {
                return source;
            }
        };
    }

    private ServerInfoController controller(String baseUrl, boolean localOnly,
                                           String source) {
        return new ServerInfoController(links(baseUrl, localOnly, source), jwt);
    }

    private String bearer() {
        return "Bearer " + jwt.issueAccessJwt("alice");
    }

    @Test
    void aMissingOrUnusableTokenIsRejectedAsUnauthorized() {
        ServerInfoController controller =
                controller("http://localhost:8080", true, "APP_BASE_URL");

        assertThatThrownBy(() -> controller.invitationLink(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> controller.invitationLink("Basic abc"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.invitationLink("Bearer not.a.jwt"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anAuthenticatedCallerSeesTheBaseUrlAndWhetherItIsLocalOnly() {
        ServerInfoController controller = controller("http://localhost:8080",
                true, "APP_BASE_URL");

        InvitationLinkInfo info = controller.invitationLink(bearer());

        assertThat(info.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(info.localOnly()).isTrue();
        assertThat(info.source()).isEqualTo("APP_BASE_URL");
    }

    @Test
    void aRemoteReachableBaseUrlIsReportedAsNotLocalOnly() {
        ServerInfoController controller = controller(
                "http://192.168.4.31:8080", false, "auto-detected LAN address");

        InvitationLinkInfo info = controller.invitationLink(bearer());

        assertThat(info.baseUrl()).isEqualTo("http://192.168.4.31:8080");
        assertThat(info.localOnly()).isFalse();
        assertThat(info.source()).isEqualTo("auto-detected LAN address");
    }
}
