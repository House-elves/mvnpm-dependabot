import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The live half of the validation: a throwaway app on the elf's 999-SNAPSHOT,
 * started in dev mode, and a headless Claude session that drives its Dev UI
 * through chrome-devtools-mcp.
 *
 * chrome-devtools-mcp runs --headless --isolated, so it launches its own
 * browser with a temporary profile: nothing here needs the principal's Chrome
 * open, and nothing touches its tabs or profile.
 */
final class DevUiTester {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    record Page(String page, boolean ok, String notes) {}

    record Outcome(boolean ran, boolean pass, String summary, List<Page> pages,
                   List<String> consoleErrors, List<String> suspects, List<String> serverErrors) {
        static Outcome notRun(String why) {
            return new Outcome(false, false, why, List.of(), List.of(), List.of(), List.of());
        }
    }

    /** What the session is told about one bump, so it knows where to look. */
    record Focus(String ga, String npm, String from, String to, Set<String> roots, Set<String> jsFiles) {}

    private final Config config;
    private final Workspace workspace;
    private final Path runDir;

    DevUiTester(Config config, Workspace workspace, Path runDir) {
        this.config = config;
        this.workspace = workspace;
        this.runDir = runDir;
    }

    /** Creates the app (with these extensions) and runs one Dev UI session against it. */
    Outcome test(String label, Collection<String> extensions, List<Focus> focus) {
        Path appsDir = config.stateDir.resolve("apps");
        Path app = appsDir.resolve("devui-check");
        try {
            createApp(appsDir, app, extensions);
        } catch (IOException e) {
            return Outcome.notRun("could not create the test app: " + e.getMessage());
        }

        Path devLog = runDir.resolve("dev-" + label + ".log");
        Process dev = null;
        try {
            dev = startDev(app, devLog);
            String base = "http://localhost:" + config.devPort;
            String waitErr = waitForDevUi(dev, base, devLog);
            if (waitErr != null) return Outcome.notRun(waitErr);
            long devLogMark = Files.size(devLog);
            Outcome o = drive(label, base, extensions, focus);
            List<String> serverErrors = serverErrors(devLog, devLogMark);
            return new Outcome(o.ran(), o.pass(), o.summary(), o.pages(), o.consoleErrors(), o.suspects(), serverErrors);
        } catch (IOException e) {
            return Outcome.notRun("dev mode failed: " + e.getMessage());
        } finally {
            if (dev != null) stop(dev);
        }
    }

    // ---- the app ----

    private void createApp(Path appsDir, Path app, Collection<String> extensions) throws IOException {
        Files.createDirectories(appsDir);
        // A project outside a checkout picks its ~/.mavenrc workspace from .quarkus-ws.
        Files.writeString(appsDir.resolve(".quarkus-ws"), config.workspace() + "\n");
        deleteTree(app);
        List<String> cmd = List.of("mvn", "-B", "io.quarkus:quarkus-maven-plugin:999-SNAPSHOT:create",
                "-DprojectGroupId=org.acme", "-DprojectArtifactId=devui-check",
                "-DplatformGroupId=io.quarkus", "-DplatformArtifactId=quarkus-bom", "-DplatformVersion=999-SNAPSHOT",
                "-Dextensions=" + String.join(",", extensions), "-DnoCode=false");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(appsDir.toFile());
        Workspace.env(pb.environment());
        var r = Exec.runToFile(pb, runDir.resolve("create-app.log"), 10, TimeUnit.MINUTES);
        if (r.exitCode() != 0 || !Files.exists(app.resolve("pom.xml"))) {
            throw new IOException("quarkus:create failed; see " + runDir.resolve("create-app.log"));
        }
        if (extensions.contains(DATASOURCE)) addEntities(app);
    }

    /** Agroal's Dev UI draws an ER diagram (viz-js), which needs a datasource with related tables. */
    static final String DATASOURCE = "quarkus-agroal";
    static final List<String> DATASOURCE_EXTRAS = List.of("quarkus-jdbc-h2", "quarkus-hibernate-orm");

    private static void addEntities(Path app) throws IOException {
        Path pkg = app.resolve("src/main/java/org/acme");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Owner.java"), """
                package org.acme;

                import jakarta.persistence.Entity;
                import jakarta.persistence.GeneratedValue;
                import jakarta.persistence.Id;
                import jakarta.persistence.OneToMany;
                import java.util.List;

                @Entity
                public class Owner {
                    @Id @GeneratedValue public Long id;
                    public String name;
                    @OneToMany(mappedBy = "owner") public List<Pet> pets;
                }
                """);
        Files.writeString(pkg.resolve("Pet.java"), """
                package org.acme;

                import jakarta.persistence.Entity;
                import jakarta.persistence.GeneratedValue;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;

                @Entity
                public class Pet {
                    @Id @GeneratedValue public Long id;
                    public String name;
                    @ManyToOne public Owner owner;
                }
                """);
        Path props = app.resolve("src/main/resources/application.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "quarkus.hibernate-orm.schema-management.strategy=drop-and-create\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private Process startDev(Path app, Path devLog) throws IOException {
        List<String> cmd = List.of("mvn", "-B", "quarkus:dev",
                "-Dquarkus.http.port=" + config.devPort, "-Ddebug=false",
                "-Dquarkus.analytics.disabled=true", "-Dquarkus.console.enabled=false",
                "-Dquarkus.test.continuous-testing=disabled");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(app.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(devLog.toFile()));
        Workspace.env(pb.environment());
        Process p = pb.start();
        p.getOutputStream().close();
        return p;
    }

    /** Waits for the Dev UI to answer. Null when it is up, otherwise why not. */
    private String waitForDevUi(Process dev, String base, Path devLog) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!dev.isAlive()) {
                return "dev mode exited (" + dev.exitValue() + ") before the Dev UI came up:\n"
                        + Workspace.tail(devLog, 40);
            }
            try {
                var resp = http.send(HttpRequest.newBuilder(URI.create(base + "/q/dev-ui/")).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) return null;
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }
        return "the Dev UI did not answer within 10 minutes:\n" + Workspace.tail(devLog, 40);
    }

    private static void stop(Process dev) {
        dev.descendants().forEach(ProcessHandle::destroy);
        dev.destroy();
        try {
            if (!dev.waitFor(30, TimeUnit.SECONDS)) {
                dev.descendants().forEach(ProcessHandle::destroyForcibly);
                dev.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> serverErrors(Path devLog, long from) {
        try {
            String all = Files.readString(devLog);
            String during = all.substring((int) Math.min(from, all.length()));
            return during.lines().filter(l -> l.contains(" ERROR ") || l.contains("[ERROR]"))
                    .distinct().limit(20).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---- the session ----

    private Outcome drive(String label, String base, Collection<String> extensions, List<Focus> focus) {
        Path screens = runDir.resolve("screens-" + label);
        Path mcp = runDir.resolve("mcp.json");
        try {
            Files.createDirectories(screens);
            Map<String, Object> server = Map.of(
                    "type", "stdio",
                    "command", "npx",
                    "args", List.of("-y", "chrome-devtools-mcp@latest", "--headless", "--isolated",
                            "--executablePath", config.chromePath, "--viewport", "1600x1000"));
            Files.writeString(mcp, MAPPER.writeValueAsString(Map.of("mcpServers", Map.of("chrome-devtools", server))));
        } catch (IOException e) {
            return Outcome.notRun("could not write the MCP config: " + e.getMessage());
        }

        List<String> cmd = new ArrayList<>(List.of("claude", "-p",
                "--output-format", "json",
                "--mcp-config", mcp.toString(), "--strict-mcp-config",
                // Browser plus read-only access to the checkout, nothing else.
                "--allowedTools", "mcp__chrome-devtools,Read,Grep,Glob",
                "--disallowedTools", "Bash,Edit,Write,NotebookEdit,WebFetch,WebSearch,Agent"));
        if (!config.agentModel.isEmpty()) {
            cmd.add("--model");
            cmd.add(config.agentModel);
        }
        // The session runs from the run dir: chrome-devtools-mcp only writes
        // screenshots inside the client's roots. The source is an extra,
        // read-only directory.
        cmd.add("--add-dir");
        cmd.add(workspace.dir().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(runDir.toFile());
        var r = Exec.run(pb, config.uiTimeoutMinutes, TimeUnit.MINUTES, prompt(base, extensions, focus, screens));
        try {
            Files.writeString(runDir.resolve("ui-" + label + ".json"), r.stdout());
        } catch (IOException ignored) {
            // the transcript is a convenience
        }
        if (r.timedOut()) return Outcome.notRun("the Dev UI session timed out after " + config.uiTimeoutMinutes + " minutes");
        try {
            JsonNode out = MAPPER.readTree(r.stdout());
            if (out.path("is_error").asBoolean(false)) {
                return Outcome.notRun("the Dev UI session failed: " + out.path("result").asText(out.path("subtype").asText()));
            }
            String text = out.path("result").asText("");
            Matcher m = JSON_BLOCK.matcher(text);
            String last = null;
            while (m.find()) last = m.group(1);
            if (last == null) return Outcome.notRun("the Dev UI session gave no verdict:\n" + text);
            JsonNode v = MAPPER.readTree(last);
            List<Page> pages = new ArrayList<>();
            v.path("pages").forEach(p -> pages.add(new Page(p.path("page").asText(),
                    "ok".equalsIgnoreCase(p.path("status").asText()), p.path("notes").asText(""))));
            return new Outcome(true, "pass".equalsIgnoreCase(v.path("verdict").asText()),
                    v.path("summary").asText(""), pages, strings(v.path("consoleErrors")),
                    strings(v.path("suspects")), List.of());
        } catch (IOException e) {
            return Outcome.notRun("unreadable Dev UI session output (" + e.getMessage() + "): "
                    + (r.stdout().isBlank() ? r.stderr() : r.stdout().substring(0, Math.min(500, r.stdout().length()))));
        }
    }

    private String prompt(String base, Collection<String> extensions, List<Focus> focus, Path screens) {
        StringBuilder bumps = new StringBuilder();
        for (Focus f : focus) {
            bumps.append("- ").append(f.ga()).append(" (npm `").append(f.npm()).append("`) ")
                 .append(f.from()).append(" -> ").append(f.to()).append('\n');
            if (!f.roots().equals(Set.of(f.ga()))) {
                bumps.append("  transitive; pulled in by: ").append(String.join(", ", f.roots())).append('\n');
            }
            for (String js : f.jsFiles()) bumps.append("  used by: ").append(js).append('\n');
        }
        return """
                You are a QA tester for the Quarkus Dev UI. Dependabot bumped these mvnpm (npm-on-Maven)
                libraries, and a Quarkus 999-SNAPSHOT with the bumps is running in dev mode:

                %s
                The app has these extensions: %s
                Dev UI: %s/q/dev-ui/

                Your job: use the chrome-devtools tools to check that the Dev UI still works, with
                extra attention to the pages that use the bumped libraries. The Quarkus source the app
                was built from is at %s; you may Read/Grep it to work out which Dev UI page a "used by"
                file renders (its extension, and the page/card it registers).

                Do this:
                1. Open %s/q/dev-ui/ and wait for it to render. Check the console and network:
                   failed module/import-map loads (404 under /_static/ or /q/dev-ui/) are the classic
                   symptom of a bad mvnpm bump.
                2. Visit the Extensions page, then every page in the left menu (Configuration,
                   Workspace, Endpoints, Continuous Testing, Dev Services, Build Metrics, Readme,
                   Dependencies, and whatever else is there).
                3. For each extension card, open each of its pages.
                4. For every page that uses a bumped library, actually exercise it: let graphs and
                   charts render, open dialogs, switch tabs, use a search/filter box, expand items.
                   Confirm the library's output appears (a rendered graph, formatted markdown, etc.),
                   not an empty or broken element.
                5. On every page, read the console (errors and warnings) and look for failed network
                   requests. Take a screenshot of each page that uses a bumped library, saving it under
                   %s (one file per page, named after the page).

                Judge fairly: a console warning that has nothing to do with the bumped libraries (for
                example a dev service that is still starting) is not a failure. A blank page, a missing
                component, an uncaught exception, or a failed script/module load is.

                End your final message with exactly one fenced json block, and nothing after it:
                ```json
                {"verdict": "pass" | "fail",
                 "summary": "one or two sentences",
                 "pages": [{"page": "name or path", "status": "ok" | "broken", "notes": "short"}],
                 "consoleErrors": ["error text (page)"],
                 "suspects": ["groupId:artifactId of a bump you believe broke something"]}
                ```
                """.formatted(bumps, String.join(", ", extensions), base, workspace.dir(), base, screens);
    }

    // ---- helpers ----

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }

    static Set<String> union(Collection<String> a, Collection<String> b) {
        Set<String> s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }
}
