package com.example.signer.server.web;

import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.auth.RefreshTokenStore;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.EmailVerificationTokenRepository;
import com.example.signer.server.db.repository.PasswordResetTokenRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.invites.EmailService;
import com.example.signer.server.invites.InvitationLinkResolver;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The password-change notice (security Phase D1 risk D8) is <b>best-effort</b>:
 * the password has already changed and the sessions are already gone by the
 * time it is sent, so a mail server that is down must not undo any of that.
 *
 * <p>A unit test rather than an HTTP one, because the only way to see this is
 * to make sending fail — which the development mailbox never does.
 */
class WebAccountNoticeTests {

    private static final String OLD = "the-old-password-1";
    private static final String NEW = "the-new-password-2";

    private final UserRepository users = mock(UserRepository.class);
    private final EmailVerificationTokenRepository verifications =
            mock(EmailVerificationTokenRepository.class);
    private final PasswordResetTokenRepository resets =
            mock(PasswordResetTokenRepository.class);
    private final EmailService email = mock(EmailService.class);
    private final RefreshTokenStore refreshTokens = mock(RefreshTokenStore.class);
    private final WebSessionRegistry webSessions = mock(WebSessionRegistry.class);

    private WebAccountService service() {
        return new WebAccountService(users, verifications, resets, email,
                mock(InvitationLinkResolver.class), refreshTokens, webSessions,
                Clock.fixed(Instant.parse("2026-10-21T17:00:00Z"), ZoneOffset.UTC), 3);
    }

    private UserEntity account() {
        UserEntity user = new UserEntity("ada", "ada@example.test",
                PasswordHashing.hash(OLD), "Ada", AccountStatus.ACTIVE);
        when(users.findByUsername("ada")).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void theNoticeIsSentWhenTheHolderChangesTheirOwnPassword() {
        UserEntity user = account();

        assertThat(service().changePassword("ada", OLD, NEW, "session-1")).isTrue();

        // afterReset = false: the holder knew the old password.
        verify(email).sendPasswordChanged("ada@example.test",
                Instant.parse("2026-10-21T17:00:00Z"), false);
        assertThat(PasswordHashing.matches(NEW, user.getPasswordHash())).isTrue();
    }

    @Test
    void aFailingNoticeDoesNotUndoThePasswordChange() {
        UserEntity user = account();
        doThrow(new IllegalStateException("the mail server is down"))
                .when(email).sendPasswordChanged(anyString(), any(), anyBoolean());

        boolean changed = service().changePassword("ada", OLD, NEW, "session-1");

        assertThat(changed).as("the change stands even though the notice failed").isTrue();
        assertThat(PasswordHashing.matches(NEW, user.getPasswordHash()))
                .as("the new password is in place").isTrue();
        assertThat(PasswordHashing.matches(OLD, user.getPasswordHash())).isFalse();
        // And the revocations happened before the notice was attempted, so
        // they are not undone either.
        verify(webSessions).revokeAllExcept("ada", "session-1");
        verify(refreshTokens).revokeAllForUser("ada");
    }

    @Test
    void noNoticeIsSentWhenTheCurrentPasswordIsWrong() {
        UserEntity user = account();

        assertThat(service().changePassword("ada", "not-the-password", NEW, "session-1"))
                .isFalse();

        verify(email, org.mockito.Mockito.never())
                .sendPasswordChanged(anyString(), any(), anyBoolean());
        assertThat(PasswordHashing.matches(OLD, user.getPasswordHash()))
                .as("the old password still stands").isTrue();
    }

    @Test
    void anAccountWithNoAddressOnFileIsSimplyNotNotified() {
        UserEntity user = new UserEntity("ada", null, PasswordHashing.hash(OLD),
                "Ada", AccountStatus.ACTIVE);
        when(users.findByUsername("ada")).thenReturn(Optional.of(user));

        assertThat(service().changePassword("ada", OLD, NEW, "session-1")).isTrue();

        verify(email, org.mockito.Mockito.never())
                .sendPasswordChanged(anyString(), any(), anyBoolean());
    }
}
