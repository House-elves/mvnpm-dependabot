import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The elf's own checkout of one watched repo, and the builds run in it.
 *
 * Isolation comes from two things. The checkout is a separate clone (not a
 * worktree of the principal's), fetching straight from upstream, so nothing
 * here touches the principal's remotes, branches or object store. And
 * ~/.mavenrc gives it its own local repository by directory name, so what is
 * built here lands in ~/.m2/worktrees/<checkout dir>/repository and never
 * overwrites a build the principal is testing elsewhere. A Quarkus checkout is
 * recognised by ~/.mavenrc itself; any other repo is pointed at its workspace
 * with QUARKUS_WS.
 */
final class Workspace {

    private static final String AUTHOR_NAME = "mvnpm-dependabot";
    private static final String AUTHOR_EMAIL = "mvnpm-dependabot@house-elves.local";

    private final Config config;
    private final Target target;
    private final Path dir;
    private final Path runDir;
    private String branch;

    Workspace(Config config, Target target, Path runDir) {
        this.config = config;
        this.target = target;
        this.dir = target.checkout();
        this.runDir = runDir;
    }

    Path dir() { return dir; }

    String upstream() { return "https://github.com/" + target.repo() + ".git"; }

    /** The upstream default branch (main for Quarkus and SmallRye, but not for every repo). */
    String branch() throws IOException {
        if (branch == null) {
            for (String line : git(dir.getParent(), 2, "ls-remote", "--symref", upstream(), "HEAD").split("\n")) {
                if (line.startsWith("ref: refs/heads/")) {
                    branch = line.substring("ref: refs/heads/".length(), line.indexOf('\t')).strip();
                }
            }
            if (branch == null) branch = "main";
        }
        return branch;
    }

    /** Clones on first use, then resets to a clean upstream default branch. Returns its sha. */
    String resetToMain() throws IOException {
        if (!Files.isDirectory(dir.resolve(".git"))) {
            System.out.println("cloning " + upstream() + " into " + dir + " (first run)");
            Files.createDirectories(dir.getParent());
            git(dir.getParent(), 60, "clone", "--filter=blob:none", "--no-checkout", upstream(), dir.getFileName().toString());
        }
        git(dir, 15, "fetch", "--no-tags", upstream(), "+refs/heads/" + branch() + ":refs/elf/main");
        git(dir, 5, "checkout", "--force", "--detach", "refs/elf/main");
        git(dir, 5, "clean", "-fd", "--exclude=target");
        return git(dir, 1, "rev-parse", "HEAD").strip();
    }

    /** Merges a PR's head into the current tree. False on conflict (the merge is aborted). */
    boolean merge(GitHub.Pr pr) throws IOException {
        git(dir, 5, "fetch", "--no-tags", upstream(), "+refs/pull/" + pr.number() + "/head:refs/elf/pr-" + pr.number());
        var r = gitRaw(dir, 5, "-c", "user.name=" + AUTHOR_NAME, "-c", "user.email=" + AUTHOR_EMAIL,
                "merge", "--no-edit", "--no-ff", "refs/elf/pr-" + pr.number());
        if (r.exitCode() != 0) {
            gitRaw(dir, 1, "merge", "--abort");
            return false;
        }
        return true;
    }

    /**
     * The principal's `qb`, spelled out (a shell function is not visible to
     * systemd): -Dquickly without the test modules, in parallel. A fresh
     * workspace repo first needs the extension processor, and the Gradle
     * model once.
     */
    Exec.Result build() {
        Path ioQuarkus = repo().resolve("io/quarkus");
        Path log = runDir.resolve("build.log");
        if (!Files.exists(ioQuarkus.resolve("quarkus-extension-processor/999-SNAPSHOT"))) {
            var r = mvn(log, config.buildTimeoutMinutes, "install", "-Dquickly", "-Dno-test-modules",
                    "-Dskip.gradle.build=true", "-Prelocations", "-pl", "core/processor", "-am");
            if (r.exitCode() != 0) return r;
        }
        boolean gradleModel = Files.exists(ioQuarkus.resolve("quarkus-gradle-model/999-SNAPSHOT"));
        return mvn(log, config.buildTimeoutMinutes, "install", "-Dquickly", "-Dno-test-modules",
                "-Dskip.gradle.build=" + gradleModel, "-T", "16C", "-Prelocations");
    }

    /** Re-installs just the BOMs - all that differs between bisect steps. */
    Exec.Result installBoms() {
        return mvn(runDir.resolve("bom.log"), 10, "install", "-Dquickly", "-pl", "bom/dev-ui,bom/application");
    }

    /**
     * CI's "Populate the .m2 repository" step, on just the modules that use the
     * bumped artifacts. This is what failed for hpcc-js/wasm 2.35.1: go-offline
     * resolves each dependency's tree on its own, without the BOM's pins, so a
     * range conflict deep in an mvnpm tree only shows up here.
     */
    Exec.Result goOffline(Collection<String> modules) {
        return mvn(runDir.resolve("go-offline.log"), 20, "-e", "-B", "--fail-at-end",
                "--settings", ".github/mvn-settings.xml", "-Dscalpel.enabled=false",
                "dependency:go-offline", "-Dgo-offline", "-pl", String.join(",", modules));
    }

    /**
     * A plain Maven repo: install the modules that use the bump (and what they
     * need) without tests, then run those modules' own tests. With no modules
     * the whole project is built, untested. Each step's output goes to
     * build.log.
     */
    Exec.Result buildModules(Collection<String> modules, boolean tests) {
        Path log = runDir.resolve("build.log");
        if (modules.isEmpty()) {
            return mvn(log, config.buildTimeoutMinutes, "install", "-DskipTests");
        }
        String pl = String.join(",", modules);
        var r = mvn(log, config.buildTimeoutMinutes, "install", "-DskipTests", "-pl", pl, "-am");
        if (r.exitCode() != 0 || !tests) return r;
        return mvn(log, config.buildTimeoutMinutes, "verify", "-pl", pl);
    }

    Path repo() {
        return Path.of(System.getProperty("user.home"), ".m2", "worktrees", dir.getFileName().toString(), "repository");
    }

    // ---- plumbing ----

    private Exec.Result mvn(Path log, int timeoutMinutes, String... args) {
        List<String> cmd = new ArrayList<>(List.of("mvn", "-B"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        env(pb.environment());
        if (!target.quarkus()) pb.environment().put("QUARKUS_WS", dir.getFileName().toString());
        appendLine(log, "\n$ " + String.join(" ", cmd));
        return Exec.runToFile(pb, log, timeoutMinutes, TimeUnit.MINUTES);
    }

    /** ~/.mavenrc picks the workspace from the checkout's dir; make sure nothing overrides it. */
    static void env(Map<String, String> env) {
        env.remove("QUARKUS_WS");
        env.put("QUARKUS_WS_QUIET", "1");
        env.putIfAbsent("MAVEN_OPTS", "-Xmx4g");
    }

    private static String git(Path cwd, int timeoutMinutes, String... args) throws IOException {
        var r = gitRaw(cwd, timeoutMinutes, args);
        if (r.exitCode() != 0) {
            throw new IOException("git " + args[0] + " failed: " + (r.timedOut() ? "timed out" : r.stderr()));
        }
        return r.stdout();
    }

    private static Exec.Result gitRaw(Path cwd, int timeoutMinutes, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        return Exec.run(pb, timeoutMinutes, TimeUnit.MINUTES);
    }

    private static void appendLine(Path log, String line) {
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, line + "\n", java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the log is a convenience
        }
    }

    /** The last N lines of a log, for comments and emails. */
    static String tail(Path log, int lines) {
        try {
            List<String> all = Files.readAllLines(log);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(no log: " + e.getMessage() + ")";
        }
    }

    /** The [ERROR] lines of a log that say what went wrong, without Maven's help boilerplate. */
    static List<String> errors(Path log) {
        try {
            return Files.readAllLines(log).stream()
                    .filter(l -> l.startsWith("[ERROR] Failed to execute goal") || l.startsWith("[ERROR] Failed to collect"))
                    .distinct().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
