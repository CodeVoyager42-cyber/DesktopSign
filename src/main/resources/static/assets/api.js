// The only place that talks to the server.
//
// Everything here is shaped by three facts about this API:
//
//  1. It authenticates with a SESSION COOKIE, not a bearer token. The cookie
//     is __Host- prefixed and HttpOnly, so this script can neither read it
//     nor set it — it only has to make sure requests carry it, which
//     `credentials: "same-origin"` does. So nothing is kept in web
//     storage: no credential exists here to keep.
//
//  2. Every unsafe request needs a CSRF token: the server holds it in the
//     session and mirrors it into a readable XSRF-TOKEN cookie, which we
//     echo in the X-XSRF-TOKEN header. The server rotates the token at
//     sign-in, so it is read fresh for every request rather than cached.
//
//  3. Several endpoints answer 202 whether or not anything happened —
//     sign-up, resend, reset request — because reporting the difference
//     would say who has an account. The UI must not pretend to know more
//     than the server said.

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

/** Reads a cookie this script is allowed to see. Null when absent. */
function cookie(name) {
  const prefix = name + "=";
  for (const part of document.cookie.split("; ")) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length));
    }
  }
  return null;
}

/**
 * The current CSRF token, asking the server for one if we have none.
 *
 * GET /web/api/csrf creates a session for an anonymous visitor, which is why
 * it is rate-limited in its own bucket (Phase C risk C9) — so this is called
 * when a token is actually needed, not on every page load.
 */
async function csrfToken() {
  const existing = cookie(CSRF_COOKIE);
  if (existing) {
    return existing;
  }
  await fetch("/web/api/csrf", { credentials: "same-origin" });
  return cookie(CSRF_COOKIE);
}

/**
 * One result shape for every call, so views never touch fetch directly:
 * { ok, status, code, message, body }.
 *
 * `code` and `message` come from the server's own error body when it sent
 * one. They are shown as given: the wording of a refusal is deliberate here
 * (a reset link that is unknown, expired or already used produces one
 * identical message on purpose), and improvising a friendlier version in the
 * browser would undo that.
 */
async function parse(response) {
  let body = null;
  const type = response.headers.get("Content-Type") || "";
  if (type.includes("application/json")) {
    body = await response.json().catch(() => null);
  }
  return {
    ok: response.ok,
    status: response.status,
    code: body && body.code ? body.code : null,
    message: body && body.message ? body.message : null,
    body,
  };
}

export async function get(path) {
  const response = await fetch(path, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  return parse(response);
}

export async function post(path, payload) {
  const token = await csrfToken();
  const response = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...(token ? { [CSRF_HEADER]: token } : {}),
    },
    body: JSON.stringify(payload ?? {}),
  });
  return parse(response);
}

/* The endpoints, named, so a typo is a missing function and not a 404. */

export const api = {
  me: () => get("/web/api/auth/me"),
  signIn: (username, password) => post("/web/api/auth/login", { username, password }),
  signOut: () => post("/web/api/auth/logout", {}),
  signUp: (email, password, displayName) =>
    post("/web/api/auth/register", { email, password, displayName }),
  verifyEmail: (token) => post("/web/api/auth/verify-email", { token }),
  resendVerification: (email) => post("/web/api/auth/resend-verification", { email }),
  requestPasswordReset: (email) => post("/web/api/auth/password-reset/request", { email }),
  confirmPasswordReset: (token, newPassword) =>
    post("/web/api/auth/password-reset/confirm", { token, newPassword }),
  changePassword: (currentPassword, newPassword) =>
    post("/web/api/me/password", { currentPassword, newPassword }),

  /* Workflows (Phase E). Two lists, because which group a document belongs
     in depends on the viewer's own slot and not on the document's state:
     `participating` knows whose turn it is, `owned` knows what you sent. */
  participating: () => get("/web/api/workflows/participating"),
  owned: () => get("/web/api/workflows/owned"),

  /**
   * The finished document. A plain URL rather than a fetch: this is a
   * download, and letting the browser do it keeps the bytes out of this
   * script entirely. The session cookie travels with it because it is the
   * same origin — which is the whole reason the website is served from here.
   */
  downloadUrl: (workflowId) =>
    "/web/api/workflows/" + encodeURIComponent(workflowId) + "/download",
};

/** Minimum password length, as the server enforces it. */
export const MIN_PASSWORD_LENGTH = 8;
