import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/** What the tab's buttons may do on GitHub: the merge's rules, and the writes allowed at all. */
class ActionsTest {

    static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    static final String R = "quarkusio/quarkus";

    static Bumps.Pr at(String sha, String ci, String mergeState) {
        Bumps.Pr p = Prs.bump(ci, mergeState, "MERGEABLE", true);
        return new Bumps.Pr(p.repo(), p.number(), p.title(), p.url(), p.author(), p.headRef(), sha, p.ci(), p.reviewDecision(),
                p.mergeable(), p.mergeState(), p.approved(), p.draft(), p.createdAt(), p.updatedAt());
    }

    static Board.Check verdict(String sha, String verdict) {
        return new Board.Check(sha, verdict, true, null, Prs.NOW);
    }

    @Test
    void mergesOnlyASafeGreenBumpAtTheHeadYouSaw() {
        Bumps.Pr green = at(SHA, "SUCCESS", "CLEAN");
        assertNull(Actions.refusal(green, SHA, verdict(SHA, "SAFE")));
        assertTrue(Actions.refusal(at("f".repeat(40), "SUCCESS", "CLEAN"), SHA, verdict(SHA, "SAFE")).contains("changed since you saw it"));
        assertTrue(Actions.refusal(green, SHA, null).contains("isn't checked at this head"));
        assertTrue(Actions.refusal(green, SHA, verdict("e".repeat(40), "SAFE")).contains("isn't checked at this head"));
        assertTrue(Actions.refusal(green, SHA, verdict(SHA, "NEEDS_HUMAN")).contains("didn't find"));
        assertTrue(Actions.refusal(at(SHA, "PENDING", "BLOCKED"), SHA, verdict(SHA, "SAFE")).contains("isn't green"));
        assertTrue(Actions.refusal(at(SHA, "SUCCESS", "BLOCKED"), SHA, verdict(SHA, "SAFE")).contains("isn't green"));
    }

    @Test
    void mergesTheWayTheRepoAllows() throws Exception {
        assertEquals("squash", Actions.method(json("""
                {"mergeCommitAllowed": true, "squashMergeAllowed": true, "rebaseMergeAllowed": false, "viewerDefaultMergeMethod": "SQUASH"}""")));
        assertEquals("merge", Actions.method(json("""
                {"mergeCommitAllowed": true, "squashMergeAllowed": false, "rebaseMergeAllowed": false, "viewerDefaultMergeMethod": "REBASE"}""")));
        assertNull(Actions.method(json("{}")));
    }

    @Test
    void writesOnlyWhatTheButtonsDo() throws Exception {
        assertTrue(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/actions/runs/11/approve", json("{}")));
        assertTrue(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/actions/runs/11/rerun-failed-jobs", json("{}")));
        assertEquals("https://github.com/q/q/runs/1", GitHub.webUrl("https://github.com/q/q/runs/1"));
        assertNull(GitHub.webUrl("javascript:alert(1)"));
        assertNull(GitHub.webUrl("data:text/html,x"));
        assertNull(GitHub.webUrl(null));
        assertFalse(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/actions/runs/11/rerun-failed-jobs",
                json("{\"enable_debug_logging\": true}")));
        assertFalse(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/actions/runs/11/rerun", json("{}")));
        assertFalse(GitHub.allowedWrite(R, "POST", "/repos/other/repo/actions/runs/11/rerun-failed-jobs", json("{}")));
        assertTrue(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/actions/runs/11/pending_deployments",
                json("{\"environment_ids\": [99], \"state\": \"approved\", \"comment\": \"\"}")));
        assertFalse(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/actions/runs/11/pending_deployments",
                json("{\"environment_ids\": [99], \"state\": \"rejected\", \"comment\": \"\"}")));
        assertTrue(GitHub.allowedWrite(R, "PUT", "/repos/quarkusio/quarkus/pulls/7/merge",
                json("{\"merge_method\": \"squash\", \"sha\": \"" + SHA + "\"}")));
        assertFalse(GitHub.allowedWrite(R, "PUT", "/repos/quarkusio/quarkus/pulls/7/merge", json("{\"merge_method\": \"squash\"}")));
        assertTrue(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/issues/7/comments", json("{\"body\": \"@dependabot rebase\"}")));
        assertFalse(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/issues/7/comments", json("{\"body\": \"@dependabot merge\"}")));
        // Only in the client's own repo, and nothing else at all.
        assertFalse(GitHub.allowedWrite(R, "POST", "/repos/other/repo/actions/runs/11/approve", json("{}")));
        assertFalse(GitHub.allowedWrite(R, "PATCH", "/repos/quarkusio/quarkus/issues/7", json("{\"state\": \"closed\"}")));
        assertFalse(GitHub.allowedWrite(R, "POST", "/repos/quarkusio/quarkus/pulls/7/reviews", json("{\"event\": \"APPROVE\"}")));
    }

    static JsonNode json(String s) throws Exception {
        return Board.MAPPER.readTree(s);
    }
}
