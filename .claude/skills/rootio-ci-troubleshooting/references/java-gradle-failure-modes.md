# root.io CI failure modes — Java / Gradle

This covers Java/Gradle repos using the `io.root.patcher` Gradle plugin (added via the INE-837 JFrog/root.io
migration). The mechanism is the same idea as the npm world (see `failure-modes.md` for that side): a plugin
rewrites vulnerable dependencies to patched `io.root.*`-namespaced builds served from JFrog Artifactory
(`artifacts.bwell.com/artifactory/virtual-maven`). The `rootio { }` block in `build.gradle` configures it.

Unlike npm overrides (which are static, computed ahead of time by `rootio_patcher npm remediate` and
committed into `package.json`), the Gradle plugin appears to **resolve patched versions dynamically at
build time** — so which exact patched version a dependency resolves to can drift between when
`gradle.lockfile` was last generated and when a build actually runs, days or weeks later.

## 1. Stale `gradle.lockfile` vs. a live-resolved root.io patch version

**Symptom:** `:compileJava` (or any task needing `compileClasspath`) fails with:
```
Could not resolve all files for configuration ':compileClasspath'.
> Did not resolve '<group>:<artifact>:<old-version>-root.io.N' which has been forced / substituted
  to a different version: '<old-version>-root.io.M'
```
Look a few lines earlier in the log for the actual trigger — the `rootio` plugin logs every decision it
makes during resolution:
```
No patch for <group>:<artifact>:<version>
Patching <group>:<artifact>:<old-version>-root.io.N -> <group>:<artifact>:<old-version>-root.io.M
```

**Why:** this is the direct Gradle-world analog of the npm side's "moving CVE feed" (failure mode #2 in
`failure-modes.md`) — the root.io patch feed published a newer patched build for a dependency (`root.io.N`
→ `root.io.M`) sometime after `gradle.lockfile` was last regenerated. Gradle's `dependencyLocking` (enabled
via `resolutionStrategy.activateDependencyLocking()` in this repo's `build.gradle`) then does its job
correctly: it strictly refuses to accept a resolution that doesn't exactly match what's recorded in the
lockfile, so the build fails loudly rather than silently drifting.

This was root-caused live on `clinical-reasoning-orchestrator-service` PR #192, immediately after merging
in the INE-837 root.io migration from `main`: `gradle.lockfile` had `spring-webmvc`/`spring-expression`
pinned at `6.2.17-root.io.3`, but the plugin resolved both to `6.2.17-root.io.4` at build time.

**Diagnose:** grep the lockfile for the exact artifact/version named in the "Did not resolve" line — if
it's present but at an older `-root.io.N` suffix than what the log's "Patching X -> Y" line shows, this is
confirmed as the cause (not a real dependency conflict).

```bash
grep "<group>:<artifact>" gradle.lockfile
```

**Fix:** regenerate the lockfile so it reflects the currently-resolved (patched) versions:
```bash
./gradlew dependencies --write-locks
```
Then verify with a full build (`./gradlew build`, or at minimum `./gradlew test` if Testcontainers-based
`itest`s are affected by an unrelated local sandbox limitation — see the caveat below) before committing.
Commit `gradle.lockfile` alongside whatever change prompted the regeneration.

**Caveat — don't misdiagnose an unrelated local Testcontainers failure as part of this:** if `./gradlew
build`'s `itest` task fails with `NoClassDefFoundError` at `Unsafe.java` / `ExceptionInInitializerError` at
`GenericContainer.java`, that's a separate, already-confirmed local sandbox limitation with
Testcontainers-based integration tests (unrelated to root.io) — verify by running `./gradlew test` (unit
tests only, no Testcontainers) in isolation, which is unaffected. Don't let a red `itest` run make you think
the lockfile fix didn't work if `test` and `compileJava` are clean.

## GitHub Actions platform outages can look identical to a real CI problem

Not root.io-specific, but worth checking first whenever CI looks stuck or fails in a way that doesn't match
any known failure mode: check `https://www.githubstatus.com/api/v2/incidents/unresolved.json` for an active
Actions incident. Symptoms seen during a real GitHub-wide Actions outage: jobs stuck `queued` for hours,
`Failed to resolve action download info` / `Service Unavailable` errors before any of your own workflow
steps even run, and pushes not triggering new workflow runs at all (webhook throttling). If you see this,
retrying/re-pushing won't help until GitHub's incident resolves — check status, don't just keep re-running.
Once a stuck run does reach a terminal state (even as `failure`), `gh run rerun --failed` works again.
