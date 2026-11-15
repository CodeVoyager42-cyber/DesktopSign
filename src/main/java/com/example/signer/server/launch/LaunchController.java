package com.example.signer.server.launch;

import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase B launch-code endpoints, now a deprecation shim.
 *
 * <p>The desktop launch-code path — website "Sign now" →
 * {@code signflow://launch} → a short-lived task session — has been removed
 * deliberately (scope B). This class is what is left of it for one release:
 * the four routes still exist and answer <b>410 Gone</b>, so a desktop or
 * website build from before the removal fails in a way its own error handling
 * already understands, instead of hitting the bearer chain's terminal
 * {@code denyAll} and reading as 401 "sign in again" — advice that would
 * never work no matter how often it is followed.
 *
 * <p>The routes are deleted outright in the release after this one, at which
 * point they 404 like any other path that was never there.
 *
 * <p><b>What this shim deliberately does not do:</b> it no longer touches
 * {@link LaunchCodeService}, so no code can be issued and none can be
 * exchanged. The service itself is still wired, because the task-session
 * branch of {@code AuthenticatedRequestFilter} still validates task tokens
 * that were issued before this release. Those expire within 30 minutes and
 * no new ones can be minted, so that branch empties out on its own and is
 * removed in the next step.
 *
 * <p>Still conditional on the database, exactly as before: in file mode these
 * routes never existed, and a 404 there remains the honest answer rather than
 * a 410 claiming something was removed that this deployment never had.
 */
@RestController
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class LaunchController {

    /**
     * One message for all four routes. It names the replacement path rather
     * than only the loss, because whoever reads this in a log is trying to
     * work out what their client should do instead.
     */
    private static final String GONE =
            "The desktop launch-code path has been removed. Sign in to the "
            + "SignFlow desktop application and open the signing task from "
            + "its task list.";

    @PostMapping("/api/workflows/{workflowId}/launch-codes")
    public void issue() {
        throw removed();
    }

    @PostMapping("/api/desktop/launch")
    public void exchange() {
        throw removed();
    }

    @GetMapping("/api/desktop/launches")
    public void recent() {
        throw removed();
    }

    @PostMapping("/api/desktop/launches/{launchId}/revoke")
    public void revoke() {
        throw removed();
    }

    private static ApiException removed() {
        return new ApiException(ApiErrorCode.FEATURE_REMOVED, GONE);
    }
}
