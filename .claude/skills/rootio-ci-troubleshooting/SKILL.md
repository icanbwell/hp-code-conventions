---
name: rootio-ci-troubleshooting
description: Diagnose and fix root.io CI failures in b.well (icanbwell org) repos — both npm-based frontend repos (rootio_patcher rewriting package.json overrides) and Java/Gradle repos (the io.root.patcher Gradle plugin). Covers cases where npm ci, a Gradle build, or the validate-packages job fails in CI (GitHub Actions) even though a local check looked clean, or where CVE-remediation changes seem to work locally but break the build job. Use this whenever the user mentions root.io, rootio_patcher, io.root.patcher, "npm ci failing in CI but not locally", ERESOLVE/EUSAGE errors on a PR after touching npm overrides, a Gradle "Could not resolve all files for configuration" error mentioning a -root.io. version suffix, or a build job failing right after a dependency/CVE-remediation change on an icanbwell repo. Also trigger proactively if you're already mid-troubleshooting a root.io CI failure and the fix isn't obvious — this skill has a symptom-to-root-cause map covering ten distinct, previously-confirmed failure modes across both ecosystems, several of which are very easy to misdiagnose as "flaky CI" or "architecture mismatch" when they're actually deterministic and fixable.
argument-hint: "[repo-name] [PR-number] — e.g. 'web-playground 552', or just describe the CI error"
disable-model-invocation: false
allowed-tools: Bash, Read, Edit, Write, WebFetch
---

# root.io CI Troubleshooting

root.io remediates CVE-vulnerable dependencies by rewriting them to patched builds served from JFrog
Artifactory. Two ecosystems in this org use it, via two different mechanisms — **check which one applies
before reading further**:

- **npm / frontend repos** — `rootio_patcher` rewrites `package.json` `overrides` ahead of time (a
  separate CLI step, committed into the repo) to `@rootio/*`-aliased builds from
  `artifacts.bwell.com/artifactory/api/npm/virtual-npm/`. See the section below and
  `references/failure-modes.md` (9 failure modes).
- **Java / Gradle repos** — the `io.root.patcher` Gradle plugin (introduced via the org-wide INE-837
  migration) resolves patched builds **dynamically at build time** from
  `artifacts.bwell.com/artifactory/virtual-maven`, with `gradle.lockfile` pinning the result. See
  `references/java-gradle-failure-modes.md`.

The dynamic-vs-static difference matters: npm's overrides don't silently drift (you have to explicitly
re-run `rootio_patcher` to pick up new patches), but Gradle's plugin can resolve a *different* patched
version today than it did when `gradle.lockfile` was last committed — see
`references/java-gradle-failure-modes.md#1` for what that looks like and how to fix it.

## npm / frontend repos

`rootio_patcher` remediates CVE-vulnerable npm packages by rewriting them, via npm `overrides` in
`package.json`, to `@rootio/*`-aliased patched builds served from JFrog Artifactory
(`artifacts.bwell.com/artifactory/api/npm/virtual-npm/`). CI runs it twice, in two different jobs, and
that split is the root of most confusion:

- **`validate-packages`** — a reusable workflow (`icanbwell/actions/.github/workflows/validate-npm-packages.yaml`)
  that runs `rootio_patcher npm remediate --dry-run` on `runs-on: main-arm`. This job only checks whether
  more CVE patches are *needed* — it doesn't run your actual build.
- **`build`** — your repo's own job, which runs `npm ci` + `test`/`lint`/`format:check`/`build`. This is
  where lockfile-consistency and registry-auth problems actually surface, and it's usually the one that's
  failing when "root.io CI" is broken.

`rootio_patcher` itself is Linux-only (installed via `apt` from a JFrog Debian repo — see
`icanbwell/actions/setup-rootio-patcher/action.yaml`). On macOS, run it inside a Debian/ARM64 Docker
container to match CI. Don't bind-mount the repo into the container for `npm install` — it's dramatically
slower than `docker cp`-ing the repo in, installing, then copying `package.json`/`package-lock.json` back out.

**The single most important habit this skill teaches:** a lockfile that passes `npm ci` locally is *not*
proof it will pass in CI. Several of the failure modes below are individually deterministic once you
understand the mechanism, but look exactly like flaky/nondeterministic CI from the outside — because the
thing that differs between your local run and CI's isn't the files, it's something about the *environment*
(npm version, `.npmrc` resolution, GitHub token scope). Chase the actual mechanism, not "try again and
hope."

## Fast triage: match the error to a failure mode

Read the exact error from `gh run view --repo <org>/<repo> --job <job-id> --log-failed` (or
`--log-failed` on the specific failed step) before guessing. The error shape tells you which of the nine
failure modes in `references/failure-modes.md` you're looking at:

| Error you're seeing | Likely failure mode | Read |
|---|---|---|
| `ERESOLVE unable to resolve dependency tree` mentioning `react`, or any peer conflict that appears/disappears across identical re-runs | #1 Redundant nested override | [failure-modes.md#1](references/failure-modes.md#1-redundant-nested-override-shadowing-a-flat-override) |
| `Invalid: lock file's X does not satisfy Y` or `Missing: X from lock file`, where X/Y are `@rootio/*` or plain package versions | #1 or #4 — check npm version first (see below) | [#1](references/failure-modes.md#1-redundant-nested-override-shadowing-a-flat-override), [#4](references/failure-modes.md#4-npm-version-mismatch-between-local-and-ci) |
| `validate-packages` finds a CVE that your local `rootio_patcher` run didn't, or a `404` on a specific `@rootio/*` version | #2 Moving CVE feed | [#2](references/failure-modes.md#2-the-cve-feed-is-a-moving-target) |
| `npm ci` EUSAGE citing packages as missing from the lockfile that are clearly in `package.json` | #3 Arborist convergence instability | [#3](references/failure-modes.md#3-npm-arborist-convergence-instability) |
| Passes locally, fails in CI with the *same files*, no obvious reason | #4 npm version mismatch (check this **first** — it's the easiest to miss and the fastest to rule in/out) | [#4](references/failure-modes.md#4-npm-version-mismatch-between-local-and-ci) |
| `404 Not Found` fetching `registry.npmjs.org/@rootio%2f...` | #6 setup-node clobbered the JFrog config | [#6](references/failure-modes.md#6-actionssetup-nodes-registry-url-silently-breaks-other-npmrc-writes) |
| `401 Unauthorized` or `403 Forbidden` fetching `npm.pkg.github.com/download/@icanbwell/...` | #5 (401, no token) or #7 (403, wrong token) | [#5](references/failure-modes.md#5-jfrogs-virtual-registry-passes-through-origin-urls-unchanged), [#7](references/failure-modes.md#7-github_tokens-package-read-access-is-granted-per-package) |
| `E401`/`npm login` partway through an otherwise-progressing local install | #8 Missing local env var | [#8](references/failure-modes.md#8-jfrog_read_token-not-set-in-the-current-shell) |
| `format:check` fails listing files you never touched | #9 Repo-wide format check | [#9](references/failure-modes.md#9-formatcheck-checks-the-whole-repo-not-just-the-diff) |

## The verification loop (do this before every push)

Because failure mode #4 exists, "it passed `npm ci` on my machine" is not sufficient evidence. Use this
exact loop:

1. **Find CI's real npm version.** Open a recent `build` job run, expand the `Use Node.js` /
   `actions/setup-node` step's "Environment details" group — it prints `node: vX.Y.Z` / `npm: A.B.C`
   directly. Don't assume it matches your global npm.
2. **Regenerate with that exact version:** `npx -y npm@<A.B.C> install` (not your global `npm`).
3. **Verify with a truly fresh install, 2–3 times in a row, no deletions skipped:**
   ```bash
   rm -rf node_modules && npx -y npm@<A.B.C> ci   # repeat this line 2-3 times
   ```
   A single green run is not proof — failure mode #1 produces lockfiles that pass once and fail the very
   next run with zero file changes in between. Only trust it after 2-3 consecutive clean fresh installs.
4. **Only then** run `npm test` / `npm run lint` / `npm run format:check` / `npm run build`, commit, push.
5. **After pushing, actually check real CI** (`gh pr checks <PR> --repo <org>/<repo>`) rather than assuming
   local success transfers. If the `build` job fails fast (under ~40s), it almost always means npm never
   got past dependency resolution — read the log immediately rather than re-pushing blind.

## Java / Gradle repos

Much shorter story so far — only one confirmed failure mode, found live while merging the INE-837
root.io/JFrog migration into an in-flight PR on `clinical-reasoning-orchestrator-service`:

| Error you're seeing | Failure mode |
|---|---|
| `:compileJava` (or similar) fails: `Could not resolve all files for configuration ':compileClasspath'` naming a `-root.io.N` version that's "been forced/substituted to a different version" | [Stale gradle.lockfile vs. live-resolved patch](references/java-gradle-failure-modes.md#1-stale-gradlelockfile-vs-a-live-resolved-rootio-patch-version) — fix: `./gradlew dependencies --write-locks` |

Read `references/java-gradle-failure-modes.md` for the full diagnosis — including a caveat about not
confusing an unrelated, pre-existing local Testcontainers/`itest` sandbox limitation with a real root.io
problem, and a note on GitHub Actions platform outages (checked via githubstatus.com) producing symptoms
that look like a stuck root.io CI run but aren't.

This section will grow as more Java/Gradle-specific failure modes get confirmed — if you hit one that
isn't listed here, add it once you've root-caused it, following the same pattern as the npm entries
(exact symptom, why it happens, diagnostic commands, fix).

## If you're stuck after checking the triage table

Read `references/failure-modes.md` (npm) or `references/java-gradle-failure-modes.md` (Java/Gradle) in
full — each entry includes the exact diagnostic commands used to confirm it (not just the fix), because
confirming *which* mechanism is actually happening, rather than pattern-matching to the nearest-sounding
fix, is what makes the difference between resolving this in one push versus five.

If none of the npm modes match, the next places to look, in order:
1. Compare your repo's `.github/workflows/ci.yml` against a known-working sibling repo's (e.g.
   `icanbwell/ui-platform`) — `gh api repos/icanbwell/<repo>/contents/.github/workflows/ci.yml` — most of
   these failure modes were root-caused by finding a repo where the equivalent step is written
   differently and asking *why*.
2. `gh search code "<suspicious config key>" --owner icanbwell` to see how the rest of the org handles it
   — several fixes here (`BWELL_DEV_PAT`, writing to project `.npmrc` instead of `~/.npmrc`) were found
   this way, not from documentation.
3. Check Slack `#temp-bwell-artifacts-transition` and Confluence "Root.io Jfrog Migration Guide" for
   org-wide context on the migration itself.
