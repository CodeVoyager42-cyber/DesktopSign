package com.example.signer.server.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The few facts about this deployment that a page cannot know for itself.
 *
 * <p>There is exactly one so far, and it exists for the hand-off screen
 * (Phase F): where to get the desktop application. The website is a static
 * export, so it cannot read a property at build time that is only decided
 * when the server is deployed — and the one screen whose whole subject is
 * "SignFlow should have opened on your computer" is the one screen that has
 * to be able to say "and here is how to install it" when it has not.
 *
 * <p>The same value the invitation page has rendered since Phase 3
 * ({@code SignPageController}), from the same property, so a deployment
 * configures one link and both places use it. It is <b>empty by default</b>:
 * this project builds an installer with {@code scripts/package.sh} and does
 * not host it, so a deployment that has nowhere to point says nothing rather
 * than offering a link that 404s.
 *
 * <p>Behind the session chain, not public. Not because the link is a secret —
 * it is a download URL — but because every page that asks for it is behind a
 * session anyway, and a route that needs no session is a route that needs its
 * own rate limit (Phase C risk C9).
 */
@RestController
@RequestMapping("/web/api")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebAppInfoController {

    /** What the website may know about the application it hands off to. */
    public record AppInfoView(String appName, String downloadUrl) {
    }

    private final String appName;

    private final String downloadUrl;

    public WebAppInfoController(
            @Value("${app.name:SignFlow}") String appName,
            @Value("${signature.remote-server.invitation.download-url:${SIGNER_DOWNLOAD_URL:}}")
            String downloadUrl) {
        this.appName = appName;
        this.downloadUrl = downloadUrl;
    }

    @GetMapping("/app-info")
    public ResponseEntity<AppInfoView> appInfo() {
        // Blank becomes null: "unset" is one state, and a page testing for a
        // link should not have to know that this deployment spells it "".
        String link = this.downloadUrl == null || this.downloadUrl.isBlank()
                ? null : this.downloadUrl.trim();
        return ResponseEntity.ok(new AppInfoView(this.appName, link));
    }
}
