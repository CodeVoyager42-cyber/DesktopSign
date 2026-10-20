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
        for (var entry : properties.getUsers().entrySet()) {
            String username = entry.getKey().toLowerCase(Locale.ROOT);
            if (users.existsByUsernameIgnoreCase(username)) {
                // Development accounts count as e-mail-verified (Phase B:
                // launch codes require a verified address).
                users.findByUsername(username).filter(u -> u.getEmailVerifiedAt() == null)
                        .ifPresent(u -> u.setEmailVerifiedAt(Instant.now()));
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
        if (created) {
            log.info("Dev seed complete — configuration test users are now"
                    + " database-backed (bcrypt)");
        }
    }

    private static String devEmail(String username) {
        return username + "@example.test";
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
