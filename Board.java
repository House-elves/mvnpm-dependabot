import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The tab's board, board/board.json: every open Dependabot mvnpm bump in
 * REPOS, read from GitHub, each with the verdict the elf recorded for it
 * (state.json) and where it is in its flow ({@link Flows}).
 *
 * The `board` command reads GitHub (one search per repo, one at a time) and
 * writes it. Everything else here only joins what the last read found with
 * the elf's own files again, without asking GitHub: when a check starts or
 * ends, and after one of the tab's buttons.
 */
final class Board {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Board() {}

    /**
     * What the elf recorded the last time it checked a PR.
     *
     * @param verdict SAFE, NOT_SAFE or NEEDS_HUMAN
     * @param sha     the head commit it checked; another head means it hasn't seen the latest push
     * @param comment its comment on the PR
     */
    record Check(String sha, String verdict, boolean approved, String comment, Instant at) {
    }

    /** You asked Dependabot to rebase, at this head. Done once the head moves. */
    record Rebase(String sha, Instant at) {
    }

    /** One bump: the PR, what it bumps (null if the title isn't one bump), the elf's verdict and the flow. */
    record Row(Bumps.Pr pr, Bumps.Bump bump, Check check, boolean current, Flows.Flow flow) {
    }

    /**
     * @param fetchedAt when GitHub was read
     * @param checking  the check running when this was written, or null
     * @param errors    repos GitHub couldn't be read for (their rows are from the read before)
     */
    record View(String login, Instant fetchedAt, List<String> repos, List<Row> rows, Running checking, List<String> errors) {
    }

    // ---- the board command ----

    /** Reads GitHub and writes the board. 1 when a repo couldn't be read (the others are still written). */
    static int refresh(Config config) throws IOException {
        View before = read(config.boardPath());
        List<Bumps.Pr> prs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> heads = new HashMap<>();
        for (String repo : config.repos) {
            try {
                for (JsonNode n : new GitHub(config, repo).openDependabotPrs()) {
                    if (!Bumps.isMvnpmBump(n)) continue;
                    Bumps.Pr pr = Bumps.pr(repo, n);
                    prs.add(pr);
                    heads.put(pr.key(), pr.headSha());
                }
            } catch (IOException e) {
                errors.add(repo + ": " + e.getMessage());
                // What the last read found is better than nothing.
                if (before != null) before.rows().stream().map(Row::pr).filter(p -> p.repo().equals(repo)).forEach(prs::add);
            }
        }
        // A rebase you asked for is done once the head moved (repos that couldn't be read keep theirs).
        Map<String, Rebase> asked = new HashMap<>(rebasing(config));
        asked.entrySet().removeIf(e -> {
            String repo = e.getKey().substring(0, e.getKey().indexOf('#'));
            return errors.stream().noneMatch(x -> x.startsWith(repo + ": ")) && !e.getValue().sha().equals(heads.get(e.getKey()));
        });
        writeJson(config.rebasingPath(), asked);
        View v = build(config.githubUser, Instant.now(), config.repos, prs, state(config), Running.read(config.runningPath()),
                asked, errors, Instant.now());
        writeJson(config.boardPath(), v);
        System.out.println("board: " + v.rows().size() + " open mvnpm bump(s) in " + String.join(", ", config.repos)
                + (errors.isEmpty() ? "" : "; couldn't read " + String.join("; ", errors)));
        return errors.isEmpty() ? 0 : 1;
    }

    /** The board joined again with the elf's own files (a check started or ended), without asking GitHub. */
    static void rejoin(Config config) {
        update(config, prs -> prs);
    }

    /** The board with its PRs changed by {@code change} (after one of the tab's buttons), joined again. */
    static void update(Config config, UnaryOperator<List<Bumps.Pr>> change) {
        try {
            View before = read(config.boardPath());
            if (before == null) return;
            List<Bumps.Pr> prs = change.apply(before.rows().stream().map(Row::pr).toList());
            writeJson(config.boardPath(), build(before.login(), before.fetchedAt(), before.repos(), prs, state(config),
                    Running.read(config.runningPath()), rebasing(config), before.errors(), Instant.now()));
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not update the board: " + e.getMessage());
        }
    }

    static View build(String login, Instant fetchedAt, List<String> repos, List<Bumps.Pr> prs, JsonNode state, Running running,
                      Map<String, Rebase> asked, List<String> errors, Instant now) {
        List<Row> rows = new ArrayList<>();
        for (Bumps.Pr pr : prs) {
            Check check = check(state, pr.repo(), pr.number());
            boolean current = check != null && check.sha() != null && check.sha().equals(pr.headSha());
            boolean checking = running != null && running.covers(pr, current);
            Flows.Ctx ctx = new Flows.Ctx(check, current, checking, now, Flows.rebaseAsked(asked.get(pr.key()), pr, now));
            rows.add(new Row(pr, Bumps.bump(pr.title()), check, current, Flows.flow(pr, ctx)));
        }
        rows.sort(Comparator.comparing((Row r) -> r.pr().updatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        return new View(login, fetchedAt, List.copyOf(repos), List.copyOf(rows), running, List.copyOf(errors));
    }

    /**
     * The recorded check for one PR. Before the elf watched more than one
     * repo, Quarkus PRs were keyed by the bare number.
     */
    static Check check(JsonNode state, String repo, int number) {
        JsonNode n = state.path(repo + "#" + number);
        if (n.isMissingNode() && repo.equalsIgnoreCase(Target.QUARKUS)) n = state.path(String.valueOf(number));
        if (!n.isObject()) return null;
        Instant at = null;
        try {
            if (n.path("at").isTextual()) at = Instant.parse(n.path("at").asText());
        } catch (DateTimeParseException ignored) {
            // leave it unknown
        }
        return new Check(n.path("sha").asText(null), n.path("verdict").asText(null), n.path("approved").asBoolean(),
                n.path("comment").asText(null), at);
    }

    // ---- files ----

    static JsonNode state(Config config) throws IOException {
        return Files.isRegularFile(config.statePath()) ? MAPPER.readTree(config.statePath().toFile()) : MAPPER.createObjectNode();
    }

    static Map<String, Rebase> rebasing(Config config) {
        try {
            if (!Files.isRegularFile(config.rebasingPath())) return Map.of();
            return MAPPER.readValue(config.rebasingPath().toFile(), new TypeReference<Map<String, Rebase>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    static View read(Path file) throws IOException {
        return Files.isRegularFile(file) ? MAPPER.readValue(file.toFile(), View.class) : null;
    }

    /** Written next to the file, then moved into place: the tab never reads half of one. */
    static void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
