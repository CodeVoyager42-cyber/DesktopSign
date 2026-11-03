package com.example.signer.client;

import com.example.signer.server.api.ApiContracts.ParticipantStateView;
import com.example.signer.server.api.ApiContracts.TaskItemView;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure presentation-model mapping of the documents workspace: backend
 * states → human labels, readable titles, search / filter / sort behaviour
 * and the needs-action grouping.
 *
 * <p>One document has exactly one signer — its owner — so every state here
 * is the signed-in user's own state. Nothing waits for anybody else.
 */
class SigningTaskModelsTest {

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    private static final String NOW_ISO = Instant.now().toString();

    /** A backend item in {@code state}; SIGNED also carries a signature date. */
    private static TaskItemView item(String id, String docName, String state,
                                     String createdAt) {
        boolean signed = "SIGNED".equals(state);
        boolean waitingForMe = "YOUR_TURN".equals(state);
        return new TaskItemView(id, docName, "alice", "Alice Smith",
                "WAITING_FOR_SIGNER", createdAt, signed ? createdAt : null,
                new ParticipantStateView(state, 1, signed, waitingForMe),
                createdAt);
    }

    private static SigningTaskModels.SigningTask mapped(String state) {
        return SigningTaskModels.from(item("WF-1", "contract.pdf", state, NOW_ISO));
    }

    @Nested
    class StatusMapping {

        @Test
        void yourTurnMapsToActionRequired() {
            SigningTaskModels.SigningTask t = mapped("YOUR_TURN");
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.ACTION_REQUIRED);
            assertThat(t.statusLabel()).isEqualTo("Ready to sign");
            assertThat(t.statusStyle()).isEqualTo("action");
            assertThat(t.needsAction()).isTrue();
            assertThat(t.canSign()).isTrue();
            assertThat(t.explanation()).isEqualTo("This document is ready for your signature.");
            assertThat(t.primaryAction()).isEqualTo("Review & Sign");
        }

        @Test
        void certificatePendingIsActionableAndSaysSoInsteadOfReadyToSign() {
            SigningTaskModels.SigningTask t = mapped("CERTIFICATE_PENDING");
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.ACTION_REQUIRED);
            assertThat(t.statusLabel()).isEqualTo("Set up to sign");
            assertThat(t.needsAction()).isTrue();
            assertThat(t.certificatePending()).isTrue();
            assertThat(t.explanation())
                    .isEqualTo("Register your signing key, then sign this document.");
            assertThat(t.primaryAction()).isEqualTo("Set up to sign");
        }

        @Test
        void signedShowsTheSignatureDate() {
            SigningTaskModels.SigningTask t = mapped("SIGNED");
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.COMPLETED);
            assertThat(t.statusLabel()).isEqualTo("Signed");
            assertThat(t.statusStyle()).isEqualTo("done");
            assertThat(t.dateKind()).isEqualTo("Signed");
            assertThat(t.needsAction()).isFalse();
            assertThat(t.primaryAction()).isEqualTo("Open Document");
        }

        /**
         * {@code SIGNED} without the server's {@code signed} flag is never
         * shown as a signature — the flag is the only authority.
         */
        @Test
        void signedWithoutTheServerFlagIsNotCalledSigned() {
            TaskItemView unproven = new TaskItemView("WF-U", "contract.pdf", "alice",
                    "Alice Smith", "WAITING_FOR_SIGNER", NOW_ISO, null,
                    new ParticipantStateView("SIGNED", 1, false, false), NOW_ISO);
            SigningTaskModels.SigningTask t = SigningTaskModels.from(unproven);
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.IN_PROGRESS);
            assertThat(t.statusLabel()).isEqualTo("In progress");
            assertThat(t.dateKind()).isEqualTo("Uploaded");
        }

        @Test
        void inProgressIsNotActionable() {
            SigningTaskModels.SigningTask t = mapped("IN_PROGRESS");
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.IN_PROGRESS);
            assertThat(t.statusLabel()).isEqualTo("In progress");
            assertThat(t.statusStyle()).isEqualTo("waiting");
            assertThat(t.needsAction()).isFalse();
            assertThat(t.canSign()).isFalse();
        }

        @Test
        void cancelledAndBlockedHaveTheirOwnLabels() {
            assertThat(mapped("CANCELLED").statusLabel()).isEqualTo("Cancelled");
            assertThat(mapped("CANCELLED").statusStyle()).isEqualTo("muted");
            assertThat(mapped("BLOCKED").statusLabel()).isEqualTo("Needs attention");
            assertThat(mapped("BLOCKED").statusStyle()).isEqualTo("bad");
            assertThat(mapped("BLOCKED").explanation())
                    .isEqualTo("Signing stopped because of a problem.");
        }

        @Test
        void aStateThisBuildDoesNotKnowIsShownAsInProgress() {
            SigningTaskModels.SigningTask t = mapped("SOMETHING_NEW");
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.IN_PROGRESS);
            assertThat(t.myStateRaw()).isEqualTo("SOMETHING_NEW");
        }

        @Test
        void aMissingStateNeverThrows() {
            TaskItemView none = new TaskItemView("WF-N", "contract.pdf", "alice",
                    "Alice Smith", "DRAFT", NOW_ISO, null, null, null);
            SigningTaskModels.SigningTask t = SigningTaskModels.from(none);
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.IN_PROGRESS);
            assertThat(t.myStateRaw()).isNull();
            assertThat(t.needsAction()).isFalse();
        }
    }

    @Nested
    class TitlesAndDates {

        @Test
        void stripsExtensionAndCleansSeparators() {
            assertThat(SigningTaskModels.readableTitle("contract.pdf"))
                    .isEqualTo("Contract");
            assertThat(SigningTaskModels.readableTitle("employment_agreement.pdf"))
                    .isEqualTo("Employment Agreement");
            assertThat(SigningTaskModels.readableTitle("NDA-Acme.pdf"))
                    .isEqualTo("NDA Acme");
        }

        @Test
        void keepsMixedCaseNames() {
            assertThat(SigningTaskModels.readableTitle("AcmeNDA_v2.pdf"))
                    .isEqualTo("AcmeNDA v2");
        }

        @Test
        void blankFallsBackSafely() {
            assertThat(SigningTaskModels.readableTitle(null))
                    .isEqualTo("Untitled document");
            assertThat(SigningTaskModels.readableTitle("  "))
                    .isEqualTo("Untitled document");
        }

        @Test
        void relativeDateTodayYesterday() {
            assertThat(SigningTaskModels.relativeDate(Instant.now()))
                    .isEqualTo("today");
            assertThat(SigningTaskModels.relativeDate(
                    Instant.now().minusSeconds(86_400))).isEqualTo("yesterday");
        }

        @Test
        void anUnreadableTimestampNeverReachesACard() {
            TaskItemView broken = new TaskItemView("WF-B", "contract.pdf", "alice",
                    "Alice Smith", "WAITING_FOR_SIGNER", "not a date", null,
                    new ParticipantStateView("YOUR_TURN", 1, false, true), null);
            SigningTaskModels.SigningTask t = SigningTaskModels.from(broken);
            assertThat(t.dateText()).isNull();
            assertThat(t.dateEpochSecond()).isNull();
        }
    }

    @Nested
    class SearchFilterSort {

        private final List<TaskItemView> items = List.of(
                item("WF-1", "contract.pdf", "YOUR_TURN", Instant.now().toString()),
                item("WF-2", "nda.pdf", "SIGNED",
                        Instant.now().minusSeconds(7200).toString()),
                item("WF-3", "employment_agreement.pdf", "IN_PROGRESS",
                        Instant.now().minusSeconds(3600).toString()));

        @Test
        void searchMatchesTitleAndWorkflowId() {
            List<SigningTaskModels.SigningTask> mapped = SigningTaskModels.fromAll(items);
            assertThat(SigningTaskModels.search(mapped, "contract")).hasSize(1);
            assertThat(SigningTaskModels.search(mapped, "WF-3")).hasSize(1);
            assertThat(SigningTaskModels.search(mapped, "agreement")).hasSize(1);
            assertThat(SigningTaskModels.search(mapped, "zzz")).isEmpty();
            assertThat(SigningTaskModels.search(mapped, "  ")).hasSize(3);
        }

        @Test
        void defaultSortPutsNeedsActionFirstThenNewest() {
            List<SigningTaskModels.SigningTask> mapped = SigningTaskModels.fromAll(items);
            assertThat(mapped.get(0).myStateRaw()).isEqualTo("YOUR_TURN");
            // among the rest, the newest first (WF-3 at -1h before WF-2 at -2h)
            assertThat(mapped.get(1).workflowId()).isEqualTo("WF-3");
            assertThat(mapped.get(2).workflowId()).isEqualTo("WF-2");
        }

        @Test
        void filtersDoNotOverlap() {
            List<SigningTaskModels.SigningTask> mapped = SigningTaskModels.fromAll(items);
            assertThat(SigningTaskModels.count(mapped,
                    SigningTaskModels.TaskFilter.NEEDS_ACTION)).isEqualTo(1);
            assertThat(SigningTaskModels.count(mapped,
                    SigningTaskModels.TaskFilter.IN_PROGRESS)).isEqualTo(1);
            assertThat(SigningTaskModels.count(mapped,
                    SigningTaskModels.TaskFilter.COMPLETED)).isEqualTo(1);
            assertThat(SigningTaskModels.count(mapped,
                    SigningTaskModels.TaskFilter.CANCELLED)).isZero();
            assertThat(SigningTaskModels.count(mapped,
                    SigningTaskModels.TaskFilter.ALL)).isEqualTo(3);
        }

        @Test
        void filterCompletedReturnsOnlyDone() {
            List<SigningTaskModels.SigningTask> done = SigningTaskModels.filter(
                    SigningTaskModels.fromAll(items),
                    SigningTaskModels.TaskFilter.COMPLETED);
            assertThat(done).hasSize(1);
            assertThat(done.get(0).statusLabel()).isEqualTo("Signed");
        }

        @Test
        void needsActionCountIsCorrect() {
            assertThat(SigningTaskModels.needsActionCount(
                    SigningTaskModels.fromAll(items))).isEqualTo(1);
        }

        @Test
        void oldestSortReversesDates() {
            List<SigningTaskModels.SigningTask> sorted = SigningTaskModels.sort(
                    SigningTaskModels.fromAll(items),
                    SigningTaskModels.TaskSort.OLDEST);
            assertThat(sorted.get(sorted.size() - 1).workflowId()).isEqualTo("WF-1");
        }
    }

    @Nested
    class NextToSign {

        private final List<SigningTaskModels.SigningTask> tasks = List.of(
                SigningTaskModels.from(item("WF-A", "a.pdf", "YOUR_TURN",
                        Instant.now().minusSeconds(86_400L * 3).toString())),
                SigningTaskModels.from(item("WF-B", "b.pdf", "YOUR_TURN",
                        NOW_ISO)),
                SigningTaskModels.from(item("WF-C", "c.pdf", "IN_PROGRESS", NOW_ISO)),
                SigningTaskModels.from(item("WF-D", "d.pdf", "SIGNED", NOW_ISO)));

        @Test
        void signNextOpensTheOldestDocumentWaitingForTheUser() {
            assertThat(SigningTaskModels.nextToSign(tasks).workflowId()).isEqualTo("WF-A");
        }

        @Test
        void withNothingToSignThereIsNoNextDocument() {
            assertThat(SigningTaskModels.nextToSign(
                    List.of(tasks.get(2), tasks.get(3)))).isNull();
        }
    }
}
