package com.example.signer.server;

import org.springframework.scheduling.annotation.EnableScheduling;
import com.example.signer.config.LoginProperties;
import com.example.signer.config.Pkcs11Properties;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of the coordination server (REMOTE signing mode).
 *
 * <p>Runs as its own process from the same artifact as the desktop:
 * {@code --spring.profiles.active=server}. The desktop context stays
 * {@code WebApplicationType.NONE}; this context is a servlet app exposing
 * only {@code /api/workflows/*}.
 *
 * <p>Component scanning covers the whole application, so the server reuses
 * the PDF verification and portable-manifest services directly. The token
 * beans exist (mock by default) but are never used server-side: the server
 * never authenticates a token and never touches a private key.
 */
@SpringBootApplication(scanBasePackages = "com.example.signer")
@ConfigurationPropertiesScan(basePackageClasses = RemoteServerProperties.class)
@EnableConfigurationProperties({LoginProperties.class, Pkcs11Properties.class})
@EnableScheduling
public class WorkflowServerApplication {

    public static void main(String[] args) {
        start(args);
    }

    /** Starts the server programmatically (used by integration tests). */
    public static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(WorkflowServerApplication.class)
                .profiles("server")
                .run(args);
    }
}
