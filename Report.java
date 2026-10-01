import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Everything learned about one PR in one run, the verdict drawn from it, and
 * the PR comment that says so.
 */
final class Report {

    enum Verdict { SAFE, NOT_SAFE, NEEDS_HUMAN }

    static final class Check {
        final GitHub.Pr pr;
        BomAnalyzer.Analysis analysis;
        String analysisError;
        boolean conflicted;
        String buildError;
        boolean goOfflineRan;
        List<String> goOfflineErrors = List.of();
        DevUiTester.Outcome ui;
        String uiHow = "";          // how the Dev UI result was attributed to this PR
        Verdict verdict;
        final List<String> reasons = new ArrayList<>();
        boolean approve;

        Check(GitHub.Pr pr) { this.pr = pr; }

        /** Failed before anything was built - it is left out of the merge. */
        boolean staticFail() {
            return !pr.parsed() || analysisError != null || (analysis != null && !analysis.rangeOk());
        }
    }

    private Report() {}

    static void decide(Check c, Config config) {
        var a = c.analysis;
        if (!c.pr.parsed()) {
            c.verdict = Verdict.NEEDS_HUMAN;
            c.reasons.add("could not read a single `org.mvnpm` bump from the title (grouped update?)");
        } else if (c.analysisError != null) {
            c.verdict = Verdict.NEEDS_HUMAN;
            c.reasons.add("could not analyse the BOM: " + c.analysisError);
        } else if (!a.rangeOk()) {
            c.verdict = Verdict.NOT_SAFE;
            for (var v : a.violations()) {
                c.reasons.add("`" + a.to() + "` is outside `" + v.spec() + "`, the range `" + v.parentGa()
                        + ":" + v.parentVersion() + "` (pinned in the BOM) requires");
            }
        } else if (c.conflicted) {
            c.verdict = Verdict.NEEDS_HUMAN;
            c.reasons.add("the branch does not merge cleanly into main - `@dependabot rebase` it");
        } else if (c.buildError != null) {
            c.verdict = Verdict.NEEDS_HUMAN;
            c.reasons.add("the Quarkus build with this bump failed: " + c.buildError);
        } else if (!c.goOfflineErrors.isEmpty()) {
            c.verdict = Verdict.NOT_SAFE;
            c.reasons.add("CI's `dependency:go-offline` step fails with this bump (it will break the Initial JDK build)");
        } else if (c.ui == null || !c.ui.ran()) {
            c.verdict = Verdict.NEEDS_HUMAN;
            c.reasons.add("the Dev UI test did not run: " + (c.ui == null ? "skipped" : c.ui.summary()));
        } else if (!c.ui.pass()) {
            boolean blamed = c.uiHow.startsWith("failed with only this bump");
            c.verdict = blamed ? Verdict.NOT_SAFE : Verdict.NEEDS_HUMAN;
            c.reasons.add("the Dev UI test failed (" + c.uiHow + "): " + c.ui.summary());
        } else if (c.uiHow.startsWith("passes alone")) {
            // Each bump is fine on its own but not together: merging them all would break.
            c.verdict = Verdict.NEEDS_HUMAN;
            c.reasons.add("the Dev UI " + c.uiHow + " - merge the bumps one at a time");
        } else {
            c.verdict = Verdict.SAFE;
        }

        c.approve = c.verdict == Verdict.SAFE && (!a.major() || !a.root() || config.approveMajor);
        if (c.verdict == Verdict.SAFE && !c.approve) {
            c.reasons.add("all checks pass, but this is a major version bump of a library the Dev UI uses directly, "
                    + "so the approval is left to you");
        }
    }

    static String comment(Check c, Config config, String mainSha) {
        var a = c.analysis;
        StringBuilder sb = new StringBuilder(GitHub.MARKER).append('\n');
        String headline = switch (c.verdict) {
            case SAFE -> c.approve ? "✅ Safe to update (approved)" : "✅ Passes all checks - not approved (major bump)";
            case NOT_SAFE -> "❌ Not safe to update";
            case NEEDS_HUMAN -> "⚠️ Needs a human look";
        };
        sb.append("### mvnpm check: ").append(headline).append("\n\n");
        sb.append("@").append(config.githubUser).append(" - ready for your final check.\n\n");
        for (String r : c.reasons) sb.append("- ").append(r).append('\n');
        if (!c.reasons.isEmpty()) sb.append('\n');

        sb.append("| Check | Result |\n|---|---|\n");
        if (a != null) {
            String kind = a.root()
                    ? "Root - nothing else in the BOM depends on it"
                    : "Transitive - pulled in by " + a.constraints().stream()
                            .map(k -> "`" + k.parentGa() + ":" + k.parentVersion() + "`").collect(Collectors.joining(", "));
            sb.append("| Kind | ").append(kind).append(" |\n");
            if (!a.managed()) {
                sb.append("| BOM pin | ⚠️ not pinned in `").append(BomAnalyzer.BOM).append("` |\n");
            } else if (!a.pinnedInBom().equals(a.from())) {
                sb.append("| BOM pin | main pins `").append(a.pinnedInBom()).append("` (PR says from `")
                  .append(a.from()).append("`) |\n");
            }
            if (!a.root()) {
                sb.append("| Range | ").append(a.rangeOk() ? "✅ " : "❌ ").append(a.constraints().stream()
                        .map(k -> "`" + k.spec() + "`" + (k.admitsNew() ? "" : " (not satisfied)"))
                        .collect(Collectors.joining(", "))).append(" |\n");
            }
            if (a.major()) sb.append("| Major bump | ⚠️ `").append(a.from()).append("` → `").append(a.to()).append("` |\n");
        }
        if (!c.staticFail()) {
            sb.append("| Merges into main | ").append(c.conflicted ? "❌ conflicts" : "✅").append(" |\n");
        }
        if (!c.staticFail() && !c.conflicted) {
            sb.append("| Quarkus build | ").append(c.buildError == null ? "✅" : "❌").append(" |\n");
            if (c.goOfflineRan) {
                sb.append("| CI dependency resolution (`go-offline` on ")
                  .append(a.modules().stream().map(m -> "`" + m + "`").collect(Collectors.joining(", ")))
                  .append(") | ").append(c.goOfflineErrors.isEmpty() ? "✅" : "❌").append(" |\n");
            }
            if (c.ui != null) {
                sb.append("| Dev UI test (headless Chrome) | ")
                  .append(!c.ui.ran() ? "⚠️ did not run" : c.ui.pass() ? "✅ " : "❌ ")
                  .append(c.ui.ran() ? oneLine(c.ui.summary()) : "").append(" |\n");
            }
        }

        if (!c.goOfflineErrors.isEmpty()) {
            sb.append("\n<details><summary>go-offline errors</summary>\n\n```\n");
            c.goOfflineErrors.forEach(e -> sb.append(e).append('\n'));
            sb.append("```\n</details>\n");
        }
        if (a != null && !a.depChanges().isEmpty()) {
            sb.append("\n<details><summary>Dependency changes in `").append(a.ga()).append("` ")
              .append(a.from()).append(" → ").append(a.to()).append("</summary>\n\n")
              .append("| Dependency | Before | After | BOM pin |\n|---|---|---|---|\n");
            for (var d : a.depChanges()) {
                sb.append("| `").append(d.ga()).append("` | ").append(code(d.oldSpec())).append(" | ")
                  .append(code(d.newSpec())).append(" | ").append(code(d.pinned()))
                  .append(d.pinInRange() == null ? "" : d.pinInRange() ? " ✅" : " ⚠️ outside new range")
                  .append(" |\n");
            }
            sb.append("\n</details>\n");
        }
        if (c.ui != null && c.ui.ran()) {
            sb.append("\n<details><summary>Dev UI pages checked (").append(c.uiHow).append(")</summary>\n\n");
            if (a != null && !a.jsImporters().isEmpty()) {
                sb.append("Uses of the library: ")
                  .append(a.jsImporters().stream().map(j -> "`" + j + "`").collect(Collectors.joining(", "))).append("\n\n");
            }
            for (var p : c.ui.pages()) {
                sb.append("- ").append(p.ok() ? "✅ " : "❌ ").append(p.page())
                  .append(p.notes().isBlank() ? "" : " - " + oneLine(p.notes())).append('\n');
            }
            if (!c.ui.consoleErrors().isEmpty()) {
                sb.append("\nConsole errors:\n```\n");
                c.ui.consoleErrors().forEach(e -> sb.append(e).append('\n'));
                sb.append("```\n");
            }
            if (!c.ui.serverErrors().isEmpty()) {
                sb.append("\nServer log errors during the session:\n```\n");
                c.ui.serverErrors().forEach(e -> sb.append(e).append('\n'));
                sb.append("```\n");
            }
            sb.append("\n</details>\n");
        }

        sb.append("\n<sub>Checked against `").append(mainSha, 0, Math.min(10, mainSha.length()))
          .append("` (main) + `").append(c.pr.sha(), 0, Math.min(10, c.pr.sha().length()))
          .append("` by the [mvnpm-dependabot](https://github.com/House-elves/mvnpm-dependabot) House Elf.</sub>\n");
        return sb.toString();
    }

    private static String code(String s) { return s == null ? "-" : "`" + s + "`"; }

    private static String oneLine(String s) { return s.replace('\n', ' ').replace("|", "\\|").strip(); }
}
