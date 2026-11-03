package com.example.signer.client;

import com.example.signer.server.api.ApiContracts;
import com.example.signer.server.api.ApiStates.ParticipantState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Presentation-model mapping for the documents workspace: converts the raw
 * backend contracts ({@code TaskItemView} etc.) into human-facing models —
 * status labels, readable document titles, relative dates — and provides the
 * search / filter / sort behaviour. Pure logic, no JavaFX, so it is
 * unit-testable without the FX toolkit.
 *
 * <p>Every document belongs to one person, who is also its signer, so none
 * of this describes anybody else: there is no signing order, no waiting for
 * a co-signer and no expiry to watch.
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
     * One document as the UI renders it. Built exclusively from a backend
     * {@code TaskItemView} — no invented data.
     */
    public record SigningTask(
            String workflowId,
            String title,
            String statusLabel,
            String statusStyle,
            String dateText,
            /** Epoch second of the document's date (upload or signature). */
            Long dateEpochSecond,
            String dateKind,
            boolean needsAction,
            boolean canSign,
            boolean certificatePending,
            String myStateRaw,
            /** The one state the screen is organised around. */
            TaskState state,
            /** One plain sentence: what is going on. */
            String explanation,
            /** Label of the single primary action for this state. */
            String primaryAction,
            Long updatedEpochSecond) {

        /** Search: the document title and its id. */
        public String searchHaystack() {
            return String.join(" ",
                    title == null ? "" : title.toLowerCase(Locale.ROOT),
                    workflowId == null ? "" : workflowId.toLowerCase(Locale.ROOT));
        }
    }

    /** The states the documents screen shows. */
    public enum TaskState {
        ACTION_REQUIRED("Ready to sign", "action"),
        IN_PROGRESS("In progress", "waiting"),
        COMPLETED("Signed", "done"),
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

    /** The simple filter set of the workspace toolbar. */
    public enum TaskFilter {
        ALL, NEEDS_ACTION, IN_PROGRESS, COMPLETED, CANCELLED
    }

    /** Sort options of the workspace toolbar. */
    public enum TaskSort {
        NEEDS_ACTION_FIRST, RECENTLY_UPDATED, NEWEST, OLDEST
    }

    /* ------------------------------------------------------------------
     * mapping
     * ------------------------------------------------------------------ */

    /** Maps one backend task item to the presentation model. */
    public static SigningTask from(ApiContracts.TaskItemView item) {
        ParticipantState raw = item.myState() == null
                ? ParticipantState.UNKNOWN : item.myState().state();
        boolean waitingForMe = item.myState() != null
                && item.myState().waitingForMe();
        boolean certificatePending = raw == ParticipantState.CERTIFICATE_PENDING;
        // A document is signed ONLY when the server says so.
        boolean selfSigned = raw == ParticipantState.SIGNED
                && item.myState() != null && item.myState().signed();

        boolean needsAction = waitingForMe || certificatePending;
        boolean canSign = needsAction;

        TaskState state;
        if (certificatePending) {
            state = TaskState.ACTION_REQUIRED;
        } else if (waitingForMe) {
            state = TaskState.ACTION_REQUIRED;
        } else {
            state = switch (raw) {
                case SIGNED -> selfSigned ? TaskState.COMPLETED : TaskState.IN_PROGRESS;
                case CANCELLED -> TaskState.CANCELLED;
                case BLOCKED -> TaskState.ERROR;
                default -> TaskState.IN_PROGRESS;
            };
        }
        String label = certificatePending ? "Set up to sign" : state.label();
        String style = state.style();

        // Signed documents show the signature date; everything else shows
        // when the document was uploaded.
        String dateText = null;
        Long dateEpoch = null;
        String dateKind = "Uploaded";
        String iso = item.completedAt() != null ? item.completedAt()
                : item.createdAt();
        if (item.completedAt() != null) {
            dateKind = "Signed";
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
        Instant updated = parse(item.updatedAt());

        return new SigningTask(item.workflowId(),
                readableTitle(item.documentName()),
                label, style, dateText, dateEpoch, dateKind,
                needsAction, canSign, certificatePending,
                item.myState() == null ? null : item.myState().myStateLabel(),
                state,
                explanation(state, certificatePending),
                primaryAction(state, certificatePending),
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
    static String explanation(TaskState state, boolean certificatePending) {
        return switch (state) {
            case ACTION_REQUIRED -> certificatePending
                    ? "Register your signing key, then sign this document."
                    : "This document is ready for your signature.";
            case IN_PROGRESS -> "This document is being prepared.";
            case COMPLETED -> "You signed this document.";
            case CANCELLED -> "You cancelled this document.";
            case ERROR -> "Signing stopped because of a problem.";
        };
    }

    /** The one primary action per state. */
    static String primaryAction(TaskState state, boolean certificatePending) {
        return switch (state) {
            case ACTION_REQUIRED -> certificatePending ? "Set up to sign" : "Review & Sign";
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

    /** Case-insensitive search across title and workflow id. */
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
                case IN_PROGRESS -> task.state() == TaskState.IN_PROGRESS;
                case COMPLETED -> task.state() == TaskState.COMPLETED;
                case CANCELLED -> task.state() == TaskState.CANCELLED;
                default -> true;
            };
            if (match) {
                result.add(task);
            }
        }
        return result;
    }

    /** Sorts documents: needs-action first (then newest), newest, or oldest. */
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

    /** How many documents a filter would show (the counts on the filter buttons). */
    public static int count(List<SigningTask> tasks, TaskFilter filter) {
        return filter(tasks, filter).size();
    }

    /** The document the "Sign next" button opens: the oldest one needing action. */
    public static SigningTask nextToSign(List<SigningTask> tasks) {
        return sort(tasks, TaskSort.OLDEST).stream()
                .filter(SigningTask::needsAction)
                .findFirst().orElse(null);
    }

    /** Count of documents that need the user's action (section header). */
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
