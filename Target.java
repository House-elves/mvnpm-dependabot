import java.nio.file.Path;

/**
 * One repository the elf watches, and the elf's own clone of it.
 *
 * Quarkus gets the full treatment (BOM ranges, a 999-SNAPSHOT, CI's
 * go-offline, the Dev UI). Every other repo is a plain Maven project: the
 * modules that declare the bumped artifact are built and tested, and the web
 * UI they package is served and driven in a headless browser (WebUiTester).
 */
record Target(String repo, Path checkout) {

    static final String QUARKUS = "quarkusio/quarkus";

    boolean quarkus() { return repo.equals(QUARKUS); }

    /** smallrye/smallrye-open-api -> smallrye-open-api */
    String name()     { return repo.substring(repo.indexOf('/') + 1); }

    /** What the browser test is called in comments and emails. */
    String uiName()   { return quarkus() ? "Dev UI" : "web UI"; }
}
