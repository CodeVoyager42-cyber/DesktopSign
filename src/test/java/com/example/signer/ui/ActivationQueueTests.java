package com.example.signer.ui;

import com.example.signer.ui.InvitationLinks.LaunchArgument;
import com.example.signer.ui.SingleInstanceGuard.Reply;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationQueueTests {

    private static LaunchArgument launch(char c) {
        return new LaunchArgument(LaunchArgument.Kind.LAUNCH, String.valueOf(c).repeat(43));
    }

    private static LaunchArgument invitation(char c) {
        return new LaunchArgument(LaunchArgument.Kind.INVITATION, String.valueOf(c).repeat(40));
    }

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final ActivationQueue queue = new ActivationQueue(now::get);

    @Test
    void requestsWaitForTheWindowInArrivalOrder() {
        assertThat(queue.activate(launch('a'))).isEqualTo(Reply.ACCEPTED);
        assertThat(queue.activate(null)).isEqualTo(Reply.ACCEPTED);
        assertThat(queue.activate(invitation('b'))).isEqualTo(Reply.ACCEPTED);
        List<LaunchArgument> live = new ArrayList<>();
        List<LaunchArgument> queued = queue.ready(live::add);
        assertThat(queued).containsExactly(launch('a'), null, invitation('b'));
        queue.activate(launch('c'));
        assertThat(live).containsExactly(launch('c'));
    }

    @Test
    void theQueueIsBounded() {
        for (int i = 0; i < ActivationQueue.MAX_PENDING + 3; i++) {
            queue.activate(launch((char) ('a' + i)));
        }
        List<LaunchArgument> queued = queue.ready(a -> { });
        assertThat(queued).hasSize(ActivationQueue.MAX_PENDING);
        assertThat(queued.get(queued.size() - 1)).isEqualTo(
                launch((char) ('a' + ActivationQueue.MAX_PENDING + 2)));
    }

    @Test
    void theSameLinkTwiceWithinTwoMinutesOpensOnce() {
        List<LaunchArgument> opened = new ArrayList<>();
        queue.ready(opened::add);
        queue.activate(launch('a'));
        queue.activate(launch('a'));
        now.addAndGet(ActivationQueue.DUPLICATE_WINDOW_MS - 1);
        queue.activate(launch('a'));
        assertThat(opened).hasSize(1);
        now.addAndGet(2);
        queue.activate(launch('a'));
        assertThat(opened).hasSize(2);
        // "Just show the window" is never deduplicated; different kinds differ.
        queue.activate(null);
        queue.activate(null);
        assertThat(opened).hasSize(4);
    }

    @Test
    void aForwardedCopyOfTheStartUpLinkIsIgnored() {
        queue.markOpened(launch('s'));
        List<LaunchArgument> opened = new ArrayList<>();
        queue.ready(opened::add);
        assertThat(queue.activate(launch('s'))).isEqualTo(Reply.ACCEPTED);
        assertThat(opened).isEmpty();
    }

    @Test
    void afterCloseEveryRequestIsToldToWait() {
        queue.activate(launch('a'));
        queue.close();
        assertThat(queue.activate(launch('b'))).isEqualTo(Reply.CLOSING);
        assertThat(queue.ready(a -> { })).isEmpty();
    }
}
