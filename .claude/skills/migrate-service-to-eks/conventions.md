# Team conventions for migrate-service-to-eks

These are defaults. A team changes them by editing this file. The skill reads it at the start of every run and follows it wherever `SKILL.md` says "per conventions". If a value here conflicts with a rule the developer states in the session, the developer's rule wins.

## Workspace

`workspace_root`: `~/Documents/bWell`

The directory that holds the service repos. Used only to resolve a bare service name to a repo path in Step 0. Never resolve into a `tmp/` subdirectory unless the developer asks.

## Branch

`branch_name`: infer, then confirm

Do not hardcode a prefix. Infer the pattern from the developer's own recent branches (author email from `git config user.email`), propose it with the ticket key, and let the developer change it. Example pattern for one developer: `EM-<TICKET>`. If no pattern is clear, propose the bare ticket key.

## Commit

`commit_message`: `<TICKET> Migrates <service-name> to EKS dev (bwell-app 3.x)`

Present tense, third person. One commit. Stage explicit paths only.

`commit_trailers`: none

No Co-Authored-By or other trailers. If the session carries its own attribution rules, those apply and this file adds nothing.

## Pull request

`pr_title`: `<TICKET> Migrates <service-name> to EKS dev`

`pr_sections`: Summary, Changes, Notes, Test plan

`pr_footer`: none

Notes must always state: dev-only scope, that no file was deleted, and the dev-ue1 retirement disclosure from Step 4 of the skill.

## Process gates

`push_requires_approval`: true

`merge`: never

`trigger_deploy`: never

Keep these as `true` and `never` unless the team has agreed to change them. They are the safety gates of the skill.
