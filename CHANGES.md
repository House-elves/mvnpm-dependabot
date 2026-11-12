# What's new

What changed for you in each version of mvnpm-dependabot, newest first. The desk shows the parts that are new when it
offers you an update.

## 1.6.0

- New: **Merged bumps, to backport?** under the bumps: the mvnpm bumps merged in the last 7 days, in repos with
  backport labels (Quarkus' `triage/backport`, `triage/backport-3.40`...). A bump you merge from the tab shows there at
  once. **Backport…** lets you pick the branch; the bump is first tried as a cherry-pick onto it, and only a clean one
  is offered the label, which you confirm in the desk's bar. A conflict names the files, and no label goes on.
  `triage/backport` means the newest release branch (4.0 now).

## 1.5.0

- **Let an agent do it** on a bump that needs a look, isn't safe or fails CI (with the Sessions elf on your desk):
  a new session there makes the same bump and fixes what it needs, lock files and UI tests included. You push it as
  your own PR; then the session's **Close Dependabot's #N** closes this one here, with "Superseded by #N.", on your
  confirm.

## 1.4.1

- Close closes a bump from the tab (one it found not safe, say), once you confirm it in the desk's bar, instead of
  sending you to GitHub to do it.

## 1.4.0

- Queue checks: while a check runs, "Check this one" on another bump becomes "Queue check", and it's checked as soon
  as the running one is done, one after another (each builds Quarkus, so they don't run side by side). The tab says
  what's waiting, and × takes one off.

## 1.3.0

- CI failing opens a list of what failed, with a link to each failed check.
- Retry failed builds, Ask Dependabot to rebase, and Why is this failing (which opens the PR in the GitHub elf).

## 1.2.0

- Approve CI run shows whenever CI waits for approval, whether the bump was checked or not.

## 1.1.0

- Its own tab and dashboard card on the desk.

## 1.0.0

- Checks Dependabot's mvnpm bumps on Quarkus and the other repos you watch, and shows what it found on the desk.
