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

/** The signed-in account's own slot on a document. */
export interface MySlot {
  myStateLabel: string;
  participantOrder: number | null;
  signed: boolean;
  waitingForMe: boolean;
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
  updatedAt: string | null;
}

/** GET /web/api/workflows/owned */
export interface Workflow {
  workflowId: string;
  documentName: string;
  status: string;
  currentRevision: number;
  completedSignatures: number;
  currentSignerUserId: string | null;
  currentSignerFingerprintShort: string | null;
  blockedReason: string | null;
  awaitingYourCertificate: boolean;
}

/**
 * POST /web/api/workflows/{id}/launch-codes — the hand-off to the desktop.
 *
 * `deepLink` contains a single-use code valid for `expiresInSeconds`. It is a
 * credential: it is navigated to and never rendered, never logged, and never
 * put anywhere a reader could copy it out of.
 */
export interface LaunchCode {
  deepLink: string;
  expiresInSeconds: number;
}

/** GET /web/api/app-info — what this deployment can tell a page about itself. */
export interface AppInfo {
  appName: string;
  /** Where to get the desktop application, or null when none is configured. */
  downloadUrl: string | null;
}

/**
 * GET /web/api/download-info — what there is to download, if anything.
 *
 * Public, so the page works before the reader has an account: the app is
 * what signs, so it has to be installable first. `available` is the only
 * thing to branch on: `url` may be this
 * server's own `/downloads/<file>` or an external link configured for the
 * deployment, and `sizeBytes`/`sha256` are absent for the latter because the
 * server cannot know them about a file it does not hold.
 */
export interface DownloadInfo {
  appName: string;
  version: string | null;
  fileName: string | null;
  sizeBytes: number;
  sha256: string | null;
  url: string | null;
  portableUrl: string | null;
  portableFileName: string | null;
  portableSizeBytes: number;
  available: boolean;
}

/** One released artefact, as the dashboard's download section reads it. */
export interface ReleaseAsset {
  fileName: string;
  sizeBytes: number;
  sha256: string | null;
  /**
   * Where this server serves it from, or null when there is nothing to
   * click: a button is only ever rendered for a URL the server keeps.
   */
  url: string | null;
  /** The `.sha256` beside it on this server, when there is one. */
  checksumUrl: string | null;
}

/**
 * GET /web/api/latest-release — the newest release of the source repository,
 * behind the session. While GitHub answers, it is the truth about the
 * version, the sizes and the checksums; without it the server's own folder
 * is, exactly as `download-info` has always described it. `available` is
 * the one field to branch on.
 */
export interface LatestRelease {
  appName: string;
  tag: string | null;
  version: string | null;
  publishedAt: string | null;
  installer: ReleaseAsset | null;
  portable: ReleaseAsset | null;
  available: boolean;
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

/**
 * A HEAD: asking whether a request would be allowed, without doing it.
 *
 * This exists for one caller — the installer button. A download has to be
 * handed to the browser as a navigation, because that is the only way the
 * bytes stay out of this code (see `installerUrl` below), and a navigation
 * cannot be taken back: if the server were to answer 401 or 404, the reader
 * would be looking at a JSON error instead of a page. So the status is asked
 * for first, on the very route that will serve the file, and only a 200 is
 * acted on.
 *
 * A HEAD is the right question because the server answers it by running the
 * same handler and dropping the body: the status is the real one, and no
 * bytes are read to produce it.
 */
async function head(path: string): Promise<Result<never>> {
  try {
    return await parse<never>(
      await fetch(path, { method: "HEAD", credentials: "same-origin" }),
    );
  } catch {
    return unreachable<never>();
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

/**
 * A multipart POST: the document, plus the request as its own JSON part.
 *
 * Two details are load-bearing. The `Content-Type` is **not** set here — the
 * browser writes it, because only the browser knows the boundary it is about
 * to generate, and a hand-written header without the right boundary makes the
 * body unparseable. And the request part is appended as a `Blob` typed
 * `application/json`: a plain string part is sent as `text/plain`, which
 * Spring will not bind to `@RequestPart CreateWorkflowRequest`, so the server
 * would refuse the call for a reason that looks nothing like the cause.
 */
async function postForm<T>(path: string, form: FormData): Promise<Result<T>> {
  try {
    const token = await csrfToken();
    return await parse<T>(
      await fetch(path, {
        method: "POST",
        credentials: "same-origin",
        headers: {
          Accept: "application/json",
          ...(token ? { [CSRF_HEADER]: token } : {}),
        },
        body: form,
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

  /* Documents (Phase E). Two lists, because one knows where the document is
     and the other knows where the reader's own signature stands:
     `participating` carries the slot state, `owned` is the plain list. */
  participating: () => get<{ tasks: Task[] }>("/web/api/workflows/participating"),
  owned: () => get<Workflow[]>("/web/api/workflows/owned"),
  workflow: (id: string) =>
    get<Workflow>(`/web/api/workflows/${encodeURIComponent(id)}`),
  audit: (id: string) =>
    get<AuditEntry[]>(`/web/api/workflows/${encodeURIComponent(id)}/audit`),

  /* Uploading a document to sign. */

  /**
   * Uploads a PDF to sign: its name as one part, the file as the other. The
   * signed-in account is its only signer, so nothing else is sent.
   */
  createWorkflow: (documentName: string, file: File) => {
    const form = new FormData();
    form.append(
      "request",
      new Blob([JSON.stringify({ documentName })], {
        type: "application/json",
      }),
    );
    form.append("file", file);
    return postForm<Workflow>("/web/api/workflows", form);
  },

  /** The owner, and only before the document is signed. */
  cancel: (id: string) =>
    post<Workflow>(`/web/api/workflows/${encodeURIComponent(id)}/cancel`),

  /* The hand-off to the desktop (Phase F). */

  /**
   * A fresh link that opens SignFlow at this one document. Two minutes,
   * one use; the server refuses it unless this account is the document's
   * signer and has a certificate registered already.
   */
  launchCode: (id: string) =>
    post<LaunchCode>(
      `/web/api/workflows/${encodeURIComponent(id)}/launch-codes`,
    ),

  /** Where to get the desktop application, when this deployment says. */
  appInfo: () => get<AppInfo>("/web/api/app-info"),

  /**
   * What there is to download. The one call on this website that needs no
   * session, because the page it feeds needs none either.
   */
  downloadInfo: () => get<DownloadInfo>("/web/api/download-info"),

  /**
   * The newest release, as the source repository publishes it. Behind the
   * session like everything else on the dashboard: the server asks GitHub
   * for the account of everybody reading, at most once an hour, and the
   * download itself comes from this origin either way.
   */
  latestRelease: () => get<LatestRelease>("/web/api/latest-release"),

  /**
   * The finished document. A plain URL rather than a fetch: this is a
   * download, and letting the browser do it keeps the bytes out of this code
   * entirely. The session cookie travels with it because it is the same
   * origin — which is the whole reason the site is served from the server.
   */
  downloadUrl: (id: string) =>
    `/web/api/workflows/${encodeURIComponent(id)}/download`,

  /**
   * The desktop installer, for a signed-in reader.
   *
   * A plain URL for the same reason `downloadUrl` is one: the browser
   * downloads it, so a 200 MB installer never passes through this code and
   * never sits in a blob in memory. The session cookie travels with it
   * because it is the same origin, and the server — not this code — decides
   * whether the reader may have it.
   *
   * Constant rather than configured: unlike `downloadInfo().url`, which may
   * point at an external link a deployment set, this is a route on this
   * server and is the same everywhere.
   */
  installerUrl: () => "/web/api/desktop/installer",

  /**
   * Whether that download would be allowed, asked before it is started.
   *
   * 200 to go ahead, 401 for no session (the reader is sent to sign in),
   * 404 for a deployment that has published nothing, 429 for the rate
   * limit. See `head` above for why it is asked at all.
   */
  installerReady: () => head("/web/api/desktop/installer"),
};
