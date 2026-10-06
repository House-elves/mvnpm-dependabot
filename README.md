# mvnpm-dependabot

A [House Elf](https://github.com/House-elves) that does the morning round of Dependabot's [mvnpm](https://mvnpm.org) PRs on [Quarkus](https://github.com/quarkusio/quarkus) and any other repo you list (e.g. [SmallRye OpenAPI](https://github.com/smallrye/smallrye-open-api)), so the maintainer only has to make the final call.

Quarkus pins every mvnpm artifact the Dev UI uses (roots and transitives) in `bom/dev-ui/pom.xml`, and Dependabot opens a PR for each one. Most are fine. Some are not, in ways CI only catches late or the PR diff doesn't show:

- a **transitive** pin bumped outside the range its parent asks for (`entities` 4 → 8 when `markdown-it` wants `^4`)
- a **root** bump whose new dependency tree has an unresolvable range conflict. `hpcc-js/wasm` 2.35.1 pulled `yargs` 18.1.0 (`string-width ^8`) next to `cliui` 9.0.1 (`string-width ^7`). npm installs both. Maven fails CI's `dependency:go-offline` step.
- a bump that resolves and builds but breaks a page in the Dev UI

## What it does, every morning

```
open Dependabot PRs bumping org.mvnpm*  (not yet checked at their head commit)
  |
  | 1. BOM check: root or transitive? for a transitive, is the new version inside
  |    the range of every pinned parent? (POMs from Central, cached)
  |    -> out of range: NOT SAFE, nothing built
  v
own Quarkus clone (~/Projects/quarkus-mvnpm-elf), reset to upstream main,
every surviving PR merged in
  |
  | 2. build 999-SNAPSHOT  (-Dquickly -Dno-test-modules -T 16C, like `qb`)
  |    into ~/.m2/worktrees/quarkus-mvnpm-elf/repository, its own local repo
  | 3. CI's `dependency:go-offline -Dgo-offline` on the modules that use the bumps
  v
throwaway app (base extensions + the extensions that use the bumped libraries),
quarkus:dev on the snapshot
  |
  | 4. headless Claude + chrome-devtools-mcp (--headless --isolated) walks the
  |    Dev UI: every menu page, every extension card's pages, and exercises the
  |    pages whose JS imports a bumped library; console + network + screenshots
  |    -> fails? re-run on plain main (is main itself broken?), then each bump
  |       alone, to pin it on the right PR
  v
5. a comment on each PR (edited in place on re-checks), an approval for the safe
   ones, and a summary email
```

### Other repos

Any repo in `REPOS` other than Quarkus is treated as a plain Maven project:

```
open Dependabot PRs bumping org.mvnpm*
  |
  | 1. which modules declare the artifact; how its own dependencies moved (POMs from Central)
  v
own clone (CHECKOUTS_DIR/<name>-mvnpm-elf), reset to the default branch, every PR merged in
  |
  | 2. mvn install -DskipTests -pl <those modules> -am, then mvn verify -pl <those modules>
  |    into ~/.m2/worktrees/<name>-mvnpm-elf/repository (QUARKUS_WS)
  v
  | 3. the web UI they package (each index.html under target/classes/META-INF/resources),
  |    served at its jar path by a small in-process server, and walked by the same headless
  |    Claude + chrome-devtools-mcp session; requests nothing answered are reported
  |    -> fails? the default branch alone, then each bump alone
  v
4. comment, approval, email - as for Quarkus
```

A repo can have a profile in `WebUiTester.profile()`: the fixtures its UI fetches and what the session should exercise. `smallrye/smallrye-open-api` has one: its Swagger UI loads a sample OpenAPI 3.1 document from `/openapi` (tags, parameters, a request body, `$ref`'d schemas, a security scheme, a path item `$ref`), and the session expands everything, opens Authorize and runs Try it out on `GET /pets`. A repo without a profile gets a generic walk of its pages. A repo whose modules package no web UI ends at ⚠️ (build and tests only are not enough to approve).

### Verdicts

| Verdict | When | Approved? |
|---|---|---|
| ✅ Safe | every check passes | yes, unless it is a **major** bump of a root library and `APPROVE_MAJOR=false` |
| ❌ Not safe | out of range, CI resolution fails, or the Dev UI breaks with only this bump | no |
| ⚠️ Needs a human look | title not a single bump, merge conflict, build failure, Dev UI already broken on main, bumps only break together, the UI session didn't finish | no |

The comment and the approval are posted **as `GITHUB_USER`** (the maintainer) and the approval is binding. GitHub doesn't notify you of your own @mention, which is why the summary email exists.

A PR is checked once per head commit. When Dependabot rebases or recreates it, it is checked again and the same comment is updated.

## Isolation

- **Its own clone**, not a worktree of yours: it fetches straight from `quarkusio/quarkus` into `refs/elf/*` and never touches your remotes or branches.
- **Its own Maven repo**: `~/.mavenrc` maps each Quarkus checkout to `~/.m2/worktrees/<dir name>/repository`, so the snapshot built here never replaces one you are testing elsewhere. The test app selects that workspace with a `.quarkus-ws` file.
- **Its own browser**: `chrome-devtools-mcp --headless --isolated` launches Chromium with a temporary profile. Your Chrome doesn't need to be open, and it's never touched.
- **A bounded Claude session**: the Dev UI tester only gets the browser tools and read-only access to the source (`Read`, `Grep`, `Glob`). It has no shell and can't edit anything.

## Install

```bash
jbang app install mvnpm-dependabot@House-elves/mvnpm-dependabot

# Interactive setup - account, checkout path, email, schedule
mvnpm-dependabot --install

systemctl --user daemon-reload
systemctl --user enable --now mvnpm-dependabot.timer
```

Prerequisites: Java 21+, JBang, Maven, `gh` logged in as `GITHUB_USER` (`gh auth login`, it doesn't need to be the active account), the `claude` CLI, Node (`npx`), Chromium or Chrome, and podman/docker for any dev services the test app starts.

## Usage

```bash
mvnpm-dependabot --dry-run             # everything except commenting/approving/emailing; prints the comments
mvnpm-dependabot --pr 56903 --dry-run  # specific PRs (any state) of the first repo in REPOS, even if already checked
mvnpm-dependabot --repo smallrye/smallrye-open-api --pr 2696 --dry-run   # ... of another repo
mvnpm-dependabot --repo smallrye/smallrye-open-api --once                # just one repo
mvnpm-dependabot --once                # what the timer runs
mvnpm-dependabot --force               # re-check PRs already checked at their current commit
mvnpm-dependabot --skip-ui             # static checks, build and go-offline only
mvnpm-dependabot --status              # config and checked PRs
```

Each run keeps its artifacts in `~/.local/state/mvnpm-dependabot/runs/<timestamp>/<repo name>/` (the last 14 runs): `build.log`, `go-offline.log`, `dev-*.log`, the Dev UI session transcript `ui-*.json`, `screens-*/` screenshots, and the `comment-<pr>.md` for each PR.

## Configuration

Stored in `~/.config/mvnpm-dependabot/config`:

| Key | Default | Description |
|---|---|---|
| `REPOS` | `quarkusio/quarkus` | Comma-separated repos whose PRs are checked (`REPO`, a single repo, still works) |
| `GITHUB_USER` | - | Account that comments, approves and is @mentioned. Its token comes from `gh auth token -u` |
| `CHECKOUT` | `~/Projects/quarkus-mvnpm-elf` | The elf's own Quarkus clone; its dir name is the `~/.mavenrc` workspace |
| `CHECKOUTS_DIR` | `~/Projects` | Where the clones of the other repos go, as `<name>-mvnpm-elf` |
| `CHROME_PATH` | `/usr/bin/chromium-browser` | Browser for chrome-devtools-mcp |
| `DEV_PORT` | `18080` | Test app HTTP port (also the port the web UI of other repos is served on) |
| `BASE_EXTENSIONS` | `rest-jackson,smallrye-openapi,smallrye-health,scheduler,hibernate-validator` | Always in the test app |
| `APPROVE_MAJOR` | `false` | Also approve passing major bumps of root libraries |
| `AGENT_MODEL` | CLI default | Claude model for the Dev UI session |
| `BUILD_TIMEOUT_MINUTES` | `60` | Per Maven build |
| `UI_TIMEOUT_MINUTES` | `25` | Per Dev UI session |
| `GMAIL_ADDRESS` / `GMAIL_AUTH_USER` / `GMAIL_APP_PASSWORD` / `SEND_TO` | - | Summary email; blank `GMAIL_ADDRESS` turns it off. The password may be `sm://secret-name` (GCP Secret Manager, as in github-worker) |
| `STATE_DIR` | `~/.local/state/mvnpm-dependabot` | State, POM cache, test app, runs |

## License

Apache 2.0 - see [`LICENSE`](LICENSE).
