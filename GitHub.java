import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gh CLI, always as GITHUB_USER. The token comes from gh's own keyring
 * (`gh auth token -u`) and is passed per command as GH_TOKEN, so the elf never
 * switches the host's active gh account out from under the principal.
 *
 * Every call waits for the one before it: GitHub asks for a user's requests to
 * be made one at a time, and several at once trip its secondary rate limit
 * (the GitHub elf's board learned this with a 403 that lasted minutes).
 */
final class GitHub {

    /** Marks the elf's comment, so a re-validation edits it instead of piling up new ones. */
    static final String MARKER = "<!-- mvnpm-dependabot -->";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object ONE_AT_A_TIME = new Object();
    static final Pattern BUMP = Pattern.compile(
            "Bump ([\\w.-]+):([\\w.-]+) from (\\S+) to (\\S+)");

    record Pr(int number, String title, String branch, String sha, String url,
              String groupId, String artifactId, String from, String to) {
        boolean parsed() { return artifactId != null; }
        String ga()      { return groupId + ":" + artifactId; }
    }

    private final Config config;
    private final String repo;
    private String token;

    /** A client for one repo; every call below is against it. */
    GitHub(Config config, String repo) {
        this.config = config;
        this.repo = repo;
    }

    /** Open Dependabot PRs that bump an org.mvnpm(.*) artifact. */
    List<Pr> mvnpmPrs() throws IOException {
        JsonNode arr = json(gh("pr", "list", "--repo", repo, "--author", "app/dependabot",
                "--state", "open", "--limit", "100",
                "--json", "number,title,headRefName,headRefOid,url"));
        List<Pr> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String branch = n.path("headRefName").asText();
            String title = n.path("title").asText();
            if (!branch.contains("org.mvnpm") && !title.contains("org.mvnpm")) continue;
            Matcher m = BUMP.matcher(title);
            boolean ok = m.find() && m.group(1).startsWith("org.mvnpm");
            out.add(new Pr(n.path("number").asInt(), title, branch, n.path("headRefOid").asText(),
                    n.path("url").asText(),
                    ok ? m.group(1) : null, ok ? m.group(2) : null,
                    ok ? m.group(3) : null, ok ? m.group(4) : null));
        }
        out.sort((a, b) -> Integer.compare(a.number(), b.number()));
        return out;
    }

    /** One PR by number, whatever its author - for --pr runs. */
    Pr pr(int number) throws IOException {
        JsonNode n = json(gh("pr", "view", String.valueOf(number), "--repo", repo,
                "--json", "number,title,headRefName,headRefOid,url"));
        Matcher m = BUMP.matcher(n.path("title").asText());
        boolean ok = m.find() && m.group(1).startsWith("org.mvnpm");
        return new Pr(number, n.path("title").asText(), n.path("headRefName").asText(),
                n.path("headRefOid").asText(), n.path("url").asText(),
                ok ? m.group(1) : null, ok ? m.group(2) : null,
                ok ? m.group(3) : null, ok ? m.group(4) : null);
    }

    /** True when GITHUB_USER already left an APPROVED review on exactly this commit. */
    boolean approvedAt(int number, String sha) throws IOException {
        JsonNode reviews = json(gh("api", "--paginate", "--slurp",
                "repos/" + repo + "/pulls/" + number + "/reviews"));
        for (JsonNode page : reviews) {
            for (JsonNode r : page) {
                if (r.path("user").path("login").asText().equalsIgnoreCase(config.githubUser)
                        && "APPROVED".equals(r.path("state").asText())
                        && sha.equals(r.path("commit_id").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Creates the elf's comment, or edits it in place when one exists. Returns its URL. */
    String upsertComment(int number, String body) throws IOException {
        JsonNode pages = json(gh("api", "--paginate", "--slurp",
                "repos/" + repo + "/issues/" + number + "/comments"));
        for (JsonNode page : pages) {
            for (JsonNode c : page) {
                if (c.path("user").path("login").asText().equalsIgnoreCase(config.githubUser)
                        && c.path("body").asText().contains(MARKER)) {
                    JsonNode edited = json(ghStdin(body, "api", "-X", "PATCH",
                            "repos/" + repo + "/issues/comments/" + c.path("id").asLong(),
                            "-F", "body=@-"));
                    return edited.path("html_url").asText();
                }
            }
        }
        JsonNode created = json(ghStdin(body, "api", "-X", "POST",
                "repos/" + repo + "/issues/" + number + "/comments", "-F", "body=@-"));
        return created.path("html_url").asText();
    }

    void approve(int number, String body) throws IOException {
        ghStdin(body, "pr", "review", String.valueOf(number), "--repo", repo,
                "--approve", "--body-file", "-");
    }

    // ---- the tab: its board, and the writes behind its buttons ----

    /** What the board reads of a PR (see {@link Bumps#pr}). */
    static final String PR_FIELDS = """
            number title url state createdAt updatedAt isDraft headRefName headRefOid reviewDecision mergeable mergeStateStatus
            author { login }
            latestOpinionatedReviews(first: 20, writersOnly: true) { nodes { state } }
            commits(last: 1) { nodes { commit {
              statusCheckRollup { state contexts(first: 1) { checkRunCountsByState { state count } statusContextCountsByState { state count } } }
              status { state }
              checkSuites(first: 30) { nodes { conclusion workflowRun { databaseId workflow { databaseId }
                pendingDeploymentRequests(first: 3) { nodes { currentUserCanApprove environment { databaseId } } } } } }
            } } }
            """;

    private static final String OPEN_PRS = """
            query($q: String!, $after: String) {
              search(query: $q, type: ISSUE, first: 50, after: $after) {
                pageInfo { hasNextPage endCursor }
                nodes { ... on PullRequest { %s } }
              }
            }
            """.formatted(PR_FIELDS);

    private static final String PULL = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed viewerDefaultMergeMethod
                pullRequest(number: $number) { %s }
              }
            }
            """.formatted(PR_FIELDS);

    /** Open Dependabot PRs of the repo, as GraphQL nodes (the mvnpm ones are picked by {@link Bumps#isMvnpmBump}). */
    List<JsonNode> openDependabotPrs() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        String after = null;
        for (int page = 0; page < 6; page++) {
            List<String> args = new ArrayList<>(List.of("api", "graphql", "-f", "query=" + OPEN_PRS,
                    "-f", "q=is:open is:pr author:app/dependabot repo:" + repo));
            if (after != null) args.addAll(List.of("-f", "after=" + after));
            JsonNode search = graphql(args).path("search");
            search.path("nodes").forEach(out::add);
            JsonNode info = search.path("pageInfo");
            String next = info.path("hasNextPage").asBoolean() ? info.path("endCursor").asText(null) : null;
            if (next == null || next.equals(after) || search.path("nodes").isEmpty()) break;
            after = next;
        }
        return out;
    }

    private static final String CI = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                viewerPermission
                pullRequest(number: $number) {
                  headRefOid
                  commits(last: 1) { nodes { commit {
                    status { contexts { context state targetUrl } }
                    checkSuites(first: 50) { nodes {
                      app { slug }
                      status
                      workflowRun { databaseId workflow { databaseId name } }
                      checkRuns(first: 50, filterBy: { checkType: LATEST,
                          conclusions: [FAILURE, TIMED_OUT, CANCELLED, STARTUP_FAILURE, ACTION_REQUIRED] }) {
                        nodes { name conclusion detailsUrl }
                      }
                    } }
                  } } }
                }
              }
            }
            """;

    /**
     * What failed on the PR's head, as the GitHub elf's CI menu shows it: {headSha, failed: [{name, conclusion, url}],
     * runs: the failed GitHub Actions workflow runs (the newest of each workflow) whose failed jobs can be re-run,
     * canRerun: you have write access, which GitHub needs for that, running: the workflows running again now}.
     */
    com.fasterxml.jackson.databind.node.ObjectNode ci(int number) throws IOException {
        String[] r = repo.split("/", 2);
        JsonNode repository = graphql(List.of("api", "graphql", "-f", "query=" + CI, "-f", "owner=" + r[0], "-f", "name=" + r[1],
                "-F", "number=" + number)).path("repository");
        JsonNode pr = repository.path("pullRequest");
        if (!pr.isObject()) throw new IOException(repo + "#" + number + " isn't a pull request");
        JsonNode commit = pr.path("commits").path("nodes").path(0).path("commit");
        // A workflow run again replaces the one before it: only the newest run of each workflow counts.
        Map<Long, Long> newest = new java.util.HashMap<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            if (run.path("databaseId").isNumber() && run.path("workflow").path("databaseId").isNumber()) {
                newest.merge(run.path("workflow").path("databaseId").asLong(), run.path("databaseId").asLong(), Math::max);
            }
        }
        var out = MAPPER.createObjectNode().put("headSha", pr.path("headRefOid").asText());
        var failed = out.putArray("failed");
        java.util.Set<Long> runs = new java.util.LinkedHashSet<>();
        java.util.Set<String> running = new java.util.TreeSet<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            Long id = run.path("databaseId").isNumber() ? run.path("databaseId").asLong() : null;
            if (id != null && run.path("workflow").path("databaseId").isNumber()
                    && !id.equals(newest.get(run.path("workflow").path("databaseId").asLong()))) continue;
            // A failed workflow re-run (its newest attempt) is in progress: a summary status may still say it failed.
            if (id != null && suite.path("app").path("slug").asText().equals("github-actions")
                    && List.of("QUEUED", "IN_PROGRESS", "PENDING", "WAITING", "REQUESTED").contains(suite.path("status").asText())) {
                running.add(run.path("workflow").path("name").asText("a workflow"));
            }
            boolean actions = suite.path("app").path("slug").asText().equals("github-actions");
            for (JsonNode c : suite.path("checkRuns").path("nodes")) {
                failed.addObject().put("name", c.path("name").asText()).put("conclusion", c.path("conclusion").asText())
                        .put("url", webUrl(c.path("detailsUrl").asText(null)));
                if (actions && id != null) runs.add(id);
            }
        }
        for (JsonNode ctx : commit.path("status").path("contexts")) {
            String state = ctx.path("state").asText();
            if (state.equals("FAILURE") || state.equals("ERROR")) {
                failed.addObject().put("name", ctx.path("context").asText()).put("conclusion", state).put("url", webUrl(ctx.path("targetUrl").asText(null)));
            }
        }
        runs.forEach(out.putArray("runs")::add);
        running.forEach(out.putArray("running")::add);
        out.put("canRerun", List.of("WRITE", "MAINTAIN", "ADMIN").contains(repository.path("viewerPermission").asText()));
        return out;
    }

    /** A check's link only if it's an http(s) address: whoever posts a status sets it, so it could be anything. */
    static String webUrl(String url) {
        if (url == null) return null;
        try {
            java.net.URI u = java.net.URI.create(url);
            return ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One PR now, with what its repo allows for merging: the repository node, its pullRequest inside. */
    JsonNode pull(int number) throws IOException {
        String[] r = repo.split("/", 2);
        return graphql(List.of("api", "graphql", "-f", "query=" + PULL, "-f", "owner=" + r[0], "-f", "name=" + r[1],
                "-F", "number=" + number)).path("repository");
    }

    static final String REPO_PATH = "/repos/[A-Za-z0-9-]+/[A-Za-z0-9._-]+";
    static final Pattern APPROVE_RUN = Pattern.compile(REPO_PATH + "/actions/runs/\\d+/approve");
    static final Pattern PENDING_DEPLOYMENTS = Pattern.compile(REPO_PATH + "/actions/runs/\\d+/pending_deployments");
    static final Pattern RERUN_FAILED = Pattern.compile(REPO_PATH + "/actions/runs/\\d+/rerun-failed-jobs");
    static final Pattern MERGE = Pattern.compile(REPO_PATH + "/pulls/\\d+/merge");
    static final Pattern COMMENT = Pattern.compile(REPO_PATH + "/issues/\\d+/comments");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    static final String REBASE = "@dependabot rebase";

    /**
     * The only writes the tab's buttons make, as the GitHub elf's GitHubWriter
     * allows them: approving workflow runs (a fork's "Approve and run", or a run
     * held at deployment environments you review: approve only, no comment),
     * re-running a run's failed jobs, a merge pinned to a head commit, and the
     * one comment "@dependabot rebase".
     * Always in this client's repo.
     */
    static boolean allowedWrite(String repo, String method, String path, JsonNode body) {
        if (!path.startsWith("/repos/" + repo + "/")) return false;
        return switch (method) {
            case "POST" -> ((APPROVE_RUN.matcher(path).matches() || RERUN_FAILED.matcher(path).matches()) && body.isObject() && body.isEmpty())
                    || (PENDING_DEPLOYMENTS.matcher(path).matches() && body.size() == 3
                            && body.path("state").asText().equals("approved") && body.path("comment").asText("-").isEmpty()
                            && body.path("environment_ids").isArray() && body.path("environment_ids").size() >= 1
                            && body.path("environment_ids").size() <= 3
                            && java.util.stream.StreamSupport.stream(body.path("environment_ids").spliterator(), false)
                                    .allMatch(JsonNode::isIntegralNumber))
                    || (COMMENT.matcher(path).matches() && body.size() == 1 && REBASE.equals(body.path("body").asText()));
            case "PUT" -> MERGE.matcher(path).matches() && body.size() == 2
                    && List.of("merge", "squash", "rebase").contains(body.path("merge_method").asText())
                    && SHA.matcher(body.path("sha").asText()).matches();
            default -> false;
        };
    }

    /** One of the writes {@link #allowedWrite} allows, as GITHUB_USER; anything else is refused before gh runs. */
    JsonNode write(String method, String path, JsonNode body) throws IOException {
        if (!allowedWrite(repo, method, path, body)) {
            throw new IOException("mvnpm-dependabot only approves and re-runs CI runs, merges at a head and asks Dependabot to rebase;"
                    + " refused " + method + " " + path);
        }
        return json(ghStdin(MAPPER.writeValueAsString(body), "api", "-X", method, path.substring(1), "--input", "-"));
    }

    private JsonNode graphql(List<String> args) throws IOException {
        JsonNode res = json(gh(args.toArray(String[]::new)));
        JsonNode errors = res.path("errors");
        if (errors.isArray() && !errors.isEmpty() && (res.path("data").isMissingNode() || res.path("data").isNull())) {
            throw new IOException("GitHub: " + errors.path(0).path("message").asText("GraphQL error"));
        }
        return res.path("data");
    }

    // ---- plumbing ----

    private String token() throws IOException {
        if (token == null) {
            var r = Exec.run(new ProcessBuilder("gh", "auth", "token", "-u", config.githubUser),
                    30, TimeUnit.SECONDS);
            if (r.exitCode() != 0 || r.stdout().isBlank()) {
                throw new IOException("no gh token for " + config.githubUser
                        + " - run: gh auth login (as " + config.githubUser + ")");
            }
            token = r.stdout().strip();
        }
        return token;
    }

    private String gh(String... args) throws IOException {
        return ghStdin(null, args);
    }

    private String ghStdin(String stdin, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("gh");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("GH_TOKEN", token());
        env.put("GH_PROMPT_DISABLED", "1");
        Exec.Result r;
        synchronized (ONE_AT_A_TIME) {
            r = Exec.run(pb, 2, TimeUnit.MINUTES, stdin);
        }
        if (r.exitCode() != 0) {
            throw new IOException("gh " + String.join(" ", args.length > 3 ? List.of(args).subList(0, 3) : List.of(args))
                    + " failed: " + (r.timedOut() ? "timed out" : r.stderr()));
        }
        return r.stdout();
    }

    private static JsonNode json(String s) throws IOException {
        return MAPPER.readTree(s.isBlank() ? "[]" : s);
    }
}
