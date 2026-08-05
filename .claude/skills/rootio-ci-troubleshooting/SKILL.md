---
name: rootio-ci-troubleshooting
description: Diagnose and fix root.io/rootio_patcher CI failures in b.well (icanbwell org) npm-based frontend repos — cases where npm ci or the validate-packages job fails in CI (GitHub Actions) even though a local check looked clean, or where CVE-remediation overrides in package.json seem to work locally but break the build job. Use this whenever the user mentions root.io, rootio_patcher, "npm ci failing in CI but not locally", ERESOLVE/EUSAGE errors on a PR after touching npm overrides, or a build job failing right after a dependency/CVE-remediation change on an icanbwell frontend repo. Also trigger proactively if you're already mid-troubleshooting a root.io CI failure and the fix isn't obvious — this skill has a symptom-to-root-cause map covering nine distinct, previously-confirmed failure modes, several of which are very easy to misdiagnose as "flaky CI" or "architecture mismatch" when they're actually deterministic and fixable.
argument-hint: "[repo-name] [PR-number] — e.g. 'web-playground 552', or just describe the CI error"
disable-model-invocation: false
allowed-tools: Bash, Read, Edit, Write, WebFetch
---

# root.io / rootio_patcher CI Troubleshooting

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

## If you're stuck after checking the triage table

Read `references/failure-modes.md` in full — each entry includes the exact diagnostic commands used to
confirm it (not just the fix), because confirming *which* mechanism is actually happening, rather than
pattern-matching to the nearest-sounding fix, is what makes the difference between resolving this in one
push versus five.

If none of the nine modes match, the next places to look, in order:
1. Compare your repo's `.github/workflows/ci.yml` against a known-working sibling repo's (e.g.
   `icanbwell/ui-platform`) — `gh api repos/icanbwell/<repo>/contents/.github/workflows/ci.yml` — most of
   these failure modes were root-caused by finding a repo where the equivalent step is written
   differently and asking *why*.
2. `gh search code "<suspicious config key>" --owner icanbwell` to see how the rest of the org handles it
   — several fixes here (`BWELL_DEV_PAT`, writing to project `.npmrc` instead of `~/.npmrc`) were found
   this way, not from documentation.
3. Check Slack `#temp-bwell-artifacts-transition` and Confluence "Root.io Jfrog Migration Guide" for
   org-wide context on the migration itself.
