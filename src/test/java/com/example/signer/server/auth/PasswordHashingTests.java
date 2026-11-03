package com.example.signer.server.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 1: the single password hashing policy. */
class PasswordHashingTests {

    @Test
    void newHashesUseCost12AndVerify() {
        String hash = PasswordHashing.hash("correct horse");
        assertThat(hash).startsWith("$2a$12$").hasSize(60);
        assertThat(PasswordHashing.matches("correct horse", hash)).isTrue();
        assertThat(PasswordHashing.matches("correct horsE", hash)).isFalse();
        assertThat(PasswordHashing.needsRehash(hash)).isFalse();
    }

    @Test
    void sameInputGivesDifferentSaltedHashes() {
        assertThat(PasswordHashing.hash("same-password"))
                .isNotEqualTo(PasswordHashing.hash("same-password"));
    }

    @Test
    void olderCheaperHashesStillVerifyAndAskForRehash() {
        String legacy = BCrypt.hashpw("legacy-pass", BCrypt.gensalt(10));
        assertThat(PasswordHashing.matches("legacy-pass", legacy)).isTrue();
        assertThat(PasswordHashing.needsRehash(legacy)).isTrue();
    }

    @Test
    void missingOrMalformedHashesNeverMatchAndNeverThrow() {
        assertThat(PasswordHashing.matches("x", null)).isFalse();
        assertThat(PasswordHashing.matches("x", "")).isFalse();
        assertThat(PasswordHashing.matches("alice-pass", "alice-pass")).isFalse(); // plaintext
        assertThat(PasswordHashing.matches("x", "$2a$12$tooShort")).isFalse();
        assertThat(PasswordHashing.matches(null, PasswordHashing.hash("password1"))).isFalse();
        assertThat(PasswordHashing.needsRehash(null)).isFalse();
        assertThat(PasswordHashing.needsRehash("not-a-hash")).isFalse();
    }

    @Test
    void newPasswordRulesRejectShortAndOver72Bytes() {
        assertThat(PasswordHashing.validateNewPassword(null)).isNotNull();
        assertThat(PasswordHashing.validateNewPassword("1234567")).isNotNull();
        assertThat(PasswordHashing.validateNewPassword("12345678")).isNull();
        assertThat(PasswordHashing.validateNewPassword("a".repeat(72))).isNull();
        assertThat(PasswordHashing.validateNewPassword("a".repeat(73))).isNotNull();
        // 36 two-byte characters = 72 bytes: fine; 37 = 74 bytes: refused.
        assertThat(PasswordHashing.validateNewPassword("é".repeat(36))).isNull();
        assertThat(PasswordHashing.validateNewPassword("é".repeat(37))).isNotNull();
    }

    @Test
    void validationMessagesNeverContainThePassword() {
        String secret = "s3cr3t-" + "z".repeat(80);
        assertThat(PasswordHashing.validateNewPassword(secret)).doesNotContain(secret).doesNotContain("s3cr3t");
        assertThatThrownBy(() -> PasswordHashing.hash(secret))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cr3t"));
    }

    @Test
    void hashRefusesNullAndTruncatingInput() {
        assertThatThrownBy(() -> PasswordHashing.hash(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordHashing.hash("a".repeat(73))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recognisesBcryptFormat() {
        assertThat(PasswordHashing.isBcrypt(PasswordHashing.hash("password1"))).isTrue();
        assertThat(PasswordHashing.isBcrypt(BCrypt.hashpw("p", BCrypt.gensalt(4, new java.security.SecureRandom()))
                .replace("$2a$", "$2b$"))).isTrue();
        assertThat(PasswordHashing.isBcrypt("{noop}password")).isFalse();
    }
}
