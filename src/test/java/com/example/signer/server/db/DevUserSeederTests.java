package com.example.signer.server.db;

import com.example.signer.server.RemoteServerProperties;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dev seed, without a database. {@code FileStoreImporter} runs before the
 * seeder and creates PENDING_INVITE placeholders (no e-mail, no password) for
 * the signers of imported legacy workflows, so a dev account can already
 * exist as an unusable stub.
 */
class DevUserSeederTests {

    private static final String DEV_PASSWORD = "dev-password-123";

    private UserRepository users;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
    }

    private DevUserSeeder seeder(boolean allowPlaintext, String username) {
        RemoteServerProperties properties = new RemoteServerProperties();
        properties.setAllowPlaintextPasswords(allowPlaintext);
        properties.getUsers().put(username, DEV_PASSWORD);
        return new DevUserSeeder(users, properties);
    }

    private UserEntity placeholder(String username) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setStatus(AccountStatus.PENDING_INVITE);
        return user;
    }

    @Test
    void aPlaceholderFromTheFileImportIsCompletedInsteadOfSkipped() {
        UserEntity existing = placeholder("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(existing));

        seeder(true, "alice").run(null);

        assertThat(existing.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(existing.getEmail()).isEqualTo("alice@example.test");
        assertThat(existing.getEmailVerifiedAt()).isNotNull();
        assertThat(existing.getPasswordHash()).isNotNull();
        assertThat(PasswordHashing.matches(DEV_PASSWORD, existing.getPasswordHash()))
                .as("the configured dev password now signs in")
                .isTrue();
        verify(users, never()).save(any());
    }

    @Test
    void anExistingUsableAccountIsNeverOverwritten() {
        String ownHash = PasswordHashing.hash("the-real-password");
        UserEntity real = new UserEntity("bob", "bob@company.example", ownHash, "Bob",
                AccountStatus.ACTIVE);
        real.setEmailVerifiedAt(Instant.now());
        when(users.findByUsername("bob")).thenReturn(Optional.of(real));

        seeder(true, "bob").run(null);

        assertThat(real.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(real.getEmail()).isEqualTo("bob@company.example");
        assertThat(real.getPasswordHash()).isEqualTo(ownHash);
        verify(users, never()).save(any());
    }

    @Test
    void anInvitedAccountThatAlreadySetItsPasswordIsNotTouched() {
        // PENDING_INVITE but WITH a password: a registration in progress, not
        // an importer stub. Both conditions are required to complete a row.
        String ownHash = PasswordHashing.hash("chosen-at-registration");
        UserEntity invited = placeholder("charlie");
        invited.changePasswordHash(ownHash, Instant.now());
        when(users.findByUsername("charlie")).thenReturn(Optional.of(invited));

        seeder(true, "charlie").run(null);

        assertThat(invited.getStatus()).isEqualTo(AccountStatus.PENDING_INVITE);
        assertThat(invited.getEmail()).isNull();
        assertThat(invited.getPasswordHash()).isEqualTo(ownHash);
    }

    @Test
    void anAbsentAccountIsStillCreated() {
        seeder(true, "alice").run(null);

        verify(users).save(any(UserEntity.class));
    }

    @Test
    void productionPostureCompletesNothing() {
        UserEntity existing = placeholder("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(existing));

        seeder(false, "alice").run(null);

        assertThat(existing.getStatus()).isEqualTo(AccountStatus.PENDING_INVITE);
        assertThat(existing.getPasswordHash()).isNull();
        assertThat(existing.getEmail()).isNull();
        verify(users, never()).save(any());
    }
}
