import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * An open Dependabot mvnpm bump as the tab shows it, read from GitHub's
 * GraphQL answer the way the GitHub elf read its PRs (its GitHub.toItem,
 * ciState, commitCi and awaitingApproval), so CI, the merge state and the
 * runs waiting for approval mean the same here as they did on its board.
 */
final class Bumps {

    private Bumps() {}

    /** CI waits for you to approve its workflow runs (a fork's, or one held at a deployment environment you review). */
    static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";

    static final Set<String> RUNNING_CHECKS = Set.of("QUEUED", "IN_PROGRESS", "PENDING", "WAITING", "REQUESTED");
    static final Set<String> PASSED = Set.of("SUCCESS", "NEUTRAL", "SKIPPED");

    /**
     * @param ci             SUCCESS, FAILURE, ERROR, PENDING, EXPECTED, AWAITING_APPROVAL, or null when no checks ran
     * @param mergeable      MERGEABLE, CONFLICTING or UNKNOWN
     * @param mergeState     GitHub's mergeStateStatus: CLEAN (or HAS_HOOKS) when every required check passed
     * @param approved       approved by someone with write access and not blocked by a change request
     */
    record Pr(String repo, int number, String title, String url, String author, String headRef, String headSha,
              String ci, String reviewDecision, String mergeable, String mergeState, Boolean approved, Boolean draft,
              Instant createdAt, Instant updatedAt) {

        String key() { return repo + "#" + number; }

        Pr withCi(String newCi) {
            return new Pr(repo, number, title, url, author, headRef, headSha, newCi, reviewDecision, mergeable,
                    "UNKNOWN", approved, draft, createdAt, updatedAt);
        }
    }

    /** What the bump moves: "Bump org.mvnpm:lit from 3.3.1 to 3.3.3"; null when the title isn't one bump. */
    record Bump(String groupId, String artifactId, String from, String to, boolean major) {
    }

    /** A workflow run waiting for approval, with the deployment environments it waits at (none: a fork's run). */
    record Approval(long run, List<Long> environments) {
    }

    /** The elf's rule (GitHub.mvnpmPrs): a Dependabot PR whose branch or title names an org.mvnpm artifact. */
    static boolean isMvnpmBump(JsonNode pr) {
        String author = pr.path("author").path("login").asText("");
        if (!author.equals("dependabot") && !author.equals("app/dependabot") && !author.equals("dependabot[bot]")) return false;
        return pr.path("headRefName").asText("").contains("org.mvnpm") || pr.path("title").asText("").contains("org.mvnpm");
    }

    static Pr pr(String repo, JsonNode n) {
        JsonNode commit = n.path("commits").path("nodes").path(0).path("commit");
        String reviewDecision = text(n.path("reviewDecision"));
        List<String> writers = new ArrayList<>();
        n.path("latestOpinionatedReviews").path("nodes").forEach(r -> writers.add(r.path("state").asText()));
        boolean approved = reviewDecision != null ? reviewDecision.equals("APPROVED")
                : writers.contains("APPROVED") && !writers.contains("CHANGES_REQUESTED");
        return new Pr(repo, n.path("number").asInt(), n.path("title").asText(), n.path("url").asText(),
                n.path("author").path("login").asText(""), text(n.path("headRefName")), text(n.path("headRefOid")),
                awaitingApproval(commit).isEmpty() ? commitCi(commit) : AWAITING_APPROVAL, reviewDecision,
                text(n.path("mergeable")), text(n.path("mergeStateStatus")), approved, n.path("isDraft").asBoolean(),
                instant(n.path("createdAt")), instant(n.path("updatedAt")));
    }

    static Bump bump(String title) {
        Matcher m = GitHub.BUMP.matcher(title == null ? "" : title);
        if (!m.find() || !m.group(1).startsWith("org.mvnpm")) return null;
        String from = m.group(3), to = m.group(4);
        return new Bump(m.group(1), m.group(2), from, to, !from.split("\\.")[0].equals(to.split("\\.")[0]));
    }

    /**
     * CI as the board shows it: PENDING while any check is still queued or
     * running, otherwise GitHub's rollup. Null when no checks ran.
     */
    static String ciState(JsonNode rollup) {
        if (!rollup.isObject()) return null;
        JsonNode contexts = rollup.path("contexts");
        for (JsonNode c : contexts.path("checkRunCountsByState")) {
            if (RUNNING_CHECKS.contains(c.path("state").asText()) && c.path("count").asInt() > 0) return "PENDING";
        }
        for (JsonNode c : contexts.path("statusContextCountsByState")) {
            if (c.path("state").asText().equals("PENDING") && c.path("count").asInt() > 0) return "PENDING";
        }
        return rollup.path("state").asText(null);
    }

    /** {@link #ciState} of a commit, SUCCESS when it only fails on runs a newer run of the same workflow replaced. */
    static String commitCi(JsonNode commit) {
        String state = ciState(commit.path("statusCheckRollup"));
        return ("FAILURE".equals(state) || "ERROR".equals(state)) && passedOnRerun(commit) ? "SUCCESS" : state;
    }

    /** The workflow runs on a commit that a newer run of the same workflow replaced. */
    static Set<Long> replacedRuns(JsonNode commit) {
        Map<Long, Long> newest = new HashMap<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            if (!run.path("databaseId").isNumber() || !run.path("workflow").path("databaseId").isNumber()) continue;
            newest.merge(run.path("workflow").path("databaseId").asLong(), run.path("databaseId").asLong(), Math::max);
        }
        Set<Long> replaced = new HashSet<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            if (!run.path("databaseId").isNumber() || !run.path("workflow").path("databaseId").isNumber()) continue;
            long id = run.path("databaseId").asLong();
            if (id != newest.get(run.path("workflow").path("databaseId").asLong())) replaced.add(id);
        }
        return replaced;
    }

    /** Every check passed once the replaced runs are left out: at least one was, and nothing else failed. */
    static boolean passedOnRerun(JsonNode commit) {
        Set<Long> replaced = replacedRuns(commit);
        if (replaced.isEmpty()) return false;
        if (!commit.path("status").path("state").asText("SUCCESS").equals("SUCCESS")) return false;
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun").path("databaseId");
            if (run.isNumber() && replaced.contains(run.asLong())) continue;
            String conclusion = suite.path("conclusion").asText("");
            if (conclusion.isEmpty() && !run.isNumber()) continue;
            if (!PASSED.contains(conclusion)) return false;
        }
        return true;
    }

    /**
     * Workflow runs on this commit waiting for you to approve them: a fork's
     * ("Approve and run"), or one held at a deployment environment you review
     * (quarkusio/quarkus holds Dependabot PRs' CI at "dependabot").
     */
    static List<Approval> awaitingApproval(JsonNode commit) {
        List<Approval> runs = new ArrayList<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun").path("databaseId");
            if (!run.isNumber()) continue;
            if (suite.path("conclusion").asText().equals("ACTION_REQUIRED")) {
                runs.add(new Approval(run.asLong(), List.of()));
                continue;
            }
            List<Long> environments = new ArrayList<>();
            for (JsonNode d : suite.path("workflowRun").path("pendingDeploymentRequests").path("nodes")) {
                JsonNode env = d.path("environment").path("databaseId");
                if (d.path("currentUserCanApprove").asBoolean() && env.isNumber()) environments.add(env.asLong());
            }
            if (!environments.isEmpty()) runs.add(new Approval(run.asLong(), List.copyOf(environments)));
        }
        return runs;
    }

    /** GitHub says every required check passed and nothing else blocks the merge. */
    static boolean requiredChecksPassed(String mergeState) {
        return "CLEAN".equals(mergeState) || "HAS_HOOKS".equals(mergeState);
    }

    private static String text(JsonNode n) {
        return n.isTextual() ? n.asText() : null;
    }

    private static Instant instant(JsonNode n) {
        return n.isTextual() ? Instant.parse(n.asText()) : null;
    }
}
