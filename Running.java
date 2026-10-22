import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Which check runs now, in state/running.json, for the tab ("Checking
 * quarkus#51234") and the board (that row is being checked). Written by the
 * check that holds the lock, removed when it ends.
 *
 * @param kind  round (every open bump not checked at its head), check (the PRs named), or dry-run
 * @param repo  for a check: the repo of its PRs
 * @param prs   for a check: the PRs it checks, even if checked before
 * @param unit  the systemd unit it runs in, or null when it runs in the elf itself
 */
record Running(String kind, String repo, List<Integer> prs, Instant since, String unit) {

    /** This check looks at the PR now: a round checks the bumps not checked at their head. */
    boolean covers(Bumps.Pr pr, boolean current) {
        if (kind.equals("check")) return pr.repo().equalsIgnoreCase(repo) && prs.contains(pr.number());
        return !current;
    }

    static Running read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return Board.MAPPER.readValue(file.toFile(), Running.class);
        } catch (IOException e) {
            return null;
        }
    }

    void write(Path file) throws IOException {
        Board.writeJson(file, this);
    }

    static void clear(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the next check writes it again
        }
    }
}
