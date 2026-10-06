import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The live check for a repo that is not Quarkus: the web UI its modules
 * package under META-INF/resources (where the mvnpm jars are unpacked to),
 * served the way a servlet container would serve it from the jar, and driven
 * by the same headless Claude + chrome-devtools-mcp session as the Dev UI.
 *
 * A UI usually needs something to show. A Profile gives a repo the fixtures
 * its UI fetches (Swagger UI's OpenAPI document, say) and tells the session
 * what to exercise. A repo without one still gets a generic walk of its pages.
 */
final class WebUiTester {

    record Fixture(String contentType, String body) {}

    record Profile(Map<String, Fixture> fixtures, String notes) {
        static final Profile NONE = new Profile(Map.of(), "");
    }

    private final Config config;
    private final Target target;
    private final Workspace workspace;
    private final Path runDir;

    WebUiTester(Config config, Target target, Workspace workspace, Path runDir) {
        this.config = config;
        this.target = target;
        this.workspace = workspace;
        this.runDir = runDir;
    }

    /** Serves the built UI of these modules and runs one session against it. */
    DevUiTester.Outcome test(String label, Collection<String> modules, List<DevUiTester.Focus> focus) {
        List<Path> roots;
        try {
            roots = resourceRoots(modules);
        } catch (IOException e) {
            return DevUiTester.Outcome.notRun("could not look for the built web UI: " + e.getMessage());
        }
        List<String> pages = new ArrayList<>();
        for (Path root : roots) pages.addAll(pages(root));
        if (pages.isEmpty()) {
            return DevUiTester.Outcome.notRun("no web UI to test (no index.html under target/classes/META-INF/resources in "
                    + (modules.isEmpty() ? "any module" : String.join(", ", modules)) + ")");
        }

        Profile profile = profile(target.repo());
        String base = "http://localhost:" + config.devPort;
        try (StaticServer server = StaticServer.start(config.devPort, roots, profile.fixtures())) {
            Path screens = runDir.resolve("screens-" + label);
            var o = DevUiTester.session(config, runDir, workspace.dir(), label, screens,
                    prompt(base, pages, modules, focus, profile, screens));
            return new DevUiTester.Outcome(o.ran(), o.pass(), o.summary(), o.pages(), o.consoleErrors(),
                    o.suspects(), server.misses());
        } catch (IOException e) {
            return DevUiTester.Outcome.notRun("could not serve the web UI on port " + config.devPort + ": " + e.getMessage());
        }
    }

    /** <module>/target/classes/META-INF/resources of each module (every module's, when none are known). */
    private List<Path> resourceRoots(Collection<String> modules) throws IOException {
        List<Path> out = new ArrayList<>();
        if (modules.isEmpty()) {
            try (Stream<Path> s = Files.walk(workspace.dir(), 8)) {
                s.filter(p -> p.endsWith(Path.of("target", "classes", "META-INF", "resources")) && Files.isDirectory(p))
                        .forEach(out::add);
            }
            return out;
        }
        for (String m : modules) {
            Path r = workspace.dir().resolve(m).resolve("target/classes/META-INF/resources");
            if (Files.isDirectory(r)) out.add(r);
        }
        return out;
    }

    /**
     * Each directory with an index.html is a page, at the path it would have
     * in the jar. A template still holding ${placeholders} is not a page.
     */
    private static List<String> pages(Path root) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path index : s.filter(p -> p.getFileName().toString().equals("index.html")).toList()) {
                if (Files.readString(index).contains("${")) continue;
                String rel = root.relativize(index.getParent()).toString();
                out.add(rel.isEmpty() ? "/" : "/" + rel + "/");
            }
        } catch (IOException ignored) {
            // an unreadable tree has no pages
        }
        return out;
    }

    private String prompt(String base, List<String> pages, Collection<String> modules,
                          List<DevUiTester.Focus> focus, Profile profile, Path screens) {
        StringBuilder bumps = new StringBuilder();
        if (focus.isEmpty()) bumps.append("(none: this is the base branch as it is, for comparison)\n");
        for (DevUiTester.Focus f : focus) {
            bumps.append("- ").append(f.ga()).append(" (npm `").append(f.npm()).append("`) ")
                 .append(f.from()).append(" -> ").append(f.to()).append('\n');
        }
        StringBuilder urls = new StringBuilder();
        pages.forEach(p -> urls.append("- ").append(base).append(p).append('\n'));
        return """
                You are a QA tester for the web UI that %s packages in its jar. Dependabot bumped
                these mvnpm (npm-on-Maven) libraries, the UI was rebuilt with them, and it is now
                served at:

                %s
                Bumps:
                %s
                The source it was built from is at %s (modules: %s); you may Read/Grep it to see
                how the UI loads the libraries.
                %s
                Do this:
                1. Open each page above and wait for it to render. Check the console and network:
                   a 404 for a script, stylesheet or source map the page asks for, or an uncaught
                   exception while it starts, is the classic symptom of a bad mvnpm bump (a file
                   renamed or dropped in the new version).
                2. Exercise the UI: expand sections, open dialogs, switch tabs, use any search or
                   filter box. Confirm the library actually renders its output, not an empty or
                   broken element.
                3. On every page, read the console (errors and warnings) and look for failed network
                   requests. Take a screenshot of each page, saving it under %s (one file per page,
                   named after the page).

                Judge fairly: a warning that has nothing to do with the bumped libraries is not a
                failure. A blank page, a missing component, an uncaught exception, or a failed
                script/style load is.

                End your final message with exactly one fenced json block, and nothing after it:
                ```json
                {"verdict": "pass" | "fail",
                 "summary": "one or two sentences",
                 "pages": [{"page": "name or path", "status": "ok" | "broken", "notes": "short"}],
                 "consoleErrors": ["error text (page)"],
                 "suspects": ["groupId:artifactId of a bump you believe broke something"]}
                ```
                """.formatted(target.repo(), urls, bumps, workspace.dir(),
                String.join(", ", modules.isEmpty() ? List.of("all") : modules),
                profile.notes().isBlank() ? "" : "\n" + profile.notes().strip() + "\n", screens);
    }

    // ---- profiles ----

    static Profile profile(String repo) {
        return switch (repo) {
            case "smallrye/smallrye-open-api" -> SMALLRYE_OPEN_API;
            default -> Profile.NONE;
        };
    }

    /**
     * SmallRye OpenAPI's UI is Swagger UI (swagger-ui-dist plus a
     * swagger-ui-themes stylesheet) whose index.html loads the document from
     * /openapi. The sample covers what Swagger UI renders differently: tags,
     * parameters, a request body, $ref'd schemas, a security scheme, and a
     * 3.1 path item reference (5.33.1 fixed a bug in exactly that).
     */
    private static final Profile SMALLRYE_OPEN_API = new Profile(Map.of(
            "/openapi", new Fixture("application/yaml", """
                    openapi: 3.1.0
                    info:
                      title: mvnpm elf sample API
                      version: 1.0.0
                      description: |
                        A small document for checking the Swagger UI. **Markdown** in descriptions should render.
                    servers:
                      - url: /api
                    tags:
                      - name: pets
                        description: Pets in the store
                      - name: owners
                        description: The people who own them
                    paths:
                      /pets:
                        get:
                          tags: [pets]
                          summary: List pets
                          operationId: listPets
                          parameters:
                            - name: limit
                              in: query
                              description: How many to return
                              schema: {type: integer, minimum: 1, maximum: 100}
                          responses:
                            '200':
                              description: The pets
                              content:
                                application/json:
                                  schema:
                                    type: array
                                    items: {$ref: '#/components/schemas/Pet'}
                        post:
                          tags: [pets]
                          summary: Add a pet
                          operationId: addPet
                          security: [{apiKey: []}]
                          requestBody:
                            required: true
                            content:
                              application/json:
                                schema: {$ref: '#/components/schemas/Pet'}
                          responses:
                            '201': {description: Created}
                      /pets/{id}:
                        get:
                          tags: [pets]
                          summary: Find a pet
                          operationId: getPet
                          parameters:
                            - {name: id, in: path, required: true, schema: {type: integer, format: int64}}
                          responses:
                            '200':
                              description: The pet
                              content:
                                application/json:
                                  schema: {$ref: '#/components/schemas/Pet'}
                            '404': {description: Not found}
                      /owners:
                        $ref: '#/components/pathItems/Owners'
                    components:
                      pathItems:
                        Owners:
                          get:
                            tags: [owners]
                            summary: List owners
                            operationId: listOwners
                            responses:
                              '200':
                                description: The owners
                                content:
                                  application/json:
                                    schema:
                                      type: array
                                      items: {$ref: '#/components/schemas/Owner'}
                      securitySchemes:
                        apiKey: {type: apiKey, in: header, name: X-API-Key}
                      schemas:
                        Pet:
                          type: object
                          required: [name]
                          properties:
                            id: {type: integer, format: int64, readOnly: true}
                            name: {type: string, example: Rex}
                            status: {type: string, enum: [available, sold]}
                            owner: {$ref: '#/components/schemas/Owner'}
                        Owner:
                          type: object
                          properties:
                            id: {type: integer, format: int64}
                            name: {type: string, example: Sam}
                    """),
            "/api/pets", new Fixture("application/json",
                    "[{\"id\":1,\"name\":\"Rex\",\"status\":\"available\"},{\"id\":2,\"name\":\"Tom\",\"status\":\"sold\"}]"),
            "/api/owners", new Fixture("application/json", "[{\"id\":1,\"name\":\"Sam\"}]")),
            """
            This is SmallRye OpenAPI's bundled Swagger UI (swagger-ui-dist with a swagger-ui-themes
            stylesheet), reading a sample OpenAPI document from /openapi. Check specifically:
            - the top bar shows the SmallRye OpenAPI logo, and the theme stylesheet is applied;
            - the title "mvnpm elf sample API", version and the markdown description render;
            - both tags (pets, owners) expand, and every operation expands with its parameters,
              request body and responses - including GET /owners, which is a path item $ref;
            - the Schemas section lists Pet and Owner, and both expand;
            - the Authorize button opens the apiKey dialog, and it closes again;
            - on GET /pets, "Try it out" then "Execute" shows a 200 response with a JSON body and a
              curl command. Only execute GET /pets and GET /owners; the other operations have no
              backend.
            """);

    // ---- the server ----

    /** Serves META-INF/resources dirs plus the profile's fixtures, and records every miss. */
    static final class StaticServer implements AutoCloseable {
        private static final Map<String, String> TYPES = Map.of(
                "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
                "css", "text/css; charset=utf-8", "map", "application/json", "json", "application/json",
                "png", "image/png", "ico", "image/x-icon", "svg", "image/svg+xml",
                "woff2", "font/woff2", "woff", "font/woff");

        private final HttpServer server;
        private final List<String> misses = Collections.synchronizedList(new ArrayList<>());

        private StaticServer(HttpServer server) { this.server = server; }

        static StaticServer start(int port, List<Path> roots, Map<String, Fixture> fixtures) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            StaticServer s = new StaticServer(http);
            http.createContext("/", ex -> s.handle(ex, roots, fixtures));
            http.start();
            return s;
        }

        private void handle(HttpExchange ex, List<Path> roots, Map<String, Fixture> fixtures) throws IOException {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                Fixture f = fixtures.get(path);
                if (f != null) {
                    send(ex, 200, f.contentType(), f.body().getBytes(StandardCharsets.UTF_8));
                    return;
                }
                for (Path root : roots) {
                    Path file = root.resolve(path.substring(1)).normalize();
                    if (!file.startsWith(root)) continue;
                    if (Files.isDirectory(file)) file = file.resolve("index.html");
                    if (Files.isRegularFile(file)) {
                        String name = file.getFileName().toString();
                        String ext = name.substring(name.lastIndexOf('.') + 1);
                        send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
                        return;
                    }
                }
                misses.add("404 " + ex.getRequestMethod() + " " + path);
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        }

        private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
        }

        /** Requests the UI made that nothing could answer, deduplicated, in the order first seen. */
        List<String> misses() {
            synchronized (misses) {
                return misses.stream().distinct().limit(20).toList();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
