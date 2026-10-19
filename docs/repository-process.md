# Repository process

This is the repository-level delivery contract. Product specs and acceptance evidence remain
authoritative for behavior; this document only covers how changes move through Git and releases.

## Branch and merge

- `main` is protected, linear, and required-check gated. Do not force-push it, delete it, or
  bypass its checks.
- Work happens on short-lived branches: `feat/<scope>`, `fix/<scope>`, or `chore/<scope>`.
- Rebase or merge the current `main` before requesting review; do not keep a long-lived integration
  branch as a second authority.
- Merges use squash so the PR title becomes the commit subject. The merged head branch is deleted.
- Review the diff and the verification evidence, not only the CI status.

## Commit and PR messages

- Use Conventional Commits: `feat(scope):`, `fix(scope):`, `test(scope):`, `docs(scope):`,
  `chore(scope):`.
- A PR states scope, authorizing spec/operation, verification, risk, and rollback.
- Do not rewrite published history to hide noisy commits. Squash at merge time instead.

## Releases

- A release is an annotated `vMAJOR.MINOR.PATCH` tag on a green `main` commit.
- Release notes record the source SHA, image digests, and migration versions.
- Deployments use immutable image digests, not a mutable `latest` tag.
- Keep `latest` only as a convenience alias for the newest stable release.

## Container image retention

- Keep `latest`, the current release, and the previous three rollback releases per image.
- Before deleting an old tag, confirm no deployment or running container references it.
- Deduplicate by manifest digest; never delete a digest that still carries a retained tag.
- Image cleanup requires a token with package read/delete permission and is a separately approved
  destructive action.

## Actions artifacts

- Target retention: 30 days for ordinary CI artifacts, 90 days for release manifests and acceptance
  evidence.
- Release evidence is referenced by tag or release notes before the artifact expires.
- Workflow changes require their own explicit scope.

## History and author identity

- Do not rewrite `main` history. PR links, CI evidence, and deployment records point at its SHAs.
- Use `.mailmap` to normalize duplicate author display names without changing commits.
- Before deleting a stale branch, archive its refs (for example with `git bundle`) and record the
  branch, SHA, last commit, and merge status.
- Secrets, credentials, customer data, local task history, and private addresses are never
  committed, even to an archive in this repository.
