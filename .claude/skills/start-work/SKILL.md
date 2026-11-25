---
name: start-work
description: >-
  Find where and how to work on a part of Ephyra before changing it: the area guide, the files,
  the ports, the architecture rules, the tests and checks to run, known pitfalls, and a brief for a
  side session that is ready to send. Use this at the start of any task in this repo that names a
  feature, a bug or a part of the app ("I want to work on subtitles", "subtitles jump on mpv", "a
  download doesn't resume", "add a setting for X", "where is the
  login?", "the app starts slowly", "focus jumps away on TV", "how do I set up the test server"), before
  reading code, and whenever someone asks where something is, which rules apply to it or how to
  test it. Also use it to write the brief when handing an issue to another session or agent.
---

# Start work

`docs/areas.yaml` is the index of the app: one entry per area with a summary, keywords, the 3 to
8 paths to open first, and the docs, rules and test commands. It is small: read it whole. An area
has a guide when `docs/areas/<id>.md` exists; the guide holds the rest (ports, pitfalls, test
data, decisions, the checks before a PR). The same files are the development site
(https://midasvo.nl/ephyra/dev/), so what you find here is what a person reads there.

## Steps

1. **Find the area.** Read `docs/areas.yaml` and match the request against each entry's
   `keywords`, `title` and `summary` (keywords are English and include symptoms and class names; match a request in
   another language by its meaning). A request can touch two areas, such as "subtitles of a download"
   (subtitles-and-tracks and downloads): take the area of the behaviour that is wrong first, and
   name the second. Match on the thing more than the verb: "a download doesn't resume" is
   downloads, though "resume" also means resuming playback. If nothing matches, say so and use
   the module list in `docs/architecture/modules.md` instead.
2. **Read the guide**, if `docs/areas/<id>.md` exists. The guides are long: read the "Start here"
   block first, then only the sections the task needs (where the code is, rules, how to test it,
   pitfalls). Without a guide, the entry's `paths`, `docs`, `rules` and `tests` are what there
   is: open the paths and docs, and say that the area has no guide.
3. **Check the map against the tree.** Run
   `python3 .claude/skills/start-work/scripts/check-docs.py` (a second, no build). It finds stale
   paths, names, rule numbers, Gradle tasks and test names in the map, the guides and the other
   entry docs. The code is right where they differ; a problem it reports is a finding for the
   report.
4. **Answer the request** in the shape below. For a bug, look in the guide's pitfalls and
   decisions first: many bugs here have been seen before, and an open issue there is often the
   bug itself. Then search the issues in English words, whatever the request's language
   (`gh issue list --repo midasvo/ephyra --search "<words>" --state all`); the search is loose,
   so read what it returns. When an open issue matches, lead with it: "This is #912." When the
   request is vague ("subtitles jump": in time, on screen, or the track itself?), say
   which readings there are and make reproducing it the first step of the brief.
5. **Write the brief** if the work should go to a side session or agent (see below).

## The answer

Keep it short; link, don't copy, what the guide already says.

```
Area: <title> (<id>) — guide: docs/areas/<id>.md   [or: no guide yet]
Also touches: <other area, if any>

Where: <3–6 paths, most likely first, each with one line on why>
Ports: <from the guide; without a guide, the ports the paths go through>
Rules: <the entry's rules, R-number and one line each (docs/architecture/rules.md)>; plus the
       project rules that apply (mpv checklist for player PRs, translations for strings,
       independent review for the setup flow, …)
Test: <the entry's tests>; on a device: <test data from the guide, or the test-server skill>
Before the PR: <ktfmtFormat, the module tests, :architecture:test, the guide's checklist>
Seen before: <the guide's pitfalls and matching issues, with numbers>
```

## The brief for a side session

Write it so a session with no context can start. It names the issue, the area guide, the files
to start from, what done looks like, and the checks. Use this template:

```
<Issue title> (midasvo/ephyra#<n>, or: no issue yet).

Read first: CLAUDE.md, docs/areas/<id>.md (Start here, then <sections>)<, docs/...>.
   [For an area without a guide: CLAUDE.md, the `<id>` entry of docs/areas.yaml and its docs.]
The problem: <one paragraph: what happens, what should happen, how to reproduce it>.
Start in: <paths>. It goes through <ports>; rules <R-numbers> apply.
Seen before: <pitfalls and issues>.
Done when: <behaviour>, with a unit test that fails before the fix<, and the device check>.
Checks before the PR: <commands>. Branch fix/<n>-<slug> (or feat/, refactor/), conventional
commits, PR title names the issue. <Anything else from CLAUDE.md that applies: the mpv
checklist on both backends, translations, an independent review.>
Report: the PR number, what you checked yourself, and what is still open.
```

## When the map is wrong

If an entry or guide names a path that does not exist, misses the file where the work really
is, or gives a command that fails, fix `docs/areas.yaml` or the guide in the same PR as the work
(or say it in the report). Keep an entry an index: what is more than paths, docs, rules and tests
goes in the guide. Run the checker after moving or renaming code that a doc names, and after
editing the map or a guide; CI runs it on every pull request.

The `Docs` workflow also checks that a pull request which changes a file an area with a guide
lists (its `paths`) changes that guide or its map entry, or says in its description
`Docs: no change needed — <reason>` (`scripts/check-guides-follow.py`; run it locally against
`origin/main` before opening the PR, passing `--body FILE`, `--body-text TEXT`, or `--pr NUMBER`
when using a `Docs: no change needed` line). When you finish the work, say in the report what the guide
missed, and fix it in the same PR.
