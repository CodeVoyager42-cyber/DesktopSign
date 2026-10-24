/**
 * The only place that talks to the server.
 *
 * Everything here is shaped by three facts about this API, unchanged from the
 * module this replaces:
 *
 *  1. It authenticates with a SESSION COOKIE, not a bearer token. The cookie
 *     is `__Host-` prefixed and HttpOnly, so this code can neither read nor
 *     set it — it only has to make sure requests carry it, which
 *     `credentials: "same-origin"` does. Nothing is kept in web storage,
 *     because no credential exists here to keep.
 *
 *  2. Every unsafe request needs a CSRF token: the server holds it in the
 *     session and mirrors it into a readable `XSRF-TOKEN` cookie, which we
 *     echo in `X-XSRF-TOKEN`. **The server rotates it at sign-in**, so it is
 *     read fresh for every request and never cached in a module variable.
 *
 *  3. Several endpoints answer 202 whether or not anything happened —
 *     sign-up, resend, reset request — because reporting the difference would
 *     say who has an account. Nothing here may pretend to know more than the
 *     server said, so the server's own `message` is shown as given.
 */

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

/** Minimum password length, as the server enforces it. */
export const MIN_PASSWORD_LENGTH = 8;

export interface Result<T> {
  ok: boolean;
  status: number;
  code: string | null;
  message: string | null;
  body: T | null;
}

export interface Profile {
  username: string;
  email: string | null;
  emailVerified: boolean;
}

/** One signer slot of a workflow, as the task view reports it. */
export interface Participant {
  order: number;
  name: string;
  you: boolean;
  state: string;
}

/** The signed-in account's own slot. */
export interface MySlot {
  myStateLabel: string;
  participantOrder: number | null;
  signed: boolean;
  waitingForMe: boolean;
  waitingForClaim: boolean;
}

/** GET /web/api/workflows/participating */
export interface Task {
  workflowId: string;
  documentName: string;
  senderId: string | null;
  senderDisplayName: string | null;
  status: string;
  createdAt: string | null;
  completedAt: string | null;
  myState: MySlot | null;
  totalSignatures: number;
  completedSignatures: number;
  signingPolicy: string;
  participants: Participant[] | null;
  expiresAt: string | null;
  updatedAt: string | null;
}

/** GET /web/api/workflows/owned */
export interface Workflow {
  workflowId: string;
  documentName: string;
  status: string;
  currentRevision: number;
  completedSignatures: number;
  totalSignatures: number;
  currentSignerUserId: string | null;
  currentSignerFingerprintShort: string | null;
  blockedReason: string | null;
  signingPolicy: string;
  waitingForClaim: boolean;
  awaitingYourCertificate: boolean;
}

/** GET /web/api/workflows/{id}/audit */
export interface AuditEntry {
  sequence: number;
  eventType: string;
  actor: string | null;
  detail: string | null;
  occurredAt: string;
}

/** Reads a cookie this code is allowed to see. Null when absent. */
function cookie(name: string): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const prefix = `${name}=`;
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
 * `GET /web/api/csrf` creates a session for an anonymous visitor, which is
 * why it is rate-limited in its own bucket (Phase C risk C9) — so it is
 * called when a token is actually needed, not on every page load.
 */
async function csrfToken(): Promise<string | null> {
  const existing = cookie(CSRF_COOKIE);
  if (existing) {
    return existing;
  }
  await fetch("/web/api/csrf", { credentials: "same-origin" });
  return cookie(CSRF_COOKIE);
}

async function parse<T>(response: Response): Promise<Result<T>> {
  let body: unknown = null;
  if ((response.headers.get("Content-Type") ?? "").includes("application/json")) {
    body = await response.json().catch(() => null);
  }
  const error = body as { code?: string; message?: string } | null;
  return {
    ok: response.ok,
    status: response.status,
    code: error?.code ?? null,
    message: error?.message ?? null,
    body: (body as T) ?? null,
  };
}

/** A network failure is a result too, so no caller has to catch. */
function unreachable<T>(): Result<T> {
  return {
    ok: false,
    status: 0,
    code: "UNREACHABLE",
    message: "The server could not be reached. Check your connection and try again.",
    body: null,
  };
}

async function get<T>(path: string): Promise<Result<T>> {
  try {
    return await parse<T>(
      await fetch(path, {
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      }),
    );
  } catch {
    return unreachable<T>();
  }
}

async function post<T>(path: string, payload?: unknown): Promise<Result<T>> {
  try {
    const token = await csrfToken();
    return await parse<T>(
      await fetch(path, {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          ...(token ? { [CSRF_HEADER]: token } : {}),
        },
        body: JSON.stringify(payload ?? {}),
      }),
    );
  } catch {
    return unreachable<T>();
  }
}

/* The endpoints, named, so a typo is a missing function and not a 404. */

export const api = {
  me: () => get<Profile>("/web/api/auth/me"),
  signIn: (username: string, password: string) =>
    post<Profile>("/web/api/auth/login", { username, password }),
  signOut: () => post<void>("/web/api/auth/logout"),
  signUp: (email: string, password: string, displayName: string) =>
    post<void>("/web/api/auth/register", { email, password, displayName }),
  verifyEmail: (token: string) => post<void>("/web/api/auth/verify-email", { token }),
  resendVerification: (email: string) =>
    post<void>("/web/api/auth/resend-verification", { email }),
  requestPasswordReset: (email: string) =>
    post<void>("/web/api/auth/password-reset/request", { email }),
  confirmPasswordReset: (token: string, newPassword: string) =>
    post<void>("/web/api/auth/password-reset/confirm", { token, newPassword }),
  changePassword: (currentPassword: string, newPassword: string) =>
    post<void>("/web/api/me/password", { currentPassword, newPassword }),

  /* Workflows (Phase E). Two lists, because which group a document belongs in
     depends on the viewer's own slot and not on the document's state:
     `participating` knows whose turn it is, `owned` knows what you sent. */
  participating: () => get<{ tasks: Task[] }>("/web/api/workflows/participating"),
  owned: () => get<Workflow[]>("/web/api/workflows/owned"),
  workflow: (id: string) =>
    get<Workflow>(`/web/api/workflows/${encodeURIComponent(id)}`),
  audit: (id: string) =>
    get<AuditEntry[]>(`/web/api/workflows/${encodeURIComponent(id)}/audit`),

  /**
   * The finished document. A plain URL rather than a fetch: this is a
   * download, and letting the browser do it keeps the bytes out of this code
   * entirely. The session cookie travels with it because it is the same
   * origin — which is the whole reason the site is served from the server.
   */
  downloadUrl: (id: string) =>
    `/web/api/workflows/${encodeURIComponent(id)}/download`,
};
