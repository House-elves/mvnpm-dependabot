import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * The bumps waiting to be checked, in state/queue.json: one check runs at a time (each builds Quarkus), so a check
 * you ask for while another runs waits here, and the running check takes them one by one when it's done. The tab
 * reads it to show them, and takes one off.
 */
final class CheckQueue {

    record Item(String repo, int pr, Instant at) {}

    static final int MAX = 20;

    private CheckQueue() {}

    static Path file(Config config) {
        return config.runningPath().resolveSibling("queue.json");
    }

    static List<Item> read(Config config) {
        Path f = file(config);
        if (!Files.isRegularFile(f)) return List.of();
        try {
            return Board.MAPPER.readValue(f.toFile(), new TypeReference<List<Item>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Changes the queue under a lock of its own (the tab and the running check both change it). */
    static List<Item> change(Config config, Function<List<Item>, List<Item>> how) throws IOException {
        Path f = file(config);
        Files.createDirectories(f.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(f.resolveSibling(".queue.lock").toFile(), "rw");
             FileLock ignored = raf.getChannel().lock()) {
            List<Item> next = how.apply(new ArrayList<>(read(config)));
            if (next.isEmpty()) Files.deleteIfExists(f);
            else Board.writeJson(f, next);
            return next;
        }
    }

    static List<Item> add(Config config, String repo, int pr) throws IOException {
        return change(config, q -> {
            if (q.stream().noneMatch(i -> i.repo().equalsIgnoreCase(repo) && i.pr() == pr)) {
                if (q.size() >= MAX) throw new IllegalStateException(MAX + " checks are waiting already");
                q.add(new Item(repo, pr, Instant.now()));
            }
            return q;
        });
    }

    static List<Item> remove(Config config, String repo, int pr) throws IOException {
        return change(config, q -> {
            q.removeIf(i -> i.repo().equalsIgnoreCase(repo) && i.pr() == pr);
            return q;
        });
    }

    /** Takes the next one off, or null. */
    static Item next(Config config) throws IOException {
        Item[] taken = new Item[1];
        change(config, q -> {
            if (!q.isEmpty()) taken[0] = q.remove(0);
            return q;
        });
        return taken[0];
    }
}
