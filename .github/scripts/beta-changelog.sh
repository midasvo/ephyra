#!/usr/bin/env bash
# Writes the "New since vX.Y.Z" section of a beta's release notes.
#
#   beta-changelog.sh <sha> <beta tag> <output file> [pull request number]
#
# Needs full history and tags, with <sha> fetched, and GH_TOKEN (or a logged in gh) plus
# GITHUB_REPOSITORY. Lists every pull request merged between the latest stable tag on <sha>'s
# first-parent history and <sha> itself, taken from the "Merge pull request #N" commits, feat
# first, then fix, then the rest, newest merge first within each group. For a beta built from a
# pull request's test merge, pass its number as the fourth argument to list it first.
#
# Without a stable tag it writes a one-line fallback and exits 0. A failing git command exits
# non-zero without writing the notes, so the caller must not publish false ones.
set -euo pipefail

sha=$1
beta_tag=$2
out=$3
pr=${4:-}
repo=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Only Ephyra's own releases count: vX.Y.Z with X >= 2 (v0.x and v1.x are Findroid's), the same
# rule as the release skill's next-version.sh.
git tag --list 'v*' > "$work/all-tags"
{ grep -E '^v([2-9]|[1-9][0-9]+)\.[0-9]+\.[0-9]+$' "$work/all-tags" || true; } | sort -rV > "$work/stable-tags"

# The first-parent history of the beta, newest first. A tag counts only when its commit is on it,
# because the pull requests below are read from that same history.
git rev-list --first-parent "$sha" > "$work/history"

stable=
while read -r t; do
  c=$(git rev-parse --verify --quiet "${t}^{commit}") || continue
  if grep -qxF "$c" "$work/history"; then
    stable=$t
    break
  fi
done < "$work/stable-tags"

if [ -z "$stable" ]; then
  echo "No stable release found before this commit, so there is no list of changes." > "$out"
  exit 0
fi

# The pull request numbers, newest merge first.
git log --first-parent --format=%s "${stable}..${sha}" > "$work/subjects"
{ sed -nE 's/^Merge pull request #([0-9]+) from .*/\1/p' "$work/subjects" || true; } > "$work/numbers"
if [ -n "$pr" ]; then
  case "$pr" in *[!0-9]*) echo "pr must be a number, got '$pr'" >&2; exit 1 ;; esac
  # The test merge of a pull request is a "Merge <sha> into <sha>" commit, not a merged PR.
  { echo "$pr"; grep -vxF "$pr" "$work/numbers" || true; } > "$work/numbers.new"
  mv "$work/numbers.new" "$work/numbers"
fi

# Titles are data: they only pass through files and quoted variables, never through eval or a
# workflow expression. Markdown in a title is escaped so it cannot render a link (the brackets,
# so parentheses stay as they are), an image, HTML or formatting.
seq=0
: > "$work/rows"
while read -r n; do
  seq=$((seq + 1))
  title=$(gh pr view "$n" --repo "$repo" --json title --jq .title 2>/dev/null) || title=""
  [ -n "$title" ] || title="Pull request #$n"
  # Release PRs are the stable release itself; the betas before it already listed their changes.
  case "$title" in "chore(release)"*) continue ;; esac
  case "$title" in
    feat*) rank=1 ;;
    fix*) rank=2 ;;
    *) rank=3 ;;
  esac
  title=${title//$'\n'/ }
  title=${title//$'\r'/}
  title=$(printf '%s' "$title" | sed -E 's/[][\\`*_<>~|&]/\\&/g')
  printf '%s\t%s\t%s\t%s\n' "$rank" "$seq" "$n" "$title" >> "$work/rows"
done < "$work/numbers"

{
  echo "## New since ${stable}"
  echo
  if [ -s "$work/rows" ]; then
    sort -t$'\t' -k1,1n -k2,2n "$work/rows" | while IFS=$'\t' read -r _ _ n title; do
      echo "- ${title} ([#${n}](https://github.com/${repo}/pull/${n}))"
    done
  else
    echo "No pull requests have been merged since ${stable}."
  fi
  echo
  echo "Full list: https://github.com/${repo}/compare/${stable}...${beta_tag}"
} > "$out"
