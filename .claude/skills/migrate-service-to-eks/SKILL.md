---
name: migrate-service-to-eks
description: Migrates a Java service's dev environment from the legacy dev-ue1 cluster to dev-use1-eks (bwell-app 3.x, ArgoCD) in one PR. The PR adds the 3.x dev values, makes every merge deploy to the new EKS dev, stops deploying to dev-ue1, and sets the dev-ue1 values to zero replicas (applied by one manual legacy deploy after merge). Fixes the known chart guards up front and renders the chart locally before the PR opens. Use when asked to "migrate X to EKS", "move X to dev-use1-eks", "bwell-app 3.x dev migration in one PR", or "retire dev-ue1 for X". Dev only. Does not touch stg, prd, sbx, perf or client-sandbox, and never deletes files.
argument-hint: "[service-name or repo path] - e.g. 'activity-service'"
disable-model-invocation: false
allowed-tools: Bash, Read, Edit, Write, WebFetch
---

# Migrate a service to EKS dev, in one PR

One branch, one commit, one PR. Merging it means: the 3.x dev values exist, every merge deploys to `dev-use1-eks`, merges no longer deploy to `dev-ue1`, and the `dev-ue1` values are set to zero replicas. The pods only scale down when the developer runs one manual legacy deploy after merge (post-merge checklist, item 1).

This skill owns the whole flow. It does not invoke `migrate-bwell-app-3x` or `adopt-continuous-deployments`. It reads only the reference data of `migrate-bwell-app-3x` (see References).

## Scope

In scope: `dev-use1-eks` only.

Out of scope. Say so to the developer if asked:
1. stg, prd, sbx, perf and client-sandbox. They are migrated later.
2. Deleting any file. Root `.helm/*.values.yaml` files and the legacy `deploy.yml` stay in the repo.
3. DNS and Route 53 cutover. CIE owns it.
4. Merging the PR or triggering a deploy.

## Conventions

Read `conventions.md` in this skill's folder before Step 0. It holds the team's defaults for the workspace root, branch naming, commit message, PR title and sections, and the process gates. Wherever a step below says "per conventions", use the value from that file. Nothing in this skill hardcodes a person's naming habits.

## Rules for higher environments (future scope, apply when stg, prd or sbx are added)

Dev is low risk, so one fixed pod is acceptable there. Higher environments carry real traffic, so do not copy the dev decisions.
1. Read the live state before choosing any scaling value. The values file can be overridden: in activity-service dev the file said one replica, but CAST AI ran two. For each env, read the live Deployment (replica count, HPA objects, the `workloads.cast.ai/configuration` annotation) from Groundcover or `kubectl`, and match the effective behavior, not the file.
2. Never apply `isProduction: false` outside dev and sandbox. For stg and prd keep the chart default, which enforces at least two replicas.
3. Treat the CAST AI default as on. When the 2.x file leaves `castai` unset, CAST AI was controlling replicas. Tell the developer which 3.x type was chosen and why, and ask before lowering the replica count below what runs live.
4. Retirement of the old cluster in a higher env happens only after CIE confirms the Route 53 cutover for that env, never by default.
5. Make one environment per PR, so each can be reviewed and rolled back on its own.

## Step 0: Preflight (stop on any failure, change nothing)

0. Locate the service repo. The skill can run from any directory.
   1. If the argument is an absolute path to a git repo, use it.
   2. If the current directory is a git repo whose name or `image-name` matches the argument (or no argument was given), use it.
   3. Otherwise treat the argument as a service name and look for a repo directory of that name under `workspace_root` from `conventions.md` (never a `tmp/` copy unless the developer says so).
   4. If still not found, ask for the path.
   Call this directory `<repo>`. Use `git -C <repo>` and absolute paths from here on. Never rely on the shell's current directory. Tell the developer which repo path was chosen.
1. Tools: `git`, `gh` (run `gh auth status`) and `helm` are on the PATH.
2. The working tree of `<repo>` is clean and its current branch is not `main` or `master`. If it is the default branch, the skill creates its own branch in Step 6, so a clean default branch is fine. A dirty tree is not.
3. `.github/workflows/on_merge.yml` exists and calls `icanbwell/hp-github-workflows/.github/workflows/on_merge_pipeline.yml`. If not, FAIL LOUDLY: say that the repo does not use the shared on-merge pipeline, that all Java services are expected to, and that nothing was changed. Stop.
4. Read from the repo, never hardcode: `service-name` (the `serviceName` in values, or the `image-name` in `on_merge.yml`), the image repository (`repository-url` plus `/` plus `image-name` from `on_merge.yml`), and the values layout.
5. api-gateway check. Some services are reached by outside clients through the shared `api-gateway` service (`api.<env>.icanbwell.com/<path>`). The new clusters have no api-gateway service. Such a service must declare its routes as `endpoints:` instead of `ingress:`, or those paths break when it leaves the old cluster. v1 does not handle that case. Run the check yourself, do not just ask:
   1. Follow `reference/api-gateway-routes.md` section 1 (in `migrate-bwell-app-3x`). Read `src/routes/routes.ts` and `.helm/<env>.values.yaml` from `origin/main` of the `api-gateway` repo (a local checkout after `git fetch`, else `gh api repos/icanbwell/api-gateway/contents/...`). Routes are keyed by an env var such as `BASE_ANALYTICS_SERVICE_URL`, not by service name, so search for the service name and its variants.
   2. A matching env var is not proof. The URL often lives in SSM. Confirm the match by checking whether the service can serve HTTP at all (REST controllers in the source). A Kafka-only service with no inbound controllers cannot be a proxy target.
   3. No route targets the service: say so explicitly ("no api-gateway routes target this service") and continue.
   4. A route does target it, or the match is ambiguous: stop, name the route, and point to `reference/api-gateway-routes.md`. Ask the developer to confirm the env var.
6. Multi-workload check. If `.helm/` holds several workload directories (for example `bsights-engine-cql/` and `bsights-engine-cql-batch/`) or the repo deploys more than one image, v1 does not guess. Stop, list the workloads, and ask the developer which one this run covers and how the others are deployed. The repo-level `deploy-eks-dev` flag in `on_merge.yml` applies to the whole repo, so partial migration of a multi-workload repo needs a decision first.
7. Report what already exists (`.helm/<service-name>/`, `deploy.dev-use1-eks.yml`, `deploy-eks-dev`, `deploy-dev-ue1`). The skill is idempotent. Do only what is missing.

## Step 1: Dev values

Create `.helm/<service-name>/common.values.yaml` and `.helm/<service-name>/dev-use1-eks.values.yaml`. Start from the existing root `common.values.yaml` and `dev-ue1.values.yaml`. Leave the root files untouched in this step. If values already live in `.helm/<service-name>/`, edit `common.values.yaml` only with changes that are valid under both chart versions, and move version-incompatible values into the env files.

Apply the rules in `reference/migration-rules.md` (see References). In short:
1. `environment` becomes `clusterName: dev-use1-eks`.
2. Remove `iamRole`. The role is derived as `dev-use1-irsa-<serviceName>`.
3. Flatten the nested `bwell:` map.
4. Restructure autoscaling to `autoscaling.horizontal.type` using the mapping table in `migration-rules.md` section 5, driven only by what this service's own 2.x file says. Do not copy another service's autoscaling block (for example analytics-sync-service's `native` 1/1). When the 2.x file does not specify a value, follow the table. Raise it to the developer if the table cannot decide. CAST AI is on by default, so when the 2.x file leaves `castai` unset, ask before choosing a replica count below what runs live.
5. Ingress: keep the friendly host, add a `<service>.dev-use1.bwell.zone` host with the same `paths`, remove any `-ue1` host, give every host a `paths` array.
6. Dependency URLs: `https://` to `http://` on `bwell.zone` hosts. Bump Mongo `-pl-0` to `-pl-1`, and add `-pl-1` to bare Atlas hostnames. Map Cosmo/WunderGraph hosts to their in-cluster services.
7. Removed features with real config (`persistence`, `microservice`, `snapshot`) are raised to the developer as decisions. Never delete silently.
8. `icanbwell.com` used for in-cluster calls: present each candidate and let the developer decide.
9. Never guess `clusterName`. For dev it is `dev-use1-eks`.

## Step 2: Chart guards, applied up front

These are render guards that the plugin's rules review does not catch. Each one cost a separate fix PR in analytics-sync-service.
1. `ssmSecrets` must be an object, never an array. Remove `ssmSecrets: []`. If secrets exist, use the map form `ssm_path_key: ENV_VAR`.
2. `isProduction` defaults to true and requires at least two replicas. Set `isProduction: false` in the dev file when `minReplicas` is 1.
3. `livenessProbe.path` and `readinessProbe.path` must differ. For Spring Boot, first confirm Actuator exposes the probe groups (`management.endpoints.web.exposure.include` or the default), then use `/actuator/health/liveness` and `/actuator/health/readiness`. Keep `startupProbe.path` on `/actuator/health`. For non-Spring services, ask the developer for two distinct endpoints.

## Step 3: Deploy wiring

1. Create `.github/workflows/deploy.dev-use1-eks.yml`: `workflow_dispatch` with a required `tag` input. The input description says the tag is the semver produced by the publish pipeline, not `github.sha`. One job calls `icanbwell/hp-github-workflows/.github/workflows/deploy_argo.yml@main` with `service-name`, `tag`, `env: dev-use1-eks`, `helm-chart-version: bwell-app-3x`, and `secrets: inherit`.
2. In `on_merge.yml`, set `deploy-eks-dev: true`.
3. Do not create a local `deploy.argo.yml`. Do not create stg, prd or sbx triggers.

## Step 4: Retire dev-ue1 (default, no question)

1. In `on_merge.yml`, set `deploy-dev-ue1: false`.
2. In the root `dev-ue1.values.yaml`, add `replicaCount: 0` and `castai: {enabled: false}`, and set `autoscaling.enabled: false`. All three are required. The chart ignores `replicaCount` while `castai.enabled` is true (its default).
3. This edits existing files. It deletes nothing.
4. Disclose, do not ask. The PR Notes and the final summary both say:
   1. Merging does not scale dev-ue1 down. With `deploy-dev-ue1: false` the legacy deploy job is skipped on merge, so the zero-replica values apply only after the developer dispatches `deploy.yml` with `env: dev` and the merged build's tag.
   2. While both clusters are up, they share Kafka consumer groups and split partitions.
   3. Route 53 cutover for the friendly domains is CIE's. Callers outside the new cluster may still resolve to dev-ue1 until CIE moves the records.

## Step 5: Render check

Ask before running. The skill ships no script file. Run these commands directly, with the repo path and values from Step 0. The current directory does not matter.

```
WORK_DIR="$(mktemp -d)"
git clone --quiet --branch bwell-app-3x --depth 1 https://github.com/icanbwell/helm-charts.git "$WORK_DIR/helm-charts"
helm template "$WORK_DIR/helm-charts/charts/bwell-app" \
  --name-template <service-name> --namespace <service-name> --kube-version 1.34 \
  --set-string serviceName=<service-name> \
  --set-string image.repository=<image-repository> \
  --set-string image.tag=0.0.0-local \
  --values <repo>/.helm/<service-name>/common.values.yaml \
  --values <repo>/.helm/<service-name>/dev-use1-eks.values.yaml \
  --include-crds > /dev/null
rm -rf "$WORK_DIR"
```

This mirrors the render ArgoCD runs. It touches only the temp directory, which is deleted afterward. Nothing from this step is staged or committed, and the migration adds no script to the service repo or to any repo.

On failure, show the shortest decisive error line, fix the values, and rerun. Make no commit until it passes. Report it as "rendered the chart", not as verified in the cluster.

## Step 6: Branch, commit, PR

1. Ask for the Jira ticket key. Never invent one.
2. Ask for the full branch name. To propose a default, follow `branch_name` per conventions: read the developer's own recent branches (`git for-each-ref --sort=-committerdate refs/heads refs/remotes/origin` filtered by `git config user.email` as author) and infer their pattern. Never hardcode a prefix.
3. `git fetch` and fast-forward the default branch, then create the branch from it. If a local branch with that name already exists, do not delete or reset it. Check that it has no commits outside the default branch (`git merge-base --is-ancestor <branch> <default>`) and that no remote branch of that name exists. If both hold, check it out and fast-forward it to the default branch. If it has unique commits or a remote copy, stop and ask the developer for another branch name.
4. Commit once, after the render passes, staging explicit paths only (never `git add -A`). Message per conventions (`commit_message`, `commit_trailers`).
5. Review gate. Show the file list, the diff stat and the render result, then ask before pushing (`push_requires_approval` per conventions). On approval run `git push -u origin <branch>`. Never force-push.
6. Create the PR with `gh pr create`. Title, sections and footer per conventions (`pr_title`, `pr_sections`, `pr_footer`). Notes records dev-only scope, that no file was deleted, and the Step 4 disclosure. Test plan lists the render result and the post-merge checks below. This skill adds no attribution lines of its own. Session attribution rules apply.
7. Run `gh pr view` to verify the title, body and changed files. Report the URL.
8. Stop. Never merge. Never trigger a deploy.

## Post-merge checklist (print it, do not run it)

1. Scale dev-ue1 to zero. Dispatch `deploy.yml` with `env: dev` and the merged build's tag. Afterwards confirm the dev-ue1 pods are gone and the dev-use1-eks pod still runs. This changes the live legacy cluster, so the developer does it.
2. The IAM role `dev-use1-irsa-<serviceName>` exists in the dev account. CIE provisions it.
3. URLs in secrets or other out-of-band config use `http://` where they target `bwell.zone`, and the Mongo `-pl-1` endpoint.
4. ArgoCD shows Synced and Healthy.
5. The running pod's image tag equals the merged build's tag. This guards against the stale-manifest race.
6. `https://<service>.dev-use1.bwell.zone/actuator/health` returns UP. Non-Spring services use their own health endpoint.
7. Groundcover logs are clean, with no probe failures or restarts.
8. Later, not now: remove the `dev-use1` host after cutover, coordinate DNS with CIE, delete the 2.x files once every env is migrated.

## References

Reference data comes from the `migrate-bwell-app-3x` skill. Find the newest installed copy:

```
ls -d ~/.claude/plugins/cache/*/software-developers/*/skills/migrate-bwell-app-3x/reference | sort -V | tail -1
```

Read `migration-rules.md` (field-by-field rewrites) and `clustername-mapping.md` (`clusterName` and `baseDomain` tables) from that directory. If the plugin is not installed, use `charts/bwell-app/MIGRATION-2.x-to-3.x.md` from `icanbwell/helm-charts` on the `bwell-app-3x` branch. Always prefer the canonical doc when the two disagree.

## Error handling

1. Missing tool or access: stop in preflight and name the missing item.
2. Shared `on_merge_pipeline.yml` not used: fail loudly, change nothing.
3. Render failure: fix and rerun. Never commit a failing render.
4. Unknown values layout or api-gateway routes: stop and ask.
