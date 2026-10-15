package com.example.signer.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transparently upgrades plaintext development passwords to bcrypt hashes at
 * server startup (Phases 3–4). Values in
 * {@code signature.remote-server.users} may be configured as plaintext for
 * development convenience (e.g. the built-in {@code alice-pass} defaults);
 * this component rewrites them in place to {@code $2a$...} hashes so the
 * running server never holds — or verifies against — plaintext secrets.
 *
 * <p>Production posture: set
 * {@code signature.remote-server.allow-plaintext-passwords=false}. Startup
 * then FAILS if any configured value is still plaintext, forcing operators
 * to pre-hash (e.g. {@code bcrypt(16, "secret")} output) or use dev mode.
 *
 * <p>Plaintext values are never logged; only counts and user ids appear in
 * the log.
 */
@Component
public class DevPasswordHasher implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DevPasswordHasher.class);

    private final RemoteServerProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public DevPasswordHasher(RemoteServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Map<String, String> users = properties.getUsers();
        Map<String, String> upgraded = new LinkedHashMap<>();
        int hashed = 0;
        for (Map.Entry<String, String> e : users.entrySet()) {
            String value = e.getValue();
            if (value != null && value.startsWith("$2")) {
                upgraded.put(e.getKey(), value);
                continue;
            }
            upgraded.put(e.getKey(), encoder.encode(value == null ? "" : value));
            hashed++;
        }
        if (hashed > 0 && !properties.isAllowPlaintextPasswords()) {
            throw new IllegalStateException(
                    "signature.remote-server.allow-plaintext-passwords=false"
                            + " but " + hashed + " user password(s) are configured as"
                            + " plaintext. Pre-hash them with bcrypt before starting"
                            + " in production mode.");
        }
        if (hashed > 0) {
            users.putAll(upgraded);
            log.info("Dev password hashing: {} plaintext password(s) upgraded to"
                    + " bcrypt for users {}", hashed, users.keySet());
        }
    }
}
