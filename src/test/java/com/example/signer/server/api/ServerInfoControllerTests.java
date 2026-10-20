package com.example.signer.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.signer.server.api.ApiContracts.InvitationLinkInfo;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.security.AuthenticatedRequestFilter;
import org.springframework.mock.web.MockHttpServletRequest;
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
        return new ServerInfoController(links(baseUrl, localOnly, source));
    }

    /** A request Spring Security has authenticated as {@code alice}. */
    private static MockHttpServletRequest signedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedRequestFilter.USER_ATTR, "alice");
        return request;
    }

    @Test
    void aMissingOrUnusableTokenIsRejectedAsUnauthorized() {
        ServerInfoController controller =
                controller("http://localhost:8080", true, "APP_BASE_URL");

        // Spring Security answers 401 before the controller runs (covered by
        // SecurityHttpTests); the controller itself also refuses a request
        // that carries no authenticated identity.
        assertThatThrownBy(() -> controller.invitationLink(new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    void anAuthenticatedCallerSeesTheBaseUrlAndWhetherItIsLocalOnly() {
        ServerInfoController controller = controller("http://localhost:8080",
                true, "APP_BASE_URL");

        InvitationLinkInfo info = controller.invitationLink(signedIn());

        assertThat(info.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(info.localOnly()).isTrue();
        assertThat(info.source()).isEqualTo("APP_BASE_URL");
    }

    @Test
    void aRemoteReachableBaseUrlIsReportedAsNotLocalOnly() {
        ServerInfoController controller = controller(
                "http://192.168.4.31:8080", false, "auto-detected LAN address");

        InvitationLinkInfo info = controller.invitationLink(signedIn());

        assertThat(info.baseUrl()).isEqualTo("http://192.168.4.31:8080");
        assertThat(info.localOnly()).isFalse();
        assertThat(info.source()).isEqualTo("auto-detected LAN address");
    }
}
