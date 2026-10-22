import java.time.Instant;

/** Test builders for {@link Bumps.Pr}. */
final class Prs {

    static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    private Prs() {}

    /** A Dependabot mvnpm bump with the given GitHub state. */
    static Bumps.Pr bump(String ci, String mergeState, String mergeable, boolean approved) {
        return new Bumps.Pr("quarkusio/quarkus", 7, "Bump org.mvnpm:lit from 3.3.1 to 3.3.3",
                "https://github.com/quarkusio/quarkus/pull/7", "dependabot", "dependabot/maven/org.mvnpm-lit-3.3.3",
                "sha7", ci, approved ? "APPROVED" : null, mergeable, mergeState, approved, false,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"));
    }

    static Board.Check checked(Bumps.Pr pr, String verdict) {
        return new Board.Check(pr.headSha(), verdict, verdict.equals("SAFE"), "https://github.com/c", NOW);
    }
}
