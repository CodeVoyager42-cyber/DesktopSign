package com.example.signer.server.auth;

import com.example.signer.server.auth.JwtService.GuestAuthentication;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Least privilege for guest signing sessions: the COMPLETE list of requests
 * a guest token may make. Everything else is refused — the guest's own task
 * lists, other workflows, the audit trail, revisions, invitations, cancel,
 * workflow creation, account endpoints.
 *
 * <p>A guest may, on the ONE workflow its token is scoped to:
 * <ul>
 *   <li>{@code GET  /api/workflows/{wf}/task} — see its signing task</li>
 *   <li>{@code POST /api/workflows/{wf}/claim} — take its turn</li>
 *   <li>{@code GET  /api/workflows/{wf}/document} — download the document</li>
 *   <li>{@code POST /api/workflows/{wf}/signatures} — submit its signature</li>
 *   <li>{@code POST /api/workflows/{wf}/signers/{self}/certificate} — register
 *       its own signing key, for its own slot only</li>
 * </ul>
 * The workflow id must match the token EXACTLY (no decoding, no prefix
 * match), so an encoded or different id is simply not allowed.
 */
public final class GuestAccessPolicy {

    private static final Pattern PATH = Pattern.compile(
            "^/api/workflows/([^/]+)/(task|claim|document|signatures|signers/([^/]+)/certificate)$");

    private GuestAccessPolicy() {
    }

    /** True when {@code method uri} is one of the guest's allowed requests. */
    public static boolean allows(String method, String uri, GuestAuthentication guest) {
        return guest != null && allows(method, uri, guest.workflowId(), guest.principal());
    }

    /**
     * The same allow-list for any single-task session (guest, or a desktop
     * task session opened with a website launch code — Phase B): only these
     * requests, on exactly {@code workflowId}, the certificate route only for
     * {@code principal} itself.
     */
    public static boolean allows(String method, String uri, String workflowId, String principal) {
        if (method == null || uri == null || workflowId == null || principal == null) {
            return false;
        }
        Matcher m = PATH.matcher(uri);
        if (!m.matches() || !m.group(1).equals(workflowId)) {
            return false;
        }
        String action = m.group(2);
        return switch (action) {
            case "task", "document" -> "GET".equals(method);
            case "claim", "signatures" -> "POST".equals(method);
            default -> "POST".equals(method) && principal.equals(m.group(3));
        };
    }
}
