# Translations

Status: 458 CE-only keys (449 strings, 9 plurals) translated across 36 languages in six modules; verified by `check-translations.py` in CI. As of 2026-11-25 (`main` at `cbb5abb7f`).

## Start here

- Follow [`.claude/skills/translate-strings/SKILL.md`](../../.claude/skills/translate-strings/SKILL.md) whenever a PR adds, edits, or removes user-facing strings or plurals (the skill lists all 36 supported language codes).
- Put new or edited English CE strings in `<module>/src/main/res/values/strings.xml` and their translations in `<module>/src/main/res/values-<lang>/strings_ce.xml`. Never edit upstream's `values-<lang>/strings.xml` files.
- Six modules currently carry `strings.xml` and `strings_ce.xml`: `core`, `downloads`, `modes/film`, `player/local`, `settings`, and `setup`.
- Before translating a new sentence or updating an existing one, read neighbouring entries in that language's `strings_ce.xml` (and `strings.xml`) so register (form of address) and recurring nouns match (#1021).
- Check completeness fast: `python3 .claude/skills/translate-strings/scripts/check-translations.py` (requires `upstream/main` fetched).

## What it does

Upstream Findroid's translations come from Weblate into `<module>/src/main/res/values-<lang>/strings.xml` during upstream syncs. Because CE adds features and settings that upstream does not have, Ephyra keeps all CE-only translations in separate `strings_ce.xml` files inside each `values-<lang>/` resource directory (#106, #110). Android merges all XML resource files in a `values-<lang>/` folder at build time, so isolating CE strings in `strings_ce.xml` prevents merge conflicts with Weblate commits while keeping code references (`R.string.*`, `R.plurals.*`) unchanged.

All 36 `values-<lang>` folders are maintained even though [`locales_config.xml`](../../core/src/main/res/xml/locales_config.xml) (wired in [`app/phone/src/main/AndroidManifest.xml`](../../app/phone/src/main/AndroidManifest.xml)) only exposes 25 locales in the in-app language picker: Android still selects the other 11 (`ar`, `az`, `ca`, `da`, `et`, `fi`, `hi`, `in`, `ja`, `nn`, `tr`) when the device system language matches (#106).

Branding and variant names stay separated from Weblate keys:
- `app_name` is marked `translatable="false"` in [`core/src/main/res/values/strings.xml`](../../core/src/main/res/values/strings.xml) (`Ephyra`), [`core/src/debug/res/values/strings.xml`](../../core/src/debug/res/values/strings.xml) (`Ephyra Debug`), and [`core/src/staging/res/values/strings.xml`](../../core/src/staging/res/values/strings.xml) (`Ephyra Beta`).
- Where upstream text names "Findroid" or uses wording CE intentionally replaces (for example `ce_welcome`, `ce_welcome_text`, `ce_permission_local_network_rationale`, `ce_delete_download_message`, `ce_continue_watching`), CE adds a `ce_` key in `strings_ce.xml` and points the UI at it, leaving upstream's key intact for Weblate syncs.
- Option label arrays in [`settings/src/main/res/values/string_arrays.xml`](../../settings/src/main/res/values/string_arrays.xml) and [`core/src/main/res/values/string_arrays.xml`](../../core/src/main/res/values/string_arrays.xml) reference `@string/<key>` items, so translating the referenced `<string>` translates the array; technical value arrays carry `translatable="false"` and are never translated.

## Where the code is

| Path | What |
|---|---|
| [`.claude/skills/translate-strings/SKILL.md`](../../.claude/skills/translate-strings/SKILL.md) | Translation procedure, rules, and the canonical list of 36 supported languages |
| [`.claude/skills/translate-strings/scripts/check-translations.py`](../../.claude/skills/translate-strings/scripts/check-translations.py) | Verifies every CE-only `<string>` and `<plurals>` across all 36 languages, `<string>` placeholders (not `<plurals>` placeholders), and CLDR plural quantities |
| [`core/src/main/res/xml/locales_config.xml`](../../core/src/main/res/xml/locales_config.xml) | The 25 locales shown in the in-app language picker (referenced by [`app/phone/src/main/AndroidManifest.xml`](../../app/phone/src/main/AndroidManifest.xml)) |
| [`core/src/main/res/values/strings.xml`](../../core/src/main/res/values/strings.xml), [`core/src/debug/res/values/strings.xml`](../../core/src/debug/res/values/strings.xml), [`core/src/staging/res/values/strings.xml`](../../core/src/staging/res/values/strings.xml) | Core English strings and `app_name` per build type |
| [`settings/src/main/res/values/strings.xml`](../../settings/src/main/res/values/strings.xml), [`settings/src/main/res/values/string_arrays.xml`](../../settings/src/main/res/values/string_arrays.xml), [`core/src/main/res/values/string_arrays.xml`](../../core/src/main/res/values/string_arrays.xml) | Settings strings (largest CE string set) and `@string/` option arrays / `translatable="false"` value arrays |
| [`downloads/src/main/res/values/strings.xml`](../../downloads/src/main/res/values/strings.xml), [`modes/film/src/main/res/values/strings.xml`](../../modes/film/src/main/res/values/strings.xml), [`player/local/src/main/res/values/strings.xml`](../../player/local/src/main/res/values/strings.xml), [`setup/src/main/res/values/strings.xml`](../../setup/src/main/res/values/strings.xml) | English strings for the remaining modules with translations |
| `<module>/src/main/res/values-<lang>/strings_ce.xml` (e.g. [`core/src/main/res/values-nl/strings_ce.xml`](../../core/src/main/res/values-nl/strings_ce.xml)) | CE-only translations across all 36 languages for the 6 modules above |
| [`lint.xml`](../../lint.xml) | Disables `MissingTranslation` globally so gaps in upstream's Weblate files do not fail lint |
| [`.github/workflows/build.yaml`](../../.github/workflows/build.yaml) | Runs the `Verify CE translations` job on PRs and pushes to `main` |
| [`.claude/skills/upstream-sync/SKILL.md`](../../.claude/skills/upstream-sync/SKILL.md) | How `strings.xml` merges during upstream syncs and how duplicate keys in `strings_ce.xml` are resolved |

## Rules

- **Skill is canonical**: follow [`.claude/skills/translate-strings/SKILL.md`](../../.claude/skills/translate-strings/SKILL.md) for the 36 target language codes and translation rules.
- **CE-only `strings_ce.xml` vs Weblate `values-<lang>/strings.xml`**: never edit upstream's `values-<lang>/strings.xml` files. Add or edit English CE strings in `<module>/src/main/res/values/strings.xml` and translate them in `<module>/src/main/res/values-<lang>/strings_ce.xml` across all 36 languages. To change upstream wording, introduce a `ce_<name>` key in `strings_ce.xml` and point the code at it.
- **Match neighbouring register and terminology (#1021)**: before translating a new sentence or revising an existing string, read the neighbouring strings in that language's `strings_ce.xml` and `strings.xml` so the form of address (for example informal vs formal: German `du`/`dein` rather than `Ihr`, Estonian `su`/`oled` rather than `teie`, Traditional Chinese omitting `您的`) and terms for recurring nouns ("device", "file", "limit") match the rest of the file.
- **Placeholders and XML escaping**: keep every format specifier (`%s`, `%d`, `%1$s`, `%2$d`, …) in translated strings; escape `'` as `\'`, `"` as `\"`, and write `&` as `&amp;`.
- **Plurals**: include every required CLDR quantity for each language as enforced by `PLURAL_REQUIRED_QUANTITIES` in [`check-translations.py`](../../.claude/skills/translate-strings/scripts/check-translations.py).
- **String arrays**: translate the `<string>` keys referenced by label arrays; keep value arrays `translatable="false"` with the exact same item count and order as their label array.
- **English changes and upstream sync collisions**: when an English CE string changes, update all 36 `strings_ce.xml` files. When an upstream sync adds a key that already exists in `strings_ce.xml`, delete that key from `strings_ce.xml` across all 36 languages so Weblate's translation takes over; when upstream removes or renames a key Ephyra still uses, preserve it as a `ce_` key in `strings_ce.xml`.
- **CI triggers**: [`.github/workflows/build.yaml`](../../.github/workflows/build.yaml) excludes `docs/**` and `.claude/**` from builds, then re-includes `.claude/skills/translate-strings/**` so changes to the checker or skill still run `Verify CE translations`. `check-guides-follow.py` ignores `/res/values-<lang>/`, while edits to default `values/strings.xml` or `SKILL.md` require updating or explaining the guide.

## How to test it

Fastest real command (compares every translatable `<string>` and `<plurals>` in `res/values/strings.xml` against `upstream/main` and verifies all 36 `strings_ce.xml` files):

```bash
git fetch upstream main --no-tags   # once, or before checking against latest upstream
python3 .claude/skills/translate-strings/scripts/check-translations.py
```

On Windows, run `python` instead of `python3`.

If keys were added, renamed, or removed across modules or `string_arrays.xml`, compile or assemble so Android resource merging catches missing or duplicate resources:

```bash
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
./gradlew :app:phone:assembleLibreDebug
```

**What `check-translations.py` does NOT cover:**
- **Placeholders inside `<plurals>`**: placeholder comparison (`%s`, `%d`, `%1$s`, …) runs only on `<string>` entries; the `<plurals>` check verifies only key presence and required CLDR quantities, so missing or mismatched placeholders in plural items pass unnoticed.
- **Stale translations after English edits**: it checks key existence, not whether the text in `strings_ce.xml` reflects a changed English source string.
- **Tone, register, and terminology consistency**: mismatched pronouns (such as `Ihr` vs `du` in #1021) or inconsistent nouns pass the script unnoticed.
- **UI truncation and RTL layout**: narrow labels (such as the ~128 dp download status column in #113) and right-to-left rendering (`ar`, `iw`) require checking on an emulator or device.
- **`string_arrays.xml` and `translatable="false"` entries**: `check-translations.py` reads only `strings.xml` and ignores `translatable="false"` items, so broken `@string/` references or mismatched array lengths only surface in Gradle resource merging or code review.
- **Unused CE keys and upstream translation gaps**: the script does not flag unreferenced keys, and gaps in upstream's `values-<lang>/strings.xml` are intentionally ignored (`MissingTranslation` in [`lint.xml`](../../lint.xml)).
- **Android Lint MissingQuantity warnings**: `PLURAL_REQUIRED_QUANTITIES` intentionally omits unused forms (`one` in `az`/`tr`, `two` in `iw`, fractional `many` in `cs-rCZ`/`sk`; see [`docs/audit/static-checks.md`](../audit/static-checks.md)).

## Before a PR

```bash
python3 .claude/skills/translate-strings/scripts/check-translations.py
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
python3 .claude/skills/start-work/scripts/check-docs.py
python3 .claude/skills/start-work/scripts/check-guides-follow.py
```

- Verify that every edited English string had all 36 `strings_ce.xml` files updated and that new sentences match neighbouring address forms and terminology.
- For any added or edited `<plurals>` entry, compare placeholders (`%d`, `%s`, …) in each language's `strings_ce.xml` against the English source (`check-translations.py` validates placeholders only on `<string>` entries).
- When layout, wrapping, or directionality is affected, inspect the screen on an emulator in at least one LTR locale (such as `nl`) and one RTL locale (`ar` or `iw`).

## Decisions and history

- [#106](https://github.com/midasvo/ephyra/issues/106), [#110](https://github.com/midasvo/ephyra/pull/110): isolated CE-only strings into `<module>/src/main/res/values-<lang>/strings_ce.xml` across all 36 `values-*` folders (beyond the 25 in `locales_config.xml`), added `.claude/skills/translate-strings/` and `check-translations.py` diffing against `upstream/main` instead of enabling `MissingTranslation`, and wired the CI check into `build.yaml`.
- [#113](https://github.com/midasvo/ephyra/pull/113): shortened `download_time_left_under_minute` and `download_time_left_hours` across all 36 languages so download rows fit within ~128 dp after English was shortened in #111.
- [#553](https://github.com/midasvo/ephyra/pull/553) (closing #399, #400, #401, #402, #403, #414): introduced `player_backend_names` (`translatable="false"`), `ce_` overrides (`ce_media_segments_auto_skip_pip`, `ce_media_segments_auto_skip_always`, `ce_delete_download_message`, `ce_continue_watching`), pluralized number settings (`ce_episodes_count`, `ce_days_count`, `ce_number_no_limit`, `ce_number_off`), RTL arrow mirroring with `TextDirection.Content`, locale-aware `FormatStyle.MEDIUM` dates, and added `modes/film` to `check-translations.py`.
- [#844](https://github.com/midasvo/ephyra/pull/844) and upstream syncs: shared `**/res/values*/strings.xml` take the three-way merge result while Kotlin stays on Ephyra's side; key collisions with upstream are deleted from `strings_ce.xml` (`.claude/skills/upstream-sync/SKILL.md`).
- [#1021](https://github.com/midasvo/ephyra/pull/1021) (refs #1016): updated `ce_pref_player_quality_wifi_summary` and `ce_pref_player_quality_mobile_summary` across all 36 languages, then corrected formal address drift in `de` (`Ihr` → `dein`), `et` (`teie` → `su`), and `zh-rTW` (removing `您的`) to match neighbouring strings.

## Pitfalls

- **Register and terminology drift across languages (#1021).** Translating a string in isolation can introduce formal address (`Ihr`, `teie`, `您的`) or inconsistent nouns ("device", "file", "limit") compared with adjacent rows on the same screen. Always check neighbouring strings in each language's `strings_ce.xml` and `strings.xml`.
- **Changing English without updating `strings_ce.xml`.** `check-translations.py` passes as long as the key exists (and, for `<string>` entries only, with matching placeholders—`<plurals>` placeholders are not checked), so stale translations and broken plural placeholders silently stay behind unless every language file is updated and checked too.
- **Editing upstream's `values-<lang>/strings.xml` directly.** Weblate commits overwrite or conflict with direct edits during upstream syncs; use a `ce_` key in `strings_ce.xml` instead.
- **Duplicate resource failure after an upstream sync.** When upstream adds a key that Ephyra already has in `strings_ce.xml`, AAPT fails on duplicate resources; remove the key from all 36 `strings_ce.xml` files.
- **Translating only the 25 picker languages.** Eleven of the 36 languages are absent from [`locales_config.xml`](../../core/src/main/res/xml/locales_config.xml) (`ar`, `az`, `ca`, `da`, `et`, `fi`, `hi`, `in`, `ja`, `nn`, `tr`), yet Android still loads them from the system locale.
- **Running `check-translations.py` without `upstream/main`.** Without an up-to-date `upstream/main` ref (`git fetch upstream main --no-tags`), CE-only detection against Findroid cannot tell upstream keys from CE keys. Note also that `check-translations.py` still lists `data` in `MODULES`, though `data` currently has no `res/values/strings.xml` and is skipped cleanly.
- **Narrow layouts and RTL clipping (#113, #553).** Translated text in German, Finnish, Portuguese, or Slavic languages is often longer than English; short status fields and RTL screens need visual checks on an emulator.
