import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependabot rebases a bump whenever another one merges (they all touch the same pom), so its head moves while the
 * bump stays the same. The elf's check builds the bump merged into the latest main anyway, so its verdict still holds
 * when the PR now changes exactly the lines it checked (only a SAFE one: a NOT_SAFE bump may be fine on the newer
 * main, the parent pin it needed bumped there, so that one is checked again): then there's no need to build Quarkus again, and a Merge you
 * clicked on the head before the rebase is the same merge.
 *
 * <p>
 * "The same change": the commit the elf checked (one commit on its parent, as Dependabot makes them) changes the
 * same files with the same added and removed lines as the whole PR does now. Context lines don't count: other pins
 * next to this one move as their bumps merge. A file GitHub shows no patch for, a commit with more than one parent,
 * or a commit GitHub no longer has: not the same, so it's checked again as before.
 *
 * <p>
 * What it found is kept in state/rebased.json, {"owner/repo#n": {from, to, same, at}}, so each rebase is compared once.
 */
final class Rebased {

    private Rebased() {}

    /** One comparison: the head the elf checked ({@code from}), the PR's head then ({@code to}), and whether they match. */
    record Seen(String from, String to, boolean same, Instant at) {
    }

    static Path path(Config config) {
        return config.stateDir.resolve("rebased.json");
    }

    static Map<String, Seen> all(Config config) {
        try {
            if (!Files.isRegularFile(path(config))) return Map.of();
            return Board.MAPPER.readValue(path(config).toFile(), new TypeReference<Map<String, Seen>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** The comparison recorded for exactly these two heads, or null (pure: what the board joins). */
    static Boolean recorded(Map<String, Seen> all, String key, String from, String to) {
        Seen s = all.get(key);
        return s != null && s.from().equals(from) && s.to().equals(to) ? s.same() : null;
    }

    /**
     * The PR at {@code head} changes exactly what it did at {@code checked}: from what's recorded, else asked of GitHub
     * (and recorded). False when GitHub can't say.
     */
    static boolean same(Config config, GitHub gh, String repo, int number, String checked, String head) {
        if (checked == null || head == null) return false;
        if (checked.equals(head)) return true;
        String key = repo + "#" + number;
        Boolean known = recorded(all(config), key, checked, head);
        if (known != null) return known;
        boolean same;
        try {
            String before = commitChange(gh.get("repos/" + repo + "/commits/" + checked));
            String now = prChange(gh.get("repos/" + repo + "/pulls/" + number + "/files?per_page=100"));
            same = before != null && before.equals(now);
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't compare " + key + " at " + checked.substring(0, 7) + " and " + head.substring(0, 7)
                    + ": " + e.getMessage());
            return false; // not recorded: asked again next time
        }
        record(config, key, new Seen(checked, head, same, Instant.now()));
        System.out.println(key + ": " + (same ? "rebased without changing the bump" : "changed since the elf checked it")
                + " (" + checked.substring(0, 7) + " -> " + head.substring(0, 7) + ")");
        return same;
    }

    private static synchronized void record(Config config, String key, Seen seen) {
        Map<String, Seen> next = new HashMap<>(all(config));
        next.put(key, seen);
        // Old comparisons go: a week is longer than any bump stays open between rounds.
        next.values().removeIf(s -> s.at() == null || s.at().isBefore(Instant.now().minus(Duration.ofDays(7))));
        try {
            Board.writeJson(path(config), next);
        } catch (IOException e) {
            System.err.println("Couldn't keep " + path(config) + ": " + e.getMessage());
        }
    }

    /** A commit's change (GET /repos/../commits/sha), or null when it isn't one commit on one parent with patches. */
    static String commitChange(JsonNode commit) {
        if (commit == null || commit.path("parents").size() != 1) return null;
        return change(commit.path("files"));
    }

    /** A PR's whole change (GET /repos/../pulls/n/files), or null. */
    static String prChange(JsonNode files) {
        return files == null ? null : change(files);
    }

    /** Each file (sorted) with its status and its added and removed lines; null when a file has no patch. */
    static String change(JsonNode files) {
        if (!files.isArray() || files.isEmpty() || files.size() >= 100) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode f : files) {
            if (!f.path("patch").isTextual()) return null;
            StringBuilder s = new StringBuilder(f.path("filename").asText()).append('\0').append(f.path("status").asText()).append('\n');
            for (String line : f.path("patch").asText().split("\n", -1)) {
                if (line.startsWith("+") || line.startsWith("-")) s.append(line).append('\n');
            }
            out.add(s.toString());
        }
        out.sort(null);
        return String.join("\u0001", out);
    }
}
