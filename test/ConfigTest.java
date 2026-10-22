import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @Test
    void theVerdictsMoveOnceAndTheOldFileStays(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("old/state.json"), now = dir.resolve("config/state/state.json");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "{\"1\": {}}");
        Config.moveState(old, now);
        assertEquals("{\"1\": {}}", Files.readString(now));
        assertTrue(Files.exists(old));

        // Once there, the new file is the elf's: the old one never comes back over it.
        Files.writeString(now, "{\"2\": {}}");
        Config.moveState(old, now);
        assertEquals("{\"2\": {}}", Files.readString(now));
    }

    @Test
    void aRepoIsFoundByItsOwnerAndNameOrItsNameAlone() {
        Config c = new Config();
        c.repos = List.of("quarkusio/quarkus", "smallrye/smallrye-open-api");
        assertEquals("smallrye/smallrye-open-api", c.repo("smallrye/smallrye-open-api"));
        assertEquals("smallrye/smallrye-open-api", c.repo("smallrye-open-api"));
        assertEquals("quarkusio/quarkus", c.repo("Quarkus"));
        assertNull(c.repo("vertx"));
        assertNull(c.repo("other/quarkus-x"));
    }
}
