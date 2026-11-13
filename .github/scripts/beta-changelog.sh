#!/usr/bin/env bash
# Writes the "New since vX.Y.Z" section of a beta's release notes.
#
#   beta-changelog.sh <sha> <beta tag> <output file>
#
# Needs a checkout with full history and tags, and GH_TOKEN (or a logged in gh) plus
# GITHUB_REPOSITORY. Lists every pull request merged between the latest stable tag that is an
# ancestor of <sha> and <sha> itself, taken from the first-parent "Merge pull request #N" commits,
# feat first, then fix, then the rest. Never fails the publish: without a stable tag or without
# pull requests it writes a one-line fallback.
set -uo pipefail

sha=$1
beta_tag=$2
out=$3
repo=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}

# Highest vX.Y.Z (no suffix) that is an ancestor of the beta.
stable=$(git tag --merged "$sha" --list 'v[0-9]*' 2>/dev/null \
  | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1)

if [ -z "$stable" ]; then
  echo "No stable release found before this commit, so there is no list of changes." > "$out"
  exit 0
fi

# Titles are data: they only pass through files and quoted variables, never through eval or a
# workflow expression.
rows=$(mktemp)
git log --first-parent --format=%s "${stable}..${sha}" \
  | sed -nE 's/^Merge pull request #([0-9]+) from .*/\1/p' \
  | while read -r n; do
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
      printf '%s\t%s\t%s\n' "$rank" "$n" "$title"
    done > "$rows"

{
  echo "## New since ${stable}"
  echo
  if [ -s "$rows" ]; then
    sort -t$'\t' -k1,1n -k2,2nr "$rows" | while IFS=$'\t' read -r _ n title; do
      echo "- ${title} ([#${n}](https://github.com/${repo}/pull/${n}))"
    done
  else
    echo "No pull requests have been merged since ${stable}."
  fi
  echo
  echo "Full list: https://github.com/${repo}/compare/${stable}...${beta_tag}"
} > "$out"
rm -f "$rows"
