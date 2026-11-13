# Upstream sync and releases

Three separate flows, each a Claude Code skill:

- **Syncing with Findroid** merges Findroid's changes (mostly translations and fixes) into
  Ephyra in a pull request, when it's worth it. It never releases anything.
  See [`.claude/skills/upstream-sync/SKILL.md`](../.claude/skills/upstream-sync/SKILL.md).
- **Releasing** turns `main` into a stable `vX.Y.Z`, with the version picked by semver from the
  commits since the last release.
  See [`.claude/skills/release/SKILL.md`](../.claude/skills/release/SKILL.md).

- **Releasing libmpv** publishes a new `io.github.midasvo:libmpv` from our fork
  [midasvo/libmpv-android](https://github.com/midasvo/libmpv-android) and bumps Ephyra to it,
  tested on devices. See [`.claude/skills/libmpv-release/SKILL.md`](../.claude/skills/libmpv-release/SKILL.md)
  and the fork's `RELEASING.md`.

In Claude Code, say "sync with Findroid", "do a release" or "release libmpv" and the right skill
triggers.

## Publishing a beta

Every push to `main` publishes a beta. To publish one by hand:

```bash
# An open pull request, built merged into main
gh workflow run beta.yml -f pr=<number>

# A branch as-is
gh workflow run beta.yml --ref <branch>
```
