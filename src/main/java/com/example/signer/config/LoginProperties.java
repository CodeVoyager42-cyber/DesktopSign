package com.example.signer.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for the external {@code signature.login.*} configuration block,
 * backing the application's start-up login gate.
 *
 * <p>Defaults are {@code ISICOD} / {@code ISICOD} for development; in a real
 * deployment set {@code SIGNATURE_LOGIN_USERNAME} and
 * {@code SIGNATURE_LOGIN_PASSWORD} through the environment. Comparison is a
 * constant-time equality so log timing leaks nothing about the secret.</p>
 */
@ConfigurationProperties(prefix = "signature.login")
public class LoginProperties {

    private String username = "ISICOD";
    private String password = "ISICOD";

    /** Desktop start-up gate user (LOCAL app launch, unrelated to the server). */
    private String serverUser = "coordinator";
    /** Desktop start-up gate password (LOCAL app launch, unrelated to the server). */
    private String serverPassword = "coordinator";

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getServerUser() {
        return serverUser;
    }

    public void setServerUser(String serverUser) {
        this.serverUser = serverUser;
    }

    public String getServerPassword() {
        return serverPassword;
    }

    public void setServerPassword(String serverPassword) {
        this.serverPassword = serverPassword;
    }

    /**
     * Constant-time credential check: every byte is compared even after a
     * mismatch, so response timing does not reveal how much was correct.
     */
    public boolean matches(String username, String password) {
        return constantTimeEquals(this.username, username)
                && constantTimeEquals(this.password, password);
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            diff |= a[i % a.length] ^ b[i % b.length];
        }
        return diff == 0;
    }
}
