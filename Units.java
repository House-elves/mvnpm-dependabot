import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A check builds Quarkus, which has run out of memory inside the runner: the
 * runner's own service is capped (MemoryMax), and an elf it starts shares that
 * cap. So on Linux a check runs in a systemd user unit of its own, as the
 * runner's mvnpm.run started it, with the same properties (niced, four hours,
 * this PATH, collected when done), and the elf waits for it with its output
 * piped through: the runner's run of the elf lasts as long as the check, and
 * its log is the check's. On macOS (no systemd) the check runs in the elf.
 */
final class Units {

    /** Set inside the unit: that process does the check itself, under the lock its parent holds. */
    static final String ENV = "MVNPM_DEPENDABOT_UNIT";

    private Units() {}

    static boolean inUnit() {
        return System.getenv(ENV) != null;
    }

    /** Linux has systemd's user manager; elsewhere the check runs in the elf. */
    static boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    /** The unit's name, as the runner named them: the round, or one PR. */
    static String name(Running r) {
        return switch (r.kind()) {
            case "check" -> "mvnpm-dependabot-pr-" + r.repo().replace('/', '-') + "-" + r.prs().get(0);
            case "dry-run" -> "mvnpm-dependabot-dry-run";
            default -> "mvnpm-dependabot-round";
        };
    }

    static String description(Running r) {
        return "mvnpm-dependabot " + switch (r.kind()) {
            case "check" -> "check of " + r.repo() + "#" + r.prs().get(0);
            case "dry-run" -> "dry run";
            default -> "round";
        };
    }

    /** systemd-run of {@code self} in the unit, waited for, its output and exit code passed through. */
    static List<String> command(String unit, String description, String path, List<String> self) {
        List<String> cmd = new ArrayList<>(List.of("systemd-run", "--user", "--unit=" + unit, "--description=" + description,
                "--property=Nice=10", "--property=TimeoutStartSec=4h", "--setenv=PATH=" + path, "--setenv=" + ENV + "=" + unit,
                "--collect", "--wait", "--pipe", "--quiet"));
        cmd.addAll(self);
        return cmd;
    }

    /** This process's own command line: java with jbang's classpath, the main class and the same options. */
    static List<String> self() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        if (info.command().isEmpty() || info.arguments().isEmpty()) {
            throw new IllegalStateException("can't read this process's command line to run it in a unit");
        }
        List<String> self = new ArrayList<>();
        self.add(info.command().get());
        self.addAll(List.of(info.arguments().get()));
        return self;
    }

    /** Runs the check in its unit and waits. Stopping the elf stops the unit too. */
    static int run(Running r) throws IOException, InterruptedException {
        String unit = name(r);
        List<String> cmd = command(unit, description(r), System.getenv().getOrDefault("PATH", "/usr/bin:/bin"), self());
        System.out.println("running the check in the systemd user unit " + unit);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        Thread stop = new Thread(() -> {
            if (!p.isAlive()) return;
            try {
                new ProcessBuilder("systemctl", "--user", "stop", unit + ".service").inheritIO().start().waitFor();
            } catch (IOException | InterruptedException ignored) {
                // the unit ends at its own timeout
            }
        });
        Runtime.getRuntime().addShutdownHook(stop);
        try {
            return p.waitFor();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException shuttingDown) {
                // the hook stops the unit
            }
        }
    }
}
