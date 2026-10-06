///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.apache.maven:maven-artifact:3.9.9
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java Target.java Exec.java GitHub.java BomAnalyzer.java Workspace.java DevUiTester.java
//SOURCES WebUiTester.java
//SOURCES Report.java StateStore.java Notifier.java Installer.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * A House Elf that does the morning round of Dependabot's mvnpm PRs on
 * Quarkus (and any other repo in REPOS), so the principal only has to make
 * the final call.
 *
 * For each open Quarkus PR that bumps an org.mvnpm artifact it:
 *   1. checks the bump against the BOM: a transitive pin must stay inside the
 *      range its pinned parents ask for (BomAnalyzer);
 *   2. merges all the surviving PRs onto main in its own clone and builds a
 *      999-SNAPSHOT into its own ~/.mavenrc workspace repo (Workspace);
 *   3. runs CI's dependency:go-offline on the modules that use them - the step
 *      that broke on hpcc-js/wasm 2.35.1;
 *   4. starts a test app in dev mode and has a headless Claude session drive
 *      the Dev UI through chrome-devtools-mcp, bisecting when it fails
 *      (DevUiTester);
 *   5. comments on each PR as GITHUB_USER, approves the safe ones, and emails
 *      a summary.
 *
 * Any other repo is a plain Maven project: the bumps are merged, the modules
 * that declare them are built and tested, and the web UI they package is
 * served and driven the same way (WebUiTester). Steps 5 and the bisecting are
 * shared.
 */
@Command(name = "mvnpm-dependabot", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Validates Dependabot mvnpm PRs: BOM ranges, CI resolution, snapshot build, Dev UI test.")
public class MvnpmDependabot implements Callable<Integer> {

    @Option(names = "--install", description = "Interactive setup: config and systemd units.")
    boolean install;

    @Option(names = "--once", description = "One morning round (what the systemd timer runs).")
    boolean once;

    @Option(names = "--dry-run", description = "Do everything except comment, approve or email; print the comments.")
    boolean dryRun;

    @Option(names = "--repo", description = "Only this repo (owner/name); defaults to every repo in REPOS.")
    String repoOption;

    @Option(names = "--pr", description = "Check only these PR numbers (repeatable), even if already checked. "
            + "They belong to --repo, or to the first repo in REPOS.")
    List<Integer> prNumbers = new ArrayList<>();

    @Option(names = "--force", description = "Re-check PRs already checked at their current commit.")
    boolean force;

    @Option(names = "--skip-ui", description = "Skip the Dev UI test (verdicts become 'needs a human look').")
    boolean skipUi;

    @Option(names = "--status", description = "Show config and the PRs checked so far.")
    boolean status;

    public static void main(String[] args) {
        System.exit(new CommandLine(new MvnpmDependabot()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (install) {
            Installer.run();
            return 0;
        }
        Config config = Config.load();
        config.validate();
        if (status) {
            printStatus(config);
            return 0;
        }

        try (var lock = SingleInstanceLock.tryAcquire(config.lockPath())) {
            if (lock == null) {
                System.err.println("another mvnpm-dependabot is running; exiting");
                return 0;
            }
            if (!waitForNetwork()) {
                System.err.println("no network after waiting; exiting");
                return 1;
            }
            return round(config);
        }
    }

    private int round(Config config) throws IOException {
        List<Target> targets = config.targets();
        if (repoOption != null) {
            targets = targets.stream().filter(t -> t.repo().equals(repoOption)).toList();
            if (targets.isEmpty()) {
                System.err.println(repoOption + " is not in REPOS " + config.repos);
                return 1;
            }
        } else if (!prNumbers.isEmpty()) {
            targets = targets.subList(0, 1);
        }

        Path runDir = config.runsDir().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        List<Report.Check> all = new ArrayList<>();
        int exit = 0;
        for (Target t : targets) {
            // One repo failing (GitHub down, a clone that will not fetch) must not cost the others their round.
            try {
                all.addAll(checkRepo(config, t, runDir));
            } catch (IOException e) {
                System.err.println(t.repo() + ": " + e.getMessage());
                exit = 1;
            }
        }
        if (!all.isEmpty()) {
            if (!dryRun) new Notifier(config).sendSummary(all, runDir.toString());
            System.out.println("run artifacts: " + runDir);
        }
        return exit;
    }

    private List<Report.Check> checkRepo(Config config, Target target, Path topRunDir) throws IOException {
        GitHub gh = new GitHub(config, target.repo());
        StateStore state = StateStore.open(config.statePath());

        List<GitHub.Pr> prs = new ArrayList<>();
        if (!prNumbers.isEmpty()) {
            for (int n : prNumbers) prs.add(gh.pr(n));
        } else {
            for (GitHub.Pr pr : gh.mvnpmPrs()) {
                if (force || !state.checked(target.repo(), pr.number(), pr.sha())) prs.add(pr);
            }
        }
        if (prs.isEmpty()) {
            System.out.println(target.repo() + ": no new Dependabot mvnpm PRs to check");
            return List.of();
        }
        System.out.println(target.repo() + ": checking " + prs.size() + " PR(s): "
                + prs.stream().map(p -> "#" + p.number()).toList());

        Path runDir = topRunDir.resolve(target.name());
        Files.createDirectories(runDir);
        pruneRuns(config.runsDir(), 14);

        Workspace ws = new Workspace(config, target, runDir);
        String baseSha = ws.resetToMain();

        // 1. Static: the BOM's ranges (Quarkus), or just what the bump changes (anything else).
        BomAnalyzer analyzer = new BomAnalyzer(config, ws.dir());
        List<Report.Check> checks = new ArrayList<>();
        for (GitHub.Pr pr : prs) {
            Report.Check c = new Report.Check(target, pr);
            if (pr.parsed()) {
                try {
                    c.analysis = target.quarkus() ? analyzer.analyze(pr) : analyzer.analyzeDirect(pr);
                } catch (IOException e) {
                    c.analysisError = e.getMessage();
                }
            }
            checks.add(c);
            System.out.printf("#%d %s: %s%n", pr.number(), pr.title(),
                    c.staticFail() ? "fails the static checks" : "static checks pass");
        }

        // 2. Merge and build what survived.
        List<Report.Check> merged = new ArrayList<>();
        for (Report.Check c : checks) {
            if (c.staticFail()) continue;
            if (ws.merge(c.pr)) {
                merged.add(c);
            } else {
                c.conflicted = true;
            }
        }
        if (!merged.isEmpty()) {
            if (target.quarkus()) {
                buildAndTest(config, ws, runDir, merged);
            } else {
                buildAndTestMaven(config, target, ws, runDir, merged);
            }
        }

        // 5. Verdicts, comments, approvals.
        for (Report.Check c : checks) {
            Report.decide(c, config);
            String body = Report.comment(c, config, ws.branch(), baseSha);
            Files.writeString(runDir.resolve("comment-" + c.pr.number() + ".md"), body);
            System.out.printf("%s#%d -> %s%s%n", target.repo(), c.pr.number(), c.verdict, c.approve ? " (approve)" : "");
            if (dryRun) {
                System.out.println(body);
                continue;
            }
            String url = gh.upsertComment(c.pr.number(), body);
            boolean approved = false;
            if (c.approve) {
                if (!gh.approvedAt(c.pr.number(), c.pr.sha())) {
                    gh.approve(c.pr.number(), "All mvnpm-dependabot checks pass - see " + url);
                }
                approved = true;
            }
            state.record(target.repo(), c.pr.number(), c.pr.sha(), c.verdict.name(), approved, url);
        }
        return checks;
    }

    private void buildAndTest(Config config, Workspace ws, Path runDir, List<Report.Check> merged) throws IOException {
        System.out.println("building Quarkus 999-SNAPSHOT with " + merged.size() + " bump(s) into workspace '"
                + config.workspace() + "' (log: " + runDir.resolve("build.log") + ")");
        var build = ws.build();
        if (build.exitCode() != 0) {
            String why = build.timedOut() ? "timed out" : String.join("; ", Workspace.errors(runDir.resolve("build.log")));
            merged.forEach(c -> c.buildError = why.isBlank() ? "see build.log" : why);
            return;
        }

        // 3. CI's go-offline step, on the modules that use the bumped roots.
        Set<String> modules = new TreeSet<>();
        merged.forEach(c -> modules.addAll(c.analysis.modules()));
        if (!modules.isEmpty()) {
            System.out.println("dependency:go-offline on " + modules);
            ws.goOffline(modules);
            List<String> errors = Workspace.errors(runDir.resolve("go-offline.log"));
            for (Report.Check c : merged) {
                if (!disjoint(modules, c.analysis.modules())) c.goOfflineRan = true;
                Set<String> names = new LinkedHashSet<>(c.analysis.rootAncestors());
                names.add(c.analysis.ga());
                c.goOfflineErrors = errors.stream()
                        .filter(e -> names.stream().anyMatch(n -> e.contains(n + ":")))
                        .toList();
            }
        }

        if (skipUi) return;

        // 4. The Dev UI, all bumps together first.
        DevUiTester tester = new DevUiTester(config, ws, runDir);
        Set<String> extensions = new TreeSet<>(config.baseExtensions);
        merged.forEach(c -> extensions.addAll(c.analysis.extensions()));
        if (extensions.contains(DevUiTester.DATASOURCE)) extensions.addAll(DevUiTester.DATASOURCE_EXTRAS);
        System.out.println("Dev UI test with extensions " + extensions);
        uiBisect(ws, "Dev UI", merged,
                () -> ws.installBoms().exitCode() == 0,
                (label, bumps) -> tester.test(label, extensions, focus(bumps)));
    }

    /** A plain Maven repo: build and test the modules that use the bumps, then their web UI. */
    private void buildAndTestMaven(Config config, Target target, Workspace ws, Path runDir,
                                   List<Report.Check> merged) throws IOException {
        Set<String> modules = new TreeSet<>();
        merged.forEach(c -> modules.addAll(c.analysis.modules()));
        System.out.println("building " + target.repo() + " with " + merged.size() + " bump(s), testing "
                + (modules.isEmpty() ? "nothing (no module declares the bumps)" : modules)
                + " (log: " + runDir.resolve("build.log") + ")");
        var build = ws.buildModules(modules, true);
        if (build.exitCode() != 0) {
            String why = build.timedOut() ? "timed out" : String.join("; ", Workspace.errors(runDir.resolve("build.log")));
            merged.forEach(c -> c.buildError = why.isBlank() ? "see build.log" : why);
            return;
        }

        if (skipUi) return;

        // 4. The web UI those modules package, all bumps together first.
        WebUiTester tester = new WebUiTester(config, target, ws, runDir);
        System.out.println("web UI test of " + modules);
        uiBisect(ws, "web UI", merged,
                () -> ws.buildModules(modules, false).exitCode() == 0,
                (label, bumps) -> tester.test(label, modules, focus(bumps)));
    }

    interface UiRun {
        DevUiTester.Outcome test(String label, List<Report.Check> bumps);
    }

    /**
     * Tests every bump together. When that fails: is the base branch itself
     * broken? If not, each bump alone, so the failure lands on the right PR.
     * `rebuild` makes the current tree testable after a reset and merge. The
     * baseline session is given no bumps, so it judges the base branch as is.
     */
    private static void uiBisect(Workspace ws, String uiName, List<Report.Check> merged,
                                 BooleanSupplier rebuild, UiRun ui) throws IOException {
        String base = ws.branch();
        var together = ui.test("combined", merged);
        if (!together.ran() || together.pass()) {
            for (var c : merged) {
                c.ui = together;
                c.uiHow = merged.size() == 1 ? "this bump on " + base : "together with the other " + (merged.size() - 1) + " bump(s)";
            }
            return;
        }

        // It failed: is the base branch itself broken?
        System.out.println(uiName + " test failed; checking " + base + " without any bump");
        ws.resetToMain();
        var baseline = rebuild.getAsBoolean() ? ui.test("baseline", List.of())
                : DevUiTester.Outcome.notRun("the rebuild of " + base + " without the bumps failed");
        if (!baseline.ran() || !baseline.pass()) {
            for (var c : merged) {
                c.ui = together;
                c.uiHow = baseline.ran() ? "but it also fails on " + base + " without any bump"
                        : "and the baseline run on " + base + " did not complete";
                c.baseline = baseline;
            }
            return;
        }
        if (merged.size() == 1) {
            merged.get(0).ui = together;
            merged.get(0).uiHow = "failed with only this bump; passes on " + base;
            return;
        }

        // The base branch is fine, so bisect: each bump alone.
        for (var c : merged) {
            System.out.println(uiName + " test with only #" + c.pr.number());
            ws.resetToMain();
            ws.merge(c.pr);
            var alone = rebuild.getAsBoolean() ? ui.test("pr-" + c.pr.number(), List.of(c))
                    : DevUiTester.Outcome.notRun("the rebuild with only this bump failed");
            c.ui = alone;
            c.uiHow = !alone.ran() ? "did not complete alone"
                    : alone.pass() ? "passes alone; the combined run with the other bumps failed"
                    : "failed with only this bump; passes on " + base;
        }
    }

    private static List<DevUiTester.Focus> focus(List<Report.Check> checks) {
        return checks.stream().map(c -> new DevUiTester.Focus(c.analysis.ga(), BomAnalyzer.npmName(c.analysis.ga()),
                c.analysis.from(), c.analysis.to(), c.analysis.rootAncestors(), c.analysis.jsImporters())).toList();
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        return b.stream().noneMatch(a::contains);
    }

    private static void printStatus(Config config) throws IOException {
        for (Target t : config.targets()) {
            System.out.println("repo:       " + t.repo() + " (checkout " + t.checkout() + ")");
        }
        System.out.println("acts as:    " + config.githubUser);
        System.out.println("email:      " + (config.emailEnabled() ? config.sendTo : "(off)"));
        System.out.println("checked PRs:");
        var all = StateStore.open(config.statePath()).all();
        all.fieldNames().forEachRemaining(n -> {
            var e = all.path(n);
            System.out.printf("  #%s  %-11s %s  %s  %s%n", n, e.path("verdict").asText(),
                    e.path("approved").asBoolean() ? "approved" : "        ",
                    e.path("at").asText(), e.path("comment").asText());
        });
    }

    private static void pruneRuns(Path runsDir, int keep) {
        try (Stream<Path> s = Files.list(runsDir)) {
            List<Path> runs = s.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();
            for (Path old : runs.subList(Math.min(keep, runs.size()), runs.size())) {
                try (Stream<Path> w = Files.walk(old)) {
                    for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException ignored) {
            // housekeeping only
        }
    }

    /** Same idea as weekly-status: a Persistent timer can fire at boot, before the network is up. */
    private static boolean waitForNetwork() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            for (int attempt = 0; attempt < 15; attempt++) {
                try {
                    client.send(HttpRequest.newBuilder(URI.create("https://api.github.com"))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                            HttpResponse.BodyHandlers.discarding());
                    return true;
                } catch (Exception e) {
                    try {
                        TimeUnit.SECONDS.sleep(60);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /** Same single-instance lock idiom as session-worker. */
    static final class SingleInstanceLock implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock lock;

        private SingleInstanceLock(RandomAccessFile file, FileLock lock) {
            this.file = file;
            this.lock = lock;
        }

        static SingleInstanceLock tryAcquire(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
            FileLock lock = raf.getChannel().tryLock();
            if (lock == null) {
                raf.close();
                return null;
            }
            return new SingleInstanceLock(raf, lock);
        }

        @Override
        public void close() throws IOException {
            lock.release();
            file.close();
        }
    }
}
