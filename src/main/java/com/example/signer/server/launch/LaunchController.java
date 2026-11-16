package com.example.signer.server.launch;

import com.example.signer.server.api.ApiContracts;
import com.example.signer.server.auth.AuthController;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase B endpoints.
 * <ul>
 *   <li>{@code POST /api/workflows/{id}/launch-codes} — signed-in account
 *       (bearer for now; the website's session chain will call the same
 *       service in Phase C). Guest and task tokens are refused by the policy.</li>
 *   <li>{@code POST /api/desktop/launch} — public; the code is the credential;
 *       rate limited per address.</li>
 *   <li>{@code GET /api/desktop/launches}, {@code POST /api/desktop/launches/{id}/revoke}
 *       — the user's own launches ("opened on device X — wasn't me?").</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class LaunchController {

    private final LaunchCodeService launches;

    public LaunchController(LaunchCodeService launches) {
        this.launches = launches;
    }

    @PostMapping("/api/workflows/{workflowId}/launch-codes")
    public ResponseEntity<ApiContracts.LaunchCodeIssued> issue(@PathVariable String workflowId,
                                                               HttpServletRequest request) {
        String user = AuthController.requireUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "no-store")
                .body(launches.issue(user, workflowId));
    }

    @PostMapping("/api/desktop/launch")
    public ResponseEntity<ApiContracts.LaunchSessionView> exchange(
            @RequestBody(required = false) ApiContracts.LaunchExchangeRequest body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(launches.exchange(body));
    }

    @GetMapping("/api/desktop/launches")
    public List<ApiContracts.LaunchView> recent(HttpServletRequest request) {
        return launches.recent(AuthController.requireUser(request));
    }

    @PostMapping("/api/desktop/launches/{launchId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable long launchId, HttpServletRequest request) {
        launches.revoke(AuthController.requireUser(request), launchId);
        return ResponseEntity.noContent().build();
    }
}
