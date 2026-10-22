import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

class BoardTest {

    /** A PR node as GitHub's GraphQL answers {@link GitHub#PR_FIELDS}. */
    static JsonNode node(int number, String sha, String checkSuites, String rollup) throws Exception {
        return Board.MAPPER.readTree("""
                {"number": %d, "title": "Bump org.mvnpm:lit from 3.3.1 to 4.0.0", "url": "https://github.com/quarkusio/quarkus/pull/%d",
                 "state": "OPEN", "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-0%dT00:00:00Z", "isDraft": false,
                 "headRefName": "dependabot/maven/org.mvnpm-lit-4.0.0", "headRefOid": "%s", "reviewDecision": null,
                 "mergeable": "MERGEABLE", "mergeStateStatus": "BLOCKED", "author": {"login": "dependabot"},
                 "latestOpinionatedReviews": {"nodes": [{"state": "APPROVED"}]},
                 "commits": {"nodes": [{"commit": {"statusCheckRollup": %s, "status": null,
                   "checkSuites": {"nodes": %s}}}]}}
                """.formatted(number, number, number % 9, sha, rollup, checkSuites));
    }

    static final String GREEN = """
            {"state": "SUCCESS", "contexts": {"checkRunCountsByState": [{"state": "SUCCESS", "count": 3}], "statusContextCountsByState": []}}""";

    @Test
    void readsAPrTheWayTheGitHubElfDid() throws Exception {
        // Quarkus holds Dependabot's CI at the "dependabot" environment, which you can approve.
        JsonNode held = node(1, "a1", """
                [{"conclusion": null, "workflowRun": {"databaseId": 11, "workflow": {"databaseId": 1},
                  "pendingDeploymentRequests": {"nodes": [{"currentUserCanApprove": true, "environment": {"databaseId": 99}}]}}}]""",
                "null");
        Bumps.Pr pr = Bumps.pr("quarkusio/quarkus", held);
        assertEquals(Bumps.AWAITING_APPROVAL, pr.ci());
        assertTrue(pr.approved()); // no review decision: a writer approved
        assertEquals(List.of(new Bumps.Approval(11, List.of(99L))),
                Bumps.awaitingApproval(held.path("commits").path("nodes").path(0).path("commit")));
        assertEquals("PENDING", Bumps.ciState(Board.MAPPER.readTree("""
                {"state": "FAILURE", "contexts": {"checkRunCountsByState": [{"state": "IN_PROGRESS", "count": 1}]}}""")));

        Bumps.Bump b = Bumps.bump(pr.title());
        assertEquals("lit", b.artifactId());
        assertEquals("4.0.0", b.to());
        assertTrue(b.major());
        assertNull(Bumps.bump("Bump the mvnpm group with 3 updates"));
        assertTrue(Bumps.isMvnpmBump(held));
        assertFalse(Bumps.isMvnpmBump(Board.MAPPER.readTree("{\"author\": {\"login\": \"someone\"}, \"title\": \"org.mvnpm\"}")));
    }

    @Test
    void joinsEachBumpWithItsVerdictNewestFirst() throws Exception {
        Bumps.Pr checked = Bumps.pr("quarkusio/quarkus", node(2, "b2", "[]", GREEN));
        Bumps.Pr older = Bumps.pr("quarkusio/quarkus", node(3, "new-head", "[]", GREEN));
        Bumps.Pr never = Bumps.pr("quarkusio/quarkus", node(4, "d4", "[]", GREEN));
        JsonNode state = Board.MAPPER.readTree("""
                {"quarkusio/quarkus#2": {"sha": "b2", "verdict": "SAFE", "approved": true, "at": "2026-10-06T00:30:00Z"},
                 "3": {"sha": "old-head", "verdict": "NOT_SAFE", "approved": false}}""");
        Board.View v = Board.build("me", Prs.NOW, List.of("quarkusio/quarkus"), List.of(checked, older, never), state, null,
                Map.of(), List.of(), Prs.NOW);
        assertEquals(List.of(4, 3, 2), v.rows().stream().map(r -> r.pr().number()).toList());
        assertNull(v.rows().get(0).check());
        assertEquals("NOT_SAFE", v.rows().get(1).check().verdict()); // the legacy bare-number key
        assertFalse(v.rows().get(1).current());
        assertTrue(v.rows().get(2).current());
        assertEquals("check", v.rows().get(1).flow().next().id());

        // A round checks the bumps not checked at their head; a check, the PRs it names.
        Running round = new Running("round", null, List.of(), Prs.NOW, "mvnpm-dependabot-round");
        v = Board.build("me", Prs.NOW, List.of("quarkusio/quarkus"), List.of(checked, older, never), state, round, Map.of(),
                List.of(), Prs.NOW);
        assertEquals(List.of("agent", "agent", "ci"), v.rows().stream().map(r -> r.flow().waiting()).toList());
        Running one = new Running("check", "quarkusio/quarkus", List.of(2), Prs.NOW, null);
        assertTrue(one.covers(checked, true));
        assertFalse(one.covers(never, false));
    }

    @Test
    void theBoardFileReadsBackAndRejoinsWithoutGitHub(@TempDir Path dir) throws Exception {
        Bumps.Pr pr = Bumps.pr("quarkusio/quarkus", node(5, "e5", "[]", GREEN));
        Board.View v = Board.build("me", Prs.NOW, List.of("quarkusio/quarkus"), List.of(pr), Board.MAPPER.createObjectNode(),
                null, Map.of(), List.of(), Prs.NOW);
        Path file = dir.resolve("board/board.json");
        Board.writeJson(file, v);
        Board.View back = Board.read(file);
        assertEquals(pr, back.rows().get(0).pr());
        assertEquals("lit", back.rows().get(0).bump().artifactId());
        assertTrue(Files.readString(file).contains("\"fetchedAt\" : \"2026-10-14T00:00:00Z\""));
    }
}
