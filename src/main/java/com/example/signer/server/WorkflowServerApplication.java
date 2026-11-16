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
 * {@code WebApplicationType.NONE}; this one is a servlet app, and it serves
 * everything this product exposes over HTTP:
 *
 * <ul>
 *   <li>the desktop's bearer-token API — {@code /api/auth/*},
 *       {@code /api/workflows/**}, {@code /api/desktop/*},
 *       {@code /api/health};</li>
 *   <li>the website's session API, {@code /web/api/**}, and the website
 *       itself (the Next.js export under {@code resources/static}, routed by
 *       {@code server.web.FrontendRoutingConfiguration});</li>
 *   <li>the desktop installer, {@code /downloads/*}.</li>
 * </ul>
 *
 * <p>Which of those need a credential is not decided here: the two filter
 * chains in {@code server.security} are the authority, and both end in a
 * refusal, so a route is protected until it is opened there.
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
