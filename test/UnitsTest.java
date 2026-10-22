import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** A check in a systemd user unit of its own, with the properties the runner's mvnpm.run gave it. */
class UnitsTest {

    @Test
    void namesTheUnitsAsTheRunnerDid() {
        assertEquals("mvnpm-dependabot-pr-smallrye-smallrye-open-api-2696",
                Units.name(new Running("check", "smallrye/smallrye-open-api", List.of(2696), Prs.NOW, null)));
        assertEquals("mvnpm-dependabot-round", Units.name(new Running("round", null, List.of(), Prs.NOW, null)));
        assertEquals("mvnpm-dependabot check of quarkusio/quarkus#1",
                Units.description(new Running("check", "quarkusio/quarkus", List.of(1), Prs.NOW, null)));
    }

    @Test
    void waitsForTheUnitWithTheRunnersLimits() {
        List<String> cmd = Units.command("mvnpm-dependabot-round", "mvnpm-dependabot round", "/usr/bin",
                List.of("java", "-cp", "x.jar", "MvnpmDependabot", "--once"));
        assertEquals(List.of("systemd-run", "--user", "--unit=mvnpm-dependabot-round", "--description=mvnpm-dependabot round",
                "--property=Nice=10", "--property=TimeoutStartSec=4h", "--setenv=PATH=/usr/bin",
                "--setenv=MVNPM_DEPENDABOT_UNIT=mvnpm-dependabot-round", "--collect", "--wait", "--pipe", "--quiet",
                "java", "-cp", "x.jar", "MvnpmDependabot", "--once"), cmd);
        assertTrue(Units.self().size() > 1);
    }
}
