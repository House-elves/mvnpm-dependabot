import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/** A rebase that doesn't change the bump keeps its SAFE; one that does, or that can't be compared, doesn't. */
class RebasedTest {

    static JsonNode json(String s) throws Exception {
        return Board.MAPPER.readTree(s);
    }

    static final String BEFORE = """
            {"parents": [{"sha": "p1"}], "files": [{"filename": "bom/dev-ui/pom.xml", "status": "modified",
             "patch": "@@ -10,7 +10,7 @@\\n         <lit.version>3.3.0</lit.version>\\n-        <dompurify.version>3.4.17</dompurify.version>\\n+        <dompurify.version>3.4.18</dompurify.version>\\n         <marked.version>16.0.0</marked.version>"}]}""";

    @Test
    void theSameLinesAreTheSameChangeWhateverTheContext() throws Exception {
        String before = Rebased.commitChange(json(BEFORE));
        assertNotNull(before);
        // Rebased: a neighbour pin moved on main (context), the bump's own lines didn't.
        String after = Rebased.prChange(json("""
                [{"filename": "bom/dev-ui/pom.xml", "status": "modified",
                  "patch": "@@ -11,7 +11,7 @@\\n         <lit.version>3.3.3</lit.version>\\n-        <dompurify.version>3.4.17</dompurify.version>\\n+        <dompurify.version>3.4.18</dompurify.version>\\n         <marked.version>16.0.0</marked.version>"}]"""));
        assertEquals(before, after);
        // Dependabot moved it on to a newer version: a different change.
        String newer = Rebased.prChange(json("""
                [{"filename": "bom/dev-ui/pom.xml", "status": "modified",
                  "patch": "@@ -10,7 +10,7 @@\\n-        <dompurify.version>3.4.17</dompurify.version>\\n+        <dompurify.version>3.4.19</dompurify.version>"}]"""));
        assertNotEquals(before, newer);
    }

    @Test
    void whatCantBeComparedIsNotTheSame() throws Exception {
        assertNull(Rebased.commitChange(json("{\"parents\": [{}, {}], \"files\": []}")));
        assertNull(Rebased.commitChange(null));
        assertNull(Rebased.prChange(json("[{\"filename\": \"a.png\", \"status\": \"modified\"}]")));
        assertNull(Rebased.prChange(json("[]")));
    }

    @Test
    void aRecordedComparisonIsForExactlyThoseTwoHeads() {
        var all = Map.of("quarkusio/quarkus#7", new Rebased.Seen("a", "b", true, Prs.NOW));
        assertEquals(Boolean.TRUE, Rebased.recorded(all, "quarkusio/quarkus#7", "a", "b"));
        assertNull(Rebased.recorded(all, "quarkusio/quarkus#7", "a", "c"));
        assertNull(Rebased.recorded(all, "quarkusio/quarkus#8", "a", "b"));
    }

    @Test
    void aSafeVerdictCarriesOverASameRebaseOnTheBoard() {
        Bumps.Pr pr = ActionsTest.at("b".repeat(40), "SUCCESS", "CLEAN");
        var state = Board.MAPPER.createObjectNode();
        state.putObject(pr.key()).put("sha", "a".repeat(40)).put("verdict", "SAFE").put("approved", true);
        var same = Map.of(pr.key(), new Rebased.Seen("a".repeat(40), "b".repeat(40), true, Prs.NOW));

        Board.Row row = Board.build("me", Prs.NOW, List.of(pr.repo()), List.of(pr), state, null, Map.of(), List.of(), Prs.NOW,
                same, Map.of()).rows().get(0);
        assertTrue(row.current());
        assertEquals("merge", row.flow().next().id());
        assertTrue(row.flow().say().contains("Checked before Dependabot rebased it"));

        // Not compared (or different): checked again, as before.
        row = Board.build("me", Prs.NOW, List.of(pr.repo()), List.of(pr), state, null, Map.of(), List.of(), Prs.NOW,
                Map.of(), Map.of()).rows().get(0);
        assertEquals("check", row.flow().next().id());

        // A NOT_SAFE verdict doesn't carry: the newer main may have fixed it.
        state.putObject(pr.key()).put("sha", "a".repeat(40)).put("verdict", "NOT_SAFE");
        row = Board.build("me", Prs.NOW, List.of(pr.repo()), List.of(pr), state, null, Map.of(), List.of(), Prs.NOW,
                same, Map.of()).rows().get(0);
        assertEquals("check", row.flow().next().id());
    }

    @Test
    void aMergeYouClickedWaitsForGreenOrSaysWhyItStopped() {
        Bumps.Pr running = ActionsTest.at("b".repeat(40), "PENDING", "BLOCKED");
        var state = Board.MAPPER.createObjectNode();
        state.putObject(running.key()).put("sha", "b".repeat(40)).put("verdict", "SAFE").put("approved", true);
        var waiting = Map.of(running.key(), new MergeWhenGreen.Waiting("b".repeat(40), Prs.NOW, null));
        Board.Row row = Board.build("me", Prs.NOW, List.of(running.repo()), List.of(running), state, null, Map.of(), List.of(),
                Prs.NOW, Map.of(), waiting).rows().get(0);
        assertEquals("Safe. Merges when CI is green, as you clicked.", row.flow().say());
        assertNull(row.flow().next());
        assertEquals("unqueue-merge", row.flow().more().get(0).id());

        var stopped = Map.of(running.key(), new MergeWhenGreen.Waiting("b".repeat(40), Prs.NOW, "Didn't merge: CI on it isn't green (failure)."));
        row = Board.build("me", Prs.NOW, List.of(running.repo()), List.of(running), state, null, Map.of(), List.of(),
                Prs.NOW, Map.of(), stopped).rows().get(0);
        assertTrue(row.flow().say().startsWith("Didn't merge"));
    }
}
