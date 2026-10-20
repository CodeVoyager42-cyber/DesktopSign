package com.example.signer.server.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a desktop <b>task session</b> (Phase B launch code) may do: exactly
 * the four requests needed to sign an already-prepared slot of one
 * workflow, and nothing else.
 *
 * <p>Deliberately <b>not</b> the guest allow-list: a task session may never
 * bind or replace a certificate (that would let a stolen launch code attach
 * the thief's card to the victim's slot), create or manage workflows or
 * invitations, read audit trails, or issue further launch codes. Its slot is
 * resolved on the server from the session's account; the URL never selects
 * a signer.
 *
 * <table>
 *   <caption>Allowed requests (own workflow only)</caption>
 *   <tr><td>GET</td><td>/api/workflows/{id}/task</td><td>status of the task</td></tr>
 *   <tr><td>GET</td><td>/api/workflows/{id}/document</td><td>the revision to sign</td></tr>
 *   <tr><td>POST</td><td>/api/workflows/{id}/claim</td><td>take the turn (flexible order)</td></tr>
 *   <tr><td>POST</td><td>/api/workflows/{id}/signatures</td><td>submit the signed revision</td></tr>
 * </table>
 *
 * Matching is on the raw request URI, exact and anchored: any other
 * spelling is refused (deny by default).
 */
public final class TaskAccessPolicy {

    private static final Pattern PATH =
            Pattern.compile("^/api/workflows/([^/]+)/(task|document|claim|signatures)$");

    private TaskAccessPolicy() {
    }

    /** True when {@code method uri} is one of the task session's requests for its own workflow. */
    public static boolean allows(String method, String uri, String workflowId) {
        if (method == null || uri == null || workflowId == null) {
            return false;
        }
        Matcher m = PATH.matcher(uri);
        if (!m.matches() || !m.group(1).equals(workflowId)) {
            return false;
        }
        return switch (m.group(2)) {
            case "task", "document" -> "GET".equals(method);
            default -> "POST".equals(method); // claim, signatures
        };
    }

    /**
     * The one request still answered once the task is finished (signed,
     * cancelled or completed): its status, so the app can show the outcome.
     */
    public static boolean isStatusRead(String method, String uri, String workflowId) {
        return "GET".equals(method) && uri != null && workflowId != null
                && uri.equals("/api/workflows/" + workflowId + "/task");
    }
}
