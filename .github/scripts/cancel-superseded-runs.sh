#!/usr/bin/env bash
# Keeps one run per pull request and workflow: the one for the PR's current head commit.
#
# A `concurrency` group with `cancel-in-progress` cancels whichever run is active, without
# looking at its commit. When GitHub starts the run of an older push after the run of a newer
# one, that cancels the check of the head commit. So this compares commits instead:
#   - a run that is not for the PR's head commit cancels itself;
#   - a run that is for the head commit cancels the unfinished runs of older commits.
#
# Needs `actions: write`. A pull request from a fork gets a read-only token: cancelling then
# fails and the run simply continues, which only costs runner time.
#
# Environment: GH_TOKEN, PR_NUMBER, PR_HEAD_SHA, PR_HEAD_REF, PR_HEAD_REPO, and the GITHUB_*
# variables of the runner.
set -euo pipefail

if [ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]; then
  exit 0
fi

current_head=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}" --jq '.head.sha')

if [ "$current_head" != "$PR_HEAD_SHA" ]; then
  echo "This run is for ${PR_HEAD_SHA}, but the pull request is at ${current_head}."
  if gh api -X POST "repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}/cancel" >/dev/null; then
    echo "Cancelled this run."
    # The cancellation stops the job within seconds; don't start the build meanwhile.
    sleep 120
  else
    echo "Could not cancel this run (read-only token?); continuing."
  fi
  exit 0
fi

workflow_id=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}" --jq '.workflow_id')

# The branch filter also matches a same-named branch of another fork, so check the repository.
# Runs of the same commit (a re-run, a reopened PR) are left alone.
gh api --method GET "repos/${GITHUB_REPOSITORY}/actions/workflows/${workflow_id}/runs" \
  -f event=pull_request -f branch="$PR_HEAD_REF" -F per_page=100 \
  --jq '.workflow_runs[]
        | select(.status != "completed")
        | select(.head_sha != env.PR_HEAD_SHA)
        | select(.head_repository.full_name == env.PR_HEAD_REPO)
        | .id' |
  while read -r run_id; do
    if gh api -X POST "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}/cancel" >/dev/null; then
      echo "Cancelled superseded run ${run_id}."
    else
      echo "Could not cancel run ${run_id}; continuing."
    fi
  done
