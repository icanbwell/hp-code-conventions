# root.io CI failure modes — detailed reference

Eleven distinct, confirmed failure modes, in the order you're likely to hit them working through a
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

**Confirmed variant — one patch in a multi-package batch isn't published yet:** `rootio_patcher
--dry-run=false` can successfully write overrides for *all* flagged packages, then `npm install` fails
with `ETARGET` / `notarget` on only one of them, even though its sibling patches in the same run
install fine. Live example on `web-playground`: a dry-run flagged `nanoid`, `postcss`, and `rollup`;
after applying all three, `npm install` failed with `notarget No matching version found for
postcss@8.4.31-aikido.4`, while `nanoid@3.3.8-aikido.2` and `rollup@3.29.5-aikido.1` installed cleanly
in the same pass. Confirmed via direct lookup (`npm view postcss@8.4.31-aikido.4 version` also 404s,
and a raw JFrog packument query for `postcss` returned zero `-aikido.*` versions at all) — the CVE
database already knows about a fix build that JFrog hasn't finished mirroring yet. This is the same
root cause as the general case above, just scoped to a single package instead of the whole batch, and
short retries within one sitting won't necessarily resolve it (mirror sync can lag longer than a single
troubleshooting session).

**Fix for the variant:** don't block the whole PR on the one package that isn't resolvable yet.
Individually verify each flagged package with `npm view <pkg>@<patched-version> version` *before*
running `npm install` — for any that 404, revert just that package's override value(s) back to its
prior (still-working) patched version everywhere it appears (flat override key *and* every nested
per-parent override — `rootio_patcher` itself writes to all of them, so revert all of them the same
way), then `npm install`/`npm ci` normally for the rest. Note this means `validate-packages` will keep
reporting that one CVE as pending in every future CI run until the mirror catches up — that's expected,
not a bug in your fix. If leaving it pending would fail a required check the team is not willing to
wait on, `rootio_patcher npm remediate --ignore=<pkg>@<version>` (or a `.rootioignore` file) exists to
suppress a specific CVE from the check, but treat this as a last resort requiring an explicit,
named human sign-off, not something to reach for by default — it silences a real, unpatched
vulnerability rather than fixing it, and the CI gate exists specifically to catch this class of thing.
It also carries more than a CI-cosmetics risk: an unpatched CVE left in place is expected to fail the
subsequent deployment-to-dev gate too, not just `validate-packages` — don't treat "PR is green" as the
finish line if the ignore was only ever meant to be temporary.

**Confirmed resolution timeline (same `web-playground` `postcss@8.4.31-aikido.4` example above):** the
gap was not indefinite. It was still absent from the mirror when first checked, and confirmed present
(via the same `npm view postcss@8.4.31-aikido.4 version` / raw packument query) later the same working
session — on the order of hours, not days. Re-running the full remediation loop at that point converged
cleanly (`rootio_patcher --dry-run` → "No patches needed") with no `--ignore` needed. Practical takeaway:
prefer "wait and re-check the registry directly" over reaching for `--ignore` unless the team has an
actual deadline that can't absorb a same-day retry — worth noting a previously-merged sibling PR was
separately observed hitting this same gate around the same time, suggesting this mirror-lag pattern may
recur more often than "rare edge case." If you see it recur, it's worth flagging to whoever owns the
JFrog mirror as a frequency signal, even though the per-PR fix here doesn't change.

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
    echo '//artifacts.bwell.com/artifactory/api/npm/virtual-npm/:_authToken=<the JFROG_READ_TOKEN env var>' >> .npmrc
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

**Confirmed variant — a stale duplicate export shadows a working token:** if `~/.zshrc` (or
equivalent) has been edited more than once and ended up with *two* `export JFROG_READ_TOKEN=...`
lines, the later one wins — and it can be a token that still works fine against the npm registry
(`artifacts.bwell.com/artifactory/api/npm/virtual-npm/`) while returning `401` specifically against
the private-debian apt repo used to install `rootio_patcher` itself
(`artifacts.bwell.com/artifactory/private-debian/dists/.../InRelease`). This looks identical to the
token simply being unset, except `[ -z "$JFROG_READ_TOKEN" ]` reports it *is* set — the value is just
wrong for that one endpoint. Confirmed live: an earlier `export` in the file (line 6) worked with
`curl -u "$JFROG_READ_USER:$JFROG_READ_TOKEN" .../private-debian/dists/bookworm/InRelease` → `200`;
a later duplicate export further down the same file (line 34, presumably added in a later edit without
removing the first) → `401` on the identical request.

**Diagnose:** `grep -n JFROG_READ_TOKEN ~/.zshrc` — more than one `export` line is the tell. Test each
candidate value directly against the failing endpoint with `curl -u` before assuming the token itself
is invalid or expired.

**Fix:** test candidate tokens directly rather than assuming "the token" is a single value; use
whichever one 200s for the specific host that's failing. Separately, flag the duplicate export to
whoever owns that shell profile — deduplicating it prevents the next person (or the next tool run) from
hitting the same 401 with no obvious cause.

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

## 10. JFrog username (an email address) breaks credential-URL parsing

**Symptom:** a package-manager update step fails with an invalid-URL error while installing
`rootio_patcher` in a custom Alpine/`apk`-based container setup (a *different* setup than this org's
shared `icanbwell/actions/setup-rootio-patcher` action — that action uses apt's `auth.conf.d`
login/password mechanism, not an embedded-credentials URL, so it isn't exposed to this specific bug;
this applies if you or your team built your own install step that constructs a
`https://user:token@host/...`-style URL directly).

**Why:** `JFROG_READ_USER` is an email address, which itself contains an `@`. Embedded directly into a
`https://<user>:<token>@<host>` credentials URL, that gives the URL a second `@` — the parser can't tell
which one separates credentials from host, and the request fails or resolves to the wrong host entirely.

**Fix:** percent-encode the `@` in the username (`%40`) before building the credentials string, e.g.
`user%40company.com` instead of `user@company.com`. If you have any control over the setup, prefer a
mechanism that takes username/password as separate fields (like apt's `auth.conf.d`, used by this org's
shared action) over building a combined credentials URL by hand — it sidesteps this whole class of bug.

*(Contributed by amandaglz, from a custom rootio_patcher install step outside the shared org action.)*

## 11. Incremental lockfile updates can silently leave CVEs unpatched

**Symptom:** `rootio_patcher npm remediate --dry-run` keeps reporting the same pending CVEs as still
needing patches, even after you believed you'd already applied them in a prior round.

**Why:** running `npm install --package-lock-only` on top of an *existing* lockfile doesn't reliably
re-apply overrides to every already-resolved nested dependency path — it only revisits and updates the
paths npm's resolver happens to touch during that incremental pass, which can leave some previously
(and now incorrectly) resolved nested entries stale and unpatched. This is a sharper, more specific
version of failure mode #3's advice (multiple `npm install` passes to settle) — the risk here isn't
just "needs more passes," it's that an *incremental* update on an existing lockfile can leave real,
unpatched CVEs behind indefinitely without erroring, because nothing forces those stale nested paths to
be re-visited.

**Fix:** don't rely on incremental updates when reconciling CVE patches. Delete `package-lock.json`
entirely and force a fully fresh resolve, then verify with one more dry-run before trusting it:
```bash
rm package-lock.json
npm install --package-lock-only          # inside the Debian/ARM64 container matching CI
rootio_patcher npm remediate --package-manager=npm --dry-run   # should report "No patches needed"
```

*(Contributed by amandaglz.)*
