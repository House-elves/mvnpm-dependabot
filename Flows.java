import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Where each bump is, whose move it is, and the next step: the rules of the
 * GitHub elf's FlowClassifier.mvnpm(), from when its board had an mvnpm lane,
 * with the steps this tab can take. Pure, so the rules are easy to test.
 */
final class Flows {

    private Flows() {}

    static final List<String> STEPS = List.of("Check", "Merge");

    static final String YOU = "you", AGENT = "agent", CI = "ci", OTHERS = "others";

    /** How long a rebase you asked Dependabot for counts as on its way. */
    static final Duration REBASE_WAIT = Duration.ofHours(1);

    /**
     * @param steps   the flow's stages, for the row's stepper
     * @param step    the current stage, an index into {@code steps}
     * @param waiting whose move it is: {@link #YOU}, {@link #AGENT} (the elf is checking it), {@link #CI} or {@link #OTHERS}
     * @param since   when it started waiting there (best effort)
     * @param say     one sentence on where it is
     * @param next    the main step, or null when there is nothing for you to do
     * @param more    other steps that fit now
     */
    record Flow(List<String> steps, int step, String waiting, Instant since, String say, Next next, List<Next> more) {

        Flow with(String newWaiting, Instant newSince, String newSay, Next newNext, List<Next> newMore) {
            return new Flow(steps, step, newWaiting, newSince, newSay, newNext, newMore);
        }
    }

    /**
     * A step the tab knows how to take.
     *
     * @param id check (run the elf on it), approve-ci, merge, dependabot-rebase (the elf's actions), comment (read
     *           the elf's comment on GitHub), github (the PR on GitHub), checks (its CI on GitHub)
     */
    record Next(String id, String label) {
    }

    /**
     * Everything the rules look at besides the PR.
     *
     * @param check       the elf's last recorded check, or null if it never checked this PR
     * @param current     that check was made at the PR's current head
     * @param checking    a check of this PR runs now
     * @param rebaseAsked you asked Dependabot to rebase this head in the last hour, or null
     */
    record Ctx(Board.Check check, boolean current, boolean checking, Instant now, Instant rebaseAsked) {
    }

    static Flow flow(Bumps.Pr i, Ctx c) {
        return rebasing(mvnpm(i, c), c);
    }

    /** You asked Dependabot to rebase: wait for it rather than ask again. */
    static Flow rebasing(Flow f, Ctx c) {
        if (c.rebaseAsked() == null || c.checking()) return f;
        List<Next> more = f.more().stream().filter(n -> n.id().equals("github")).toList();
        return f.with(OTHERS, c.rebaseAsked(), "You asked Dependabot to rebase it. Waiting for its push.", null, more);
    }

    /** You asked at this head within the last hour; null once the head moved or the hour is up. */
    static Instant rebaseAsked(Board.Rebase asked, Bumps.Pr i, Instant now) {
        if (asked == null || asked.sha() == null || !asked.sha().equals(i.headSha()) || asked.at().isBefore(now.minus(REBASE_WAIT))) {
            return null;
        }
        return asked.at();
    }

    /**
     * A Dependabot mvnpm bump: the elf checks it, then you merge it (or close it). CI waiting for approval can always
     * be approved, checked or not: a new commit (Dependabot's rebase) needs its runs approved before it's checked again.
     */
    static Flow mvnpm(Bumps.Pr i, Ctx c) {
        Flow f = mvnpmState(i, c);
        boolean offered = f.next() != null && f.next().id().equals("approve-ci") || f.more().stream().anyMatch(n -> n.id().equals("approve-ci"));
        if (!Bumps.AWAITING_APPROVAL.equals(i.ci()) || c.checking() || offered) return f;
        List<Next> more = new java.util.ArrayList<>();
        more.add(new Next("approve-ci", "Approve CI run"));
        more.addAll(f.more());
        return f.with(f.waiting(), f.since(), f.say() + " CI waits for you to approve the run.", f.next(), List.copyOf(more));
    }

    private static Flow mvnpmState(Bumps.Pr i, Ctx c) {
        Board.Check r = c.check();
        if (c.checking()) return flow(0, AGENT, c.now(), "mvnpm-dependabot is checking it.", null, List.of());
        if (r == null || !c.current()) {
            return flow(0, YOU, i.updatedAt(), r == null ? "Not checked yet." : "Not checked at this commit.",
                    new Next("check", "Check now"), List.of());
        }
        if (hasConflicts(i) || behind(i)) {
            return flow(1, YOU, i.updatedAt(), hasConflicts(i) ? "Conflicts with its base." : "Behind its base.",
                    new Next("dependabot-rebase", "Ask Dependabot to rebase"), List.of());
        }
        return switch (r.verdict() == null ? "" : r.verdict()) {
            case "SAFE" -> {
                if (Bumps.AWAITING_APPROVAL.equals(i.ci())) {
                    yield flow(1, YOU, i.updatedAt(), "Safe. CI waits for you to approve the run.",
                            new Next("approve-ci", "Approve CI run"), List.of());
                }
                if (pending(i, c)) yield flow(1, CI, i.updatedAt(), "Safe. CI is running.", null, List.of());
                if (ciFailing(i)) {
                    // A web-jar bump rarely breaks the build: usually CI broke on the base and was fixed there since.
                    // Re-running tests the same old merge again; a rebase takes the fix from the base.
                    yield flow(1, YOU, i.updatedAt(), "Safe, but CI is failing. Rebasing picks up any CI fixes on its base.",
                            new Next("dependabot-rebase", "Ask Dependabot to rebase"),
                            List.of(new Next("checks", "See CI on GitHub"), new Next("github", "Close on GitHub")));
                }
                if (green(i)) yield flow(1, YOU, i.updatedAt(), "Safe and green.", new Next("merge", "Merge"), List.of());
                yield flow(1, CI, i.updatedAt(), "Safe. Waiting for required checks.", null, List.of());
            }
            case "NOT_SAFE" -> flow(1, YOU, i.updatedAt(), "Not safe to merge.", new Next("github", "Close on GitHub"),
                    List.of(new Next("comment", "Why"), new Next("check", "Check again")));
            default -> flow(1, YOU, i.updatedAt(), "The elf wants a human to look.", new Next("comment", "Read its comment"),
                    List.of(new Next("check", "Check again")));
        };
    }

    private static Flow flow(int step, String waiting, Instant since, String say, Next next, List<Next> more) {
        return new Flow(STEPS, step, waiting, since, say, next, more);
    }

    static boolean ciFailing(Bumps.Pr i) {
        return "FAILURE".equals(i.ci()) || "ERROR".equals(i.ci());
    }

    static boolean pending(Bumps.Pr i, Ctx c) {
        return "PENDING".equals(i.ci()) || "EXPECTED".equals(i.ci())
                // Green so far, but required checks haven't all run (often: the real CI hasn't started yet). Only
                // just after a change: later, a green PR GitHub still blocks waits for something else.
                || "SUCCESS".equals(i.ci()) && "BLOCKED".equals(i.mergeState()) && !Boolean.TRUE.equals(i.draft())
                        && !"REVIEW_REQUIRED".equals(i.reviewDecision()) && !"CHANGES_REQUESTED".equals(i.reviewDecision())
                        && i.updatedAt() != null && i.updatedAt().isAfter(c.now().minus(Duration.ofMinutes(30)));
    }

    /** Every check passed and GitHub lets it merge: what the merge action asks for too. */
    static boolean green(Bumps.Pr i) {
        return "SUCCESS".equals(i.ci()) && !Boolean.TRUE.equals(i.draft()) && Bumps.requiredChecksPassed(i.mergeState());
    }

    static boolean hasConflicts(Bumps.Pr i) {
        return "CONFLICTING".equals(i.mergeable());
    }

    static boolean behind(Bumps.Pr i) {
        return "BEHIND".equals(i.mergeState()) && !hasConflicts(i);
    }
}
