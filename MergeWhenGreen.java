import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Merges you clicked while CI was still running (after Dependabot's rebase, usually): state/merge-when-green.json,
 * {"owner/repo#n": {sha, at, error}}. Each refresh of the board tries them again ({@link #run}), with the same rules as
 * the Merge button: still the same bump, found safe, CI green. Nothing else is merged, and nothing you didn't click.
 *
 * <p>
 * One that can't merge any more (CI failed, Dependabot changed the bump, a day went by) stops waiting: it keeps
 * why, which its row says, until you click Merge again or Don't merge.
 */
final class MergeWhenGreen {

    private MergeWhenGreen() {}

    /** How long a merge waits for CI before it stops. */
    static final Duration WAIT = Duration.ofDays(1);

    /**
     * @param sha   the head it merges at (moved along when Dependabot rebases without changing the bump)
     * @param error why it stopped waiting, or null while it waits
     */
    record Waiting(String sha, Instant at, String error) {
    }

    static Path path(Config config) {
        return config.stateDir.resolve("merge-when-green.json");
    }

    static Map<String, Waiting> all(Config config) {
        try {
            if (!Files.isRegularFile(path(config))) return Map.of();
            return Board.MAPPER.readValue(path(config).toFile(), new TypeReference<Map<String, Waiting>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    static synchronized void put(Config config, String key, Waiting w) throws IOException {
        Map<String, Waiting> next = new HashMap<>(all(config));
        if (w == null) next.remove(key);
        else next.put(key, w);
        Board.writeJson(path(config), next);
    }

    /** Tries each waiting merge (the board command, before it reads GitHub). */
    static void run(Config config) {
        for (Map.Entry<String, Waiting> e : all(config).entrySet()) {
            Waiting w = e.getValue();
            if (w.error() != null) continue;
            String key = e.getKey();
            String repo = key.substring(0, key.indexOf('#'));
            int number = Integer.parseInt(key.substring(key.indexOf('#') + 1));
            try {
                if (config.repo(repo) == null) {
                    put(config, key, null);
                    continue;
                }
                if (w.at().isBefore(Instant.now().minus(WAIT))) {
                    put(config, key, new Waiting(w.sha(), w.at(), "CI wasn't green within a day, so it didn't merge."));
                    continue;
                }
                Actions.merge(config, new GitHub(config, repo), repo, number, w.sha(), true);
            } catch (IllegalArgumentException x) {
                // Not an open bump any more (merged or closed on GitHub): nothing to wait for.
                try {
                    put(config, key, null);
                } catch (IOException ignored) {
                    // tried again next time
                }
            } catch (IllegalStateException x) {
                try {
                    put(config, key, new Waiting(w.sha(), w.at(), "Didn't merge: " + x.getMessage()));
                } catch (IOException ignored) {
                    // tried again next time
                }
            } catch (IOException | RuntimeException x) {
                // GitHub couldn't be reached: tried again at the next refresh.
                System.err.println("Merge when green, " + key + ": " + x.getMessage());
            }
        }
    }
}
