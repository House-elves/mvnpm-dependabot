import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The static half of the validation: everything that can be decided from POMs
 * alone, before anything is built.
 *
 * Quarkus pins every mvnpm artifact - roots and transitives - in
 * bom/dev-ui/pom.xml. mvnpm publishes npm's semver ranges as Maven ranges
 * ([7.2.0,8)), so a bump of a TRANSITIVE pin is only safe when the new version
 * still sits inside the range every pinned parent asks for. A bump of a ROOT
 * (nothing in the BOM depends on it) has no parent to violate, but its own new
 * dependencies can still disagree with the pins - which is reported, not
 * failed on, because the dependency:go-offline step decides that for real.
 */
final class BomAnalyzer {

    static final String BOM = "bom/dev-ui/pom.xml";
    private static final String CENTRAL = "https://repo1.maven.org/maven2/";
    private static final Pattern PROP = Pattern.compile("\\$\\{([^}]+)}");

    record Dep(String ga, String spec) {}

    record Constraint(String parentGa, String parentVersion, String spec, boolean admitsNew, boolean admitsOld) {}

    record DepChange(String ga, String oldSpec, String newSpec, String pinned, Boolean pinInRange) {}

    record Analysis(
            String ga, String from, String to,
            boolean managed, String pinnedInBom,
            boolean root, boolean major,
            List<Constraint> constraints,      // what each pinned parent requires of this artifact
            List<DepChange> depChanges,        // how this artifact's own dependencies moved
            Set<String> rootAncestors,         // the roots that pull this artifact in (itself, for a root)
            Set<String> modules,               // repo modules whose pom.xml declares one of those roots
            Set<String> jsImporters,           // Dev UI JS files that import one of those roots
            Set<String> extensions) {          // extension artifactIds to put in the test app

        boolean rangeOk() { return constraints.stream().allMatch(Constraint::admitsNew); }

        List<Constraint> violations() {
            return constraints.stream().filter(c -> !c.admitsNew()).toList();
        }
    }

    private final Config config;
    private final Path checkout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final Map<String, List<Dep>> pomDeps = new HashMap<>();

    private Map<String, String> pins;              // GA -> pinned version
    private Map<String, Set<String>> dependents;   // GA -> pinned GAs whose POM depends on it

    BomAnalyzer(Config config, Path checkout) {
        this.config = config;
        this.checkout = checkout;
    }

    Analysis analyze(GitHub.Pr pr) throws IOException {
        loadBom();
        String ga = pr.ga();
        String pinned = pins.get(ga);

        List<Constraint> constraints = new ArrayList<>();
        for (String parent : dependents.getOrDefault(ga, Set.of())) {
            String parentVersion = pins.get(parent);
            for (Dep d : deps(parent, parentVersion)) {
                if (d.ga().equals(ga)) {
                    constraints.add(new Constraint(parent, parentVersion, d.spec(),
                            admits(d.spec(), pr.to()), admits(d.spec(), pr.from())));
                }
            }
        }

        Map<String, String> oldDeps = toMap(deps(ga, pr.from()));
        Map<String, String> newDeps = toMap(deps(ga, pr.to()));
        List<DepChange> changes = new ArrayList<>();
        Set<String> all = new TreeSet<>(oldDeps.keySet());
        all.addAll(newDeps.keySet());
        for (String d : all) {
            String o = oldDeps.get(d), n = newDeps.get(d);
            if (n != null && n.equals(o)) continue;
            String pin = pins.get(d);
            changes.add(new DepChange(d, o, n, pin,
                    (n == null || pin == null || !isRange(n)) ? null : admits(n, pin)));
        }

        Set<String> roots = rootsOf(ga);
        Set<String> modules = modulesDeclaring(roots);
        Set<String> js = jsImporting(roots);
        Set<String> extensions = new TreeSet<>();
        Stream.concat(modules.stream(), js.stream()).forEach(p -> extensionOf(p).ifPresent(extensions::add));

        return new Analysis(ga, pr.from(), pr.to(), pinned != null, pinned,
                dependents.getOrDefault(ga, Set.of()).isEmpty(), isMajor(pr.from(), pr.to()),
                constraints, changes, roots, modules, js, extensions);
    }

    // ---- BOM ----

    private void loadBom() throws IOException {
        if (pins != null) return;
        Document doc = parse(Files.readString(checkout.resolve(BOM)));
        Map<String, String> props = properties(doc);
        pins = new LinkedHashMap<>();
        Element dm = child(doc.getDocumentElement(), "dependencyManagement");
        if (dm != null) {
            for (Element d : children(child(dm, "dependencies"), "dependency")) {
                String g = text(d, "groupId"), a = text(d, "artifactId"), v = resolve(text(d, "version"), props);
                if (g != null && g.startsWith("org.mvnpm") && v != null) pins.put(g + ":" + a, v);
            }
        }
        dependents = new HashMap<>();
        for (var e : pins.entrySet()) {
            for (Dep d : deps(e.getKey(), e.getValue())) {
                if (pins.containsKey(d.ga())) {
                    dependents.computeIfAbsent(d.ga(), k -> new TreeSet<>()).add(e.getKey());
                }
            }
        }
    }

    /** Pinned roots that (transitively) pull this artifact in - itself when nothing depends on it. */
    private Set<String> rootsOf(String ga) {
        Set<String> roots = new TreeSet<>(), seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(ga));
        while (!todo.isEmpty()) {
            String cur = todo.pop();
            if (!seen.add(cur)) continue;
            Set<String> ps = dependents.getOrDefault(cur, Set.of());
            if (ps.isEmpty()) roots.add(cur);
            todo.addAll(ps);
        }
        return roots;
    }

    // ---- where the roots are used ----

    private Set<String> modulesDeclaring(Set<String> roots) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(checkout.resolve("extensions"), 4)) {
            for (Path pom : s.filter(p -> p.getFileName().toString().equals("pom.xml")).toList()) {
                String xml = Files.readString(pom);
                for (String root : roots) {
                    String[] g = root.split(":");
                    if (xml.contains("<groupId>" + g[0] + "</groupId>")
                            && xml.contains("<artifactId>" + g[1] + "</artifactId>")) {
                        out.add(checkout.relativize(pom.getParent()).toString());
                    }
                }
            }
        }
        return out;
    }

    private Set<String> jsImporting(Set<String> roots) throws IOException {
        List<Pattern> imports = new ArrayList<>();
        for (String root : roots) {
            String npm = Pattern.quote(npmName(root));
            imports.add(Pattern.compile("(from\\s+|import\\s*\\(?\\s*)['\"]" + npm + "(/[^'\"]*)?['\"]"));
        }
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(checkout.resolve("extensions"))) {
            for (Path js : s.filter(p -> p.toString().endsWith(".js") && p.toString().contains("/src/main/resources/")
                    && !p.toString().contains("/target/")).toList()) {
                String src = Files.readString(js);
                for (Pattern p : imports) {
                    if (p.matcher(src).find()) {
                        out.add(checkout.relativize(js).toString());
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** org.mvnpm:marked -> marked; org.mvnpm.at.hpcc-js:wasm -> @hpcc-js/wasm */
    static String npmName(String ga) {
        String[] p = ga.split(":");
        return p[0].startsWith("org.mvnpm.at.") ? "@" + p[0].substring("org.mvnpm.at.".length()) + "/" + p[1] : p[1];
    }

    /** extensions/kafka-streams/deployment/... -> quarkus-kafka-streams (from its runtime pom) */
    private java.util.Optional<String> extensionOf(String relPath) {
        String[] parts = relPath.split("/");
        if (parts.length < 2 || !parts[0].equals("extensions")) return java.util.Optional.empty();
        String ext = parts[1];
        // Always in Dev UI anyway, and not standalone extensions.
        if (ext.equals("devui") || ext.equals("web-dependency-locator") || ext.equals("vertx-http")) {
            return java.util.Optional.empty();
        }
        Path runtimePom = checkout.resolve("extensions").resolve(ext).resolve("runtime/pom.xml");
        try {
            if (Files.exists(runtimePom)) {
                String a = text(parse(Files.readString(runtimePom)).getDocumentElement(), "artifactId");
                if (a != null) return java.util.Optional.of(a);
            }
        } catch (IOException e) {
            // fall through to the conventional name
        }
        return java.util.Optional.of("quarkus-" + ext);
    }

    // ---- POMs from Central (immutable, so cached forever) ----

    private List<Dep> deps(String ga, String version) throws IOException {
        String key = ga + ":" + version;
        List<Dep> cached = pomDeps.get(key);
        if (cached != null) return cached;
        String[] p = ga.split(":");
        Path file = config.pomCache().resolve(p[0]).resolve(p[1] + "-" + version + ".pom");
        if (!Files.exists(file)) {
            String url = CENTRAL + p[0].replace('.', '/') + "/" + p[1] + "/" + version + "/" + p[1] + "-" + version + ".pom";
            try {
                var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IOException("HTTP " + resp.statusCode() + " for " + url);
                }
                Files.createDirectories(file.getParent());
                Files.writeString(file, resp.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        Document doc = parse(Files.readString(file));
        Map<String, String> props = properties(doc);
        List<Dep> out = new ArrayList<>();
        for (Element d : children(child(doc.getDocumentElement(), "dependencies"), "dependency")) {
            String scope = text(d, "scope");
            if ("test".equals(scope) || "true".equals(text(d, "optional"))) continue;
            out.add(new Dep(text(d, "groupId") + ":" + text(d, "artifactId"), resolve(text(d, "version"), props)));
        }
        pomDeps.put(key, out);
        return out;
    }

    // ---- versions ----

    /** A soft version ("18.0.0") admits anything; only a real range ([7.2.0,8)) constrains. */
    static boolean admits(String spec, String version) {
        if (spec == null || version == null) return true;
        try {
            VersionRange r = VersionRange.createFromVersionSpec(spec);
            if (!r.hasRestrictions()) return true;
            return r.containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException e) {
            return true;
        }
    }

    static boolean isRange(String spec) {
        try {
            return VersionRange.createFromVersionSpec(spec).hasRestrictions();
        } catch (InvalidVersionSpecificationException e) {
            return false;
        }
    }

    static boolean isMajor(String from, String to) {
        return !first(from).equals(first(to));
    }

    private static String first(String v) {
        int dot = v.indexOf('.');
        return dot < 0 ? v : v.substring(0, dot);
    }

    // ---- tiny DOM helpers ----

    private static Map<String, String> toMap(List<Dep> deps) {
        Map<String, String> m = new TreeMap<>();
        deps.forEach(d -> m.put(d.ga(), d.spec()));
        return m;
    }

    private static Document parse(String xml) throws IOException {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("unparseable POM: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> properties(Document doc) {
        Map<String, String> props = new HashMap<>();
        Element p = child(doc.getDocumentElement(), "properties");
        if (p != null) {
            NodeList nl = p.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i) instanceof Element e) props.put(e.getTagName(), e.getTextContent().strip());
            }
        }
        String v = text(doc.getDocumentElement(), "version");
        if (v != null) props.put("project.version", v);
        return props;
    }

    private static String resolve(String v, Map<String, String> props) {
        if (v == null) return null;
        Matcher m = PROP.matcher(v);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group())));
        m.appendTail(sb);
        return sb.toString();
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && e.getTagName().equals(name)) return e;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && e.getTagName().equals(name)) out.add(e);
        }
        return out;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().strip();
    }
}
