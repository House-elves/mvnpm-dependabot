import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The GitHub elf's FlowClassifierTest for its mvnpm lane, with the steps this tab takes. */
class FlowsTest {

    static final Instant NOW = Prs.NOW;

    static Flows.Ctx ctx(Bumps.Pr pr, String verdict) {
        return new Flows.Ctx(verdict == null ? null : Prs.checked(pr, verdict), verdict != null, false, NOW, null);
    }

    static void is(Flows.Flow f, String waiting, int step, String next) {
        assertEquals(waiting, f.waiting(), f.say());
        assertEquals(step, f.step(), f.say());
        if (next == null) assertNull(f.next(), f.say());
        else assertEquals(next, f.next().id(), f.say());
    }

    static List<String> ids(List<Flows.Next> next) {
        return next.stream().map(Flows.Next::id).toList();
    }

    @Test
    void aSafeBumpWithFailingCiIsRebasedFirst() {
        Bumps.Pr bump = Prs.bump("FAILURE", "UNSTABLE", "MERGEABLE", false);
        Flows.Flow f = Flows.flow(bump, ctx(bump, "SAFE"));
        is(f, Flows.YOU, 1, "dependabot-rebase");
        assertEquals(List.of("checks", "github"), ids(f.more()));

        // Asked: wait for Dependabot's push, no button to ask again.
        Instant asked = NOW.minusSeconds(120);
        Flows.Flow waiting = Flows.flow(bump, new Flows.Ctx(Prs.checked(bump, "SAFE"), true, false, NOW,
                Flows.rebaseAsked(new Board.Rebase(bump.headSha(), asked), bump, NOW)));
        is(waiting, Flows.OTHERS, 1, null);
        assertEquals(List.of("github"), ids(waiting.more()));
        // Once the head moved (or an hour went by), the ask is done with.
        assertNull(Flows.rebaseAsked(new Board.Rebase("older-sha", asked), bump, NOW));
        assertNull(Flows.rebaseAsked(new Board.Rebase(bump.headSha(), NOW.minusSeconds(7200)), bump, NOW));
    }

    @Test
    void aSafeBumpWhoseCiWaitsForYouAsksYouToApproveIt() {
        // quarkusio/quarkus#57185: Quarkus CI held at the "dependabot" environment, which you review.
        Bumps.Pr bump = Prs.bump(Bumps.AWAITING_APPROVAL, "BLOCKED", "MERGEABLE", false);
        is(Flows.flow(bump, ctx(bump, "SAFE")), Flows.YOU, 1, "approve-ci");
    }

    @Test
    void ciWaitingForApprovalCanBeApprovedBeforeTheNewCommitIsChecked() {
        // quarkusio/quarkus#57654: Dependabot rebased it, so its check is at an older head, and CI waits for you.
        Bumps.Pr bump = Prs.bump(Bumps.AWAITING_APPROVAL, "BLOCKED", "MERGEABLE", false);
        Flows.Flow f = Flows.flow(bump, new Flows.Ctx(new Board.Check("older", "SAFE", true, null, NOW), false, false, NOW, null));
        is(f, Flows.YOU, 0, "check");
        assertEquals(List.of("approve-ci"), ids(f.more()));
        assertEquals("Not checked at this commit. CI waits for you to approve the run.", f.say());
        // While it's being checked, nothing else.
        assertEquals(List.of(), ids(Flows.flow(bump, new Flows.Ctx(null, true, true, NOW, null)).more()));
    }

    @Test
    void notCheckedOrCheckedAtAnOlderHeadIsCheckedFirst() {
        Bumps.Pr bump = Prs.bump("SUCCESS", "CLEAN", "MERGEABLE", true);
        Flows.Flow never = Flows.flow(bump, ctx(bump, null));
        is(never, Flows.YOU, 0, "check");
        assertEquals("Not checked yet.", never.say());
        Flows.Flow older = Flows.flow(bump, new Flows.Ctx(new Board.Check("older", "SAFE", true, null, NOW), false, false, NOW, null));
        is(older, Flows.YOU, 0, "check");
        assertEquals("Not checked at this commit.", older.say());
    }

    @Test
    void whileTheElfChecksItItsTheAgentsMove() {
        Bumps.Pr bump = Prs.bump("SUCCESS", "CLEAN", "MERGEABLE", true);
        Flows.Flow f = Flows.flow(bump, new Flows.Ctx(null, false, true, NOW, NOW.minusSeconds(60)));
        is(f, Flows.AGENT, 0, null);
        assertEquals("mvnpm-dependabot is checking it.", f.say());
    }

    @Test
    void conflictsOrBehindAskDependabotToRebase() {
        Bumps.Pr conflicts = Prs.bump("SUCCESS", "DIRTY", "CONFLICTING", true);
        is(Flows.flow(conflicts, ctx(conflicts, "SAFE")), Flows.YOU, 1, "dependabot-rebase");
        Bumps.Pr behind = Prs.bump("SUCCESS", "BEHIND", "MERGEABLE", true);
        Flows.Flow f = Flows.flow(behind, ctx(behind, "NOT_SAFE"));
        is(f, Flows.YOU, 1, "dependabot-rebase");
        assertEquals("Behind its base.", f.say());
    }

    @Test
    void aSafeGreenBumpIsYoursToMergeApprovedOrNot() {
        Bumps.Pr approved = Prs.bump("SUCCESS", "CLEAN", "MERGEABLE", true);
        is(Flows.flow(approved, ctx(approved, "SAFE")), Flows.YOU, 1, "merge");
        Bumps.Pr notApproved = Prs.bump("SUCCESS", "CLEAN", "MERGEABLE", false);
        is(Flows.flow(notApproved, ctx(notApproved, "SAFE")), Flows.YOU, 1, "merge");
    }

    @Test
    void aSafeBumpWaitsOnRunningOrRequiredChecks() {
        Bumps.Pr running = Prs.bump("PENDING", "BLOCKED", "MERGEABLE", true);
        is(Flows.flow(running, ctx(running, "SAFE")), Flows.CI, 1, null);
        // Green, but GitHub still blocks it long after the last change: required checks it waits for.
        Bumps.Pr blocked = Prs.bump("SUCCESS", "BLOCKED", "MERGEABLE", true);
        Flows.Flow f = Flows.flow(blocked, ctx(blocked, "SAFE"));
        is(f, Flows.CI, 1, null);
        assertEquals("Safe. Waiting for required checks.", f.say());
    }

    @Test
    void notSafeIsClosedAndNeedsAHumanIsRead() {
        Bumps.Pr bump = Prs.bump("SUCCESS", "CLEAN", "MERGEABLE", false);
        Flows.Flow notSafe = Flows.flow(bump, ctx(bump, "NOT_SAFE"));
        is(notSafe, Flows.YOU, 1, "github");
        assertEquals(List.of("comment", "check"), ids(notSafe.more()));
        Flows.Flow human = Flows.flow(bump, ctx(bump, "NEEDS_HUMAN"));
        is(human, Flows.YOU, 1, "comment");
        assertEquals(List.of("check"), ids(human.more()));
    }
}
