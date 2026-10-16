package com.example.signer.config;

import com.example.signer.SignerApplication;
import com.example.signer.server.RemoteServerProperties;
import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.auth.AuthController;
import com.example.signer.token.TokenService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Spring startups WITHOUT the {@code dev} profile (the test classpath
 * normally adds {@code dev}; these contexts read only the production
 * {@code application.yml}). They prove the production posture end to end:
 * no JWT secret → no server; no PKCS#11 token → no desktop and no mock
 * fallback; no default accounts.
 */
class ProductionStartupTests {

    private static final String JWT_SECRET = "prod-test-secret-0123456789abcdef0123456789";

    private static List<String> serverArgs(String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.location=classpath:/application.yml",
                "--server.port=0",
                "--signature.remote-server.storage="
                        + Files.createTempDirectory("prod-startup").toString(),
                "--signature.remote-server.tls-offloaded=true",
                "--signature.remote-server.database.enabled=false",
                "--logging.file.name="));
        args.addAll(List.of(extra));
        return args;
    }

    private static ConfigurableApplicationContext startServer(List<String> args) {
        return new SpringApplicationBuilder(WorkflowServerApplication.class)
                .profiles("server")
                .run(args.toArray(String[]::new));
    }

    @Test
    void productionServerWithoutAJwtSecretRefusesToStart() throws Exception {
        assertThatThrownBy(() -> startServer(serverArgs(
                "--signature.remote-server.jwt-secret=")).close())
                .hasStackTraceContaining("SIGNER_SERVER_JWT_SECRET");
    }

    @Test
    void productionServerWithoutTlsRefusesToStart() throws Exception {
        List<String> args = serverArgs("--signature.remote-server.jwt-secret=" + JWT_SECRET);
        args.remove("--signature.remote-server.tls-offloaded=true");
        assertThatThrownBy(() -> startServer(args).close())
                .hasStackTraceContaining("TLS is not configured");
    }

    @Test
    void productionServerHasNoDefaultAccounts() throws Exception {
        try (ConfigurableApplicationContext ctx = startServer(serverArgs(
                "--signature.remote-server.jwt-secret=" + JWT_SECRET))) {
            assertThat(ctx.getBean(RemoteServerProperties.class).getUsers()).isEmpty();
            assertThat(ctx.getBean(RemoteServerProperties.class).isAllowPlaintextPasswords())
                    .isFalse();
            assertThat(ctx.getBean(RemoteServerProperties.class).isAcceptDevelopmentIdentities())
                    .isFalse();
            AuthController auth = ctx.getBean(AuthController.class);
            for (String[] known : new String[][] {{"alice", "alice-pass"},
                    {"bob", "bob-pass"}, {"charlie", "charlie-pass"}}) {
                assertThat(auth.login(new LoginRequest(known[0], known[1]))
                        .getStatusCode().value()).isEqualTo(401);
            }
            assertThat(ctx.getBeanNamesForType(
                    com.example.signer.server.db.DevUserSeeder.class)).isEmpty();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIf(
            value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
            disabledReason = "PostgreSQL not reachable")
    void productionServerOnAFreshDatabaseSeedsNoAccounts() throws Exception {
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        String db = "signflow_prod_check";
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/postgres", user, password);
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + db);
            st.execute("CREATE DATABASE " + db);
        }
        List<String> args = serverArgs(
                "--signature.remote-server.jwt-secret=" + JWT_SECRET,
                "--signature.remote-server.database.enabled=true",
                "--signature.remote-server.database.url=jdbc:postgresql://localhost:5432/" + db,
                "--signature.remote-server.database.username=" + user,
                "--signature.remote-server.database.password=" + password,
                "--signature.remote-server.invitation.public-base-url=https://signflow.example.com",
                "--signature.remote-server.email.mode=smtp",
                "--spring.mail.host=localhost");
        args.remove("--signature.remote-server.database.enabled=false");
        try (ConfigurableApplicationContext ctx = startServer(args)) {
            assertThat(ctx.getBeanNamesForType(
                    com.example.signer.server.db.DevUserSeeder.class)).isEmpty();
            assertThat(ctx.getBean(com.example.signer.server.db.repository.UserRepository.class)
                    .count()).isZero();
            assertThat(ctx.getBean(AuthController.class)
                    .login(new LoginRequest("alice", "alice-pass")).getStatusCode().value())
                    .isEqualTo(401);
        }
    }

    @Test
    void productionServerRefusesPlaintextPasswordsEvenIfConfigured() throws Exception {
        assertThatThrownBy(() -> startServer(serverArgs(
                "--signature.remote-server.jwt-secret=" + JWT_SECRET,
                "--signature.remote-server.users.alice=alice-pass")).close())
                .hasStackTraceContaining("plaintext");
    }

    @Test
    void productionDesktopWithoutAPkcs11TokenRefusesToStartInsteadOfUsingTheMock() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(SignerApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("desktop")
                .run("--spring.config.location=classpath:/application.yml",
                        "--signature.pkcs11.enabled=false",
                        "--logging.file.name=")
                .close())
                .hasStackTraceContaining("No signing token is configured");
    }

    @Test
    void withoutDevAndWithoutValidationTheTokenLayerStillHasNoMockFallback() {
        // A non-desktop, non-dev context (validator has nothing to check):
        // still no mock bean outside 'dev' — detection fails with the
        // configuration error instead of handing out a software key.
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SignerApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("desktop-test-no-validation")
                .run("--spring.config.location=classpath:/application.yml",
                        "--signature.pkcs11.enabled=false",
                        "--logging.file.name=")) {
            assertThat(ctx.getBeanNamesForType(
                    com.example.signer.token.mock.MockSigningToken.class)).isEmpty();
            TokenService tokens = ctx.getBean(TokenService.class);
            assertThatThrownBy(tokens::detect)
                    .hasMessageContaining("No signing token is configured");
        }
    }
}
