package com.example.signer.client;

import com.example.signer.server.api.ApiContracts;
import com.example.signer.server.workflow.WorkflowState;
import com.example.signer.server.api.ApiStates.ParticipantState;
import com.example.signer.server.api.ApiStates.SlotState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Presentation-model mapping for the Signing Tasks workspace: converts the
 * raw backend contracts ({@code TaskItemView} etc.) into human-facing
 * models — status labels, readable document titles, relative dates — and
 * provides the search / filter / sort behaviour. Pure logic, no JavaFX, so
 * it is unit-testable without the FX toolkit.
 *
 * <p>The raw workflow id and revision numbers never reach these models:
 * the UI shows them only via the explicit "Details" support panel.
 */
public final class SigningTaskModels {

    private SigningTaskModels() {
    }

    /* ------------------------------------------------------------------
     * presentation model
     * ------------------------------------------------------------------ */

    /**
     * One signing task as the UI renders it. Built exclusively from a
     * backend {@code TaskItemView} — no invented data.
     */
    public record SigningTask(
            String workflowId,
            String title,
            String senderName,
            String senderEmail,
            String statusLabel,
            String statusStyle,
            String dateText,
            /** Epoch second of the task's date (creation or completion). */
            Long dateEpochSecond,
            String dateKind,
            String progressText,
            boolean needsAction,
            boolean canSign,
            boolean waitingForClaim,
            boolean certificatePending,
            String myStateRaw,
            /** The one state the screen is organised around. */
            TaskState state,
            /** One plain sentence: what is going on / who we are waiting for. */
            String explanation,
            /** Label of the single primary action for this state. */
            String primaryAction,
            /** Signing order, every slot (empty when the server did not send it). */
            List<Participant> participants,
            /** True for FLEXIBLE workflows: signers sign in any order. */
            boolean anyOrder,
            /** "You are signer #2 of 3", or null. */
            String yourRole,
            /** "Expires Oct 12, 2026" style text, or null when unknown. */
            String expiresText,
            Long expiresEpochSecond,
            /** Expires within 48 hours. */
            boolean expiresSoon,
            Long updatedEpochSecond) {

        /** Search: document, requester, and the signers' names. */
        public String searchHaystack() {
            StringBuilder people = new StringBuilder();
            if (participants != null) {
                for (Participant p : participants) {
                    people.append(' ').append(p.name() == null ? "" : p.name());
                }
            }
            return String.join(" ",
                    title == null ? "" : title.toLowerCase(Locale.ROOT),
                    senderName == null ? "" : senderName.toLowerCase(Locale.ROOT),
                    senderEmail == null ? "" : senderEmail.toLowerCase(Locale.ROOT),
                    people.toString().toLowerCase(Locale.ROOT),
                    workflowId == null ? "" : workflowId.toLowerCase(Locale.ROOT));
        }
    }

    /** The states the Signing Tasks screen shows. */
    public enum TaskState {
        ACTION_REQUIRED("Action required", "action"),
        WAITING("Waiting", "waiting"),
        COMPLETED("Completed", "done"),
        EXPIRED("Expired", "muted"),
        DECLINED("Declined", "bad"),
        CANCELLED("Cancelled", "muted"),
        ERROR("Needs attention", "bad");

        private final String label;
        private final String style;

        TaskState(String label, String style) {
            this.label = label;
            this.style = style;
        }

        public String label() {
            return label;
        }

        /** CSS suffix (task-state--action, …). */
        public String style() {
            return style;
        }
    }

    /** One signer slot in the signing order. */
    public record Participant(int order, String name, boolean you, SlotState state) {
        public boolean signed() {
            return state == SlotState.SIGNED;
        }

        public boolean current() {
            return state == SlotState.CURRENT;
        }
    }

    /** The simple filter set of the workspace toolbar. */
    public enum TaskFilter {
        ALL, NEEDS_ACTION, WAITING, COMPLETED, DECLINED, EXPIRED,
        /** Cancelled / withdrawn by the requester. */
        REVOKED
    }

    /** Sort options of the workspace toolbar. */
    public enum TaskSort {
        NEEDS_ACTION_FIRST, RECENTLY_UPDATED, NEWEST, OLDEST, EXPIRING_SOON
    }

    /* ------------------------------------------------------------------
     * mapping
     * ------------------------------------------------------------------ */

    /** Maps one backend task item to the presentation model. */
    public static SigningTask from(ApiContracts
            .TaskItemView item) {
        ParticipantState raw = item.myState() == null
                ? ParticipantState.UNKNOWN : item.myState().state();
        boolean waitingForMe = item.myState() != null
                && item.myState().waitingForMe();
        boolean waitingForClaim = item.myState() != null
                && item.myState().waitingForClaim();
        boolean certificatePending = raw == ParticipantState.CERTIFICATE_PENDING;
        // A slot is signed ONLY when the server says so for this slot.
        boolean selfSigned = raw == ParticipantState.SIGNED
                && item.myState() != null && item.myState().signed();

        boolean needsAction = waitingForMe || waitingForClaim || certificatePending;
        boolean canSign = needsAction;
        String label;
        String style;
        if (waitingForMe) {
            label = "Action required";
            style = "action";
        } else if (certificatePending) {
            label = "Set up to sign";
            style = "action";
        } else if (waitingForClaim) {
            label = "Ready for you to sign";
            style = "action";
        } else {
            switch (raw) {
                case SIGNED -> {
                    // The label is shown only when the server also reports
                    // THIS slot's signature as accepted — never inferred from
                    // registration, sign-in, opening the document or the
                    // workflow being completed by others.
                    if (selfSigned) {
                        label = "Completed";
                        style = "done";
                    } else {
                        label = "Awaiting signature";
                        style = "waiting";
                    }
                }
                case INVITED -> {
                    // An invitation exists; the recipient has no account yet.
                    // Invitation state, not a signature.
                    label = "Invited — not signed";
                    style = "waiting";
                }
                case DECLINED -> {
                    label = "Declined";
                    style = "bad";
                }
                case EXPIRED -> {
                    label = "Expired";
                    style = "warn";
                }
                case REVOKED -> {
                    label = "Withdrawn";
                    style = "bad";
                }
                case BLOCKED -> {
                    label = "Needs attention";
                    style = "bad";
                }
                case WAITING_FOR_OTHERS -> {
                    label = "Waiting for others";
                    style = "waiting";
                }
                case IN_PROGRESS -> {
                    label = "In progress";
                    style = "waiting";
                }
                case WAITING_FOR_CLAIM -> {
                    label = "Ready for you to sign";
                    style = "action";
                }
                default -> {
                    label = "In progress";
                    style = "waiting";
                }
            }
        }

        String sender = item.senderDisplayName();
        if (sender == null || sender.isBlank()) {
            sender = item.senderId();
        }

        // Completed tasks show the completion date; everything else shows
        // when the document was sent (creation).
        String dateText = null;
        Long dateEpoch = null;
        String dateKind = "Sent";
        String iso = item.completedAt() != null ? item.completedAt()
                : item.createdAt();
        if (item.completedAt() != null) {
            dateKind = "Completed";
        }
        if (iso != null) {
            try {
                Instant instant = Instant.parse(iso);
                dateEpoch = instant.getEpochSecond();
                dateText = relativeDate(instant);
            } catch (Exception ignored) {
                dateText = null; // never surface a raw timestamp on a card
            }
        }

        String progress = item.totalSignatures() > 1
                ? item.completedSignatures() + " of "
                        + item.totalSignatures() + " signatures complete"
                : null;

        // ---- redesigned presentation (real server data only) ----------
        List<Participant> participants = new ArrayList<>();
        if (item.participants() != null) {
            for (var p : item.participants()) {
                participants.add(new Participant(p.order(), p.name(), p.you(), p.slotState()));
            }
        }
        boolean anyOrder = "FLEXIBLE".equals(item.signingPolicy());
        boolean allSigned = item.totalSignatures() > 0
                && item.completedSignatures() >= item.totalSignatures();
        TaskState state;
        if (needsAction) {
            state = TaskState.ACTION_REQUIRED;
        } else {
            state = switch (raw) {
                case EXPIRED -> TaskState.EXPIRED;
                case DECLINED -> TaskState.DECLINED;
                case REVOKED, CANCELLED -> TaskState.CANCELLED;
                case BLOCKED -> TaskState.ERROR;
                default -> WorkflowState.COMPLETED.name().equals(item.status()) || (selfSigned && allSigned)
                        ? TaskState.COMPLETED : TaskState.WAITING;
            };
        }
        Instant expires = parse(item.expiresAt());
        Instant updated = parse(item.updatedAt());
        String expiresText = expires == null ? null
                : (expires.isBefore(Instant.now()) ? "Expired " : "Expires ")
                        + DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
                                .withZone(ZoneId.systemDefault()).format(expires);
        boolean expiresSoon = expires != null && expires.isAfter(Instant.now())
                && expires.isBefore(Instant.now().plus(48, ChronoUnit.HOURS));
        Participant me = participants.stream().filter(Participant::you).findFirst().orElse(null);
        String role = me == null || participants.isEmpty() ? null
                : anyOrder ? "You are one of " + participants.size() + " signers"
                : "You are signer #" + me.order() + " of " + participants.size();

        return new SigningTask(item.workflowId(),
                readableTitle(item.documentName()),
                sender,
                null, // sender email is not exposed in the task list
                label, style, dateText, dateEpoch, dateKind,
                progress,
                needsAction, canSign,
                waitingForClaim, certificatePending,
                item.myState() == null ? null : item.myState().myStateLabel(),
                state,
                explanation(state, certificatePending, selfSigned, participants, anyOrder,
                        expires),
                primaryAction(state, certificatePending),
                List.copyOf(participants), anyOrder, role,
                expiresText, expires == null ? null : expires.getEpochSecond(), expiresSoon,
                updated == null ? dateEpoch : Long.valueOf(updated.getEpochSecond()));
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One plain sentence for the card and the details page. */
    static String explanation(TaskState state, boolean certificatePending, boolean selfSigned,
                              List<Participant> participants, boolean anyOrder,
                              Instant expires) {
        return switch (state) {
            case ACTION_REQUIRED -> certificatePending
                    ? "Register your signing key to take part in this request."
                    : "Your signature is required.";
            case WAITING -> {
                Participant current = participants.stream().filter(Participant::current)
                        .findFirst().orElse(null);
                if (selfSigned) {
                    Participant next = current != null ? current : participants.stream()
                            .filter(p -> !p.signed() && !p.you()).findFirst().orElse(null);
                    yield next == null || anyOrder && current == null
                            ? "You signed this document. Waiting for the other signers."
                            : "You signed this document. Waiting for " + next.name() + ".";
                }
                if (current != null && !current.you()) {
                    yield "Waiting for " + current.name() + " to sign"
                            + (anyOrder ? "." : " first.");
                }
                Participant me = participants.stream().filter(Participant::you)
                        .findFirst().orElse(null);
                Participant before = me == null ? null : participants.stream()
                        .filter(p -> p.order() < me.order() && !p.signed())
                        .findFirst().orElse(null);
                yield before == null ? "Waiting for the other signers."
                        : "Waiting for " + before.name() + " to sign first.";
            }
            case COMPLETED -> "All required signatures have been completed.";
            case EXPIRED -> expires == null ? "This signing request has expired."
                    : "This signing request expired on " + DateTimeFormatter
                            .ofPattern("MMMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault())
                            .format(expires) + ".";
            case DECLINED -> "This signing request was declined.";
            case CANCELLED -> "The requester withdrew this signing request.";
            case ERROR -> "This signing request stopped because of a problem.";
        };
    }

    /** The one primary action per state. */
    static String primaryAction(TaskState state, boolean certificatePending) {
        return switch (state) {
            case ACTION_REQUIRED -> certificatePending ? "Set up to sign" : "Review & Sign";
            case WAITING -> "View Workflow";
            case COMPLETED -> "Open Document";
            default -> "View Details";
        };
    }

    /** Maps and sorts a full backend payload with the default ordering. */
    public static List<SigningTask> fromAll(
            List<ApiContracts.TaskItemView> items) {
        return sort(items.stream().map(SigningTaskModels::from).toList(),
                TaskSort.NEEDS_ACTION_FIRST);
    }

    /* ------------------------------------------------------------------
     * search / filter / sort
     * ------------------------------------------------------------------ */

    /** Case-insensitive search across title, sender and workflow id. */
    public static List<SigningTask> search(List<SigningTask> tasks,
                                           String query) {
        if (query == null || query.isBlank()) {
            return tasks;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<SigningTask> result = new ArrayList<>();
        for (SigningTask task : tasks) {
            if (task.searchHaystack().contains(needle)) {
                result.add(task);
            }
        }
        return result;
    }

    /** Applies one workspace filter. */
    public static List<SigningTask> filter(List<SigningTask> tasks,
                                           TaskFilter filter) {
        if (filter == null || filter == TaskFilter.ALL) {
            return tasks;
        }
        List<SigningTask> result = new ArrayList<>();
        for (SigningTask task : tasks) {
            boolean match = switch (filter) {
                case NEEDS_ACTION -> task.state() == TaskState.ACTION_REQUIRED;
                // "Waiting" = waiting for someone else. Documents that need
                // the user's own signature live under "Action required" only,
                // so the filters never overlap.
                case WAITING -> task.state() == TaskState.WAITING;
                case COMPLETED -> task.state() == TaskState.COMPLETED;
                case DECLINED -> task.state() == TaskState.DECLINED;
                case EXPIRED -> task.state() == TaskState.EXPIRED;
                case REVOKED -> task.state() == TaskState.CANCELLED;
                default -> true;
            };
            if (match) {
                result.add(task);
            }
        }
        return result;
    }

    /** Sorts tasks: needs-action first (then newest), newest, or oldest. */
    public static List<SigningTask> sort(List<SigningTask> tasks,
                                         TaskSort sort) {
        Comparator<SigningTask> byDate = comparatorByDate();
        Comparator<SigningTask> base = switch (sort == null
                ? TaskSort.NEEDS_ACTION_FIRST : sort) {
            case NEWEST -> byDate;
            case OLDEST -> byDate.reversed();
            case RECENTLY_UPDATED -> Comparator.comparing(
                    (SigningTask t) -> t.updatedEpochSecond() == null
                            ? Long.MIN_VALUE : t.updatedEpochSecond(),
                    Comparator.reverseOrder());
            // Soonest expiry first; tasks without an expiry go last.
            case EXPIRING_SOON -> Comparator.comparing(
                    (SigningTask t) -> t.expiresEpochSecond() == null
                            ? Long.MAX_VALUE : t.expiresEpochSecond());
            case NEEDS_ACTION_FIRST -> Comparator
                    .comparing((SigningTask t) -> !t.needsAction())
                    .thenComparing(byDate);
        };
        List<SigningTask> copy = new ArrayList<>(tasks);
        copy.sort(base);
        return copy;
    }

    private static Comparator<SigningTask> comparatorByDate() {
        return Comparator.comparing(
                (SigningTask t) -> t.dateEpochSecond() == null
                        ? Long.MIN_VALUE : t.dateEpochSecond(),
                Comparator.reverseOrder());
    }

    /** How many tasks a filter would show (the counts on the filter buttons). */
    public static int count(List<SigningTask> tasks, TaskFilter filter) {
        return filter(tasks, filter).size();
    }

    /** The task the "Sign next" button opens: the oldest one needing action. */
    public static SigningTask nextToSign(List<SigningTask> tasks) {
        return sort(tasks, TaskSort.OLDEST).stream()
                .filter(SigningTask::needsAction)
                .findFirst().orElse(null);
    }

    /** Count of tasks that need the user's action (section header). */
    public static long needsActionCount(List<SigningTask> tasks) {
        return tasks.stream().filter(SigningTask::needsAction).count();
    }

    /* ------------------------------------------------------------------
     * friendly text helpers
     * ------------------------------------------------------------------ */

    /**
     * Turns a raw document filename into a readable title: the extension is
     * dropped, underscores/dashes become spaces, and known single words are
     * capitalised. Never invents content that is not in the filename.
     */
    public static String readableTitle(String documentName) {
        if (documentName == null || documentName.isBlank()) {
            return "Untitled document";
        }
        String name = documentName.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1
                && name.substring(dot + 1).equalsIgnoreCase("pdf")) {
            name = name.substring(0, dot);
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        if (name.isEmpty()) {
            return documentName;
        }
        // Title-case single generic words ("contract" → "Contract") but
        // leave mixed-case names ("AcmeNDA") untouched.
        if (name.equals(name.toLowerCase(Locale.ROOT))
                || name.equals(name.toUpperCase(Locale.ROOT))) {
            String[] parts = name.split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase(Locale.ROOT));
            }
            name = sb.toString();
        }
        return name;
    }

    /** "today", "yesterday", "3 days ago", or "Oct 5, 2026". */
    public static String relativeDate(Instant instant) {
        Instant now = Instant.now();
        long days = ChronoUnit.DAYS.between(
                instant.truncatedTo(ChronoUnit.DAYS),
                now.truncatedTo(ChronoUnit.DAYS));
        if (days <= 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        return DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }
}
