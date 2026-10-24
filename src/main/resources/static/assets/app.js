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
//
// Layout follows the design direction: one centred column, one primary
// action per screen as a filled button, secondary actions as text buttons,
// separation by tinted surfaces rather than shadows.

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

// Nulls are filtered rather than passed through: replaceChildren does not
// reject null, it inserts the STRING "null" — so a conditional child that
// evaluates to nothing silently prints the word in the page. That is how the
// navigation bar came to read "SignFlownull".
function show(...nodes) {
  view.replaceChildren(el("div", { class: "panel" }, nodes.filter(Boolean)));
}

function title(text) {
  return el("h1", { text });
}

function lede(text) {
  return el("p", { class: "lede", text });
}

function field(label, name, type, extras = {}) {
  const input = el("input", { id: name, name, type, required: true, ...extras });
  return {
    input,
    row: el("p", { class: "field" }, [
      el("label", { for: name, text: label }),
      input,
    ]),
  };
}

/** A form whose single primary action fills the width of the sheet. */
function sheet(children, submitLabel) {
  const f = el("form", {}, [
    ...children,
    el("button", { type: "submit", class: "block", text: submitLabel }),
  ]);
  return { form: f, node: el("div", { class: "sheet" }, [f]) };
}

/** Title, two lines, and a "… ›" action. */
function card(heading, lines, action) {
  return el("div", { class: "card" }, [
    el("h3", { text: heading }),
    ...[].concat(lines).map((line) =>
      typeof line === "string" ? el("p", { text: line }) : line),
    action,
  ]);
}

/** A message area, announced when its text changes. */
function note() {
  const node = el("p", { class: "note", role: "status", "aria-live": "polite" });
  return {
    node,
    say(message, kind = "info") {
      node.textContent = message;
      node.className = "note " + kind;
    },
  };
}

function badge(text, pending) {
  return el("span", { class: pending ? "badge pending" : "badge", text });
}

function link(href, text) {
  return el("a", { href, text });
}

function textButton(label, onClick) {
  const button = el("button", { type: "button", class: "text", text: label });
  button.addEventListener("click", onClick);
  return button;
}

function links(...items) {
  const spaced = [];
  items.filter(Boolean).forEach((item, index) => {
    if (index > 0) spaced.push(el("span", { class: "sep", text: "·" }));
    spaced.push(item);
  });
  return el("p", { class: "links" }, spaced);
}

/** The token a page was opened with, from the query string. */
function tokenFromUrl() {
  return new URLSearchParams(location.search).get("token") || "";
}

/* ------------------------------- views --------------------------------- */

function signInView() {
  const status = note();
  const user = field("E-mail address or username", "username", "text",
    { autocomplete: "username", autofocus: true });
  const password = field("Password", "password", "password",
    { autocomplete: "current-password" });

  const { form, node } = sheet([user.row, password.row], "Sign in");
  form.addEventListener("submit", async (event) => {
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
    title("Sign in"),
    lede("Use the account you created for SignFlow."),
    node,
    status.node,
    links(link("/forgot-password", "Forgotten your password?"),
      link("/sign-up", "Create an account")),
  );
}

function signUpView() {
  const status = note();
  const email = field("E-mail address", "email", "email",
    { autocomplete: "email", autofocus: true });
  const name = field("Your name", "displayName", "text", { autocomplete: "name" });
  const password = field("Password", "password", "password",
    { autocomplete: "new-password", minlength: String(MIN_PASSWORD_LENGTH) });

  const { form, node } = sheet([email.row, name.row, password.row],
    "Create account");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Creating your account…");
    const result = await api.signUp(email.input.value, password.input.value,
      name.input.value);
    if (result.ok) {
      // 202 whether or not an account was created: the server will not say
      // whether the address was already taken, and neither will this. The
      // wording has to be true in both cases.
      show(
        title("Check your e-mail"),
        lede("If that address can be used, a confirmation link is on its way"
          + " to it."),
        el("div", { class: "notice" }, [
          el("p", { text: "The link works once and expires in 24 hours."
            + " Nothing else happens until you open it." }),
        ]),
        links(link("/sign-in", "Back to sign in")),
      );
      return;
    }
    // The password rules describe the request, not who exists, so the server
    // does report those.
    status.say(result.message
      || "That did not work. Check the address and try again.", "bad");
  });

  show(
    title("Create an account"),
    lede(`At least ${MIN_PASSWORD_LENGTH} characters, and not a password you use`
      + " anywhere else."),
    node,
    status.node,
    links(link("/sign-in", "I already have an account")),
  );
}

function forgotPasswordView() {
  const status = note();
  const email = field("E-mail address", "email", "email",
    { autocomplete: "email", autofocus: true });

  const { form, node } = sheet([email.row], "Send a reset link");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Sending…");
    await api.requestPasswordReset(email.input.value);
    // Always 202, known address or not. Saying "if" is the honest wording.
    show(
      title("Check your e-mail"),
      lede("If that address belongs to an account, a link to set a new password"
        + " is on its way."),
      el("div", { class: "notice" }, [
        el("p", { text: "The link works once and expires in 30 minutes." }),
        el("p", { text: "Setting a new password signs the account out"
          + " everywhere, on the website and in the desktop app." }),
      ]),
      links(link("/sign-in", "Back to sign in")),
    );
  });

  show(
    title("Forgotten password"),
    lede("We will e-mail you a link to set a new one."),
    node,
    status.node,
    links(link("/sign-in", "Back to sign in")),
  );
}

async function verifyEmailView() {
  const token = tokenFromUrl();
  show(title("Confirming your address"), el("p", { class: "note", role: "status",
    text: "One moment…" }));

  const result = token ? await api.verifyEmail(token) : { ok: false, message: null };
  if (result.ok) {
    show(
      title("Address confirmed"),
      lede("Thank you. You can now create signing requests and sign documents."),
      links(link("/sign-in", "Sign in")),
    );
    return;
  }
  // Unknown, expired and already-used links all produce the same answer, by
  // design. The page offers the only useful next step instead of guessing
  // which it was.
  show(
    title("That link did not work"),
    lede(result.message
      || "This confirmation link is not valid. It may have expired, or been"
         + " used already."),
    el("div", { class: "notice" }, [
      el("p", { text: "Sign in and ask for a new one — the page will offer it"
        + " while your address is unconfirmed." }),
    ]),
    links(link("/sign-in", "Sign in")),
  );
}

function resetPasswordView() {
  const token = tokenFromUrl();
  if (!token) {
    show(
      title("That link is incomplete"),
      lede("Open the link from the e-mail again, or ask for a new one."),
      links(link("/forgot-password", "Send a new link")),
    );
    return;
  }

  const status = note();
  const password = field("New password", "newPassword", "password",
    { autocomplete: "new-password", minlength: String(MIN_PASSWORD_LENGTH),
      autofocus: true });

  const { form, node } = sheet([password.row], "Set new password");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Setting your new password…");
    const result = await api.confirmPasswordReset(token, password.input.value);
    if (result.ok) {
      show(
        title("Password set"),
        lede("You can sign in with it now. Everywhere else that was signed in"
          + " to this account has been signed out."),
        links(link("/sign-in", "Sign in")),
      );
      return;
    }
    status.say(result.message
      || "This link is not valid. It may have expired, or been used already.",
      "bad");
  });

  show(
    title("Set a new password"),
    lede(`At least ${MIN_PASSWORD_LENGTH} characters. This signs the account out`
      + " everywhere else."),
    node,
    status.node,
  );
}

function accountView(me) {
  const status = note();
  const confirmed = me.emailVerified;

  const resend = textButton("Send the confirmation link again", async () => {
    status.say("Sending…");
    await api.resendVerification(me.email);
    status.say("If that address still needs confirming, another link is on its"
      + " way.", "info");
  });

  const signOut = textButton("Sign out", async () => {
    await api.signOut();
    await route("/sign-in");
  });

  show(
    title(me.username),
    lede(me.email || "No e-mail address on file."),
    confirmed
      ? card("E-mail address", [
          me.email || "",
          badge("Confirmed"),
        ])
      : card("E-mail address", [
          me.email || "",
          badge("Not confirmed yet", true),
          el("p", { text: "You can sign in and look around, but not create"
            + " signing requests or sign documents until it is confirmed." }),
        ], resend),
    card("Password", [
      "Changing it signs out every other session, on the website and in the",
      "desktop app, and e-mails you to say it happened.",
    ], el("p", { class: "more" }, [link("/change-password", "Change password ›")])),
    status.node,
    links(signOut),
  );
}

function changePasswordView() {
  const status = note();
  const current = field("Current password", "currentPassword", "password",
    { autocomplete: "current-password", autofocus: true });
  const next = field("New password", "newPassword", "password",
    { autocomplete: "new-password", minlength: String(MIN_PASSWORD_LENGTH) });

  const { form, node } = sheet([current.row, next.row], "Change password");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.say("Changing your password…");
    const result = await api.changePassword(current.input.value, next.input.value);
    if (result.ok) {
      show(
        title("Password changed"),
        lede("Every other session has been signed out, and you have been"
          + " e-mailed to say it happened."),
        links(link("/", "Back to your account")),
      );
      return;
    }
    status.say(result.message || "That did not work.", "bad");
  });

  show(
    title("Change password"),
    lede(`At least ${MIN_PASSWORD_LENGTH} characters. You stay signed in here;`
      + " everywhere else is signed out."),
    node,
    status.node,
    links(link("/", "Cancel")),
  );
}

function errorView(message) {
  show(
    title("Something went wrong"),
    lede(message),
    links(link("/", "Start again")),
  );
}

/* ------------------------------ routing -------------------------------- */

// Client-side views for the paths the server forwards to this document. Each
// one is listed in FrontendRoutingConfiguration.PAGES, which
// SecurityConfig.PUBLIC reads as well, so a path that is not here does not
// reach here either.
const ROUTES = {
  "/sign-in": signInView,
  "/sign-up": signUpView,
  "/forgot-password": forgotPasswordView,
  "/verify-email": verifyEmailView,
  "/reset-password": resetPasswordView,
};

/** Screens that need a session; without one they fall back to signing in. */
const SIGNED_IN_ROUTES = {
  "/change-password": changePasswordView,
};

async function route(path) {
  const target = path || location.pathname;
  if (target !== location.pathname) {
    history.pushState({}, "", target);
  }
  renderNav(null);

  const open = ROUTES[target];
  if (open) {
    await open();
    return;
  }

  // "/" and the signed-in screens: the server is the authority on whether we
  // are signed in, so ask rather than guess from a cookie we cannot read.
  const me = await api.me();
  if (!me.ok || !me.body) {
    signInView();
    return;
  }
  renderNav(me.body);
  const signedIn = SIGNED_IN_ROUTES[target];
  if (signedIn) {
    signedIn(me.body);
  } else {
    accountView(me.body);
  }
}

function renderNav(me) {
  const items = [el("span", { class: "brand" }, [
    el("span", { class: "brand-mark", text: "Sign" }),
    el("span", { text: "Flow" }),
  ])];
  if (me) {
    items.push(el("span", { class: "who", text: me.username }));
  }
  nav.replaceChildren(...items);
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

route().catch(() => errorView("The server could not be reached. Try again in"
  + " a moment."));
