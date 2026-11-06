# What's new

What changed for you in each version of mvnpm-dependabot, newest first. The desk shows the parts that are new when it
offers you an update.

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
