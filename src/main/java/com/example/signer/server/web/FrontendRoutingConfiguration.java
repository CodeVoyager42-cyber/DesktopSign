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

    /** Signing in. */
    public static final String SIGN_IN_PAGE = "/sign-in";

    /** Creating an account. */
    public static final String SIGN_UP_PAGE = "/sign-up";

    /** Asking for a password-reset link. */
    public static final String FORGOT_PASSWORD_PAGE = "/forgot-password";

    /**
     * Changing a password while signed in. A page of its own rather than a
     * panel on the account screen, because the design keeps one primary
     * action per screen — and because it is then a link somebody can be
     * sent. Whether the visitor is signed in is decided by the server when
     * the page asks {@code /web/api/auth/me}, not by this route: a page is
     * not an authorisation.
     */
    public static final String CHANGE_PASSWORD_PAGE = "/change-password";

    /** The account screen. */
    public static final String ACCOUNT_PAGE = "/account";

    /**
     * The document dashboard — the screen somebody signing in is going to.
     * {@code /} is the public page that explains the product, as every site
     * of this kind has; the dashboard is a path of its own behind it.
     */
    public static final String DOCUMENTS_PAGE = "/documents";

    /**
     * One document: where it stands, and its history in order.
     *
     * <p>The workflow is named in the query string — {@code
     * /document?id=<workflowId>} — and not as a path segment, because the
     * website is a static export and {@code /documents/<id>} would need a
     * page per workflow (they are created at runtime) or a catch-all
     * forward. The reasoning against a catch-all is in this class's own
     * comment and has not changed: it would answer every mistyped path with
     * an HTML 200. The id is not a credential, and the page is no more an
     * authorisation than any other here — the server decides what it may
     * read when it asks for the workflow, and answers a stranger with the
     * same 404 as for a workflow that does not exist.
     */
    public static final String DOCUMENT_PAGE = "/document";

    /**
     * Sending a document out: the file, the signers, the order.
     *
     * <p>The one page that uploads anything, and the only website route that
     * is multipart. It needs no id of its own — what it creates is identified
     * by the answer, which is why it ends by going to {@link #DOCUMENT_PAGE}
     * for the workflow it just started.
     */
    public static final String SEND_PAGE = "/send";

    /**
     * The hand-off: the screen that opens the desktop application at one
     * signing task (Phase F).
     *
     * <p>Takes the workflow in the query string like {@link #DOCUMENT_PAGE},
     * for the same reason. It holds no code of its own — it asks for one when
     * it loads, and that code is good for two minutes — so the path is not
     * worth bookmarking and nothing is lost if it is.
     *
     * <p><b>Not {@code /sign}</b>, although that is what the button says.
     * {@code /sign/{token}} is already the public, server-rendered invitation
     * page, and a second meaning one segment away from it is the kind of
     * neighbourhood where a matcher written {@code /sign*} quietly covers a
     * page nobody meant it to. {@code /open} also says what the screen does.
     */
    public static final String OPEN_PAGE = "/open";

    /**
     * Where to get the desktop application (Phase F, finding F2).
     *
     * <p>Public, and the only signed-in-looking page that is: an invited
     * signer has no account yet and cannot be asked to make one before
     * installing the application that signs. The page itself reveals nothing
     * — a version, a size and a checksum — and the file it links to is
     * public for the same reason.
     */
    public static final String DOWNLOAD_PAGE = "/download";

    /**
     * Every path forwarded to the website. {@code SecurityConfig.PUBLIC}
     * reads this array, so adding a page is one line here and nothing else —
     * which also means the two lists cannot disagree about what is reachable.
     */
    public static final String[] PAGES = {
        VERIFY_EMAIL_PAGE, RESET_PASSWORD_PAGE,
        SIGN_IN_PAGE, SIGN_UP_PAGE, FORGOT_PASSWORD_PAGE, CHANGE_PASSWORD_PAGE,
        ACCOUNT_PAGE, DOCUMENTS_PAGE, DOCUMENT_PAGE, SEND_PAGE, OPEN_PAGE, DOWNLOAD_PAGE,
    };

    /**
     * The file a page is served from: {@code /sign-in} → {@code
     * /sign-in.html}.
     *
     * <p>Each page is its own document now. The website used to be one
     * {@code index.html} that built every view in the browser, so every path
     * forwarded to that single file; the Next.js export writes a real
     * document per route instead, which is why the forward is derived from
     * the path rather than being one constant.
     *
     * <p>Still a list and <b>not</b> a catch-all: the reasoning above about
     * honest 404s is unchanged, and now there is a second reason — a
     * {@code /**} forward would answer a mistyped path with whichever page
     * the fallback named, which is a worse lie than a 404.
     */
    static String fileFor(String page) {
        return "forward:" + page + ".html";
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String page : PAGES) {
            registry.addViewController(page).setViewName(fileFor(page));
        }
    }
}
