# root.io CI failure modes — Python / pip

This covers Python repos where `rootio_patcher pip remediate` is invoked directly in a CI step, rather than
through a Gradle plugin or a shared `validate-packages` reusable workflow. Confirmed on `bsights-cql`, a
plain `pip`/`Pipfile` repo (no Poetry/Pipenv-managed CI installs — see gotcha #2 below).

The mechanism is the **static, ahead-of-time** kind — same idea as npm's `package.json` `overrides`, not
Gradle's dynamic-at-build-time resolution (see `java-gradle-failure-modes.md`'s intro for that contrast).
A CI-only `requirements.txt` pins individual packages directly to root.io-patched builds by version string,
e.g. `urllib3==1.26.19+aikido.4`, served from
`artifacts.bwell.com/artifactory/api/pypi/virtual-pypi/simple`. There is no separate CLI rewrite step
committed as a diff the way `rootio_patcher npm remediate` rewrites `package.json` — engineers hand-edit
the pinned version string directly in `requirements.txt` when a patch is needed.

## 1. `rootio_patcher pip remediate --dry-run` hard-fails the whole job — no `continue-on-error`

**Symptom:** a workflow step named something like "Validate dependencies are Root.io hardened" fails with
exit code 2, and every later step in the same job (tests, "Validate libraries", a downstream publish/deploy
step) is skipped. Only steps with `if: always()` or `if: failure()` still run (on `bsights-cql`'s DEV Deploy the
job's "Report Results" step ran after a failed install while the update/publish steps were skipped).

**Root cause:** `--dry-run` mode's whole purpose is to *check* whether every pinned dependency is already on
its latest root.io-hardened build, without applying anything — but it signals "a patch is needed" by
exiting non-zero (confirmed: exit code 2), not by printing a warning and exiting 0. If the calling workflow
step has no `continue-on-error: true` (bsights-cql's doesn't), GitHub Actions treats that non-zero exit as a
hard step failure and the job stops there — this is a **materially different failure shape than npm's**
`validate-packages`, which is its own separate reusable-workflow job that can fail independently without
blocking your repo's `build` job. Here, one Python job does dependency install, root.io validation, tests,
CQL/library validation, and the actual dev-FHIR publish step in sequence — so a stale pin silently blocks
a deploy that has nothing else wrong with it.

The dry-run's actual output names both the stale and target versions directly, e.g.:
```
urllib3 @ 1.26.19+aikido.4 needs urllib3 @ 1.26.19+aikido.5 (fixes AIKIDO-2026-499214)
```

**Fix:** bump the named package's pin in whichever `requirements.txt` the failing step's earlier "Install
Python dependencies" step actually installs from (see gotcha #2 — it's easy to edit the wrong file) to the
exact version the dry-run output names, e.g.:
```diff
-urllib3==1.26.19+aikido.4
+urllib3==1.26.19+aikido.5
```
Confirmed fix on `bsights-cql` PR #596 for `AIKIDO-2026-499214`.

**Where the package name is:** the failing step's raw log, not the job summary. The "Validate Packages"
job summary only says "found N vulnerable package(s)"; the step log above it prints the dry-run output
(`Package: aiohttp @ 3.14.3`, `Patch (Non-Aliased): aiohttp @ 3.14.3+aikido.1`, `CVEs Fixed: [...]`). Search
the raw log for `Package:`. This check can also turn red with **no change in your PR** — a new advisory
published for a pinned package is enough (seen on `bsights-cql` PR #635: it passed on one commit and failed
on the next, with only workflow files changed).

**Verify the target build exists on JFrog before pushing (confirmed):** list the files the virtual index
serves for the package, using the same read credentials CI uses:
```bash
curl -s -u "$JFROG_READ_USER:$JFROG_READ_TOKEN" \
  https://artifacts.bwell.com/artifactory/api/pypi/virtual-pypi/simple/<package>/ \
  | grep -o '<package>-<version>[^"#<]*' | sort -u
```
The listing also shows **which file types exist** (wheel tags like `cp311`, or only an sdist `.tar.gz`) —
read it before pushing, because that predicts failure mode #4 below. (`pip index versions` was suggested
earlier and has not been tried.)

## 2. Two unrelated files can both "pin" the same package name — don't edit the wrong one

**Symptom:** you bump a package's version in what looks like the obvious dependency file, but CI's root.io
step still reports the same CVE as unpatched.

**Root cause:** a `Pipfile.lock`-based repo's actual local/runtime dependency graph (resolved via `pipenv`)
is a completely separate file from a CI-only `requirements.txt` that a workflow step installs from
directly via plain `pip install -r`. On `bsights-cql`, `Pipfile.lock` pins `urllib3==2.7.0` (an unrelated,
untouched-by-root.io version, used for local dev tooling) while
`.github/workflows/scripts/requirements.txt` separately pins `urllib3==1.26.19+aikido.4` (the actual
root.io-relevant pin, installed only in CI via `pip install -r .github/workflows/scripts/requirements.txt`
in the "Install Python dependencies" step). These two files name the same package at two entirely different,
unrelated version numbers on purpose — they serve different install paths. Before editing a version pin,
confirm which file the failing workflow's own "Install Python dependencies" (or equivalent) step actually
installs from — `grep -rn "<package>" --include='*.txt' --include='Pipfile.lock' .` across the repo and check each hit against
the workflow YAML, don't assume there's only one place a package could be pinned.

## 3. The patched build needs a newer transitive pin than `requirements.txt` has — `ResolutionImpossible`

**Symptom:** after bumping a package to its `+aikido.N` build, the "Install Python dependencies" step fails
before any root.io check runs:
```
ERROR: Cannot install -r requirements.txt (line 17) and yarl==1.24.5 because these package versions have conflicting dependencies.
    aiohttp 3.14.3+aikido.1 depends on yarl<2.0 and >=1.25.1
ERROR: ResolutionImpossible
```

**Root cause:** a CI-only `requirements.txt` pins *every* package exactly (including transitive ones), so pip
has no room to move a dependency. A root.io-patched build can carry different dependency bounds than the
upstream release it patches. Here `aiohttp==3.14.3` accepted `yarl==1.24.5`; `aiohttp==3.14.3+aikido.1`
requires `yarl>=1.25.1`.

**Fix:** bump the conflicting pin named in the error to a version that satisfies it, after checking that
version is on JFrog (same `curl` listing as in #1). Then re-run — the bump can surface a *new* dry-run
finding or another conflict, so expect to iterate. Confirmed on `bsights-cql` PR #635 (`yarl` 1.24.5 → 1.25.1).

## 4. The patched build has no wheel for the runner's Python — pip compiles from source and `gcc` is missing

**Symptom:** the install step downloads a `.tar.gz` (not a `.whl`) for the patched package and dies in a
compile:
```
Downloading aiohttp-3.14.3%2Baikido.1.tar.gz
  gcc ... -c aiohttp/_http_parser.c
  error: [Errno 2] No such file or directory: 'gcc'
ERROR: Failed building wheel for aiohttp
```

**Root cause:** root.io-patched builds are published to JFrog separately from upstream's wheels, and not for
every Python version. `aiohttp 3.14.3+aikido.1` had an sdist and a single `cp313` wheel — nothing for the
`cp311` that the repo's workflows use (`python-version: '3.11'`). Pip falls back to the sdist, which compiles
C extensions, and the runner label `main` has no C compiler. Upstream's `3.14.3` has prebuilt cp311 wheels,
which is why this only appears once the patched pin is applied.

**Fix for the shared validation job (confirmed):** the reusable workflow
`icanbwell/actions/.github/workflows/validate-pip-packages.yaml` takes an `apt-packages` input — pass the
compiler there:
```yaml
  validate-packages:
    uses: icanbwell/actions/.github/workflows/validate-pip-packages.yaml@<sha>
    with:
      python-version: '3.11'
      apt-packages: 'build-essential'   # patched aiohttp has no cp311 wheel
```
Confirmed on `bsights-cql` PR #635 (`Validate Packages` went green; the log shows
`Successfully installed ... aiohttp-3.14.3+aikido.1`).

**Not covered by that fix — check every other install site:** any workflow that runs `pip install -r` on the
same `requirements.txt` on the same runner label (deploy/publish jobs, in `bsights-cql`: seven workflows)
hits the same missing compiler, and `apt-packages` only affects the reusable validation job. Whether those
jobs fail is **not yet verified** — confirm by running one (a dry-run of a standalone workflow that installs
the same file is enough) before merging a PR that adds such a pin, or add an `apt-get install -y
build-essential` step before their pip install. Other options, not tried: a Python version the patched
build ships a wheel for (here cp313 — risky when other pins are old, e.g. `pydantic==1.10.4`), or asking
whoever publishes the patched builds to publish a wheel for your Python version.
