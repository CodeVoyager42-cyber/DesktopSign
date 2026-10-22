package com.example.signer.server.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the website from <b>this application's own origin</b> (security
 * Phase D1 risk D2).
 *
 * <h2>Why same-origin</h2>
 *
 * <p>Because the session cookie already is. It is named with the
 * {@code __Host-} prefix and sent {@code Secure} and {@code SameSite=Lax}
 * (Phase C), and that combination only works when the pages using it come
 * from the same host. A frontend on a second origin — a separate dev server,
 * a CDN, another hostname — would have forced {@code SameSite=None}, dropped
 * the {@code __Host-} prefix, and needed CORS with credentials: three
 * weakenings of a posture that is already built and tested, in exchange for
 * nothing the deployment needs. So there is <b>no CORS configuration in this
 * application</b>, and that is the decision rather than an omission.
 *
 * <h2>What is routed here</h2>
 *
 * <p>Static files under {@code src/main/resources/static} are served by Spring
 * Boot's own resource handling. This class adds only the <b>named</b>
 * application routes that have no file of their own — the two pages the
 * account e-mails link to — by forwarding them to the single entry point.
 *
 * <p>Deliberately a list and <b>not</b> a catch-all SPA fallback. A
 * {@code /**} forward is the usual shortcut and it is a trap here: it would
 * answer every mistyped API path with an HTML page and a 200, turning what
 * should be a clean 404 into something a client cannot tell from success. A
 * new page in the website means a line added here, which is a fair price for
 * keeping 404s honest.
 *
 * <p>The forwarded token is safe to carry in the URL as far as this goes: the
 * {@code Referrer-Policy: no-referrer} header added for risk L9 covers these
 * pages too, so neither the fetches the page makes nor the links the reader
 * follows can take the token elsewhere.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FrontendRoutingConfiguration implements WebMvcConfigurer {

    /** Confirming an e-mail address — the link in the verification e-mail. */
    public static final String VERIFY_EMAIL_PAGE = "/verify-email";

    /** Setting a new password — the link in the reset e-mail. */
    public static final String RESET_PASSWORD_PAGE = "/reset-password";

    /** The one document the website is served from. */
    static final String ENTRY_POINT = "forward:/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController(VERIFY_EMAIL_PAGE).setViewName(ENTRY_POINT);
        registry.addViewController(RESET_PASSWORD_PAGE).setViewName(ENTRY_POINT);
    }
}
