package com.example.signer.server.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which requests an <b>account</b> must have a verified e-mail address to
 * make (security Phase D1, risk D4): the ones that commit the account's name
 * to a document, or open a session that can.
 *
 * <table>
 *   <caption>Requires a verified address</caption>
 *   <tr><td>POST</td><td>/api/workflows/{id}/claim</td><td>take the signing turn</td></tr>
 *   <tr><td>GET</td><td>/api/workflows/{id}/document</td><td>download the revision to sign</td></tr>
 *   <tr><td>POST</td><td>/api/workflows/{id}/signatures</td><td>submit a signature</td></tr>
 *   <tr><td>POST</td><td>/api/workflows/{id}/signers/{user}/certificate</td><td>bind a signing certificate</td></tr>
 * </table>
 *
 * <p>The document download is the one <b>read</b> here, and it is here
 * deliberately: it is the first step of signing rather than a way of looking
 * around, and handing out the document to an account that has not proved its
 * address serves no purpose when it cannot submit the result. Listing
 * workflows, reading a task, its revisions and its audit trail stay open — an
 * unverified account may still sign in and see where it stands.
 *
 * <p>{@code POST /api/workflows} (starting a signing request) is not here
 * either — it is gated inside {@code RemoteWorkflowService.createWorkflow},
 * because that rule has to hold for every caller path including the website,
 * which this policy's filter does not see.
 *
 * <p>There used to be an exemption here for guest signing sessions, which
 * proved control of an address with an e-mailed code and had no account to
 * verify. Guest sessions are gone with the invitation workflow, so every
 * caller this policy sees now holds an account and the exemption is gone
 * with them.
 *
 * <p>Matching is on the raw request URI, exact and anchored: this policy only
 * ever <i>adds</i> a requirement, so a spelling it fails to match is still
 * subject to every other rule.
 */
public final class SigningActionPolicy {

    private static final Pattern PATH = Pattern.compile(
            "^/api/workflows/[^/]+/"
                    + "(claim|document|signatures|signers/[^/]+/certificate)$");

    private SigningActionPolicy() {
    }

    /**
     * Whether {@code method uri} is an action that an account may take only
     * with a verified e-mail address.
     */
    public static boolean requiresVerifiedEmail(String method, String uri) {
        if (method == null || uri == null) {
            return false;
        }
        Matcher m = PATH.matcher(uri);
        if (!m.matches()) {
            return false;
        }
        // The download is a GET; everything else here is a POST. Pairing the
        // method with the action means a covered path cannot be reached with
        // some other method and slip past. HEAD counts as the download
        // because Spring serves it from the same handler.
        return "document".equals(m.group(1))
                ? "GET".equals(method) || "HEAD".equals(method)
                : "POST".equals(method);
    }
}
