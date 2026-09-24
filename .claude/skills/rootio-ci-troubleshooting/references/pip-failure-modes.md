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
exit code 2, and every step after it in the same job (tests, "Validate libraries", a downstream
publish/deploy step) never runs at all — not even ones with `if: always()`, because the job's default
`Report Results` gate never gets a chance to evaluate; the job stops immediately at the failed step.

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

**Open gap, flagged rather than papered over:** unlike the npm side's verification loop (`rm -rf
node_modules && npm ci`, repeatable locally against the real registry), there is no confirmed local
reproduction path for this yet — `rootio_patcher pip remediate --dry-run` needs `ROOTIO_PKG_URL` /
`ROOTIO_PIP_INDEX_URL` pointed at JFrog's `virtual-pypi` index plus `JFROG_READ_USER`/`JFROG_READ_TOKEN`
credentials that weren't available in the session that found this fix. That means the exact target version
named in a dry-run's output was trusted at face value and pushed without confirming the patched build
actually exists on JFrog first — the real proof only came from a subsequent CI run. If you have JFrog read
credentials, verify before pushing:
```bash
pip index versions <package> --index-url https://artifacts.bwell.com/artifactory/api/pypi/virtual-pypi/simple
```
If you confirm this works, update this entry with the confirmed command and remove this caveat.

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
installs from — `grep -rn "<package>" **/*.txt **/Pipfile.lock` across the repo and check each hit against
the workflow YAML, don't assume there's only one place a package could be pinned.
