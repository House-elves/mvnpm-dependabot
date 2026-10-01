import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Interactive setup, same shape as session-worker's: prompts, writes
 * ~/.config/mvnpm-dependabot/config (0600), writes systemd user units.
 * Gmail settings default to github-worker's, so an existing sm:// secret
 * reference is reused instead of copying a password.
 */
public final class Installer {

    private Installer() {}

    public static void run() {
        Console console = System.console();
        BufferedReader fallback = (console == null)
                ? new BufferedReader(new InputStreamReader(System.in))
                : null;
        String home = System.getProperty("user.home");
        Map<String, String> gw = readGithubWorkerConfig();

        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("REPO",         prompt(console, fallback, "Repository", "quarkusio/quarkus"));
        cfg.put("GITHUB_USER",  require(prompt(console, fallback,
                "GitHub account that comments and approves (needs a gh login)", "phillip-kruger")));
        cfg.put("CHECKOUT",     prompt(console, fallback,
                "The elf's own Quarkus clone (its dir name is the ~/.mavenrc workspace)",
                home + "/Projects/quarkus-mvnpm-elf"));
        cfg.put("CHROME_PATH",  prompt(console, fallback, "Chrome/Chromium binary", "/usr/bin/chromium-browser"));
        cfg.put("DEV_PORT",     prompt(console, fallback, "HTTP port for the test app", "18080"));
        cfg.put("APPROVE_MAJOR", prompt(console, fallback,
                "Approve passing MAJOR bumps of root libraries too? (true/false)", "false"));
        cfg.put("AGENT_MODEL",  prompt(console, fallback, "Claude model for the Dev UI test (blank = CLI default)", ""));
        cfg.put("GMAIL_ADDRESS", prompt(console, fallback,
                "Summary email From (blank = no email)", gw.getOrDefault("GMAIL_ADDRESS", "")));
        if (!cfg.get("GMAIL_ADDRESS").isEmpty()) {
            cfg.put("GMAIL_AUTH_USER", prompt(console, fallback, "Gmail SMTP auth user",
                    gw.getOrDefault("GMAIL_AUTH_USER", cfg.get("GMAIL_ADDRESS"))));
            cfg.put("GMAIL_APP_PASSWORD", prompt(console, fallback, "Gmail app password (or sm://secret-name)",
                    gw.getOrDefault("GMAIL_APP_PASSWORD", "")));
            cfg.put("SEND_TO", prompt(console, fallback, "Send summary to", gw.getOrDefault("SEND_TO", "")));
        }
        String schedule = prompt(console, fallback, "Schedule (systemd OnCalendar)", "*-*-* 06:00:00");

        try {
            writeConfig(cfg);
            Files.createDirectories(Config.STATE_DIR_DEFAULT);
            writeSystemd(schedule);
            printNextSteps();
        } catch (IOException e) {
            System.err.println("install failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, String> readGithubWorkerConfig() {
        Map<String, String> m = new HashMap<>();
        Path p = Path.of(System.getProperty("user.home"), ".config", "github-worker", "config");
        try {
            for (String line : Files.readAllLines(p)) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.strip().startsWith("#")) m.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException ignored) {
            // no github-worker here; no defaults
        }
        return m;
    }

    private static void writeConfig(Map<String, String> cfg) throws IOException {
        Files.createDirectories(Config.CONFIG_DIR);
        StringBuilder sb = new StringBuilder("# mvnpm-dependabot config - written by --install\n");
        cfg.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Files.writeString(Config.CONFIG_PATH, sb.toString());
        try {
            Files.setPosixFilePermissions(Config.CONFIG_PATH, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        System.out.println("wrote " + Config.CONFIG_PATH);
    }

    private static void writeSystemd(String schedule) throws IOException {
        Path unitDir = Path.of(System.getProperty("user.home"), ".config", "systemd", "user");
        Files.createDirectories(unitDir);

        // A full Quarkus build plus a Dev UI session: give it hours, and run it
        // niced so a morning at the keyboard is not fighting the build.
        String service = """
                [Unit]
                Description=mvnpm-dependabot (validate Dependabot mvnpm PRs)
                After=network-online.target

                [Service]
                Type=oneshot
                Nice=10
                TimeoutStartSec=4h
                Environment=PATH=%h/.local/bin:%h/.jbang/bin:%h/.sdkman/candidates/jbang/current/bin:%h/.sdkman/candidates/java/current/bin:%h/.sdkman/candidates/maven/current/bin:/usr/local/bin:/usr/bin:/bin
                ExecStart=%h/.jbang/bin/mvnpm-dependabot --once
                """;

        String timer = String.format("""
                [Unit]
                Description=mvnpm-dependabot every morning

                [Timer]
                OnCalendar=%s
                Persistent=true

                [Install]
                WantedBy=timers.target
                """, schedule);

        Files.writeString(unitDir.resolve("mvnpm-dependabot.service"), service);
        Files.writeString(unitDir.resolve("mvnpm-dependabot.timer"), timer);
        System.out.println("wrote systemd user units (service + timer)");
    }

    private static void printNextSteps() {
        System.out.println("""

                Next steps:
                  systemctl --user daemon-reload
                  systemctl --user enable --now mvnpm-dependabot.timer

                Try it first without writing to GitHub:
                  mvnpm-dependabot --dry-run
                """);
    }

    private static String prompt(Console console, BufferedReader fallback, String label, String dflt) {
        String suffix = dflt.isEmpty() ? "" : " [" + dflt + "]";
        try {
            String s;
            if (console != null) {
                s = console.readLine("%s%s: ", label, suffix);
            } else {
                System.out.print(label + suffix + ": ");
                s = fallback.readLine();
            }
            if (s == null || s.isBlank()) return dflt;
            return s.strip();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            System.err.println("required value missing; aborting");
            System.exit(1);
        }
        return value;
    }
}
