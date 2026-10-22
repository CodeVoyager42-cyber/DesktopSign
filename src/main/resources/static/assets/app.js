// The website: sign up, confirm an address, sign in, reset or change a
// password, sign out. Everything the account API offers and nothing else —
// there are no workflow routes under /web/api yet (Phase E).
//
// Written as plain ES modules with no build step, deliberately. The server
// has no Node toolchain and does not need one for this much, and a module
// served from /assets satisfies the Content-Security-Policy as it stands:
// script-src 'self'. There is no inline script anywhere here, and no inline
// event handler — every listener is addEventListener, which is what keeps
// 'unsafe-inline' out of the policy.

import { api, MIN_PASSWORD_LENGTH } from "./api.js";

const view = document.getElementById("view");
const nav = document.getElementById("nav");

/* ------------------------------ building ------------------------------- */

/**
 * Builds an element. Text is always set through textContent, never
 * innerHTML: everything shown here can contain a server message or something
 * a person typed, and the policy that stops an injected <script> running is
 * not a reason to hand one to the parser in the first place.
 */
function el(tag, attributes = {}, children = []) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === null || value === undefined || value === false) continue;
    if (name === "text") node.textContent = value;
    else if (name === "class") node.className = value;
    else node.setAttribute(name, value === true ? "" : value);
  }
  for (const child of [].concat(children)) {
    if (child) node.append(child);
  }
  return node;
}

function field(label, name, type, extras = {}) {
  const input = el("input", { id: name, name, type, required: true, ...extras });
  return { input, row: el("p", { class: "field" }, [el("label", { for: name, text: label }), input]) };
}

function form(children, submitLabel) {
  return el("form", { novalidate: false }, [
    ...children,
    el("p", { class: "field" }, [el("button", { type: "submit", text: submitLabel })]),
  ]);
}

/** A message area whose text is replaced rather than appended to. */
function note() {
  const node = el("p", { class: "note", role: "status" });
  return {
    node,
    say(message, kind = "info") {
      node.textContent = message;
      node.className = "note " + kind;
    },
    clear() {
      node.textContent = "";
      node.className = "note";
    },
  };
}

function link(href, text) {
  return el("a", { href, text });
}

function show(...nodes) {
  view.replaceChildren(...nodes);
}

/** The token a page was opened with, from the query string. */
function tokenFromUrl() {
  return new URLSearchParams(location.search).get("token") || "";
}

/* ------------------------------- views --------------------------------- */

function signInView() {
  const status = note();
  const user = field("E-mail address or username", "username", "text",
    { autocomplete: "username" });
  const password = field("Password", "password", "password",
    { autocomplete: "current-password" });

  const f = form([user.row, password.row], "Sign in");
  f.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Signing in…");
    const result = await api.signIn(user.input.value, password.input.value);
    if (result.ok) {
      // The session cookie is set; ask the server who we are rather than
      // assuming the typed identifier — signing in with an e-mail
      // authenticates as the account that owns it.
      await route("/");
      return;
    }
    // One message for a wrong password, an unknown account and a locked one,
    // because the server deliberately gives one answer for all three.
    status.say(result.message
      || "Wrong e-mail or password. After several failed attempts, sign-in is"
         + " blocked for a few minutes.", "bad");
  });

  show(
    el("h1", { text: "Sign in" }),
    f,
    status.node,
    el("p", { class: "links" }, [
      link("/forgot-password", "Forgotten your password?"),
      el("span", { text: " · " }),
      link("/sign-up", "Create an account"),
    ]),
  );
}

function signUpView() {
  const status = note();
  const email = field("E-mail address", "email", "email", { autocomplete: "email" });
  const name = field("Your name", "displayName", "text", { autocomplete: "name" });
  const password = field("Password", "password", "password",
    { autocomplete: "new-password", minlength: String(MIN_PASSWORD_LENGTH) });

  const f = form([email.row, name.row, password.row], "Create account");
  f.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Creating your account…");
    const result = await api.signUp(email.input.value, password.input.value,
      name.input.value);
    if (result.ok) {
      // 202 whether or not an account was created: the server will not say
      // whether the address was already taken, and neither will this. The
      // wording has to be true in both cases.
      show(
        el("h1", { text: "Check your e-mail" }),
        el("p", { text: "If that address can be used, a confirmation link is on its"
          + " way to it. The link works once and expires in 24 hours." }),
        el("p", { class: "note" , text: "Nothing else happens until you open it." }),
        el("p", { class: "links" }, [link("/sign-in", "Back to sign in")]),
      );
      return;
    }
    // The password rules describe the request, not who exists, so the server
    // does report those.
    status.say(result.message || "That did not work. Check the address and try again.",
      "bad");
  });

  show(
    el("h1", { text: "Create an account" }),
    el("p", { text: `At least ${MIN_PASSWORD_LENGTH} characters, and not a password you`
      + " use anywhere else." }),
    f,
    status.node,
    el("p", { class: "links" }, [link("/sign-in", "I already have an account")]),
  );
}

function forgotPasswordView() {
  const status = note();
  const email = field("E-mail address", "email", "email", { autocomplete: "email" });

  const f = form([email.row], "Send a reset link");
  f.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Sending…");
    await api.requestPasswordReset(email.input.value);
    // Always 202, known address or not. Saying "if" is the honest wording.
    show(
      el("h1", { text: "Check your e-mail" }),
      el("p", { text: "If that address belongs to an account, a link to set a new"
        + " password is on its way. It works once and expires in 30 minutes." }),
      el("p", { class: "note", text: "Setting a new password signs the account out"
        + " everywhere, on the website and in the desktop app." }),
      el("p", { class: "links" }, [link("/sign-in", "Back to sign in")]),
    );
  });

  show(
    el("h1", { text: "Forgotten password" }),
    el("p", { text: "We will e-mail you a link to set a new one." }),
    f,
    status.node,
    el("p", { class: "links" }, [link("/sign-in", "Back to sign in")]),
  );
}

async function verifyEmailView() {
  const token = tokenFromUrl();
  show(el("h1", { text: "Confirming your address" }),
    el("p", { class: "note", text: "One moment…" }));

  const result = token ? await api.verifyEmail(token) : { ok: false, message: null };
  if (result.ok) {
    show(
      el("h1", { text: "Address confirmed" }),
      el("p", { text: "Thank you. You can now create signing requests and sign"
        + " documents." }),
      el("p", { class: "links" }, [link("/sign-in", "Sign in")]),
    );
    return;
  }
  // Unknown, expired and already-used links all produce the same answer, by
  // design. The page offers the only useful next step instead of guessing
  // which it was.
  show(
    el("h1", { text: "That link did not work" }),
    el("p", { text: result.message
      || "This confirmation link is not valid. It may have expired, or been used"
         + " already." }),
    el("p", { text: "Sign in and ask for a new one." }),
    el("p", { class: "links" }, [link("/sign-in", "Sign in")]),
  );
}

function resetPasswordView() {
  const token = tokenFromUrl();
  const status = note();
  const password = field("New password", "newPassword", "password",
    { autocomplete: "new-password", minlength: String(MIN_PASSWORD_LENGTH) });

  const f = form([password.row], "Set new password");
  f.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Setting your new password…");
    const result = await api.confirmPasswordReset(token, password.input.value);
    if (result.ok) {
      show(
        el("h1", { text: "Password set" }),
        el("p", { text: "You can sign in with it now. Everywhere else that was"
          + " signed in to this account has been signed out." }),
        el("p", { class: "links" }, [link("/sign-in", "Sign in")]),
      );
      return;
    }
    status.say(result.message
      || "This link is not valid. It may have expired, or been used already.", "bad");
  });

  if (!token) {
    show(
      el("h1", { text: "That link is incomplete" }),
      el("p", { text: "Open the link from the e-mail again, or ask for a new one." }),
      el("p", { class: "links" }, [link("/forgot-password", "Send a new link")]),
    );
    return;
  }

  show(
    el("h1", { text: "Set a new password" }),
    el("p", { text: `At least ${MIN_PASSWORD_LENGTH} characters. This signs the account`
      + " out everywhere else." }),
    f,
    status.node,
  );
}

function accountView(me) {
  const status = note();
  const unverified = !me.emailVerified;

  const current = field("Current password", "currentPassword", "password",
    { autocomplete: "current-password" });
  const next = field("New password", "newPassword", "password",
    { autocomplete: "new-password", minlength: String(MIN_PASSWORD_LENGTH) });

  const changeForm = form([current.row, next.row], "Change password");
  changeForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Changing your password…");
    const result = await api.changePassword(current.input.value, next.input.value);
    if (result.ok) {
      current.input.value = "";
      next.input.value = "";
      status.say("Password changed. Every other session has been signed out, and"
        + " you have been e-mailed about it.", "good");
      return;
    }
    status.say(result.message || "That did not work.", "bad");
  });

  const resend = el("button", { type: "button", text: "Send it again" });
  resend.addEventListener("click", async () => {
    resend.disabled = true;
    await api.resendVerification(me.email);
    status.say("If that address still needs confirming, another link is on its way.",
      "info");
  });

  const signOut = el("button", { type: "button", class: "quiet", text: "Sign out" });
  signOut.addEventListener("click", async () => {
    await api.signOut();
    await route("/");
  });

  show(
    el("h1", { text: me.displayName || me.username }),
    el("p", { class: "muted", text: me.email || "" }),
    unverified
      ? el("div", { class: "warn" }, [
          el("p", { text: "Your e-mail address is not confirmed yet. You can sign in"
            + " and look around, but not create signing requests or sign"
            + " documents until it is." }),
          resend,
        ])
      : el("p", { class: "good", text: "E-mail address confirmed." }),
    el("h2", { text: "Change password" }),
    changeForm,
    status.node,
    el("p", { class: "links" }, [signOut]),
  );
}

function errorView(message) {
  show(
    el("h1", { text: "Something went wrong" }),
    el("p", { text: message }),
    el("p", { class: "links" }, [link("/", "Start again")]),
  );
}

/* ------------------------------ routing -------------------------------- */

// Client-side views for the paths the server forwards to this document. Each
// one is listed in FrontendRoutingConfiguration and in SecurityConfig.PUBLIC,
// so a path that is not here does not reach here either.
const ROUTES = {
  "/": null, // decided by whether there is a session
  "/sign-in": signInView,
  "/sign-up": signUpView,
  "/forgot-password": forgotPasswordView,
  "/verify-email": verifyEmailView,
  "/reset-password": resetPasswordView,
};

async function route(path) {
  const target = path || location.pathname;
  if (target !== location.pathname) {
    history.pushState({}, "", target);
  }
  renderNav(null);

  const render = ROUTES[target];
  if (render) {
    await render();
    return;
  }

  // "/" and anything else forwarded here: the server is the authority on
  // whether we are signed in, so ask rather than guess from a cookie we
  // cannot read.
  const me = await api.me();
  if (me.ok && me.body) {
    renderNav(me.body);
    accountView(me.body);
  } else {
    signInView();
  }
}

function renderNav(me) {
  nav.replaceChildren(
    el("span", { class: "brand", text: "SignFlow" }),
    me ? el("span", { class: "who", text: me.username }) : null,
  );
}

/** Links inside the app navigate without a page load. */
document.addEventListener("click", (event) => {
  const anchor = event.target.closest("a[href^='/']");
  if (!anchor || anchor.target === "_blank" || event.metaKey || event.ctrlKey) {
    return;
  }
  event.preventDefault();
  route(anchor.getAttribute("href"));
});

window.addEventListener("popstate", () => route());

route().catch(() => errorView("The server could not be reached. Try again in a moment."));
