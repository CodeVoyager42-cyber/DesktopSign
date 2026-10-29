package com.example.signer.server.db;

import org.springframework.context.annotation.Profile;
import com.example.signer.server.RemoteServerProperties;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Development seed (brief §46): migrates the configuration test accounts
 * (alice / bob / charlie) into the database on first DB-mode startup. Rows
 * are created only when absent; passwords are bcrypt-hashed immediately.
 *
 * <p>This is a DEVELOPMENT convenience, gated on the existing dev flag
 * {@code allow-plaintext-passwords=true} — the same flag that already allows
 * plaintext config passwords. Production posture (flag = false) never seeds
 * and never auto-hashes.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class DevUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final UserRepository users;
    private final RemoteServerProperties properties;

    public DevUserSeeder(UserRepository users, RemoteServerProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isAllowPlaintextPasswords()) {
            return; // production posture: no seeding from config
        }
        boolean created = false;
        boolean completed = false;
        for (var entry : properties.getUsers().entrySet()) {
            String username = entry.getKey().toLowerCase(Locale.ROOT);
            UserEntity existing = users.findByUsername(username).orElse(null);
            if (existing != null) {
                // FileStoreImporter runs before this seeder and creates
                // PENDING_INVITE placeholders — no e-mail, no password — for
                // the signers of imported legacy workflows. A dev account can
                // therefore already exist as an unusable stub; complete it
                // instead of skipping it, or it can never sign in.
                if (isPlaceholder(existing)) {
                    if (existing.getEmail() == null || existing.getEmail().isBlank()) {
                        existing.setEmail(devEmail(username));
                    }
                    existing.changePasswordHash(
                            PasswordHashing.hash(entry.getValue()), Instant.now());
                    existing.setStatus(AccountStatus.ACTIVE);
                    existing.setEmailVerifiedAt(Instant.now());
                    completed = true;
                    log.info("Dev seed: completed placeholder account '{}'", username);
                } else if (existing.getEmailVerifiedAt() == null) {
                    // Development accounts count as e-mail-verified (Phase B:
                    // launch codes require a verified address).
                    existing.setEmailVerifiedAt(Instant.now());
                }
                continue;
            }
            UserEntity user = new UserEntity();
            user.setUsername(username);
            user.setEmail(devEmail(username));
            user.setDisplayName(capitalize(username));
            user.changePasswordHash(PasswordHashing.hash(entry.getValue()), Instant.now());
            user.setStatus(AccountStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            users.save(user);
            created = true;
            log.info("Dev seed: created account '{}' ({}@…)", username, username);
        }
        if (created || completed) {
            log.info("Dev seed complete — configuration test users are now"
                    + " database-backed (bcrypt)");
        }
    }

    /**
     * Whether {@code user} is an unusable stub rather than a real account:
     * invited-but-never-registered AND without a password. Both conditions
     * are required, so a registered account (it has a password) and an
     * active one are never touched.
     */
    private static boolean isPlaceholder(UserEntity user) {
        return user.getStatus() == AccountStatus.PENDING_INVITE
                && user.getPasswordHash() == null;
    }

    private static String devEmail(String username) {
        return username + "@example.test";
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
