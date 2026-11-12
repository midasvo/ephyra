#!/usr/bin/env bash
# Old name of scripts/avds.sh, which now also makes the phone and TV AVDs. Kept so that the
# commands in older docs and notes still work.
exec "$(dirname "$0")/avds.sh" "$@"
