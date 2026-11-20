# Repository settings

GitHub settings that cannot live in files. Only an owner of `jopomods/overseer` can change them.
The repository went public on 2026-11-20 with these steps; keep this list in sync with the settings.

## Before making it public

- Check the history once more for secrets and personal data (CONTRIBUTING.md, "No personal data"):
  every branch and `refs/pull/*` (PR heads become public too), issues, PR texts and comments,
  releases. `gitleaks git . --log-opts="--all"` after fetching `+refs/pull/*:refs/remotes/origin-pr/*`
  covers secrets; grep for server addresses, real UUIDs and player names by hand.
  Commit authors are GitHub accounts; squash merges made in the web UI carry the merger's profile
  name.

## Make it public

Settings → General → Danger Zone → Change visibility → Public.

## Afterwards

1. **Ruleset for `main`** (Settings → Rules → Rulesets → New branch ruleset):
   - Target: default branch; enforcement: active
   - Restrict deletions, block force pushes
   - Require a pull request before merging (0 approvals is fine for a single maintainer)
   - Require status checks to pass: **CI ok** (ADR-0015) and **PR title is a Conventional Commit**,
     with "require branches to be up to date"
   - Allowed merge method: squash
   - Bypass: release-please needs none (it opens PRs like everyone else)
2. **Merge button** (Settings → General → Pull Requests): only "Allow squash merging", default
   commit message "Pull request title"; enable "Automatically delete head branches".
3. **Security** (Settings → Code security): private vulnerability reporting, Dependabot alerts and
   security updates, secret scanning with push protection. CodeQL runs from
   `.github/workflows/codeql.yml` (skipped while private).
4. **Actions** (Settings → Actions → General): keep "Allow GitHub Actions to create and approve pull
   requests" on; release-please needs it to open the release PR. Require actions to be pinned to a
   full commit SHA (every workflow does; Dependabot keeps the pins current). Fork pull request
   workflows: require approval for all external contributors. Workflow permissions stay read-only
   by default; jobs that need more ask for it.
5. Optional: Discussions for questions, so issues stay for bugs and features.
