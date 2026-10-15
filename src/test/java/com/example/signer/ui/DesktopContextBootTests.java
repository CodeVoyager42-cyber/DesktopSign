package com.example.signer.ui;

import com.example.signer.SignerApplication;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.token.TokenService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the desktop launcher failure: the Spring context died
 * at {@link SignerFxApplication#init()} with
 * {@code NoSuchBeanDefinitionException: InvitationService} because the old
 * {@code WorkflowRestController} had a hard constructor dependency on a
 * database-only bean. The fix (ObjectProvider in the controller) plus the
 * restored full component scan are proven here by booting the EXACT context
 * {@code init()} builds — SpringApplication over SignerApplication, profile
 * "desktop", WebApplicationType.NONE — and asserting the previously failing
 * beans resolve: desktop beans present, database-only beans absent.
 */
class DesktopContextBootTests {

    @Test
    void desktopContextBootsPastFormerInitFailurePoint() {
        ConfigurableApplicationContext context = null;
        try {
            // Mirrors SignerFxApplication.init() — same sources, same flags.
            SpringApplication app = new SpringApplication(SignerApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setAdditionalProfiles("desktop");
            context = app.run();

            // Desktop beans resolve (the ui-only scan had lost these).
            assertThat(context.getBean(TokenService.class)).isNotNull();

            // Database-only beans stay absent in the desktop context.
            assertThat(context.getBeanProvider(InvitationService.class).getIfAvailable())
                    .as("InvitationService must not exist in the desktop context")
                    .isNull();

            // The controller that used to hard-depend on InvitationService
            // is present and injectable — the exact former failure point.
            assertThat(context.getBean(
                    com.example.signer.server.api.WorkflowRestController.class))
                    .isNotNull();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }
}
