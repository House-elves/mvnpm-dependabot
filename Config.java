import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KEY=VALUE config at ~/.config/mvnpm-dependabot/config, same shape as the
 * other elves. No secrets live here: the GitHub token is read from gh's
 * keyring for GITHUB_USER at run time.
 */
public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "mvnpm-dependabot");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");
    static final Path STATE_DIR_DEFAULT = Path.of(
            System.getenv().getOrDefault("XDG_STATE_HOME",
                    System.getProperty("user.home") + "/.local/state")).resolve("mvnpm-dependabot");

    List<String> repos;     // owner/name of each repo whose PRs are checked
    String githubUser;      // the account that comments, approves, and is mentioned
    Path checkout;          // the elf's own Quarkus clone - its dir name is the ~/.mavenrc workspace
    Path checkoutsDir;      // where the clones of the other repos go (<name>-mvnpm-elf)
    String chromePath;      // chromium/chrome binary for chrome-devtools-mcp
    String agentModel;      // empty = claude CLI default
    int buildTimeoutMinutes;
    int uiTimeoutMinutes;
    int devPort;
    boolean approveMajor;   // approve a passing major bump of a root library, or leave it to the principal
    List<String> baseExtensions;
    Path stateDir;

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Run: mvnpm-dependabot --install");
            System.exit(1);
        }
        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }

        String home = System.getProperty("user.home");
        Config c = new Config();
        c.repos               = List.of(raw.getOrDefault("REPOS", raw.getOrDefault("REPO", Target.QUARKUS))
                .split("\\s*,\\s*"));
        c.githubUser          = raw.getOrDefault("GITHUB_USER", "");
        c.checkout            = Path.of(raw.getOrDefault("CHECKOUT", home + "/Projects/quarkus-mvnpm-elf"));
        c.checkoutsDir        = Path.of(raw.getOrDefault("CHECKOUTS_DIR", home + "/Projects"));
        c.chromePath          = raw.getOrDefault("CHROME_PATH", "/usr/bin/chromium-browser");
        c.agentModel          = raw.getOrDefault("AGENT_MODEL", "");
        c.buildTimeoutMinutes = Integer.parseInt(raw.getOrDefault("BUILD_TIMEOUT_MINUTES", "60"));
        c.uiTimeoutMinutes    = Integer.parseInt(raw.getOrDefault("UI_TIMEOUT_MINUTES", "25"));
        c.devPort             = Integer.parseInt(raw.getOrDefault("DEV_PORT", "18080"));
        c.approveMajor        = Boolean.parseBoolean(raw.getOrDefault("APPROVE_MAJOR", "false"));
        c.baseExtensions      = List.of(raw.getOrDefault("BASE_EXTENSIONS",
                "rest-jackson,smallrye-openapi,smallrye-health,scheduler,hibernate-validator").split("\\s*,\\s*"));
        c.stateDir            = Path.of(raw.getOrDefault("STATE_DIR", STATE_DIR_DEFAULT.toString()));
        return c;
    }

    void validate() {
        if (githubUser.isEmpty()) {
            System.err.println("Error: GITHUB_USER not configured in " + CONFIG_PATH);
            System.exit(1);
        }
    }

    /** Each configured repo with its clone: CHECKOUT for Quarkus, CHECKOUTS_DIR/<name>-mvnpm-elf otherwise. */
    List<Target> targets() {
        return repos.stream().map(r -> {
            if (r.equals(Target.QUARKUS)) return new Target(r, checkout);
            return new Target(r, checkoutsDir.resolve(r.substring(r.indexOf('/') + 1) + "-mvnpm-elf"));
        }).toList();
    }

    /** The ~/.mavenrc workspace name of the Quarkus checkout: its directory name. */
    String workspace()  { return checkout.getFileName().toString(); }
    Path lockPath()     { return stateDir.resolve("lock"); }
    Path statePath()    { return stateDir.resolve("state.json"); }
    Path pomCache()     { return stateDir.resolve("pom-cache"); }
    Path runsDir()      { return stateDir.resolve("runs"); }
}
