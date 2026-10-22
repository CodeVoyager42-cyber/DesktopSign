package com.example.signer.server.auth;

import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.LoginFailureRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.invites.AccountEmailTemplate;
import com.example.signer.server.invites.EmailService;
import com.example.signer.server.security.InMemoryRateLimitStore;
import com.example.signer.server.security.RateLimitStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security Phase 2 risk L3, the last of its four parts: telling the holder
 * their account has been locked.
 *
 * <p>The first test is the feature. The second is the one that matters more:
 * a <b>per-address</b> lock sends nothing. An attacker trips those on
 * purpose, cheaply and repeatedly, so notifying on them would have turned
 * this notice into a way to mail-bomb the person it is meant to protect —
 * a worse denial of service than the lockout L3 is about.
 *
 * <p>No database: what is being tested is which events send a message and
 * which do not, so the repositories are mocked and the lock is made to fire
 * by saying so.
 */
class AccountLockNoticeTests {

    private static final String PASSWORD = "password-123";
    private static final String HASH = PasswordHashing.hash(PASSWORD);

    private UserRepository repo;
    private LoginFailureRepository failures;
    private EmailService email;
    private RateLimitStore rateLimits;
    private DbUserAccounts accounts;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        failures = mock(LoginFailureRepository.class);
        email = mock(EmailService.class);
        rateLimits = new InMemoryRateLimitStore(Clock.systemUTC());
        when(repo.findByUsername(anyString())).thenReturn(Optional.empty());
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());
        when(failures.findByUsernameAndClientAddress(anyString(), anyString()))
                .thenReturn(Optional.empty());
        accounts = new DbUserAccounts(repo, failures, 5, 50, 15, email, rateLimits);
    }

    /** An account with a verified address, and whichever lock is said to fire. */
    private UserEntity account(boolean verified, boolean accountLockFires) {
        UserEntity user = new UserEntity("ada", "ada@example.test", HASH, "Ada",
                AccountStatus.ACTIVE);
        if (verified) {
            user.setEmailVerifiedAt(Instant.parse("2026-10-01T09:00:00Z"));
        }
        when(repo.findByUsername("ada")).thenReturn(Optional.of(user));
        when(repo.lockIfTooManyFailures(any(), anyInt(), any()))
                .thenReturn(accountLockFires ? 1 : 0);
        return user;
    }

    /* ------------------------------------------------------------------ */

    @Test
    void theAccountLockTellsTheHolder() {
        account(true, true);

        assertThat(accounts.matches("ada", "wrong-password", "203.0.113.7")).isFalse();

        verify(email).sendAccountLocked(eq("ada@example.test"), any(Instant.class));
    }

    @Test
    void aPerAddressLockTellsNobody() {
        // The attacker's own lock. They can trip one every few failures, from
        // every address they have, so a notice here would be a mail-bombing
        // tool aimed at the holder.
        account(true, false);

        for (int i = 0; i < 10; i++) {
            assertThat(accounts.matches("ada", "wrong-" + i, "203.0.113.7")).isFalse();
        }

        verify(failures, times(10)).countFailure(anyString(), anyString(), any());
        verify(email, never()).sendAccountLocked(anyString(), any());
    }

    @Test
    void aSecondAccountLockInsideTheWindowIsNotASecondMessage() {
        // Capped, because a stranger can cause it — the opposite choice from
        // the password-change notice (risk D8), which is deliberately not
        // capped because only the holder can cause that one.
        account(true, true);

        for (int i = 0; i < 4; i++) {
            accounts.matches("ada", "wrong-" + i, "203.0.113.7");
        }

        verify(email, times(1)).sendAccountLocked(anyString(), any());
    }

    @Test
    void anUnverifiedAddressIsNotWrittenTo() {
        // It has not been shown to belong to the holder; the sign-up
        // confirmation is the only thing that should ever reach it.
        account(false, true);

        assertThat(accounts.matches("ada", "wrong-password", "203.0.113.7")).isFalse();

        verify(email, never()).sendAccountLocked(anyString(), any());
    }

    @Test
    void aMailerThatThrowsDoesNotBreakTheRefusal() {
        // The lock is already in place by the time this is sent. A mail
        // server that is down must not turn a refused sign-in into a 500.
        account(true, true);
        doThrow(new IllegalStateException("no mail server"))
                .when(email).sendAccountLocked(anyString(), any());

        assertThat(accounts.matches("ada", "wrong-password", "203.0.113.7"))
                .as("still just a refused sign-in").isFalse();

        verify(repo).incrementFailedLogins(any());
    }

    @Test
    void aServerWithNoMailerConfiguredSimplySendsNothing() {
        DbUserAccounts withoutMail = new DbUserAccounts(repo, failures);
        account(true, true);

        assertThat(withoutMail.matches("ada", "wrong-password", "203.0.113.7")).isFalse();

        verify(email, never()).sendAccountLocked(anyString(), any());
    }

    @Test
    void theMessageCarriesNoLinkAndSaysWhenItLifts() {
        Instant until = Instant.parse("2026-10-22T14:32:00Z");

        String body = AccountEmailTemplate.lockedOutText(until, "SignFlow");

        assertThat(body)
                .as("a notice that something is wrong is what a phishing link imitates")
                .doesNotContain("http://").doesNotContain("https://");
        assertThat(body).contains("unlocks by itself");
        assertThat(body)
                .as("and it says plainly that the password itself has not been given up")
                .contains("They\nhave not got in");
        assertThat(AccountEmailTemplate.lockedOutSubject("SignFlow"))
                .isEqualTo("Your SignFlow account is temporarily locked");
    }

    @Test
    void theMessageDoesNotNameTheAddressTheAttemptsCameFrom() {
        // The server knows it. Telling the holder is worth less than it looks
        // — usually a proxy or a shared NAT address, possibly an innocent
        // third party, and nothing they can act on.
        String body = AccountEmailTemplate.lockedOutText(
                Instant.parse("2026-10-22T14:32:00Z"), "SignFlow");

        assertThat(body).doesNotContain("203.0.113").doesNotContain("address");
    }

    @Test
    void theNoticeCapIsItsOwnBudgetAndSpendsNoOtherLimit() {
        // One store, namespaced buckets (§6): the notice cap must not be
        // able to exhaust the sign-in limit for the same address, or an
        // attacker could lock an address out by provoking notices.
        account(true, true);
        accounts.matches("ada", "wrong-password", "203.0.113.7");

        assertThat(rateLimits.retryAfterSeconds("addr:203.0.113.7", 20,
                Duration.ofMinutes(1)))
                .as("the per-address sign-in bucket is untouched by the notice")
                .isZero();
    }
}
