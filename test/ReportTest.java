import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** A bump into a maintenance branch ("[2.x] Bump ...") is judged on that branch, and its comment says so, not main. */
class ReportTest {

    static GitHub.Pr pr(String base) {
        return new GitHub.Pr(2817, "[2.x] Bump org.mvnpm:graphql-ws from 6.4.1 to 6.4.3",
                "dependabot/maven/2.x/org.mvnpm-graphql-ws-6.4.3", base, "4495f578a5aaaaaaaa", "https://github.com/x",
                "org.mvnpm", "graphql-ws", "6.4.1", "6.4.3");
    }

    static Report.Check conflicted(String base) {
        Report.Check c = new Report.Check(new Target("smallrye/smallrye-graphql", Path.of("/tmp/x")), pr(base), base);
        c.analysis = new BomAnalyzer.Analysis("org.mvnpm:graphql-ws", "6.4.1", "6.4.3", true, "6.4.1", true, false,
                List.of(), List.of(), Set.of("org.mvnpm:graphql-ws"), Set.of("ui/graphiql"), Set.of(), Set.of());
        c.conflicted = true;
        return c;
    }

    @Test
    void aConflictNamesThePrsBase() {
        Config config = new Config();
        config.githubUser = "someone";
        Report.Check c = conflicted("2.x");
        Report.decide(c, config);
        assertEquals(Report.Verdict.NEEDS_HUMAN, c.verdict);
        assertEquals(List.of("the branch does not merge cleanly into 2.x - `@dependabot rebase` it"), c.reasons);

        String body = Report.comment(c, config, c.base, "6dfe5394f9aaaaaa");
        assertTrue(body.contains("| Merges into 2.x | ❌ conflicts |"), body);
        assertTrue(body.contains("`6dfe5394f9` (2.x)"), body);
        assertFalse(body.contains("main"), body);
    }
}
