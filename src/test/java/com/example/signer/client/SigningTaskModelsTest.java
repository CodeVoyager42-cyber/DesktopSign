package com.example.signer.client;

import com.example.signer.server.api.ApiContracts.ParticipantStateView;
import com.example.signer.server.api.ApiContracts.TaskItemView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the pure presentation-model mapping of the Signing Tasks workspace
 * (brief §5/§28): backend states → human labels, readable titles, search /
 * filter / sort behaviour, and the needs-action grouping.
 */
class SigningTaskModelsTest {

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    private static TaskItemView item(String id, String docName, String sender,
                                     String state, String createdAt) {
        boolean waitingForMe = "YOUR_TURN".equals(state);
        boolean waitingForClaim = "WAITING_FOR_CLAIM".equals(state);
        boolean signed = "SIGNED".equals(state);
        return new TaskItemView(id, docName, sender, sender,
                "WAITING_FOR_SIGNER", createdAt,
                signed ? createdAt : null,
                new ParticipantStateView(state, 1, signed,
                        waitingForMe, waitingForClaim),
                2, signed ? 2 : 1);
    }

    private static final String NOW_ISO =
            Instant.now().toString();

    @Nested
    class StatusMapping {

        @Test
        void yourTurnMapsToActionRequired() {
            SigningTaskModels.SigningTask t = SigningTaskModels.from(
                    item("WF-1", "contract.pdf", "alice", "YOUR_TURN", NOW_ISO));
            assertThat(t.statusLabel()).isEqualTo("Action required");
            assertThat(t.statusStyle()).isEqualTo("action");
            assertThat(t.needsAction()).isTrue();
            assertThat(t.canSign()).isTrue();
        }

        @Test
        void waitingForClaimIsActionable() {
            SigningTaskModels.SigningTask t = SigningTaskModels.from(
                    item("WF-2", "contract.pdf", "alice", "WAITING_FOR_CLAIM",
                            NOW_ISO));
            assertThat(t.statusLabel()).isEqualTo("Ready for you to sign");
            assertThat(t.needsAction()).isTrue();
            assertThat(t.waitingForClaim()).isTrue();
        }

        @Test
        void certificatePendingIsActionable() {
            SigningTaskModels.SigningTask t = SigningTaskModels.from(
                    item("WF-3", "contract.pdf", "alice",
                            "CERTIFICATE_PENDING", NOW_ISO));
            assertThat(t.statusLabel()).isEqualTo("Set up to sign");
            assertThat(t.needsAction()).isTrue();
        }

        @Test
        void waitingForOthersIsNotActionable() {
            SigningTaskModels.SigningTask t = SigningTaskModels.from(
                    item("WF-4", "contract.pdf", "alice",
                            "WAITING_FOR_OTHERS", NOW_ISO));
            assertThat(t.statusLabel()).isEqualTo("Waiting for others");
            assertThat(t.needsAction()).isFalse();
        }

        @Test
        void completedShowsCompletionDate() {
            TaskItemView item = item("WF-5", "nda.pdf", "alice", "SIGNED",
                    NOW_ISO);
            SigningTaskModels.SigningTask t = SigningTaskModels.from(item);
            assertThat(t.statusLabel()).isEqualTo("Completed");
            assertThat(t.dateKind()).isEqualTo("Completed");
        }

        @Test
        void declinedExpiredRevokedBlockedLabels() {
            assertThat(SigningTaskModels.from(item("A", "d.pdf", "s",
                    "DECLINED", NOW_ISO)).statusLabel()).isEqualTo("Declined");
            assertThat(SigningTaskModels.from(item("B", "d.pdf", "s",
                    "EXPIRED", NOW_ISO)).statusLabel()).isEqualTo("Expired");
            assertThat(SigningTaskModels.from(item("C", "d.pdf", "s",
                    "REVOKED", NOW_ISO)).statusLabel()).isEqualTo("Withdrawn");
            assertThat(SigningTaskModels.from(item("D", "d.pdf", "s",
                    "BLOCKED", NOW_ISO)).statusLabel())
                    .isEqualTo("Needs attention");
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
                    Instant.now().minusSeconds(86400)))
                    .isEqualTo("yesterday");
        }

        @Test
        void senderFallsBackToId() {
            TaskItemView noName = new TaskItemView("WF-9", "contract.pdf",
                    "alice", null, "WAITING_FOR_SIGNER", NOW_ISO, null,
                    new ParticipantStateView("YOUR_TURN", 1, false, true, false),
                    2, 1);
            SigningTaskModels.SigningTask t = SigningTaskModels.from(noName);
            assertThat(t.senderName()).isEqualTo("alice");
        }
    }

    @Nested
    class SearchFilterSort {

        private final List<TaskItemView> items = List.of(
                item("WF-1", "contract.pdf", "alice", "YOUR_TURN",
                        Instant.now().toString()),
                item("WF-2", "nda.pdf", "bob", "SIGNED",
                        Instant.now().minusSeconds(7200).toString()),
                item("WF-3", "employment_agreement.pdf", "carol",
                        "WAITING_FOR_OTHERS",
                        Instant.now().minusSeconds(3600).toString()));

        @Test
        void searchMatchesTitleAndSender() {
            List<SigningTaskModels.SigningTask> mapped =
                    SigningTaskModels.fromAll(items);
            assertThat(SigningTaskModels.search(mapped, "contract"))
                    .hasSize(1);
            assertThat(SigningTaskModels.search(mapped, "BOB"))
                    .hasSize(1);
            assertThat(SigningTaskModels.search(mapped, "agreement"))
                    .hasSize(1);
            assertThat(SigningTaskModels.search(mapped, "zzz")).isEmpty();
        }

        @Test
        void defaultSortPutsNeedsActionFirstThenNewest() {
            List<SigningTaskModels.SigningTask> mapped =
                    SigningTaskModels.fromAll(items);
            assertThat(mapped.get(0).myStateRaw()).isEqualTo("YOUR_TURN");
            // among the rest, the newest first (wf-3 at -1h before wf-2 at -2h)
            assertThat(mapped.get(1).workflowId()).isEqualTo("WF-3");
            assertThat(mapped.get(2).workflowId()).isEqualTo("WF-2");
        }

        @Test
        void filterCompletedReturnsOnlyDone() {
            List<SigningTaskModels.SigningTask> mapped =
                    SigningTaskModels.fromAll(items);
            List<SigningTaskModels.SigningTask> done = SigningTaskModels.filter(
                    mapped, SigningTaskModels.TaskFilter.COMPLETED);
            assertThat(done).hasSize(1);
            assertThat(done.get(0).statusLabel()).isEqualTo("Completed");
        }

        @Test
        void filterNeedsActionReturnsActionable() {
            List<SigningTaskModels.SigningTask> mapped =
                    SigningTaskModels.fromAll(items);
            List<SigningTaskModels.SigningTask> action =
                    SigningTaskModels.filter(mapped,
                            SigningTaskModels.TaskFilter.NEEDS_ACTION);
            assertThat(action).hasSize(1);
            assertThat(action.get(0).needsAction()).isTrue();
        }

        @Test
        void needsActionCountIsCorrect() {
            assertThat(SigningTaskModels.needsActionCount(
                    SigningTaskModels.fromAll(items))).isEqualTo(1);
        }

        @Test
        void oldestSortReversesDates() {
            List<SigningTaskModels.SigningTask> mapped =
                    SigningTaskModels.fromAll(items);
            List<SigningTaskModels.SigningTask> sorted = SigningTaskModels.sort(
                    mapped, SigningTaskModels.TaskSort.OLDEST);
            assertThat(sorted.get(sorted.size() - 1).workflowId())
                    .isEqualTo("WF-1");
        }
    }

    @Nested
    class FilterCountsAndNext {

        private final List<SigningTaskModels.SigningTask> tasks = List.of(
                SigningTaskModels.from(item("WF-A", "a.pdf", "alice", "YOUR_TURN",
                        Instant.now().minusSeconds(86_400L * 3).toString())),
                SigningTaskModels.from(item("WF-B", "b.pdf", "alice", "WAITING_FOR_CLAIM",
                        NOW_ISO)),
                SigningTaskModels.from(item("WF-C", "c.pdf", "alice", "WAITING_FOR_OTHERS",
                        NOW_ISO)),
                SigningTaskModels.from(item("WF-D", "d.pdf", "alice", "SIGNED", NOW_ISO)));

        @Test
        void filtersDoNotOverlap() {
            assertThat(SigningTaskModels.count(tasks, SigningTaskModels.TaskFilter.NEEDS_ACTION))
                    .isEqualTo(2);
            assertThat(SigningTaskModels.filter(tasks, SigningTaskModels.TaskFilter.WAITING))
                    .extracting(SigningTaskModels.SigningTask::workflowId)
                    .containsExactly("WF-C");
            assertThat(SigningTaskModels.count(tasks, SigningTaskModels.TaskFilter.COMPLETED))
                    .isEqualTo(1);
            assertThat(SigningTaskModels.count(tasks, SigningTaskModels.TaskFilter.ALL))
                    .isEqualTo(4);
        }

        @Test
        void signNextOpensTheOldestDocumentWaitingForTheUser() {
            assertThat(SigningTaskModels.nextToSign(tasks).workflowId()).isEqualTo("WF-A");
            assertThat(SigningTaskModels.nextToSign(List.of(tasks.get(2), tasks.get(3)))).isNull();
        }
    }

    @Nested
    class RedesignedStates {

        private TaskItemView full(String state, String wfStatus, String policy,
                                  List<com.example.signer.server.api.ApiContracts.TaskParticipantView> people,
                                  String expiresAt, int total, int done) {
            boolean me = "YOUR_TURN".equals(state);
            boolean claim = "WAITING_FOR_CLAIM".equals(state);
            boolean signed = "SIGNED".equals(state);
            return new TaskItemView("WF-R", "Employment_Contract.pdf", "alice", "Alice Smith",
                    wfStatus, NOW_ISO, signed ? NOW_ISO : null,
                    new ParticipantStateView(state, 2, signed, me, claim),
                    total, done, policy, people, expiresAt, NOW_ISO);
        }

        private com.example.signer.server.api.ApiContracts.TaskParticipantView p(
                int order, String name, boolean you, String st) {
            return new com.example.signer.server.api.ApiContracts.TaskParticipantView(order, name, you, st);
        }

        @Test
        void actionRequiredShowsRoleExpiryAndTheReviewButton() {
            String in30h = Instant.now().plusSeconds(30 * 3600).toString();
            var t = SigningTaskModels.from(full("YOUR_TURN", "WAITING_FOR_SIGNER", "SEQUENTIAL",
                    List.of(p(1, "Alice Smith", false, "SIGNED"), p(2, "Mouad", true, "CURRENT"),
                            p(3, "Charlie", false, "WAITING")), in30h, 3, 1));
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.ACTION_REQUIRED);
            assertThat(t.explanation()).isEqualTo("Your signature is required.");
            assertThat(t.primaryAction()).isEqualTo("Review & Sign");
            assertThat(t.yourRole()).isEqualTo("You are signer #2 of 3");
            assertThat(t.expiresText()).startsWith("Expires ");
            assertThat(t.expiresSoon()).isTrue();
            assertThat(t.anyOrder()).isFalse();
            assertThat(t.participants()).extracting(SigningTaskModels.Participant::name)
                    .containsExactly("Alice Smith", "Mouad", "Charlie");
        }

        @Test
        void waitingNamesWhoIsBlocking() {
            var blocked = SigningTaskModels.from(full("WAITING_FOR_OTHERS", "WAITING_FOR_SIGNER",
                    "SEQUENTIAL", List.of(p(1, "Alice Smith", false, "CURRENT"),
                            p(2, "Mouad", true, "WAITING")), null, 2, 0));
            assertThat(blocked.state()).isEqualTo(SigningTaskModels.TaskState.WAITING);
            assertThat(blocked.explanation()).isEqualTo("Waiting for Alice Smith to sign first.");
            assertThat(blocked.primaryAction()).isEqualTo("View Workflow");

            var signedByMe = SigningTaskModels.from(full("SIGNED", "WAITING_FOR_SIGNER",
                    "SEQUENTIAL", List.of(p(1, "Mouad", true, "SIGNED"),
                            p(2, "Charlie", false, "CURRENT")), null, 2, 1));
            assertThat(signedByMe.state()).isEqualTo(SigningTaskModels.TaskState.WAITING);
            assertThat(signedByMe.explanation())
                    .isEqualTo("You signed this document. Waiting for Charlie.");
        }

        @Test
        void completedExpiredDeclinedAndCancelledHaveTheirOwnStateAndAction() {
            var done = SigningTaskModels.from(full("SIGNED", "COMPLETED", "SEQUENTIAL",
                    List.of(p(1, "Mouad", true, "SIGNED")), null, 1, 1));
            assertThat(done.state()).isEqualTo(SigningTaskModels.TaskState.COMPLETED);
            assertThat(done.primaryAction()).isEqualTo("Open Document");
            assertThat(done.explanation()).isEqualTo("All required signatures have been completed.");

            var expired = SigningTaskModels.from(full("EXPIRED", "WAITING_FOR_SIGNER", "SEQUENTIAL",
                    List.of(), "2026-10-06T10:00:00Z", 1, 0));
            assertThat(expired.state()).isEqualTo(SigningTaskModels.TaskState.EXPIRED);
            assertThat(expired.explanation()).startsWith("This signing request expired on October");
            assertThat(expired.primaryAction()).isEqualTo("View Details");

            assertThat(SigningTaskModels.from(full("DECLINED", "WAITING_FOR_SIGNER", "SEQUENTIAL",
                    List.of(), null, 1, 0)).state()).isEqualTo(SigningTaskModels.TaskState.DECLINED);
            var cancelled = SigningTaskModels.from(full("CANCELLED", "CANCELLED", "SEQUENTIAL",
                    List.of(), null, 1, 0));
            assertThat(cancelled.state()).isEqualTo(SigningTaskModels.TaskState.CANCELLED);
            assertThat(cancelled.explanation()).isEqualTo("The requester withdrew this signing request.");
            assertThat(SigningTaskModels.filter(List.of(cancelled), SigningTaskModels.TaskFilter.REVOKED))
                    .hasSize(1);
            assertThat(SigningTaskModels.from(full("BLOCKED", "FAILED", "SEQUENTIAL",
                    List.of(), null, 1, 0)).state()).isEqualTo(SigningTaskModels.TaskState.ERROR);
        }

        @Test
        void flexibleWorkflowsNeverImplyAnOrder() {
            var t = SigningTaskModels.from(full("WAITING_FOR_CLAIM", "WAITING_FOR_SIGNER", "FLEXIBLE",
                    List.of(p(1, "Alice Smith", false, "SIGNED"), p(2, "Mouad", true, "WAITING"),
                            p(3, "Bob", false, "WAITING")), null, 3, 1));
            assertThat(t.anyOrder()).isTrue();
            assertThat(t.yourRole()).isEqualTo("You are one of 3 signers");
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.ACTION_REQUIRED);
        }

        @Test
        void searchFindsSignersAndSortingByExpiryPutsTheSoonestFirst() {
            var soon = SigningTaskModels.from(full("YOUR_TURN", "WAITING_FOR_SIGNER", "SEQUENTIAL",
                    List.of(p(1, "Charlie Brown", false, "SIGNED"), p(2, "Mouad", true, "CURRENT")),
                    Instant.now().plusSeconds(3600).toString(), 2, 1));
            var later = SigningTaskModels.from(full("YOUR_TURN", "WAITING_FOR_SIGNER", "SEQUENTIAL",
                    List.of(p(1, "Mouad", true, "CURRENT")),
                    Instant.now().plusSeconds(86_400L * 5).toString(), 1, 0));
            assertThat(SigningTaskModels.search(List.of(soon, later), "charlie")).containsExactly(soon);
            assertThat(SigningTaskModels.sort(List.of(later, soon),
                    SigningTaskModels.TaskSort.EXPIRING_SOON)).containsExactly(soon, later);
        }

        @Test
        void olderServersWithoutTheNewFieldsStillWork() {
            var t = SigningTaskModels.from(item("WF-OLD", "contract.pdf", "alice", "YOUR_TURN", NOW_ISO));
            assertThat(t.state()).isEqualTo(SigningTaskModels.TaskState.ACTION_REQUIRED);
            assertThat(t.participants()).isEmpty();
            assertThat(t.expiresText()).isNull();
            assertThat(t.yourRole()).isNull();
        }
    }
}
