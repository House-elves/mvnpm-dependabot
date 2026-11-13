import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backport labels on merged bumps, as Quarkus does it: {@code triage/backport-3.40} asks for a backport to the 3.40
 * branch, {@code triage/backport} to the newest release branch. A repo takes part when it has such labels.
 *
 * <p>
 * Before a label goes on, the bump is tried as a cherry-pick onto its branch, without a checkout: {@code git
 * merge-tree} merges the PR's change (from where it left its base to its head) into the branch, in a bare, shallow,
 * blobless copy of the repo (in the work dir's git/), which fetches only what that merge reads. A conflict means it
 * can't be backported as is, and no label is added. The GitHub elf does the same for the PRs on its board.
 */
final class Backport {

    private Backport() {}

    /** triage/backport, triage/backport-3.40, backport?: the version, when there is one, is the branch. */
    static final Pattern LABEL = Pattern.compile("(?:[A-Za-z0-9 _.-]{1,40}/)?backport(?:-([0-9][A-Za-z0-9.]{0,20}))?\\??",
            Pattern.CASE_INSENSITIVE);
    static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    /** How long a merged bump is offered for backporting. */
    static final int DAYS = 7;

    /** GitHub's REST API (a path without the leading /), or null for a 404. Tests fake it. */
    interface Rest {
        JsonNode get(String path) throws IOException;
    }

    /** The cherry-pick, tried: the files that conflict. Tests fake it. */
    interface Git {
        List<String> conflicts(String repo, int number, String from, String head, String branch) throws IOException;
    }

    record Label(String name, String branch) {
    }

    /** A merged bump: its labels now, and the backport labels its repo has. */
    record Merged(String key, String repo, int number, String title, String url, Instant mergedAt, List<String> labels,
            List<Label> backport) {
    }

    record Check(String repo, int number, String label, String branch, boolean clean, List<String> conflicts, boolean labelled) {
    }

    // ---- the tab's list ----

    /** board/merged.json: the mvnpm bumps merged in REPOS in the last {@link #DAYS} days, in repos with backport labels. */
    static void refresh(Config config) {
        List<Merged> all = new ArrayList<>();
        for (String repo : config.repos) {
            try {
                all.addAll(merged(new GitHub(config, repo)::get, repo, Instant.now()));
            } catch (IOException | RuntimeException e) {
                System.err.println("Reading the merged bumps of " + repo + " failed: " + e.getMessage());
            }
        }
        var skipped = skipped(config.stateDir);
        all.removeIf(m -> skipped.containsKey(m.key()));
        all.sort(Comparator.comparing(Merged::mergedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        try {
            ObjectNode view = Board.MAPPER.createObjectNode();
            view.put("fetchedAt", Instant.now().toString()).put("days", DAYS);
            view.set("items", Board.MAPPER.valueToTree(all));
            Board.writeJson(mergedPath(), view);
        } catch (IOException e) {
            System.err.println("Could not write the merged bumps: " + e.getMessage());
        }
    }

    /**
     * The merged bumps you said not to backport, in state/backport-skipped.json ({key: when}): they leave the list for
     * good. Kept for a month, longer than a bump stays on the list.
     */
    static java.util.Map<String, Instant> skipped(Path stateDir) {
        java.util.Map<String, Instant> out = new java.util.LinkedHashMap<>();
        Path f = stateDir.resolve("backport-skipped.json");
        try {
            if (Files.exists(f)) {
                Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(30));
                Board.MAPPER.readTree(f.toFile()).fields().forEachRemaining(e -> {
                    try {
                        Instant at = Instant.parse(e.getValue().asText());
                        if (at.isAfter(cutoff)) out.put(e.getKey(), at);
                    } catch (RuntimeException ignored) {
                        // A broken entry is dropped.
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read " + f + ": " + e.getMessage());
        }
        return out;
    }

    /** Not to backport: off the list from now on, and off board/merged.json now. Nothing changes on GitHub. */
    static void skip(Path stateDir, String key) throws IOException {
        java.util.Map<String, Instant> all = skipped(stateDir);
        all.put(key, Instant.now());
        ObjectNode out = Board.MAPPER.createObjectNode();
        all.forEach((k, v) -> out.put(k, v.toString()));
        Files.createDirectories(stateDir);
        Board.writeJson(stateDir.resolve("backport-skipped.json"), out);
        if (Files.exists(mergedPath())) {
            ObjectNode view = (ObjectNode) Board.MAPPER.readTree(mergedPath().toFile());
            ArrayNode items = Board.MAPPER.createArrayNode();
            view.path("items").forEach(i -> { if (!i.path("key").asText().equals(key)) items.add(i); });
            view.set("items", items);
            Board.writeJson(mergedPath(), view);
        }
    }

    static Path mergedPath() {
        return Config.BOARD_DIR.resolve("merged.json");
    }

    static List<Merged> merged(Rest gh, String repo, Instant now) throws IOException {
        List<Label> backport = labels(gh, repo);
        if (backport.isEmpty()) return List.of();
        String since = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(DAYS).toString();
        String q = "repo:" + repo + " is:pr is:merged merged:>=" + since + " author:app/dependabot mvnpm";
        JsonNode found = gh.get("search/issues?per_page=100&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        List<Merged> out = new ArrayList<>();
        for (JsonNode i : found == null ? Board.MAPPER.createArrayNode() : found.path("items")) {
            String title = i.path("title").asText();
            if (!title.contains("org.mvnpm")) continue;
            List<String> has = new ArrayList<>();
            i.path("labels").forEach(l -> has.add(l.path("name").asText()));
            String at = i.path("pull_request").path("merged_at").asText(null);
            int number = i.path("number").asInt();
            out.add(new Merged(repo + "#" + number, repo, number, title, i.path("html_url").asText(),
                    at == null ? null : Instant.parse(at), has, backport));
        }
        return out;
    }

    /** The repo's backport labels with their branches, newest branch first (a label without a branch is left out). */
    static List<Label> labels(Rest gh, String repo) throws IOException {
        List<String> names = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode p = gh.get("repos/" + repo + "/labels?per_page=100&page=" + page);
            if (p == null || !p.isArray() || p.isEmpty()) break;
            p.forEach(l -> {
                if (LABEL.matcher(l.path("name").asText()).matches()) names.add(l.path("name").asText());
            });
            if (p.size() < 100) break;
        }
        if (names.isEmpty()) return List.of();
        List<String> branches = new ArrayList<>();
        JsonNode refs = gh.get("repos/" + repo + "/git/matching-refs/heads/");
        if (refs != null) refs.forEach(r -> {
            String name = r.path("ref").asText().replaceFirst("^refs/heads/", "");
            if (RELEASE.matcher(name).matches()) branches.add(name);
        });
        List<Label> out = new ArrayList<>();
        for (String n : names) {
            String b = branchOf(n, branches);
            if (b != null) out.add(new Label(n, b));
        }
        out.sort(Comparator.comparing((Label l) -> version(l.branch()), Comparator.reverseOrder()));
        return out;
    }

    static String branchOf(String label, List<String> releaseBranches) {
        Matcher m = LABEL.matcher(label);
        if (!m.matches()) return null;
        if (m.group(1) != null) return releaseBranches.contains(m.group(1)) ? m.group(1) : null;
        return releaseBranches.stream().max(Comparator.comparing(Backport::version)).orElse(null);
    }

    private static long version(String branch) {
        Matcher m = RELEASE.matcher(branch);
        return m.matches() ? Long.parseLong(m.group(1)) * 100_000 + Long.parseLong(m.group(2)) : -1;
    }

    // ---- the check, and the label ----

    /** Does the merged bump cherry-pick cleanly onto the label's branch? */
    static Check check(Rest gh, Git git, String repo, int number, String label) throws IOException {
        if (!LABEL.matcher(label).matches()) throw new IllegalArgumentException("Not a backport label: " + label);
        JsonNode pr = gh.get("repos/" + repo + "/pulls/" + number);
        if (pr == null) throw new IllegalArgumentException("There's no PR " + repo + "#" + number + ".");
        if (!pr.path("merged").asBoolean()) throw new IllegalStateException(repo + "#" + number + " isn't merged, so there's nothing to backport yet.");
        if (!pr.path("title").asText().contains("org.mvnpm")) throw new IllegalArgumentException(repo + "#" + number + " isn't an mvnpm bump");
        Label l = labels(gh, repo).stream().filter(x -> x.name().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalStateException(repo + " has no label " + label + " with a branch to backport to."));
        boolean labelled = false;
        for (JsonNode x : pr.path("labels")) labelled |= x.path("name").asText().equals(label);
        String base = pr.path("base").path("sha").asText(), head = pr.path("head").path("sha").asText();
        JsonNode cmp = gh.get("repos/" + repo + "/compare/" + base + "..." + head);
        String from = cmp == null ? "" : cmp.path("merge_base_commit").path("sha").asText("");
        if (!SHA.matcher(from).matches() || !SHA.matcher(head).matches()) {
            throw new IllegalStateException("GitHub didn't say where " + repo + "#" + number + " left its base, so it can't be tried.");
        }
        List<String> conflicts = git.conflicts(repo, number, from, head, l.branch());
        return new Check(repo, number, label, l.branch(), conflicts.isEmpty(), conflicts, labelled);
    }

    /** A bump the tab just merged, at the top of board/merged.json (GitHub's search finds it only later). */
    static void justMerged(Rest gh, String repo, int number) {
        try {
            List<Label> backport = labels(gh, repo);
            JsonNode pr = gh.get("repos/" + repo + "/pulls/" + number);
            if (backport.isEmpty() || pr == null) return;
            List<String> has = new ArrayList<>();
            pr.path("labels").forEach(l -> has.add(l.path("name").asText()));
            String at = pr.path("merged_at").asText(null);
            Merged m = new Merged(repo + "#" + number, repo, number, pr.path("title").asText(), pr.path("html_url").asText(),
                    at == null ? Instant.now() : Instant.parse(at), has, backport);
            ObjectNode view = Files.exists(mergedPath()) ? (ObjectNode) Board.MAPPER.readTree(mergedPath().toFile())
                    : Board.MAPPER.createObjectNode().put("days", DAYS);
            ArrayNode items = Board.MAPPER.createArrayNode().add(Board.MAPPER.valueToTree(m));
            view.path("items").forEach(i -> { if (!i.path("key").asText().equals(m.key())) items.add(i); });
            view.set("items", items);
            Board.writeJson(mergedPath(), view);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not add " + repo + "#" + number + " to the merged bumps: " + e.getMessage());
        }
    }

    /** board/merged.json with the label on the bump, so the tab shows it before the next refresh. */
    static void labelled(String key, String label) {
        try {
            if (!Files.exists(mergedPath())) return;
            JsonNode view = Board.MAPPER.readTree(mergedPath().toFile());
            for (JsonNode i : view.path("items")) {
                if (i.path("key").asText().equals(key) && i.path("labels") instanceof ArrayNode a) {
                    boolean has = false;
                    for (JsonNode x : a) has |= x.asText().equals(label);
                    if (!has) a.add(label);
                }
            }
            Board.writeJson(mergedPath(), view);
        } catch (IOException | RuntimeException e) {
            // The next refresh shows it.
        }
    }

    // ---- git ----

    /** git in {@code dir}/owner_repo.git, the token in its environment (never on its command line). */
    static Git cli(Path dir, String token) {
        return (repo, number, from, head, branch) -> {
            if (!GitHub.REPO_NAME.matcher(repo).matches() || !RELEASE.matcher(branch).matches()) {
                throw new IllegalArgumentException("Refused: " + repo + " " + branch);
            }
            Files.createDirectories(dir);
            Path g = dir.resolve(repo.replace('/', '_') + ".git");
            if (!Files.isDirectory(g)) {
                git(null, token, "init", "-q", "--bare", g.toString());
                git(g, token, "remote", "add", "origin", "https://github.com/" + repo + ".git");
                git(g, token, "config", "remote.origin.promisor", "true");
                git(g, token, "config", "remote.origin.partialclonefilter", "blob:none");
            }
            String onto = "refs/backport/" + branch;
            git(g, token, "fetch", "-q", "--filter=blob:none", "--depth=1", "origin", from,
                    "+refs/pull/" + number + "/head:refs/backport/pr-" + number, "+refs/heads/" + branch + ":" + onto);
            Exec.Result r = exec(g, token, "merge-tree", "--write-tree", "--name-only", "--no-messages", "--merge-base=" + from, onto, head);
            if (r.exitCode() == 0) return List.of();
            if (r.exitCode() != 1) throw new IOException("git couldn't try the cherry-pick: " + (r.stderr() + r.stdout()).strip());
            List<String> lines = new ArrayList<>(r.stdout().lines().filter(s -> !s.isBlank()).toList());
            if (!lines.isEmpty()) lines.removeFirst(); // the tree
            return List.copyOf(new LinkedHashSet<>(lines));
        };
    }

    private static void git(Path g, String token, String... args) throws IOException {
        Exec.Result r = exec(g, token, args);
        if (r.exitCode() != 0) throw new IOException("git " + args[0] + " failed: " + (r.timedOut() ? "timed out" : r.stderr().strip()));
    }

    private static Exec.Result exec(Path g, String token, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        if (g != null) cmd.addAll(List.of("-C", g.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (token != null && !token.isBlank()) {
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic "
                    + Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8)));
        }
        return Exec.run(pb, 5, TimeUnit.MINUTES);
    }
}
