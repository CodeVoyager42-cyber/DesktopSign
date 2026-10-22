package com.example.signer.server.auth;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.LoginFailureRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.web.WebAuthController;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 risk L3, from the request down: that the address a sign-in
 * actually arrived from is the one the lock is keyed on.
 *
 * <p>{@link DbUserAccountsTests} covers the policy with mocks and
 * {@link AuthenticationPersistenceLiveTests} covers it against the real
 * table. Neither can show the <b>plumbing</b> — that the controllers pass the
 * right address, that both sign-in routes count against the same pair, and
 * that a client cannot choose its own lockout key with a forwarded header.
 * That is what this class is for, which is why it drives the controllers with
 * requests whose remote address it sets rather than calling the service.
 */
// MOCK rather than NONE: WebAuthController is @ConditionalOnWebApplication,
// and one of the claims here is that the website counts against the same pair
// as the desktop API, so the website's controller has to exist. Nothing here
// goes through a filter chain — the controllers are called directly, with
// requests whose remote address the test sets.
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-l3-lockout-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + LoginLockoutPerAddressLiveTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.max-failures=5",
                "signature.remote-server.login.account-max-failures=50",
                "signature.remote-server.login.lock-minutes=15",
                "signature.remote-server.jwt-secret=l3-lockout-test-secret-at-least-32-bytes"
        })
// The server profile is what makes this a servlet context
// (spring.main.web-application-type), and so what makes the website's
// controller exist beside the API's.
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.auth.LoginLockoutPerAddressLiveTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class LoginLockoutPerAddressLiveTests {

    static final String DB = "signflow_l3_lockout_test";

    private static final String PASSWORD = "correct-password-1";
    private static final String ATTACKER = "203.0.113.7";
    private static final String HOLDER = "198.51.100.4";

    @Autowired
    private AuthController api;

    @Autowired
    private WebAuthController website;

    @Autowired
    private UserRepository users;

    @Autowired
    private LoginFailureRepository failures;

    public static boolean freshDatabase() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DB);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */

    @Test
    void theDesktopApiLocksTheAddressTheRequestCameFromNotTheAccount() {
        String name = newAccount();

        for (int i = 0; i < 5; i++) {
            assertThat(apiLogin(name, "wrong-" + i, ATTACKER)).isEqualTo(401);
        }

        assertThat(pairLock(name, ATTACKER))
                .as("the address that sent them is locked out")
                .isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(users.findByUsername(name).orElseThrow().getLockedUntil())
                .as("and the account is not, so the holder is not locked out").isNull();

        assertThat(apiLogin(name, PASSWORD, ATTACKER))
                .as("the right password from the locked address is still refused")
                .isEqualTo(401);
        assertThat(apiLogin(name, PASSWORD, HOLDER))
                .as("the holder signs in from their own address").isEqualTo(200);
    }

    @Test
    void theWebsiteAndTheDesktopApiCountAgainstTheSamePair() {
        // They check the same passwords against the same accounts, so two
        // independent budgets would mean ten attempts per address instead of
        // five — the same reasoning that makes the rate-limit filter a
        // shared singleton.
        String name = newAccount();

        for (int i = 0; i < 3; i++) {
            assertThat(apiLogin(name, "wrong-" + i, ATTACKER)).isEqualTo(401);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(webLogin(name, "wrong-web-" + i, ATTACKER)).isEqualTo(401);
        }

        assertThat(failures.findByUsernameAndClientAddress(name, ATTACKER))
                .as("one row, not one per route").isPresent();
        assertThat(pairLock(name, ATTACKER))
                .as("three from the API and two from the website spent one budget of five")
                .isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(webLogin(name, PASSWORD, ATTACKER))
                .as("and the lock applies to the route that did not fill it")
                .isEqualTo(401);
    }

    @Test
    void aForwardedHeaderCannotChooseTheLockoutKey() {
        // If it could, an attacker would simply send a new one every five
        // attempts and have no lockout at all. The remote address is the
        // only thing trusted here, exactly as in the rate-limit filter.
        String name = newAccount();

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(ATTACKER);
            request.addHeader("X-Forwarded-For", "10.0.0." + i);
            assertThat(api.login(new LoginRequest(name, "wrong-" + i), request)
                    .getStatusCode().value()).isEqualTo(401);
        }

        assertThat(failures.findAll())
                .as("five different forwarded addresses, one real one")
                .filteredOn(f -> f.getUsername().equals(name))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.getClientAddress()).isEqualTo(ATTACKER));
        assertThat(apiLogin(name, PASSWORD, ATTACKER)).isEqualTo(401);
    }

    @Test
    void theSweepForgetsIdlePairsAndKeepsTheRestOfTheAccountIntact() {
        String name = newAccount();
        assertThat(apiLogin(name, "wrong", ATTACKER)).isEqualTo(401);
        assertThat(failures.findByUsernameAndClientAddress(name, ATTACKER)).isPresent();

        // A day old, so the hourly sweep is due to forget it. Aged in SQL
        // rather than by waiting.
        exec("UPDATE login_failures SET last_failure_at = now() - INTERVAL '2 days'"
                + " WHERE username = '" + name + "'");

        accounts().deleteIdleLoginFailures();

        assertThat(failures.findByUsernameAndClientAddress(name, ATTACKER))
                .as("the address is not kept for ever after one attempt").isEmpty();
        assertThat(users.findByUsername(name))
                .as("and the account itself is untouched by housekeeping").isPresent();
    }

    @Test
    void aDeletedAccountTakesItsFailureRowsWithIt() {
        // ON DELETE CASCADE, like refresh tokens and the one-time tokens: an
        // account's rows are its own, and the account sweep (risk D11) must
        // not be blocked by them.
        String name = newAccount();
        assertThat(apiLogin(name, "wrong", ATTACKER)).isEqualTo(401);

        users.delete(users.findByUsername(name).orElseThrow());

        assertThat(failures.findByUsernameAndClientAddress(name, ATTACKER)).isEmpty();
    }

    /* ------------------------------ helpers ----------------------------- */

    private DbUserAccounts accounts() {
        return new DbUserAccounts(users, failures);
    }

    private String newAccount() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserEntity(name, name + "@example.test",
                PasswordHashing.hash(PASSWORD), name, AccountStatus.ACTIVE));
        return name;
    }

    private Instant pairLock(String name, String address) {
        return failures.findByUsernameAndClientAddress(name, address)
                .orElseThrow().getLockedUntil();
    }

    private int apiLogin(String user, String password, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return api.login(new LoginRequest(user, password), request)
                .getStatusCode().value();
    }

    /**
     * Only ever called for attempts that are expected to fail, which is all
     * this needs: a refused sign-in answers before the session and CSRF
     * handling a success would do, so no servlet container is required.
     */
    private int webLogin(String user, String password, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return website.login(new WebAuthController.LoginRequest(user, password),
                request, new MockHttpServletResponse()).getStatusCode().value();
    }

    private void exec(String sql) {
        String url = "jdbc:postgresql://localhost:5432/" + DB;
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
