import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the tab's buttons do on GitHub: approve CI to run, re-run its failed
 * jobs, merge, and ask Dependabot to rebase; and, only reading, what failed.
 * Each write is an action in elf.yaml with ask: true, so the desk shows its
 * confirm bar and nothing is written until you click there.
 * The values come as JSON on stdin (the runner checked them against elf.yaml
 * first); the answer is the last line printed. Each reads the PR from GitHub
 * again first and refuses what doesn't fit: only open mvnpm bumps in REPOS,
 * and a merge only at the head you saw, when the elf found it safe there and
 * CI is green. The writes themselves go through {@link GitHub#write}'s list.
 */
final class Actions {

    private Actions() {}

    static int run(Config config, String action) throws IOException {
        String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        JsonNode values = Board.MAPPER.readTree(stdin.isBlank() ? "{}" : stdin);
        return run(config, action, values);
    }

    static int run(Config config, String action, JsonNode values) {
        try {
            String repo = config.repo(values.path("repo").asText(""));
            int number = values.path("pr").asInt(0);
            if (repo == null || !repo.contains("/")) throw new IllegalArgumentException(values.path("repo").asText() + " isn't in REPOS");
            if (number <= 0) throw new IllegalArgumentException("Which PR?");
            GitHub gh = new GitHub(config, repo);
            ObjectNode answer = switch (action) {
                case "approve-ci" -> approveCi(config, gh, repo, number);
                case "ci-report" -> ciReport(gh, repo, number);
                case "ci-rerun" -> ciRerun(config, gh, repo, number);
                case "merge" -> merge(config, gh, repo, number, values.path("headSha").asText(""));
                case "dependabot-rebase" -> rebase(config, gh, repo, number);
                default -> throw new IllegalArgumentException("Not one of mvnpm-dependabot's actions: " + action);
            };
            System.out.println(Board.MAPPER.writeValueAsString(answer));
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    /** The PR as GitHub has it now, if it's an open mvnpm bump; the repository node it came in. */
    record Pull(JsonNode repository, JsonNode node, Bumps.Pr pr) {
    }

    static Pull pull(GitHub gh, String repo, int number) throws IOException {
        JsonNode r = gh.pull(number);
        JsonNode n = r.path("pullRequest");
        if (!n.isObject()) throw new IllegalArgumentException(repo + "#" + number + " isn't a pull request");
        if (!"OPEN".equals(n.path("state").asText())) throw new IllegalArgumentException(repo + "#" + number + " isn't open any more");
        if (!Bumps.isMvnpmBump(n)) throw new IllegalArgumentException(repo + "#" + number + " isn't a Dependabot mvnpm bump");
        return new Pull(r, n, Bumps.pr(repo, n));
    }

    static ObjectNode approveCi(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        List<Bumps.Approval> runs = Bumps.awaitingApproval(p.node().path("commits").path("nodes").path(0).path("commit"));
        if (runs.isEmpty()) throw new IllegalStateException("No workflow runs on " + repo + "#" + number + " wait for approval.");
        for (Bumps.Approval run : runs) {
            if (run.environments().isEmpty()) {
                gh.write("POST", "/repos/" + repo + "/actions/runs/" + run.run() + "/approve", Board.MAPPER.createObjectNode());
            } else {
                ObjectNode body = Board.MAPPER.createObjectNode().put("state", "approved").put("comment", "");
                run.environments().forEach(body.putArray("environment_ids")::add);
                gh.write("POST", "/repos/" + repo + "/actions/runs/" + run.run() + "/pending_deployments", body);
            }
        }
        System.out.println("Approved " + runs.size() + " workflow run(s) on " + repo + "#" + number);
        // CI runs now: the row says so until the next read of GitHub.
        Board.update(config, prs -> prs.stream().map(x -> x.key().equals(p.pr().key()) ? x.withCi("PENDING") : x).toList());
        return Board.MAPPER.createObjectNode().put("approved", runs.size());
    }

    /** What failed on the PR's head (only reads): see {@link GitHub#ci}. */
    static ObjectNode ciReport(GitHub gh, String repo, int number) throws IOException {
        pull(gh, repo, number);
        return gh.ci(number);
    }

    /** Re-runs the failed jobs of each failed GitHub Actions run on the PR's head, as the GitHub elf's Retry does. */
    static ObjectNode ciRerun(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        ObjectNode ci = gh.ci(number);
        if (!ci.path("canRerun").asBoolean()) throw new IllegalStateException("You don't have write access to " + repo + ", which GitHub needs to re-run its workflows.");
        if (ci.path("runs").isEmpty()) throw new IllegalStateException("Nothing to re-run: the failed checks aren't GitHub Actions jobs.");
        for (JsonNode run : ci.path("runs")) {
            gh.write("POST", "/repos/" + repo + "/actions/runs/" + run.asLong() + "/rerun-failed-jobs", Board.MAPPER.createObjectNode());
        }
        System.out.println("Re-ran the failed jobs of " + ci.path("runs").size() + " workflow run(s) on " + repo + "#" + number);
        // CI runs again: the row says so until the next read of GitHub.
        Board.update(config, prs -> prs.stream().map(x -> x.key().equals(p.pr().key()) ? x.withCi("PENDING") : x).toList());
        return Board.MAPPER.createObjectNode().put("rerun", ci.path("runs").size());
    }

    static ObjectNode merge(Config config, GitHub gh, String repo, int number, String headSha) throws IOException {
        if (!GitHub.SHA.matcher(headSha).matches()) throw new IllegalArgumentException("Merge needs the head you saw");
        Pull p = pull(gh, repo, number);
        String why = refusal(p.pr(), headSha, Board.check(Board.state(config), repo, number));
        if (why != null) throw new IllegalStateException(why);
        String method = method(p.repository());
        if (method == null) throw new IllegalStateException(repo + " allows no merge method");
        ObjectNode body = Board.MAPPER.createObjectNode().put("merge_method", method).put("sha", headSha);
        JsonNode done = gh.write("PUT", "/repos/" + repo + "/pulls/" + number + "/merge", body);
        System.out.println("Merged " + repo + "#" + number + " (" + method + ") at " + headSha.substring(0, 7));
        // Merged: it leaves the board.
        Board.update(config, prs -> prs.stream().filter(x -> !x.key().equals(p.pr().key())).toList());
        return Board.MAPPER.createObjectNode().put("merged", done.path("sha").asText(headSha)).put("method", method);
    }

    /** Why the tab's Merge is refused, or null: the head you saw, the elf's SAFE at that head, and green CI. */
    static String refusal(Bumps.Pr pr, String headSha, Board.Check check) {
        if (!headSha.equals(pr.headSha())) {
            return pr.key() + " changed since you saw it (its head is " + abbrev(pr.headSha()) + " now). Refresh and look again.";
        }
        if (check == null || !headSha.equals(check.sha())) return pr.key() + " isn't checked at this head yet.";
        if (!"SAFE".equals(check.verdict())) return "mvnpm-dependabot didn't find " + pr.key() + " safe (" + check.verdict() + ").";
        if (!Flows.green(pr)) return "CI on " + pr.key() + " isn't green" + (pr.ci() == null ? "" : " (" + pr.ci().toLowerCase() + ")") + ".";
        return null;
    }

    /** The merge method the repo allows: your default there when it's allowed, else the first it allows. */
    static String method(JsonNode repository) {
        List<String> allowed = new ArrayList<>();
        if (repository.path("mergeCommitAllowed").asBoolean()) allowed.add("merge");
        if (repository.path("squashMergeAllowed").asBoolean()) allowed.add("squash");
        if (repository.path("rebaseMergeAllowed").asBoolean()) allowed.add("rebase");
        String preferred = repository.path("viewerDefaultMergeMethod").asText("").toLowerCase();
        return allowed.contains(preferred) ? preferred : allowed.isEmpty() ? null : allowed.get(0);
    }

    static ObjectNode rebase(Config config, GitHub gh, String repo, int number) throws IOException {
        Pull p = pull(gh, repo, number);
        gh.write("POST", "/repos/" + repo + "/issues/" + number + "/comments",
                Board.MAPPER.createObjectNode().put("body", GitHub.REBASE));
        System.out.println("Asked Dependabot to rebase " + repo + "#" + number);
        // The row waits for Dependabot's push rather than offer to ask again.
        Map<String, Board.Rebase> asked = new HashMap<>(Board.rebasing(config));
        asked.put(p.pr().key(), new Board.Rebase(Objects.requireNonNull(p.pr().headSha()), Instant.now()));
        Board.writeJson(config.rebasingPath(), asked);
        Board.rejoin(config);
        return Board.MAPPER.createObjectNode().put("asked", true);
    }

    private static String abbrev(String sha) {
        return sha == null ? "unknown" : sha.substring(0, Math.min(7, sha.length()));
    }
}
