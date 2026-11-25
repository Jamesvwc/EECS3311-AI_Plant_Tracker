package com.plantorganizer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.plantorganizer.agent.PendingChanges.PendingChange;
import com.plantorganizer.support.MutableClock;

class PendingChangesTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"), ZoneId.of("UTC"));
    private final PendingChanges pending = new PendingChanges(clock);
    private final AtomicInteger runs = new AtomicInteger();

    private PendingChange propose(long userId) {
        return pending.propose(userId, "MARK_WATERED", "Mark Fern as watered now?", "Yes", () -> {
            runs.incrementAndGet();
            return ChatReply.of("Done.", "MARK_WATERED");
        });
    }

    @Test
    void aProposalIsTakenOnceAndOnlyByItsOwner() {
        PendingChange proposal = propose(1);
        assertThat(pending.take(2, proposal.id())).isEmpty();
        assertThat(pending.take(1, proposal.id())).contains(proposal);
        assertThat(pending.take(1, proposal.id())).isEmpty();
        assertThat(runs).hasValue(0); // taking hands the change over; it never runs by itself
    }

    @Test
    void proposalsExpire() {
        PendingChange proposal = propose(1);
        clock.advance(PendingChanges.LIFETIME.plus(Duration.ofSeconds(1)));
        assertThat(pending.isWaiting(1, proposal.id())).isFalse();
        assertThat(pending.take(1, proposal.id())).isEmpty();
    }

    @Test
    void onlyTheNewestProposalsAreKept() {
        List<PendingChange> made = new ArrayList<>();
        for (int i = 0; i < PendingChanges.MAX_PER_USER + 2; i++) {
            made.add(propose(1));
        }
        assertThat(pending.isWaiting(1, made.get(0).id())).isFalse();
        assertThat(pending.isWaiting(1, made.get(1).id())).isFalse();
        assertThat(pending.isWaiting(1, made.get(2).id())).isTrue();
        assertThat(pending.isWaiting(1, made.get(made.size() - 1).id())).isTrue();
    }

    @Test
    void theLastOfferListsOnlyButtonsStillWaiting() {
        PendingChange first = propose(1);
        PendingChange second = propose(1);
        pending.offered(1, new ChatReply("Two changes", List.of(PendingChanges.button("Yes", "APPROVE", first),
                PendingChanges.button("Yes", "APPROVE", second), ChatAction.open("Open", "x.html")), "X", List.of()));
        assertThat(pending.lastOffer(1)).hasSize(2);

        pending.take(1, first.id());
        assertThat(pending.lastOffer(1)).extracting(PendingChanges::proposalId).containsExactly(second.id());

        pending.offered(1, ChatReply.of("No buttons", "GENERAL"));
        assertThat(pending.lastOffer(1)).isEmpty();
        assertThat(pending.isWaiting(1, second.id())).isTrue(); // still approvable from its own message

        pending.clear(1);
        assertThat(pending.isWaiting(1, second.id())).isFalse();
    }
}
