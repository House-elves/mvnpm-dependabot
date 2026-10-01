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
 */
final class GitHub {

    /** Marks the elf's comment, so a re-validation edits it instead of piling up new ones. */
    static final String MARKER = "<!-- mvnpm-dependabot -->";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BUMP = Pattern.compile(
            "Bump ([\\w.-]+):([\\w.-]+) from (\\S+) to (\\S+)");

    record Pr(int number, String title, String branch, String sha, String url,
              String groupId, String artifactId, String from, String to) {
        boolean parsed() { return artifactId != null; }
        String ga()      { return groupId + ":" + artifactId; }
    }

    private final Config config;
    private String token;

    GitHub(Config config) { this.config = config; }

    /** Open Dependabot PRs that bump an org.mvnpm(.*) artifact. */
    List<Pr> mvnpmPrs() throws IOException {
        JsonNode arr = json(gh("pr", "list", "--repo", config.repo, "--author", "app/dependabot",
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
        JsonNode n = json(gh("pr", "view", String.valueOf(number), "--repo", config.repo,
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
                "repos/" + config.repo + "/pulls/" + number + "/reviews"));
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
                "repos/" + config.repo + "/issues/" + number + "/comments"));
        for (JsonNode page : pages) {
            for (JsonNode c : page) {
                if (c.path("user").path("login").asText().equalsIgnoreCase(config.githubUser)
                        && c.path("body").asText().contains(MARKER)) {
                    JsonNode edited = json(ghStdin(body, "api", "-X", "PATCH",
                            "repos/" + config.repo + "/issues/comments/" + c.path("id").asLong(),
                            "-F", "body=@-"));
                    return edited.path("html_url").asText();
                }
            }
        }
        JsonNode created = json(ghStdin(body, "api", "-X", "POST",
                "repos/" + config.repo + "/issues/" + number + "/comments", "-F", "body=@-"));
        return created.path("html_url").asText();
    }

    void approve(int number, String body) throws IOException {
        ghStdin(body, "pr", "review", String.valueOf(number), "--repo", config.repo,
                "--approve", "--body-file", "-");
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
        var r = Exec.run(pb, 2, TimeUnit.MINUTES, stdin);
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
