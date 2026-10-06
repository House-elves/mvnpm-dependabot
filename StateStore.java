import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Which PR head commits have been checked. A PR is checked once per commit:
 * when Dependabot rebases or recreates the branch the sha moves and the PR is
 * checked again (and its comment edited in place).
 */
final class StateStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final ObjectNode root;

    private StateStore(Path path, ObjectNode root) {
        this.path = path;
        this.root = root;
    }

    static StateStore open(Path path) throws IOException {
        if (Files.exists(path)) return new StateStore(path, (ObjectNode) MAPPER.readTree(path.toFile()));
        return new StateStore(path, MAPPER.createObjectNode());
    }

    /**
     * Entries are keyed owner/name#number. Before the elf watched more than
     * one repo they were keyed by the bare number, and those are Quarkus's.
     */
    private static String key(String repo, int pr) { return repo + "#" + pr; }

    boolean checked(String repo, int pr, String sha) {
        if (sha.equals(root.path(key(repo, pr)).path("sha").asText())) return true;
        return repo.equals(Target.QUARKUS) && sha.equals(root.path(String.valueOf(pr)).path("sha").asText());
    }

    void record(String repo, int pr, String sha, String verdict, boolean approved, String commentUrl) throws IOException {
        if (repo.equals(Target.QUARKUS)) root.remove(String.valueOf(pr));
        ObjectNode n = root.putObject(key(repo, pr));
        n.put("sha", sha);
        n.put("verdict", verdict);
        n.put("approved", approved);
        n.put("comment", commentUrl);
        n.put("at", Instant.now().toString());
        save();
    }

    ObjectNode all() { return root; }

    private void save() throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
