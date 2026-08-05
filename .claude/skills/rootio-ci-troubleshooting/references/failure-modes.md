# root.io CI failure modes — detailed reference

Nine distinct, confirmed failure modes, in the order you're likely to hit them working through a
root.io remediation. Each entry includes what you'll actually see, why it happens, the diagnostic
commands that confirmed it (not just the fix — reproducing the mechanism is what tells you you've
actually found the cause, versus just guessing and getting lucky), and the fix.

## 1. Redundant nested override shadowing a flat override

**Symptom:** `ERESOLVE unable to resolve dependency tree` — often naming `react`, `react-dom`, or
whatever package sits at the point in the tree where two override rules collide — or `Invalid: lock
file's X does not satisfy Y` / `Missing: X from lock file`. The distinguishing trait: it's flaky in a
very specific way — the *same committed files* pass `npm ci` on one run and fail on the very next,
with zero changes in between. That flakiness is the tell; a real missing-dependency problem is
deterministic.

**Why:** npm overrides come in two shapes.

```jsonc
// flat — matches ONLY exact-version requests for "pkg", anywhere in the tree
"pkg@1.2.3": "npm:@rootio/pkg@1.2.3-root.io.1"

// nested — matches ANY "pkg" dependency anywhere within <scope>'s subtree,
// regardless of what version was actually requested
"<scope>": {
  "pkg": "npm:@rootio/pkg@1.2.3-root.io.1"
}
```

If a flat override for `pkg@1.2.3` already exists and reaches a target package (because that
dependency happens to request exactly `1.2.3`), adding a *second, nested* override for the same target
under some scope is redundant — and it pushes npm's arborist resolver right up against the edge of its
own consistency-checking, which produces genuinely non-deterministic results across otherwise-identical
runs. This isn't a hypothetical: it happened twice in one session on `icanbwell/web-playground`, for the
exact same `@icanbwell/embeddable-core` scope — once for a `lodash`/`minimatch` pair, then again later
for `minimatch` alone after a subsequent `rootio_patcher` round re-added it.

**Diagnose:** run `npm install` and read the `ERESOLVE overriding peer dependency` output closely — it
names which override actually won for a given resolution. Then grep `package.json`'s `overrides` block
for the same target package/version under a *different* key (flat vs. nested) — if you find both, you
likely have this bug.

```bash
grep -n '"pkg@' package.json          # flat overrides for this package
grep -n -B1 '"pkg":' package.json      # nested overrides for this package
```

**Fix:** remove the nested override if a flat override for the exact same target version already
reaches it. Regenerate the lockfile (`npm install`, 2-3 times — see #3) and re-verify with a fresh
`npm ci` run 2-3 times in a row before trusting it.

## 2. The CVE feed is a moving target

**Symptom:** `validate-packages` finds a new CVE that your local `rootio_patcher` run, done minutes
earlier, didn't flag — or a specific `@rootio/*` version tag 404s that used to resolve fine.

**Why:** rootio_patcher's vulnerability database updates continuously. A clean local check can go
stale by the time CI actually runs. This is not a mistake on your part.

**Fix:** re-run the convergence loop: `rootio_patcher npm remediate --package-manager=npm
--dry-run` (in a Debian/ARM64 container matching CI) → `npm install` → repeat until it reports "No
patches needed." Push again.

## 3. npm arborist convergence instability

**Symptom:** `npm ci` fails with `EUSAGE` citing packages (commonly things like `metro`, `chalk`,
`yargs`, or transitive `@rootio/*` aliases) as "missing from lock file," even though they're clearly
present in `package.json` — and this can happen on a lockfile that was JUST generated, immediately
after `npm install` finished with no errors.

**Why:** with 90+ override entries, npm's own lockfile-writer can produce output that's internally
self-inconsistent on a single pass. This was misdiagnosed once as a CPU-architecture mismatch (`main`
vs `main-arm` GitHub Actions runners) — that theory was tested via a QEMU-emulated `linux/amd64`
container and disproven; the same failure occurred in pure same-architecture (ARM64-to-ARM64) testing
too. It's arborist settling, not architecture.

**Fix:** run `npm install` two or three additional times in a row, without deleting anything in
between. Then verify with a genuinely fresh install:

```bash
rm -rf node_modules && npm ci   # repeat 2-3 times before trusting a green result
```

## 4. npm version mismatch between local and CI

**Symptom:** the exact same `package.json`/`package-lock.json` passes a clean local `npm ci` — even
2-3 times in a row — and then fails in CI with a lockfile-validation error you didn't see locally. Or
the reverse: it fails locally but CI (using a *different* npm version) passes cleanly on the identical
files. Both directions have been observed on this override topology — neither npm version is
uniformly "more correct," they just apply their own lockfile self-consistency checks slightly
differently.

**Why:** `npm ci`'s lockfile self-consistency validation logic is not perfectly stable across npm
versions. CI resolves whichever npm version `actions/setup-node@v6` bundles for the requested Node
version (this can drift over time — it is not the same as whatever's installed on your laptop).

**Diagnose:** open a recent `build` job run in GitHub Actions, expand the `Use Node.js` /
`actions/setup-node` step, and look at its "Environment details" log group — it prints the exact
`node:`/`npm:` versions used. Then reproduce with that exact version locally:

```bash
npx -y npm@<CI's exact version> ci      # against your current committed files, no changes
```

If this instantly reproduces (or resolves) a failure your global npm didn't, you've confirmed this is
the mechanism — stop trusting your global npm's verdict for this repo and always use the pinned
version.

**Fix:** regenerate and re-verify using CI's exact npm version specifically:

```bash
npx -y npm@<CI's exact version> install
rm -rf node_modules && npx -y npm@<CI's exact version> ci   # 2-3 times
```

## 5. JFrog's virtual registry passes through origin URLs unchanged

**Symptom:** `npm ci` in CI fails with `401 Unauthorized` (or later, once GitHub Packages auth is
configured — see #7 — a `403 Forbidden`) fetching `https://npm.pkg.github.com/download/@icanbwell/...`,
even though the project's `.npmrc` only configures the JFrog registry and never mentions
`npm.pkg.github.com` at all.

**Why:** `package-lock.json`'s `"resolved"` field for `@icanbwell/*` scoped packages can end up
pointing directly at `npm.pkg.github.com`, *even when the lockfile was generated purely through JFrog's
virtual-npm proxy* — confirmed by regenerating with `--userconfig=/dev/null` (eliminating any local
`@icanbwell:registry` override) and observing the resolved URL was still `npm.pkg.github.com`. JFrog's
virtual repo is passing through the origin registry's own tarball URL rather than rewriting it to
point back at itself. Critically, `npm ci` fetches the *exact* URL stored in the lockfile — it never
re-resolves through whatever registry is configured at install time. So success depends entirely on
whether the *active* `.npmrc` at install time has credentials for whatever host got baked into the
lockfile at generation time, regardless of which registry was used to generate it.

**Diagnose:**
```bash
grep -A3 '"node_modules/@icanbwell/<pkg>":' package-lock.json | grep resolved
```
If it says `npm.pkg.github.com`, CI needs GitHub Packages credentials configured — JFrog alone won't
cover it for this specific package/version.

**Fix:** configure GitHub Packages auth in the CI workflow (see #6 and #7 for how to do this
correctly — there are two more traps in the naive version of this fix).

## 6. actions/setup-node's `registry-url` silently breaks other `.npmrc` writes

**Symptom:** after adding GitHub Packages auth (`registry-url` + `scope` on the `actions/setup-node@v6`
step, per #5's fix), CI starts failing with `404 Not Found` on `registry.npmjs.org/@rootio%2f<pkg>` —
the **public** npm registry, for a package that only exists in JFrog's private mirror.

**Why:** when given a `registry-url` input, `actions/setup-node@v6` writes its own temporary
`.npmrc` and exports the `NPM_CONFIG_USERCONFIG` environment variable pointing to it — this
environment variable *persists for the rest of the job* and replaces `~/.npmrc` as npm's active
user-level config. Any earlier or later step that does `echo '...' >> ~/.npmrc` (a very common pattern
for configuring the JFrog registry) is now writing to a file npm no longer reads. Those registry lines
silently vanish, and npm falls back to its hardcoded default — the public registry — for anything not
covered by the scope-specific config, which is why `@rootio/*` packages 404 while `@icanbwell/*`
packages start working.

**Diagnose:** check the failing step's logged `env:` block in the Actions log — if you see
`NPM_CONFIG_USERCONFIG: /home/runner/_work/_temp/.npmrc` (or similar) and your JFrog step wrote to
`~/.npmrc`, that's the mismatch.

**Fix:** write the JFrog registry config to the **project-level** `.npmrc` (a bare `.npmrc`, relative
to the working directory) instead of `~/.npmrc`. Project config is always read regardless of
`NPM_CONFIG_USERCONFIG` redirection. This is the pattern already used successfully in
`icanbwell/ui-platform`'s `ci.yml` — when in doubt, diff your workflow against a working sibling repo's.

```yaml
- name: Setup JFrog npm registry
  env:
    JFROG_READ_TOKEN: ${{ secrets.JFROG_READ_TOKEN }}
  run: |
    echo 'registry=https://artifacts.bwell.com/artifactory/api/npm/virtual-npm/' >> .npmrc
    echo '//artifacts.bwell.com/artifactory/api/npm/virtual-npm/:_authToken=${JFROG_READ_TOKEN}' >> .npmrc
    echo '//artifacts.bwell.com/artifactory/api/npm/virtual-npm/:always-auth=true' >> .npmrc
```

## 7. GITHUB_TOKEN's package-read access is granted per-package

**Symptom:** the ephemeral `secrets.GITHUB_TOKEN` (wired via `NODE_AUTH_TOKEN`) successfully fetches
*some* `@icanbwell/*` packages but 403s (`permission_denied: read_package`) on others — same scope,
same token, same workflow, different packages. This looks like it must be a bug, but it isn't.

**Why:** GitHub Packages' "Manage Actions access" setting is configured *per package*, on that
package's own repository — it's not an org-wide switch. A package's maintainers have to explicitly
grant read access to each consuming repo (or set the policy to "all repositories in the org"). If
they've only granted access to a subset of repos, any repo outside that list gets a 403 with
`GITHUB_TOKEN`, even though the org's own GitHub Packages generally "work" for other packages that do
have the grant configured. Note also: GitHub Packages returns `403`, not `404`, for both "no access"
and "this version doesn't exist," specifically to avoid leaking existence — don't assume 403 means the
version was deleted without checking (see below).

**Diagnose:** before concluding it's a permissions gap, rule out a deleted/missing version — if you (or
anyone with working access) can `npm view <pkg>@<version> version` successfully, the version exists and
this is a permissions issue, not a missing-version issue.

Then check how the rest of the org handles this — b.well's actual convention, found via:
```bash
gh search code "NODE_AUTH_TOKEN" --owner icanbwell
```
turned up dozens of repos using `secrets.BWELL_DEV_PAT` instead of `secrets.GITHUB_TOKEN`.

**Fix:** use the shared `secrets.BWELL_DEV_PAT` org secret (confirmed already inherited by every repo
in the org — check with `gh api repos/<org>/<repo>/actions/secrets` if unsure) instead of
`secrets.GITHUB_TOKEN` for `NODE_AUTH_TOKEN`. A real PAT carries the token owner's own org-wide package
read access rather than being subject to per-package Actions grants — this is why it's the pattern
used everywhere else in the org, not an oversight in `web-playground`'s workflow specifically.

```yaml
- run: npm ci
  env:
    JFROG_READ_TOKEN: ${{ secrets.JFROG_READ_TOKEN }}
    NODE_AUTH_TOKEN: ${{ secrets.BWELL_DEV_PAT }}
```

## 8. JFROG_READ_TOKEN not set in the current shell

**Symptom:** (local troubleshooting only) `npm install`/`npm ci` progresses through a large chunk of
the dependency tree — sometimes hundreds of packages in — and then fails with `E401` / "Incorrect or
missing password" / suggests `npm login`.

**Why:** the repo's `.npmrc` references `${JFROG_READ_TOKEN}`, normally exported from `~/.zshrc` (or
equivalent). Non-interactive or tool-driven shells (background task runners, some CI-like local
harnesses) don't automatically source shell profile files — if the token isn't actually in the
environment, the substitution resolves to empty and requests go out unauthenticated.

**Diagnose:**
```bash
[ -z "$JFROG_READ_TOKEN" ] && echo "UNSET" || echo "set, length ${#JFROG_READ_TOKEN}"
```

**Fix:** `source ~/.zshrc` (or wherever it's exported) before running npm commands in a fresh shell.

## 9. format:check checks the whole repo, not just the diff

**Symptom:** a PR's CI fails at the `format:check` (prettier) step, listing files the PR never touched.

**Why:** `prettier . --list-different` checks every file in the repo, not just the ones in your diff.
Pre-existing unformatted files on `main` — from a prettier version bump, a config change, or files that
were simply never run through the formatter — will fail this check on *any* PR, regardless of what
that PR actually changed.

**Diagnose:** isolate whether the failure is pre-existing before assuming you caused it:
```bash
git status --short                       # note your current changes
git stash --include-untracked
npx prettier . --list-different           # check against the unmodified base
git stash pop
```
If the same files fail before and after your changes are restored, it's pre-existing.

**Fix:** since the check isn't diff-scoped, your PR's CI won't go green until those files pass too —
even if they're unrelated to your change. Run `npm run format` (prettier `--write`) to fix them. This
is a purely mechanical, safe change (whitespace/quote-style only) — verify the diff really is
formatting-only before committing, and flag transparently that you're fixing pre-existing drift as
part of getting CI green, not because it's part of the PR's actual scope.
