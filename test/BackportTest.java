import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class BackportTest {

    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), FROM = "c".repeat(40);
    final List<String> tried = new ArrayList<>();
    List<String> conflicts = List.of();

    final Map<String, String> github = Map.of(
            "repos/quarkusio/quarkus/labels?per_page=100&page=1",
            "[{\"name\":\"triage/backport\"},{\"name\":\"triage/backport-3.40\"},{\"name\":\"area/core\"}]",
            "repos/quarkusio/quarkus/git/matching-refs/heads/",
            "[{\"ref\":\"refs/heads/main\"},{\"ref\":\"refs/heads/3.40\"},{\"ref\":\"refs/heads/4.0\"}]",
            "repos/quarkusio/quarkus/pulls/7",
            "{\"merged\":true,\"title\":\"Bump org.mvnpm:graphiql from 5.4.0 to 6.0.1\",\"labels\":[],"
                    + "\"base\":{\"sha\":\"" + BASE + "\"},\"head\":{\"sha\":\"" + HEAD + "\"}}",
            "repos/quarkusio/quarkus/pulls/8", "{\"merged\":false,\"title\":\"Bump org.mvnpm:x from 1 to 2\"}",
            "repos/quarkusio/quarkus/compare/" + BASE + "..." + HEAD, "{\"merge_base_commit\":{\"sha\":\"" + FROM + "\"}}");

    JsonNode read(String path) {
        try {
            if (path.startsWith("search/issues")) {
                return Board.MAPPER.readTree("{\"items\":[{\"number\":7,\"title\":\"Bump org.mvnpm:graphiql from 5.4.0 to 6.0.1\","
                        + "\"html_url\":\"u\",\"labels\":[{\"name\":\"triage/backport-3.40\"}],"
                        + "\"pull_request\":{\"merged_at\":\"2026-11-11T10:00:00Z\"}}]}");
            }
            String s = github.get(path);
            return s == null ? null : Board.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    final Backport.Git git = (repo, number, from, head, branch) -> {
        tried.add(repo + "#" + number + " " + from.charAt(0) + ".." + head.charAt(0) + " onto " + branch);
        return conflicts;
    };

    @Test
    void labelsAndBranches() {
        assertEquals("4.0", Backport.branchOf("triage/backport", List.of("3.40", "4.0")));
        assertEquals("3.40", Backport.branchOf("triage/backport-3.40", List.of("3.40", "4.0")));
        assertNull(Backport.branchOf("triage/backport-3.20", List.of("3.40", "4.0")));
        assertNull(Backport.branchOf("area/core", List.of("3.40")));
    }

    @Test
    void mergedBumpsWithTheirLabels() throws Exception {
        var merged = Backport.merged(this::read, "quarkusio/quarkus", Instant.parse("2026-11-12T00:00:00Z"));
        assertEquals(1, merged.size());
        assertEquals(List.of("triage/backport-3.40"), merged.getFirst().labels());
        assertEquals(List.of("triage/backport", "triage/backport-3.40"), merged.getFirst().backport().stream().map(Backport.Label::name).toList());
    }

    @Test
    void theCheckTriesTheCherryPick() throws Exception {
        var c = Backport.check(this::read, git, "quarkusio/quarkus", 7, "triage/backport");
        assertTrue(c.clean());
        assertEquals("4.0", c.branch());
        assertEquals(List.of("quarkusio/quarkus#7 c..b onto 4.0"), tried);
        conflicts = List.of("bom/application/pom.xml");
        c = Backport.check(this::read, git, "quarkusio/quarkus", 7, "triage/backport-3.40");
        assertFalse(c.clean());
        assertEquals(List.of("bom/application/pom.xml"), c.conflicts());
        assertThrows(IllegalStateException.class, () -> Backport.check(this::read, git, "quarkusio/quarkus", 8, "triage/backport"));
    }

    @Test
    void onlyOneBackportLabelIsWritten() {
        ObjectNode ok = Board.MAPPER.createObjectNode();
        ok.putArray("labels").add("triage/backport-3.40");
        assertTrue(GitHub.allowedWrite("quarkusio/quarkus", "POST", "/repos/quarkusio/quarkus/issues/7/labels", ok));
        ObjectNode other = Board.MAPPER.createObjectNode();
        other.putArray("labels").add("area/core");
        assertFalse(GitHub.allowedWrite("quarkusio/quarkus", "POST", "/repos/quarkusio/quarkus/issues/7/labels", other));
        assertFalse(GitHub.allowedWrite("quarkusio/quarkus", "POST", "/repos/other/repo/issues/7/labels", ok));
    }

    @Test
    void notToBackportStaysOffTheList(@org.junit.jupiter.api.io.TempDir java.nio.file.Path state) throws Exception {
        assertTrue(Backport.skipped(state).isEmpty());
        assertEquals(Map.of(), Backport.skipped(state));
        java.nio.file.Files.writeString(state.resolve("backport-skipped.json"),
                "{\"quarkusio/quarkus#58343\":\"" + Instant.now() + "\",\"a/b#1\":\"2020-01-01T00:00:00Z\"}");
        // An old one is forgotten: the bump left the list long ago.
        assertEquals(java.util.Set.of("quarkusio/quarkus#58343"), Backport.skipped(state).keySet());
    }
}
