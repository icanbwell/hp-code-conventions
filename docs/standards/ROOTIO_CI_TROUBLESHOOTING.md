# root.io CI Troubleshooting

Two ecosystems in this org use root.io, via different mechanisms — check which one applies before
reading further. This doc's numbered failure modes below are all **npm**; see
[Java / Gradle](#java--gradle) for the Gradle-specific one.

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

**The single most important habit here:** a lockfile that passes `npm ci` locally is *not* proof it will
pass in CI. Several of the failure modes below are individually deterministic once you understand the
mechanism, but look exactly like flaky/nondeterministic CI from the outside — because the thing that
differs between your local run and CI's isn't the files, it's something about the *environment* (npm
version, `.npmrc` resolution, GitHub token scope). Chase the actual mechanism, not "try again and hope."

A Claude Code skill covering this same content, with a symptom-to-root-cause quick-reference table, lives
at [`.claude/skills/rootio-ci-troubleshooting/`](../../.claude/skills/rootio-ci-troubleshooting/SKILL.md)
in this repo.

## The verification loop (do this before every push)

1. **Find CI's real npm version.** Open a recent `build` job run, expand the `Use Node.js` /
   `actions/setup-node` step's "Environment details" group — it prints `node: vX.Y.Z` / `npm: A.B.C`
   directly. Don't assume it matches your global npm.
2. **Regenerate with that exact version:** `npx -y npm@<A.B.C> install` (not your global `npm`).
3. **Verify with a truly fresh install, 2–3 times in a row, no deletions skipped:**
   ```bash
   rm -rf node_modules && npx -y npm@<A.B.C> ci   # repeat this line 2-3 times
   ```
   A single green run is not proof — failure mode 1 below produces lockfiles that pass once and fail
   the very next run with zero file changes in between.
4. **Only then** run `npm test` / `npm run lint` / `npm run format:check` / `npm run build`, commit, push.
5. **After pushing, actually check real CI** rather than assuming local success transfers.

### Recommended local remediation order

*(Confirmed by amandaglz.)*

```bash
npm install
npm run format          # should come clean; fix this first if it doesn't
rootio_patcher npm remediate --package-manager=npm --dry-run
# if it reports patches needed:
rootio_patcher npm remediate --package-manager=npm --dry-run=false
npm install
npm run eslint
```

## Failure modes

### 1. Redundant nested override shadowing a flat override

**Symptom:** flaky `ERESOLVE unable to resolve dependency tree` (often naming `react` or whatever
package sits at the collision point), or `Invalid: lock file's X does not satisfy Y` / `Missing: X from
lock file` — appearing and disappearing across otherwise-identical `npm ci` runs with zero file changes
in between.

**Root cause:** npm overrides come in two shapes:

```jsonc
// flat — matches ONLY exact-version requests for "pkg", anywhere in the tree
"pkg@1.2.3": "npm:@rootio/pkg@1.2.3-root.io.1"

// nested — matches ANY "pkg" dependency anywhere within <scope>'s subtree,
// regardless of what version was actually requested
"<scope>": {
  "pkg": "npm:@rootio/pkg@1.2.3-root.io.1"
}
```

If a flat override for a version already exists and reaches a target package, adding a redundant
**nested** override for the same target under some scope isn't just superfluous — it puts npm's
arborist resolver at the edge of its own consistency-checking and produces genuinely non-deterministic
results across otherwise-identical `npm ci` runs. This exact bug was found and fixed *twice* in one
session on `icanbwell/web-playground`, for the same scope, because a later `rootio_patcher` round
silently reintroduced it after it had already been removed once.

**Fix:** before adding a nested override, check whether a flat override for the exact version already
reaches the target — inspect `npm install`'s `ERESOLVE overriding peer dependency` output, which names
the override that actually won.

### 2. The CVE feed is a moving target

`rootio_patcher`'s vulnerability database updates continuously. A clean local check can go stale by the
time CI runs minutes later — new patches appear, or old patched versions get pulled from the registry
(404s). Not a mistake on your part; re-run the convergence loop (`rootio_patcher npm remediate` → `npm
install` → repeat until "No patches needed") and push again.

**Confirmed variant — one package in a batch isn't published yet:** `--dry-run=false` can write
overrides for every flagged package, then `npm install` `ETARGET`/`notarget`s on only one of them while
its siblings install fine (live example: a `nanoid`/`postcss`/`rollup` batch on `web-playground` where
`postcss@8.4.31-aikido.4` 404'd via `npm view` and a raw registry query while the other two resolved
cleanly). Don't block the PR on it — verify each patched version individually with `npm view
<pkg>@<version> version` before installing, revert just the unresolvable one's override(s) back to its
prior working version (flat key *and* every nested per-parent copy), and install normally for the rest.
`validate-packages` will keep flagging that one CVE until the mirror catches up — expected, not a bug.
`rootio_patcher npm remediate --ignore=<pkg>@<version>` (or `.rootioignore`) can suppress it, but only
with an explicit human sign-off — it silences a real CVE rather than fixing it. An unpatched CVE also
risks failing the deployment-to-dev gate later, not just this CI check.

**Confirmed resolution timeline (same `postcss` example):** the gap wasn't indefinite — confirmed absent,
then confirmed present via the same `npm view`/registry-packument check, within the same working session
(hours, not days). Re-running the remediation loop then converged cleanly with no `--ignore` needed.
Prefer waiting and re-checking the registry directly over reaching for `--ignore` unless there's a real
deadline. A previously-merged sibling PR was separately seen hitting this same gate around the same
time — if this recurs, it's worth flagging to whoever owns the JFrog mirror as a frequency signal.

### 3. npm arborist convergence instability

With a large override count, a single `npm install` pass can produce a lockfile that fails a strict,
immediately-following `npm ci` — `EUSAGE` citing packages as "missing from lock file" that are clearly
in `package.json`. This is **not** a CPU-architecture issue (verified via same-arch ARM64-to-ARM64
testing) — it's npm's own lockfile-writer settling. Running `npm install` 2-3 more times in a row (no
deletions between) causes it to fully settle. Always verify with a truly fresh `rm -rf node_modules &&
npm ci`, repeated 2-3 times — a single passing run is not proof (see #1).

### 4. npm version mismatch between local and CI

The biggest hidden trap: verifying locally with a different npm version than CI uses can produce a
false "it passes." The exact same files can pass 3x clean local `npm ci` runs under one npm version,
then instantly reproduce (or resolve) CI's exact result when re-tested with CI's actual npm version via
`npx -y npm@<version> ci`. Always check the CI job's real npm version (see the verification loop above)
— don't trust whatever's installed globally on your machine.

### 5. JFrog's virtual registry passes through origin URLs unchanged

`package-lock.json`'s `"resolved"` field for `@icanbwell/*` scoped packages can end up pointing directly
at `npm.pkg.github.com`, even when resolved purely through JFrog's virtual-npm proxy — JFrog passes
through the origin registry's own tarball URL rather than rewriting it to point back at itself. `npm
ci` fetches the *exact* URL stored in the lockfile, never re-resolving at install time — so whether
that fetch succeeds depends on whether the *active* `.npmrc` at CI-install-time has credentials for
whatever host got baked in at generation time, not on which registry generated it.

**Confirmed contrasting variant — JFrog isn't at fault, a local `~/.npmrc` baked the URL in:** same
symptom, different root cause. On `web-playground`, a direct JFrog packument query for the exact
package/version/hash that 401'd returned `200` — JFrog mirrors it fine. The real cause: whoever last
regenerated `package-lock.json` had a personal `~/.npmrc` with `@icanbwell:registry=https://
npm.pkg.github.com/` active, so npm resolved those packages against GitHub Packages directly instead of
JFrog. Confirmed 2026-09-14: 29 lockfile entries affected in one regeneration, silently broke that
repo's Docker-based deploy for 3+ days (PR CI didn't catch it — its `actions/setup-node` step configures
*both* registries, masking the JFrog-only gap the Docker build actually has). **Fix (cheaper than adding
GH auth):** rewrite the affected `"resolved"` URLs from `npm.pkg.github.com/download/<scope>/` to
`artifacts.bwell.com/artifactory/api/npm/virtual-npm/download/<scope>/`, verifying each one returns
`200` first — only valid when JFrog actually mirrors the package. **Prevention:** regenerate lockfiles
with `npm install --userconfig=/dev/null` so they reflect what CI/Docker will see, not your personal
`~/.npmrc`.

### 6. `actions/setup-node`'s `registry-url` silently breaks other `.npmrc` writes

When given a `registry-url` input (needed to configure GitHub Packages auth via `scope` +
`NODE_AUTH_TOKEN`), `actions/setup-node@v6` writes its own temp `.npmrc` and exports
`NPM_CONFIG_USERCONFIG` pointing to it for the rest of the job — this persists across all subsequent
steps and **replaces** `~/.npmrc` as npm's active user config. Any other step that does `echo '...' >>
~/.npmrc` (a common pattern for configuring the JFrog registry) is now writing to a file npm no longer
reads, so those registry lines silently vanish and npm falls back to the public registry for anything
not covered by the scope-specific config — causing 404s on `@rootio/*` packages that only exist in the
JFrog mirror.

**Fix:** write registry config to the **project-level** `.npmrc` (bare `.npmrc`, cwd-relative) instead
of `~/.npmrc` — project config is always read regardless of `NPM_CONFIG_USERCONFIG` redirection.

### 7. `GITHUB_TOKEN`'s GitHub Packages read access is granted per-package

The ephemeral `secrets.GITHUB_TOKEN`'s GitHub Packages read access is controlled per-package, via each
package's own "Manage Actions access" setting — not org-wide. This produces a confusing inconsistency:
the same token, same scope, can succeed on one `@icanbwell/*` package and 403 on another. Before
concluding it's a permissions gap, rule out a deleted version — GitHub Packages returns `403` for both
"no access" and "nonexistent version," to avoid leaking existence. The org-wide fix (already used by
dozens of repos — `gh search code "NODE_AUTH_TOKEN" --owner icanbwell`): use the shared
`secrets.BWELL_DEV_PAT` org secret instead of `secrets.GITHUB_TOKEN`. A real PAT carries the token
owner's own org-wide package read access rather than being subject to per-package Actions grants.

### 8. `JFROG_READ_TOKEN` not set in the current shell

(Local troubleshooting only.) The repo's `.npmrc` references `${JFROG_READ_TOKEN}`, sourced from your
shell profile. Non-interactive shells don't automatically source it — if `npm install`/`npm ci` fails
with `E401`/"npm login" errors partway through an otherwise-progressing install, check whether the
token is actually set before assuming a credentials problem with the token itself.

**Confirmed variant — a stale duplicate export shadows a working token:** a shell profile edited more
than once can end up with two `export JFROG_READ_TOKEN=...` lines; the later one wins. That value can
be valid for the npm registry while returning `401` specifically against the private-debian apt repo
used to install `rootio_patcher` itself — `[ -z "$JFROG_READ_TOKEN" ]` reports it *is* set, masking the
real issue. `grep -n JFROG_READ_TOKEN ~/.zshrc` to spot duplicates, and test each candidate value
directly against the failing endpoint with `curl -u` rather than assuming "the token" is a single
value.

### 9. `format:check` checks the whole repo, not just the diff

`prettier . --list-different` checks every file in the repo. Pre-existing unformatted files on `main` —
unrelated to your PR — will fail this check regardless of what you actually changed. Isolate whether a
failure is pre-existing with `git stash` before assuming you caused it; if it is, you'll still need to
run `npm run format` (prettier `--write`) to get CI green, since the check isn't diff-scoped.

### 10. JFrog username (an email address) breaks credential-URL parsing

Applies to custom Alpine/`apk`-based install setups that build a `https://user:token@host/...` URL by
hand — not this org's shared `setup-rootio-patcher` action, which uses apt's `auth.conf.d` login/password
mechanism and isn't exposed to this bug. `JFROG_READ_USER` is an email address, which itself contains an
`@`; embedded in a credentials URL that gives it two `@` characters, so the parser can't tell which one
separates credentials from host. **Fix:** percent-encode the `@` (`%40`) in the username, or better,
prefer a setup that takes username/password as separate fields instead of a combined URL.

### 11. Incremental lockfile updates can silently leave CVEs unpatched

`rootio_patcher --dry-run` can keep reporting the same CVEs as pending even after you believe you've
patched them, because `npm install --package-lock-only` run on top of an *existing* lockfile doesn't
reliably re-apply overrides to every already-resolved nested dependency path — only the ones the
resolver happens to revisit. This is sharper than failure mode #3's "run install a few more times"
advice: an incremental update can leave real, unpatched CVEs behind indefinitely without ever erroring.
**Fix:** delete `package-lock.json` and force a fully fresh resolve before trusting a dry-run result:
```bash
rm package-lock.json
npm install --package-lock-only
rootio_patcher npm remediate --package-manager=npm --dry-run   # should report "No patches needed"
```

## Java / Gradle

Java/Gradle repos use the `io.root.patcher` Gradle plugin instead (introduced org-wide via the INE-837
migration), patching dependencies to `io.root.*`-namespaced builds from
`artifacts.bwell.com/artifactory/virtual-maven`. Unlike npm's static, ahead-of-time overrides, this
plugin resolves patched versions **dynamically at build time** — so `gradle.lockfile` can drift out of
sync with what it actually resolves today.

**Symptom:** `:compileJava` fails with `Could not resolve all files for configuration
':compileClasspath'`, naming a dependency "forced/substituted to a different version" than what's in
the lockfile. Look a few lines earlier in the log for `Patching <dep>:<old-version> ->
<dep>:<new-version>` — that's the plugin telling you a newer patch became available since the lockfile
was generated.

**Fix:** regenerate the lockfile, then verify with a real build:
```bash
./gradlew dependencies --write-locks
./gradlew build
```
Caveat: if `itest` fails with `NoClassDefFoundError`/`ExceptionInInitializerError` around
`GenericContainer`, that's an unrelated, pre-existing local Testcontainers sandbox limitation — verify
the fix with `./gradlew test` (no Testcontainers) in isolation rather than assuming the relock didn't
work.

Confirmed live on `clinical-reasoning-orchestrator-service` PR #192, right after merging the INE-837
migration in from `main`: the lockfile had `spring-webmvc`/`spring-expression` pinned at
`6.2.17-root.io.3` while the plugin resolved both to `6.2.17-root.io.4`.

**Unrelated but easy to conflate:** a GitHub Actions platform outage can produce symptoms that look
identical to a stuck root.io CI run — jobs stuck `queued` for hours, `Failed to resolve action download
info` / `Service Unavailable` before any of your own steps run, pushes not triggering new runs at all.
Check `https://www.githubstatus.com/api/v2/incidents/unresolved.json` before assuming it's your code;
retrying won't help until the incident resolves.
