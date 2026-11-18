# Repository settings

GitHub settings that cannot live in files. Only an owner of `jopomods/overseer` can change them.
Do them in this order when the repository goes public.

## Before making it public

- Check the history once more for secrets and personal data (CONTRIBUTING.md, "No personal data").
  Commit authors are GitHub accounts; squash merges made in the web UI carry the merger's profile
  name.

## Make it public

Settings → General → Danger Zone → Change visibility → Public.

## Afterwards

1. **Ruleset for `main`** (Settings → Rules → Rulesets → New branch ruleset):
   - Target: default branch; enforcement: active
   - Restrict deletions, block force pushes
   - Require a pull request before merging (0 approvals is fine for a single maintainer)
   - Require status checks to pass: **CI ok** (ADR-0015), with "require branches to be up to date"
   - Allowed merge method: squash
   - Bypass: release-please needs none (it opens PRs like everyone else)
2. **Merge button** (Settings → General → Pull Requests): only "Allow squash merging", default
   commit message "Pull request title"; enable "Automatically delete head branches".
3. **Security** (Settings → Code security): private vulnerability reporting, Dependabot alerts and
   security updates, secret scanning with push protection. CodeQL runs from
   `.github/workflows/codeql.yml` (skipped while private).
4. **Actions** (Settings → Actions → General): keep "Allow GitHub Actions to create and approve pull
   requests" on; release-please needs it to open the release PR.
5. Optional: Discussions for questions, so issues stay for bugs and features.
