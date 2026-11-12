# Feature checklist

One list of everything Ephyra does, for the quality audit (#361). This file is #352. The other
sub-issues fill in its cells and refer to its row IDs.

**Build under test:** v2.4.0 (`main` at `79d39f81`), which is 2.3.0 plus #346, #347, #348, #349, #362
and #364. Write the commit and build type (debug, staging,
release) you tested in your issue.

**Filled so far:** the Phone, Tablet and TV columns of every section except Player (#353, #354, #355,
debug build on Android 14 emulators and the Jellyfin demo server), all six columns of the Player
section (#356; TV on `ce_tv_1080p` with a real D-pad device, mpv with software rendering), and the
Phone and TV columns of Upgrade and migration (#358, run on a phone only). Still open: the Tablet
column of Upgrade and migration, and the **[device]** rows (#359).

**Who fills what:**

| Issue | Fills |
|---|---|
| #353 | The Phone column of every section except Player, on `ce_phone` (API 34) |
| #354 | The Tablet column of every section except Player, at the five sizes of `docs/tablet/6-test-matrix.md` |
| #355 | The TV column of every section except Player, D-pad only, on `ce_tv_1080p` and `ce_tv_4k` |
| #356 | The Player section, all six columns |
| #357 | Its own table of API level (29, 30, 33, 36/37, old TV) against the smoke set at the end of this file, using these row IDs |
| #358 | Upgrade and migration |
| #359 | The rows marked **[device]**, on the OPPO phone and the Xiaomi box |

## Legend

Every cell ends as one of these:

- `works`
- `unpolished`, with a few words on what (for example `unpolished: title cut off at 4K`)
- `broken`, with the issue number (`broken #370`)
- `not tested`, with the reason (`not tested: no HDR10+ file`, `not tested: emulator`)

"Not tested" is a valid result. A guess is not.

`n/a` means the feature cannot apply on that form factor or backend. Each `n/a` was checked in the
code, not guessed: downloads and offline mode do not exist on TV (ADR 0007), gestures and PiP do
not exist in the TV player, D-pad behaviour does not apply on a phone, settings marked
phone-only or TV-only in `SettingsViewModel` (`supportedDeviceTypes`) are hidden on the other
variant. An empty cell is work still to do.

- **Phone** is the phone APK on a phone-sized window (412×915 dp, both orientations).
- **Tablet** is the same APK at the tablet sizes (8", 10.5", foldable, 13"). A row that only
  exists on wide (expanded) windows is `n/a` for Phone.
- **TV** is the TV APK (`app/tv`), driven with the remote.
- **[device]** marks a row an emulator cannot show (display output, refresh rate, passthrough,
  Bluetooth, battery). Emulator runs write `not tested: emulator` and leave it to #359.
- **Source** is where the feature comes from: an issue or PR number, a changelog version, or a file.

---

## 1. Setup and login

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SUP-01 | Fresh install opens the Welcome screen; Continue goes to the permission screen (Android 17+) or Servers. The phone's jellyfish logo swims and reacts to tap and drag | #227, `WelcomeScreen` | works | works | works |
| SUP-02 | Add server: servers on the local network are discovered and listed; picking one connects | `AddServerViewModel` | not tested: no Jellyfin server on the emulator's network; #376 fixed: on Android 17 with local network access denied the screen says so, with an "Allow access" button, instead of searching without a word, and searches once access returns | not tested: no Jellyfin server on the emulator's network | not tested: no Jellyfin server on the emulator's network |
| SUP-03 | Add server by hand: a bare host (no scheme, no port) and an address with spaces around it both connect | ce.26, ce.1, `SetupRepositoryImpl` | works | works | works: `AddServerViewModel` trims the address (#404) |
| SUP-04 | Add server by hand: an unreachable or non-Jellyfin address shows an error in the screen, no crash | ce.1 | works (#443 fixed: the error clears as soon as the address changes) | works (#443 fixed in the same screen, tested on the phone) | works; #443 fixed in code, not tested on TV |
| SUP-05 | Adding a server that is already saved, through another address, adds the address and does not create a second server | `SetupRepositoryImpl` | works | broken #425: re-adding the saved server drops the session (401 until restart) | works |
| SUP-06 | Login with username and password lands on Home; the server's login disclaimer, if set, is shown | `LoginViewModel` | works (#375 fixed: line breaks, bold, italic and http(s) links; scripts, other links and server colours are dropped) | works (#375 fixed in the same screen; tested on the phone) | unpolished #423: runs into the overscan; #375 (raw `<br/>`) fixed in code, not tested on TV; #516 fixed: a username with spaces around it signs in |
| SUP-07 | Wrong password shows "wrong username or password", not a generic error | ce.1 | works (#388): a later Login opens empty, without the old password or error | works | works |
| SUP-08 | Quick Connect: the code shows, approving it on the server logs in; pressing it again cancels. The button is hidden when the server has Quick Connect off | ce.1, `LoginViewModel` | works (#382 fixed: a second press clears the code, a third starts a new one) | broken #382: code stays after cancel | unpolished #382: code stays after cancel |
| SUP-09 | Public users of the server are listed on the Users screen; picking one opens Login with the name filled in | `UsersViewModel` | works | works | works |
| SUP-10 | Login on TV with the on-screen keyboard and the remote only | #355 | n/a | n/a | works: Down and Left from the server button go back to the field or button that was left, Username the first time (#557); the keyboard opens on OK on a field, not when focus moves onto it or the screen opens, on Login and Add server |
| SUP-11 | The keyboard does not cover the text field; the screen resizes around it | 1.2.0-ce.0 | works | works | n/a |
| SUP-12 | A second server: add it, switch between the two from the Servers screen, each keeps its own users | `ServersScreen` | not tested: needs own server (second server) | not tested: needs own server (second server) | not tested: needs own server (second server) |
| SUP-13 | Server addresses (long-press a server, Addresses): add an address of the same server, delete one; the address in use cannot be deleted | `ServerAddressesScreen` | works (#390 fixed: removing the address in use and adding one that is already there each say why) | works (#390 fixed in the same screen, tested on the phone); the list is capped at 480 dp like Servers since #434 | n/a |
| SUP-14 | Remove a server (long-press): it disappears, and its logins are signed out on the server (dashboard) | #334 | works | works | works; server-side sign-out not observable (no user signed in) |
| SUP-15 | Remove a user (long-press or the remove button): the session ends on the server; removing the current user returns to setup | #336 | works | works | works |
| SUP-16 | The app's session leaves the server's dashboard when the app goes to the background or is closed, and comes back on return | #336, `ServerSessionKeeper` | works | works | works |
| SUP-17 | Revoked token: sign the device out from the dashboard; the app shows a toast and goes to user selection instead of failing silently | #334 | works (#388): Login from user selection opens empty | works | works |
| SUP-18 | The dashboard lists the client as Ephyra (Ephyra Beta for staging) with the device name | #172 | works | unpolished #386: server sees version 1.2.0 | unpolished #386: server sees version 1.2.0 |
| SUP-19 | Killing the app halfway through setup and starting it again opens the right step (Servers or Users) | `NavigationRoot` | works | works | works |
| SUP-20 | Stable and beta installed side by side: logging in on one does not log out the other | #98 | not tested: debug APK only | not tested: debug APK only | not tested: debug APK only |

## 2. Profiles

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| PRF-01 | The current user's face (profile picture, or initials) shows in the chrome: Home header on phone, the drawer on TV | #248 | works | works | works |
| PRF-02 | Default avatar: a user without a picture gets initials on a colour that stays the same for that user | #248, `EphyraAvatar` | works | not tested: demo user has a picture | not tested: demo user has a picture |
| PRF-03 | Profile sheet (tap the face on Home): current user, server name, the other users, public users, Add user, Settings | #248, `ProfileBottomSheet` | works; after #440 it opens fully in landscape too, with two users the other user shows at once and the sheet scrolls to Add user and Settings; the server sheet opens fully as well | works after #440, tested on the phone in landscape (411 dp tall, shorter than the 8 inch tablet and the fold): the sheet opens fully instead of half-way | n/a |
| PRF-04 | One-tap switch from the profile sheet loads Home with the other user's rows, favourites and progress | #248 | not tested: needs own server (one user) | not tested: needs own server (second user) | n/a |
| PRF-05 | Switch user from the Users screen (Settings → Users; on TV the face in the drawer) | `UsersScreen` | not tested: needs own server (one user) | works | works; #541 fixed: Back from Add user, Switch server or a public user puts focus on it again |
| PRF-06 | "Ask who is watching" on: with more than one saved user, the user list shows once per app start, not again on rotation | #245, `askUserOnStart` | not tested: needs more than one saved user | not tested: needs more than one saved user | not tested: needs more than one saved user |
| PRF-07 | Set a 4 to 6 digit PIN (Settings → Profile lock / Profile PIN); the lock badge shows on the face | #299 | works | works | works |
| PRF-08 | Change the PIN: the new one works, the old one no longer does | #299, #329 | works | works | works |
| PRF-09 | Remove the PIN: no prompt any more | #299 | works | works | works (#408): the drawer face's lock badge goes with Remove PIN and comes with Set PIN, without a restart |
| PRF-10 | Set a pattern of at least 4 dots instead of a PIN; a mismatch on confirm is reported; the pattern replaces the PIN | #330 | works | works | n/a |
| PRF-11 | Lock at start on: a cold start into the locked profile asks the PIN or pattern before Home | #299, `ProfilePinGateScreen` | works | works | works |
| PRF-12 | Wrong PIN or pattern shakes and clears; "Other profile" on the gate goes to the user list | #299 | works | works | works |
| PRF-13 | Switching to a locked profile asks its PIN or pattern; Cancel stays on the current profile | #299, `UsersViewModel` | works | not tested: needs a second profile | works |
| PRF-14 | An unlocked profile stays unlocked until another profile is picked or the process ends (`am kill` locks it again) | `ProfileLock` | works | works | works |
| PRF-15 | The PIN prompt stays usable with the keyboard up | #329 | works | works | n/a |
| PRF-16 | TV PIN pad: digits, delete and OK with the D-pad, the remote's number keys type directly, OK needs at least 4 digits | #299, `TvPinPad` | n/a | n/a | works (#409): a number key moves focus to OK, so OK after the number keys submits |
| PRF-17 | The PIN is stored on the device only: the same user on another device or app is not asked | #299 | not tested: needs a second device | not tested: needs a second device | not tested: needs a second device |

## 3. Home

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| HOM-01 | Suggestions carousel: pages through items, pauses while scrolling, opens the item on tap | #266 | works | works: an item without a backdrop shows its poster blurred, or its title on a plain card, since #433; pages by width class since #438: 2 at 8 inch and fold portrait, 3 on the fold landscape | n/a |
| HOM-02 | TV hero: the focused card's backdrop, logo or title, info line and overview | 1.2.0-ce.5 (#132) | n/a | n/a | works (#410): Continue Watching, Next Up and Latest cards show their overview too |
| HOM-03 | TV hero title: a long title is fitted, not cut off | #346 | n/a | n/a | works |
| HOM-04 | TV hero backdrop for an episode: falls back to the show's, the season's or the episode's picture instead of staying empty | #348 | n/a | n/a | works |
| HOM-05 | Suggestions row (TV) | 1.2.0-ce.5 | n/a | n/a | works |
| HOM-06 | Continue Watching row: the right items, with progress; opens the item | | works | works | works |
| HOM-07 | Next Up row: the next episode of shows in progress | | works | works | works |
| HOM-08 | Latest row per library | | works | works | works |
| HOM-09 | The order and visibility set in Home rows (SET-08) show on Home, and are kept per server and user | #142 | works (#431): Home follows a change made within a minute of its last load, without a pull | works; unpolished #431 at 8 inch: shows only after a pull to refresh | works |
| HOM-10 | Home cache on: a cold start shows the rows of the last visit at once, then updates them | #251, #270 | works | works; not tested at 8 inch: cache and network not told apart | works |
| HOM-11 | Pull to refresh reloads the rows; the spinner stays until the reload is done | ce.1 | works since #391 and #392: one spinner, the pull indicator, on a pull, a cold start and a server pick; the header keeps its width and the server name | works | n/a |
| HOM-12 | TV rows refresh in the background without reshuffling; focus comes back to the same card | #132 | n/a | n/a | works |
| HOM-13 | When one row fails to load, the others still show | 1.2.0-ce.4 (#114) | not tested: cannot make one row fail on the demo server | not tested: cannot make one row fail on the demo server | not tested: cannot make one row fail on the demo server |
| HOM-14 | Home reflects what was just watched after leaving the player (progress, Next Up) | #95 | works since #393: something just watched joins Continue Watching as the first card at the start of the row; a scrolled row stays where it is | works since #393, tested on the phone; not tested at 8 inch | works |
| HOM-15 | Empty Home and a failed load show a message with Retry | #95 | works after #377: the error button opens "Error loading data" with the reason in plain words ("Can't reach the Jellyfin server") and Retry, Share, Close, no stack trace (Share sends it); also My media and Library; empty Home not reachable | not tested: empty Home needs own server | works for a failed load (#405): the reason in plain words and a focused Retry; an empty Home on a server that answers still shows nothing |
| HOM-16 | Home header: server name opens the server switcher with "Manage servers"; Search opens search | `HomeHeader` | works since #394: Back, or the search bar's arrow, from a search opened on Home returns to Home; a search opened on My media stays there; since #543 a server renamed on the server shows its new name after the next Home load (offline the stored name stays) | works | n/a |
| HOM-17 | Long-press on a card copies the item's name | ce.33, `ClipboardUtils` | works | works | n/a |
| HOM-18 | Home does not open partly scrolled when the carousel loads last | #95 | works | works | n/a |
| HOM-19 | Moving along a TV row: the hero backdrop changes without waiting on the network | #155 | n/a | n/a | works |
| HOM-20 | OK on an episode card on TV opens its season with the episode selected | #130 | n/a | n/a | works |

## 4. Browsing

Libraries, search, favourites and the item pages.

### Navigation

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| BRW-01 | Bottom bar with Home, Favorites, Media and Downloads; on an expanded window a navigation rail instead | #188, #288 | works | works | n/a |
| BRW-02 | TV drawer: face, Search, Home, Favorites, one entry per library, Settings; Back goes Home, then opens the drawer, then leaves | ADR 0002, `MainScreen` | n/a | n/a | works (#411): after a library, Back and Left on Home open the drawer on Home; #533 fixed: Back from Settings or the face puts focus on that drawer item, also after Home's refresh; #513 fixed: after a start without the server, Retry on Home brings the libraries back into the drawer |
| BRW-03 | TV: switching drawer destinations does not shift the layout or make focus jump | #190 | n/a | n/a | works |
| BRW-04 | TV: Back from a page puts focus on the card that opened it (library, show, season) | #124, ADR 0003 | n/a | n/a | works |
| BRW-05 | Screen transitions and the predictive back preview animate without a snap at the end | #234, 1.1.0-ce.4 | not tested: a snap is not visible in screenshots | not tested: a snap is not visible in screenshots | n/a |

### Libraries, collections, search and favourites

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| BRW-06 | Media tab shows the libraries as a grid (TV: the libraries are in the drawer) | `MediaScreen` | works | works | n/a |
| BRW-07 | Library grid: first-load spinner, keeps loading pages while scrolling a large library | #265 | not tested: small demo libraries, no paging | works; paging not tested (small libraries) | works; paging not tested (small library) |
| BRW-08 | Sort: each sort option and ascending/descending give the expected order, and the choice is kept | `SortByDialog` | works | works | works |
| BRW-09 | Hide watched filter: hides watched items in every library, is kept, and "Show watched" appears when everything is hidden | ce.33, #95 | works | works | works |
| BRW-10 | TV library: A–Z jump bar when sorted by name, position counter, year under each title, a title card for items without artwork | #135 | n/a | n/a | works |
| BRW-11 | Collections (box sets) open and list their items; an episode in a collection opens in its season on TV | #129 | not tested: no collections on the demo server | not tested: no collections on the demo server | not tested: no collections on the demo server |
| BRW-12 | A folder library can be browsed folder by folder | `navigateToItem` | not tested: no folder library on the demo server | not tested: no folder library on the demo server | not tested: no folder library on the demo server |
| BRW-13 | Search: results appear after typing stops, "No results" does not flash while typing, a failed search shows Retry | 1.2.0-ce.4, #95 | works | works; failed search not tested at 8 inch | works (#417 fixed: a failed search says why in plain words, with Retry) |
| BRW-14 | TV search with the on-screen keyboard; results grouped as movies, shows, episodes, collections | #128 | n/a | n/a | works |
| BRW-15 | Ctrl+F on a hardware keyboard opens search on the Media tab | #310 | works | works | n/a |
| BRW-16 | Favorites tab lists favourite movies and shows; an empty state when there are none | #188 | works | works | works |
| BRW-17 | Downloaded items show the downloaded badge in search, favourites and the Home rows | ce.1 | works; search and favourites not checked | works; search and favourites not checked; not tested at 8 inch | n/a |

### Item pages

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| BRW-18 | Movie page: Play or Resume, restart from the beginning (phone and tablet; the TV movie page has none), trailer, watched, favourite | `ItemButtonsBar`, TV `MovieScreen` | works | works | works: Resume, Restart and the minutes left on a partly watched movie, Play otherwise (#412); no stray spinner over a loaded page (#544); a cut overview takes focus above the buttons and opens in full on OK (#545); trailer not tested (no YouTube handler) |
| BRW-19 | Version picker: an item with more than one version asks which one to play, for Play and for play from the beginning | #28, #93 | not tested: no multi-version item on the demo server | not tested: no multi-version item on the demo server | not tested: no multi-version item on the demo server |
| BRW-20 | Trailer: one plays or opens; several give a menu; hidden when offline | `ItemButtonsBar` | works | works | not tested: emulator has no YouTube handler |
| BRW-21 | Show page: seasons, Play, trailer, watched, favourite | `ShowScreen` | works | works | works: a cut overview takes focus above the buttons and opens in full on OK (#545; seen at font scale 1.3, no overview on the test server is cut at 1.0) |
| BRW-22 | Show page Shuffle starts a random episode, specials left out | #141 | n/a | n/a | works; no specials to leave out |
| BRW-23 | Season page: episodes with titles on two lines, progress, a play button on the artwork; TV adds season chips and the selected episode's details | #94, #130 | works | works | works; a season without episodes says Nothing here yet and keeps its season chips (#505); the selected episode's cut overview takes focus between the chips and the buttons and opens in full on OK (#545) |
| BRW-24 | Episode page with play, restart, watched, favourite and links to its season and show (TV: the episode inside its season page) | `EpisodeScreen`, #130 | works: the show and the season in the top bar since #395 | works: the show and the season in the top bar since #395, also in portrait; works at 8 inch and fold | works |
| BRW-25 | Person page lists the person's movies and shows, which open | #136 | works | works: the page starts below the back and home buttons since #436 | works: opens at the top with name and biography (#407); the whole first row (card, title, year) fits under the header, also at font scale 1.3, and a cut biography opens in full with OK (#508) |
| BRW-26 | Cast and crew row (actors with roles, directors, writers; an actor with several roles is one card) opens the person | #64, #136 | works; a card without a photo shows the person icon since #396 | works | works: Down from the buttons reaches the cast row also while it is below the screen, and the title stays on screen along the buttons (#514) |
| BRW-27 | Critic score, tagline, studios or networks | #332 | works | works | works: a long Writers list wraps onto its own line and Studios stays readable (#413) |
| BRW-28 | Air schedule of a running show ("Airs Thu 21:00", "Airs daily") | #332 | not tested: no running show on the demo server | not tested: no running show on the demo server | not tested: no running show on the demo server |
| BRW-29 | Watch count and last played date | #332 | works | works | works |
| BRW-30 | Links to IMDb, TMDB, TVDB and Trakt open in the browser; hidden offline | #332 | works | works | n/a |
| BRW-31 | Extras row: an extra plays as a single video | #332 | not tested: no extras on the demo server | not tested: no extras on the demo server | not tested: no extras on the demo server |
| BRW-32 | Collections row and "More like this" row open the item | #332 | works; collections row not tested (none on the demo server) | works; collections row not tested (none on the demo server) | works; collections row not tested (none on the demo server) |
| BRW-33 | Media chips: resolution, codec, HDR, Dolby Vision, HDR10+ wordmark, Atmos, 3D, with the right values for the file | #282, #332 | works; no HDR, Dolby Vision or Atmos file | works; no HDR, Dolby Vision or Atmos file | works: an undetermined audio language ("und") is left out of the chip (#415); no HDR, Dolby Vision or Atmos file |
| BRW-34 | Watched toggle updates the page and the server (check another client) | | works: the watched button fills with tertiary since #397 | works | works |
| BRW-35 | Favourite toggle updates the page, the Favorites tab and the server | | works | works | works |
| BRW-36 | Long-press on title, overview, genres, people and similar text copies it | ce.33 (#42) | works | works | n/a |
| BRW-37 | A page whose load fails (server off mid-load) shows an error with Retry instead of spinning | #95 | works after #377: its details dialog gives the reason in plain words with Retry, no stack trace | works; not tested at 8 inch | works |
| BRW-38 | Movie, show, season and episode pages show new progress after leaving the player | #95 | works | works; not tested at 8 inch (player) | works |
| BRW-39 | Status-bar icons stay readable over the backdrop | #291, #297 | works | works | n/a |
| BRW-40 | A failing image moves on to the next one; a season without a poster uses the show's | #94 | not tested: no failing image to provoke | works for person cards: the person icon without a photo since #396; season fallback not checked | works: a season without a poster shows the show's, a show without any keeps the placeholder (#416); failing image not provoked |
| BRW-41 | Dates follow the app language; premiere dates are not a day early in timezones west of UTC | #141, ce.1 | works; timezone not tested | works; timezone not tested | works (#414): four-digit year, "Jan 18, 1940" |
| BRW-42 | Theme music on the show page (setting on): plays softly, fades out on leaving, on Play and when the screensaver starts, stays quiet when another app plays audio | #141 | n/a | n/a | not tested: no theme songs on the demo server |
| BRW-43 | The Bioluminescence look: Ephyra palette, Outfit for titles, violet for favourite, watched and rating, pill buttons; TV pages fade to the surface colour and the focus ring is cyan | #224 to #242 | works | works | works (#418 fixed: the sort dialog's options and order buttons show the focus ring; #540 fixed: so do Retry, the licence text, the About buttons and the login buttons) |

### Wide windows

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| BRW-44 | Movie, show and episode pages use the hero layout: poster beside the text over a full-width backdrop | #291 | n/a | works without a backdrop since #433 (the poster blurred, or a shorter hero without any image); unpolished #432: landscape crop 3.6:1 (3.2:1 at 8 inch, 2.6:1 on the fold) | n/a |
| BRW-45 | Title and text of the hero sit under the backdrop, readable over bright artwork | #339 | n/a | works | n/a |
| BRW-46 | Season as list and detail side by side; rotating keeps the selected episode and the scroll position | #297 | n/a | works: rotation keeps the selected episode, the list position and the top bar's background since #435 (from an episode link see #517); detail pane works at 1024, 900 and 841 dp since #429: the still goes above the text and every button is whole | n/a |
| BRW-47 | Rows, grids and search use wider cards and more columns; text columns are capped at a readable width | #290 | n/a | works: carousel pages follow the width class since #438; search docked since #439: the field stays at most 720 dp at the start with the rail kept, results full width beside it | n/a |
| BRW-48 | Tab on a hardware keyboard reaches every control on Home, Media and an item page | #310, test matrix | works after #398 and #442: on Home Tab goes header, bottom bar, the carousel page on screen (never a peeking one; Left and Right page the carousel, it stands still while focused), then the row arrows and cards, each with a 2 dp cyan ring, light and dark, none after touch; item page: Tab stops on the 11 controls before the cast (was 22), not on the texts or the hidden bottom bar | Home and item page fixed with #398 and #442, tested on the phone (same components; the carousel's focusable pages follow the 1, 2 or 3 pages on screen, unit tested, not run at tablet sizes); My media works since #430: Tab goes rail, search bar, Movies, Shows at 1280, 800 and 1024 dp, Enter or typing opens the search, Esc closes it | n/a |

## 5. Downloads

Downloads exist on phone and tablet only: the TV app has no download UI and hides every
download setting (ADR 0007, `SettingsViewModel`).

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| DLD-01 | Download a movie: the quality dialog offers Original and bitrates from 4K 120 Mbps to 360p; the file arrives and plays | 1.2.0-ce.0 (#75) | works; tested on an episode (no movie offers Download) | not tested: no Download on movies (CanDownload false) | n/a |
| DLD-02 | Download one episode from its page or the season list | | works after #373: with CanDownload false the episode rows and the show page offer no Download, a downloaded episode keeps Remove; download allowed only in unit tests | works | n/a |
| DLD-03 | Download a season or a whole show: the season dialog shows what is already downloaded per season | `SeasonSelectionDialog` | works | works | n/a |
| DLD-04 | A partly downloaded season or show offers both download (the rest) and delete | #94 | works | works | n/a |
| DLD-05 | A lower-quality download shows progress against the converted size, not the original's | #76 | works | not tested: fixed with #444, not run again on a tablet | n/a |
| DLD-06 | The download goes to the storage location from Settings, without asking; an SD card works too **[device]** | `downloadStorageIndex` | not tested: emulator | not tested: emulator | n/a |
| DLD-07 | The progress ring on the item page; tapping it stops the download | 1.2.0-ce.1 | works | works | n/a |
| DLD-08 | The "downloads in progress" notification shows, and downloads carry on with the screen off or the app in the background **[device]** | `DownloadPumpService` | works on the emulator (#560): the notification shows while downloads run, says "Waiting…" or "Waiting for network" while they only wait, and goes when the queue is done; downloads that failed into a retry keep the service and carry on in the background; screen off and a real device not tested | not tested: emulator | n/a |
| DLD-09 | No more downloads run at once than "Maximum concurrent downloads" | `DownloadQueue` | works | not tested: only failing transcodes were queued | n/a |
| DLD-10 | A failed download shows the reason and a retry icon, retries by itself, and keeps its chosen quality | #82, #94 | works after #383: an HTTP 404 shows "The server refused the download (HTTP 404)" with Retry at once (Downloads row, item card; season row shows Retry), no auto-retry, also after a restart; lost network still says "Waiting for network" and resumes; 5xx keeps the auto-retries (unit tests only); after #530 a download queued or started in airplane mode says "Waiting for network" instead of "Unknown error" and starts by itself when the network returns; since #558 the item card says "Waiting for network" too instead of "Paused" | unpolished #383: only "Waiting...", no reason or retry icon | n/a |
| DLD-11 | A download in progress survives `am kill` and an app restart and resumes where it was | ce.33 (#26) | works | broken #426: a cancelled download comes back after a restart | n/a |
| DLD-12 | With mobile data off, a download on mobile data waits and says "Waiting for network" **[device]** | 1.2.0-ce.1 | not tested: emulator | not tested: emulator | n/a |
| DLD-13 | Subtitles are saved as separate files, image-based ones (PGS, VobSub) included, and show when the download plays offline | #60, #75 | not tested: no subtitles on the demo episodes | not tested: no subtitles on the demo episodes | n/a |
| DLD-14 | Downloads screen: storage summary bar (movies, shows, other, free), red under 10 % free, tap opens the download settings | 1.2.0-ce.1, #246 | works; red under 10 % not reached | works; red under 10 % not reached | n/a |
| DLD-15 | Downloading section: one status line per row (size, time left), the header shows count and speed, the estimate settles | #111, 1.2.0-ce.1 | works; since #546 the header counts failed downloads apart: "Downloading · 2 · 3 failed", or "3 failed" when nothing else is left | works: rows capped at 720 dp at the start since #434; speed and time left shown; the #546 header checked at 1280 dp in a Robolectric test, not on a tablet | n/a |
| DLD-16 | A finished download gets a check mark, a haptic tick, and moves into Movies or TV Shows | 1.2.0-ce.1 | works; haptic not observable | works; haptic not observable | n/a |
| DLD-17 | Sort by recently downloaded, name and size | 1.2.0-ce.1 | not tested: only one download | not tested: only one download | n/a |
| DLD-18 | Multi-select ("Select" or long-press), remove all selected; Back leaves selection | 1.2.0-ce.1 | works; Remove not run | works; Remove not run | n/a |
| DLD-19 | The play button on a movie or episode's artwork resumes from the downloaded copy | 1.2.0-ce.1 | not tested: player rows are #356 | works | n/a |
| DLD-20 | Downloaded show page: episodes by season with sizes, swipe to remove, "Remove all downloads" | #81, #84 | works | works: the list is capped at 720 dp since #434 | n/a |
| DLD-21 | Cancel or remove on the Downloads screen shows Undo; undoing a cancel loses no progress; the removal finishes if the screen is left | #82, #84, #94 | works | broken #426: Undo does not restore the row, cancelled downloads come back | n/a |
| DLD-22 | Delete from an item page asks to confirm; one tap on an episode's check mark removes its download at once, with an Undo snackbar | #94 | works | not tested: the download was kept for the offline rows | n/a |
| DLD-23 | Empty Downloads screen explains what goes there and links to the library | 1.2.0-ce.1 | works | not tested: the download was kept for the offline rows | n/a |
| DLD-24 | Smart Downloads on: after watching an episode, the set number of next unwatched episodes are downloaded, across seasons | #116, #119 | not tested: needs an episode watched to the end | not tested: needs the player and working transcodes | n/a |
| DLD-25 | Smart Downloads removes the episodes it downloaded itself once watched, and leaves the ones downloaded by hand | #116, #119 | not tested: needs an episode watched to the end | not tested: needs the player and working transcodes | n/a |
| DLD-26 | A Dolby Vision file is downloaded as a transcode when "Transcode Dolby Vision" is on, and plays offline | ce.30, #60 | not tested: no Dolby Vision file | not tested: no Dolby Vision file | n/a |
| DLD-27 | Downloads screen in two columns on an expanded window | #288 | n/a | works: the storage summary and the Downloading section span the grid capped at 720 dp, at the start, since #434 | n/a |

## 6. Offline mode

Phone and tablet only, except OFF-12: TV has no offline mode (ADR 0007).

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| OFF-01 | Offline mode on (Settings): the app restarts, shows only Home and Downloads, and an "Offline" label | `SettingsViewModel`, `NavigationRoot` | works | works | n/a |
| OFF-02 | Starting the app with no network turns offline mode on by itself, without a crash | ce.27, ce.28 | works | works | n/a |
| OFF-03 | Turning the network off while the app is open: the app stays usable and says what failed, no crash | #353 | works since #392 (server cut off with a proxy, not the network): a failed refresh shows the error card under the header, whose details give the reason in words with Retry, Share and Close; the header keeps its width | broken #427: My media blank, stays blank when the network returns | n/a |
| OFF-04 | Offline Home and item pages show the downloaded items | | works | works since the #428 fix (8 inch, airplane mode and the Offline Mode setting): Home shows only the downloads; with nothing downloaded it shows "Nothing downloaded yet", pointing to Downloads (10.5 inch at phone size, airplane mode), instead of the Latest rows remembered from the last online start, which could not be opened. The cancelled downloads of #426 (fixed in #471) were not retested here | n/a |
| OFF-05 | A downloaded item plays offline, and its position is kept locally | | works | works | n/a |
| OFF-06 | Back online: the position, watched state and favourites changed offline reach the server (at the next app start with network) | `SyncWorker` | works; watched and favourites not tried | works | n/a |
| OFF-07 | Online with the server down or slower than 3 s: a downloaded movie or episode still opens and plays from the local copy | #281 | not tested: cannot make the demo server slow or down | not tested: cannot make the demo server slow or down | n/a |
| OFF-08 | Offline, the quality button, trailers and external links are hidden | `PlayerActivity`, #332 | works; the player's quality button is #356 | not tested: player (#356), links not checked | n/a |
| OFF-09 | Turning offline mode off brings back all tabs and the server's data | | works | works | n/a |
| OFF-10 | Downloads keep working across a switch between online and offline | ce.29 | works | not tested: no download progressing (transcode 404, #444) | n/a |
| OFF-11 | A download started online keeps its state when the app goes offline and back | ce.29 | works | not tested: no download progressing (transcode 404, #444) | n/a |
| OFF-12 | TV started without network: no crash, a message or an empty screen instead of a hang | `RepositoryModule` | n/a | n/a | not tested: the emulator's Ethernet cannot be switched off |

## 7. Settings

One row per preference. A row passes when the setting takes effect and survives an app restart.
Where the effect is in the player, the Player row is named; the settings row checks that the value
is shown, saved and kept.

### General

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-01 | Every setting changed below is still set after `am force-stop` and a new start | | works | works | works |
| SET-02 | TV settings: one list layout on every page, a details pane for selects, numbers changed with steps (+1, −1, +10, −10) | #347, 31b087ae | n/a | n/a | works: Left and Back from the details pane return to the row that opened it (#419); ms values step by 1 and 10 s (#420) |
| SET-03 | Settings that make no sense on TV (gestures, PiP, downloads, theme) are not shown on TV | #133 | n/a | n/a | works; the mpv options page has no empty "Advanced" heading any more (#421) |

### Offline and language

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-04 | Offline mode (see OFF-01) | `offlineMode` | works | works | n/a |
| SET-05 | App language (Android 13+, disabled below): the app switches language. TV renders no row for this setting | `PreferenceAppLanguage` | works | works | n/a |
| SET-06 | Preferred audio language (player effect: PLY-36) | `preferredAudioLanguage` | works | works | works |
| SET-07 | Preferred subtitle language (player effect: PLY-37) | `preferredSubtitleLanguage` | works | works | works |

### Interface

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-08 | Home rows: show or hide each row and move it up or down; Home follows (HOM-09) | #142 | works | works: capped at 640 dp since #434 | works |
| SET-09 | Theme: System, Light, Dark; applies at once | `theme` | works | works | n/a |
| SET-10 | Dynamic colours (Android 12+, off by default): wallpaper colours replace the Ephyra palette | #225 | works | works | n/a |
| SET-11 | Theme music on the show page (off by default; effect: BRW-42) | #141 | n/a | n/a | works; effect not testable (no theme songs) |
| SET-12 | Max days in Next Up: with a limit, older shows leave the Next Up row | #114 | not tested: one show only, effect not provable | works for the value; effect not checked | works for the value; effect not provable |
| SET-13 | Watch Next row on the Android TV home screen (on by default; effect: TVS-01) | #138 | n/a | n/a | works |
| SET-14 | Show the Favorites tab: off hides the tab (phone) or drawer entry (TV) | #188 | works | works | works |

### Interface → Detail pages

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-15 | Critic rating on or off | #332 | works | not tested: the item checked has no critic score | works |
| SET-16 | Tagline on or off | #332 | works | not tested: the item checked has no tagline | works |
| SET-17 | Studios and networks on or off | #332 | works | works | works |
| SET-18 | Air schedule on or off | #332 | not tested: no running show on the demo server | not tested: no running show on the demo server | not tested: no running show on the demo server |
| SET-19 | Watch history on or off | #332 | works | works | works |
| SET-20 | External links on or off | #332 | works | works | n/a |
| SET-21 | Cast and crew on or off | #104, #332 | works | works | works |
| SET-22 | Media info: the info chip at the end of the badge row on a movie or episode page opens a sheet with the file and its video, audio and subtitle tracks. Replaced the Extra Info setting (#510) | `MediaInfoSheet` | works (#510, API 36 emulator) | not run since #510 | n/a: phone pages only |
| SET-23 | Extras row on or off | #332 | not tested: no extras on the demo server | not tested: no extras on the demo server | not tested: no extras on the demo server |
| SET-24 | Collections row on or off | #332 | not tested: no collections on the demo server | not tested: no collections on the demo server | not tested: no collections on the demo server |
| SET-25 | More like this row on or off | #332 | works | works | works |

### Player → Subtitle style

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-26 | Subtitle style screen with a live preview (phone); a plain list on TV | #24, `SubtitleStyleScreen` | works | works: the preview is capped at 640 dp like its list since #434 | works |
| SET-27 | Text colour (player effect: PLY-44) | #24 | works | works | works |
| SET-28 | Background colour | #24 | works | works | works |
| SET-29 | Edge type (outline, shadow, none...) | #24 | works | works | works |
| SET-30 | Edge colour | #24 | works | works | works |
| SET-31 | Font | #24 | works | works | works |
| SET-32 | Font size, kept within its limits when a value out of range is entered | #24, ce.1 | works | works | works |
| SET-33 | Override embedded styles (off by default; effect: PLY-45) | #300 | works | works | works |

### Player

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-34 | Player backend: ExoPlayer or mpv; the next playback uses it | `playerBackend` | works (#399): "ExoPlayer" and "mpv" | works | works (#399): "ExoPlayer" and "mpv" |
| SET-35 | Direct play Dolby Vision (on by default; ExoPlayer only; effect: PLY-116, PLY-118) | #96 | works | works | works |
| SET-36 | Match display refresh rate (TV, off by default; effect: PLY-123) | #139 | n/a | n/a | works |
| SET-37 | mpv: hardware decoding | `playerMpvHwdec` | works | works | works |
| SET-38 | mpv: video output | `playerMpvVo` | works | works | works |
| SET-39 | mpv: audio output, `audiotrack` by default | #52, `playerMpvAo` | works | works | works |
| SET-40 | mpv.conf editor: open, edit, save; Back with changes asks to discard. Not rendered on TV | ce.34, `SettingsFileEditScreen` | works | works | n/a |
| SET-41 | input.conf editor, the same | ce.34 | works | not tested: same editor, not opened | n/a |
| SET-42 | Player gestures (master switch; the gesture rows below depend on it) | `playerGestures` | works | works | n/a |
| SET-43 | Volume and brightness gestures | `playerGesturesVB` | works | works | n/a |
| SET-44 | Seek gesture | `playerGesturesSeek` | works | works | n/a |
| SET-45 | Zoom gesture | `playerGesturesZoom` | works | works | n/a |
| SET-46 | Long-press action: Chapter, 2× speed or Disabled | #50 | works | works | n/a |
| SET-47 | Remember brightness (needs the volume and brightness gestures) | `playerGesturesBrightnessRemember` | works | works | n/a |
| SET-48 | Start maximized | `playerGesturesStartMaximized` | works | works | n/a |
| SET-49 | Seek back increment (ms) | `playerSeekBackInc` | works | works | n/a |
| SET-50 | Seek forward increment (ms) | `playerSeekForwardInc` | works | works | n/a |
| SET-51 | TV seek back step | #126 | n/a | n/a | works |
| SET-52 | TV seek forward step | #126 | n/a | n/a | works |
| SET-53 | OK pauses (TV) | #126 | n/a | n/a | works |
| SET-54 | Chapter markers on the seek bar | `playerChapterMarkers` | works | works | works |
| SET-55 | Media segments: Intro action (Skip, Ask, Ignore) | #271 | works | works | works |
| SET-56 | Media segments: Outro action | #271 | works | works | works |
| SET-57 | Media segments: Recap action | #271 | works | works | works |
| SET-58 | Media segments: Preview action | #271 | works | works | works |
| SET-59 | Media segments: Commercial action | #271 | works | works | works |
| SET-60 | Skip button duration (seconds) | #271 | works | works | works |
| SET-61 | Auto-skip: always or only in picture-in-picture | #271 | works (#399): "Only in picture-in-picture" and "Always" | works (#399): same labels as the phone, checked on the phone | n/a |
| SET-62 | Next episode threshold (ms) | `playerMediaSegmentsNextEpisodeThreshold` | works | works | works: steps of 1 and 10 s, never below 0 (#420) |
| SET-63 | Trickplay | #134 | works | works | works |
| SET-64 | Trickplay while seeking with the gesture (needs Trickplay) | `playerGesturesSeekTrickplay` | works | works | n/a |
| SET-65 | Play next episode automatically: server setting, always, never | #66 | works | works | works |
| SET-66 | Still watching prompt on or off (off by default) | #349 | works | works | works |
| SET-67 | Still watching: after N episodes (needs the prompt on) | #22 | works | unpolished: row stays enabled while the prompt is off | works |
| SET-68 | Still watching: after N minutes without input | #22 | works; no upper bound (960 accepted) | unpolished: row stays enabled while the prompt is off | works |
| SET-69 | Still watching: prompt timeout (seconds) | #22 | works; since #559 a value under 5 s is saved and shown as 5 s, what the player waits anyway | unpolished: row stays enabled while the prompt is off | works |
| SET-70 | Picture-in-picture gesture (go Home to enter PiP) | `playerPipGesture` | works | works | n/a |
| SET-71 | Background playback (audio carries on in the background) | #36 | works | works | n/a |

### Users, servers, downloads, network, cache, permissions

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-72 | Users opens the user list (PRF-05) | | works | works | works |
| SET-73 | Ask who is watching on start (effect: PRF-06) | #245 | not tested: needs more than one user | works for the switch; effect not tested (one user) | works for the toggle; effect not tested (one user) |
| SET-74 | Profile lock (phone) / Profile PIN (TV) opens the lock settings: set, change, remove, lock at start | #299, #330 | works | works: capped at 640 dp since #434 | works |
| SET-75 | Servers opens the server list (SUP-12) | | works | works | works |
| SET-76 | Download over mobile data | `downloadOverMobileData` | works | works | n/a |
| SET-77 | Download when roaming (needs mobile data on) | `downloadWhenRoaming` | works | works | n/a |
| SET-78 | Storage location: internal storage and each SD card, with free space | `PreferenceDynamicSelect` | works | works | n/a |
| SET-79 | Maximum concurrent downloads | `maxConcurrentDownloads` | works | works | n/a |
| SET-80 | Smart Downloads | #116 | works | works | n/a |
| SET-81 | Episodes to keep ready (at least 1) | #116 | works (#400): "1 episode", checked on the shared still-watching row | works (#400): same code as the phone, checked on the phone | n/a |
| SET-82 | Transcode Dolby Vision downloads | ce.30 | works | works | n/a |
| SET-83 | Request timeout | `requestTimeout` | not tested: effect not observable (value kept) | works | works: steps of 1 and 10 s, never below 0 (#420) |
| SET-84 | Connect timeout | `connectTimeout` | not tested: effect not observable (value kept) | works | works: steps of 1 and 10 s, never below 0 (#420) |
| SET-85 | Socket timeout | `socketTimeout` | not tested: effect not observable (value kept) | works | works: steps of 1 and 10 s, never below 0 (#420) |
| SET-86 | Image cache on or off | `imageCache` | not tested: effect not observable (value kept) | works | works |
| SET-87 | Image cache size (MB; TV defaults to 100) | #155 | works | works | works |
| SET-88 | Load artwork at display size (on by default) | #254, #294 | not tested: effect not observable (value kept) | works | works |
| SET-89 | Remember Home rows (Home cache) | #251, #270 | works | works | works |
| SET-90 | Clear cache empties the image caches and the remembered Home rows, with a toast | #270 | works | works | works |
| SET-91 | Permissions section (Android 17+ only): local network permission state and request | 1.2.0-ce.0 | works on Android 17 in the version smoke run (#357): Denied, then Granted after allowing "Nearby devices" in App info; the card is unchanged by #376 | not tested: Android 14 | not tested: Android 14 |

### About

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| SET-92 | About shows the app version and install, server and device information | #223, #211 | works | works | works |
| SET-93 | Licences list and each licence's text (TV scrolls with the D-pad) | #211, #364 | works (#384 fixed: a tap opens the licence text in a dialog) | unpolished: "View license" opens the browser, not the text | works (#424 fixed: Back from a licence puts focus on the library that was opened; #540 fixed: the licence text shows the cyan ring and ends inside the safe area) |
| SET-94 | Export device profile: share or copy the JSON | ce.31 (#23) | works | broken #385: "Could not build device profile" | n/a |
| SET-95 | Device profile screen: what the device can decode | #211 | n/a | n/a | works |
| SET-96 | Report a problem and source code: links on phone, a QR code on TV | #223 | works | works | works |

## 8. Player

Each row has a cell per form factor and backend. mpv crashes host-GPU emulators: run mpv with
software rendering or on a device, and say which in the cell. Load test media over http, not
`file://`. `docs/mpv-checklist.md` and `docs/track-test-series.md` describe several rows in more
detail.

### Playback

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-01 | Resume from Continue Watching: starts at the saved position, no spinner after the first frame, buttons enabled | mpv-checklist, #303 | works | works | works | works | works: resumed from the episode page and a Watch Next intent (the demo server has no Continue Watching) | works: resumed through a Watch Next intent |
| PLY-02 | Play from 0 from the item page, and restart from the beginning on an item with progress | mpv-checklist | works | works | works | works | works: the movie page's Resume starts at the position and Restart at 0:00 (#412) | works: from 0 through an intent; the movie page's Restart (#412) not tested on mpv |
| PLY-03 | Play and pause; the button shows the right state, including after loading and after an item change | #305 | works | works; stays paused after going back to Original | as phone: works | as phone: works; stays paused after going back to Original | works; also while buffering (#486): the button shows Pause and pauses, and stays paused when the stream is back | works; also while buffering (#486): as ExoPlayer |
| PLY-04 | Seek bar: phone drags and lands where dropped; TV focuses the bar, Left/Right pick a time, OK commits | mpv-checklist, `TvSeekBar` | works | works | works | works | works | not tested: only the hidden-controls seek was used on mpv |
| PLY-05 | Skip back and forward buttons use the seek increments | mpv-checklist | works | works | as phone: works | as phone: works | n/a | n/a |
| PLY-06 | Previous and next on an episode; the server sees the previous episode stop (dashboard, Trakt) | #65 | works | works | works | as phone: works | works | works |
| PLY-07 | The last episode of a season continues into the next season; specials do not roll into season 1 | #29, #92 | not tested: one season, no specials on the demo server | not tested: one season, no specials on the demo server | as phone: not tested: one season, no specials on the demo server | as phone: not tested: one season, no specials on the demo server | not tested: one season, no specials on the demo server | not tested: one season, no specials on the demo server |
| PLY-08 | Next or previous to an episode with a saved position: write down where it starts (the code starts queued episodes at 0) | `PlaylistManager` | works: starts at 0 | not tested: needs a like-for-like repro | works: starts at 0 | works: starts at 0 | works: starts at 0 | works: Previous back to the session's first item resumed at its original start; Next into a saved position not tried |
| PLY-09 | Play on a show starts the next-up episode, or the first episode | `PlaylistManager` | works: next-up episode at its saved position | not tested: not run on mpv | as phone: works: next-up episode at its saved position | as phone: not tested: not run on mpv | works: first unplayed episode | not tested: not run on mpv |
| PLY-10 | Shuffle from the TV show page plays random episodes without specials | #141 | n/a | n/a | n/a | n/a | works: random episodes of the season; no specials on the demo server | not tested: not run on mpv |
| PLY-11 | Version picker: the chosen version plays | #28 | not tested: no multi-version item on the demo server | not tested: no multi-version item on the demo server | as phone: not tested: no multi-version item on the demo server | as phone: not tested: no multi-version item on the demo server | not tested: no multi-version item on the demo server | not tested: no multi-version item on the demo server |
| PLY-12 | Speed dialog, 0.5× to 2×; speed applies and audio stays in sync | `SpeedSelectionDialogFragment` | works; audio sync not audible | works | as phone: works; audio sync not audible | as phone: works | works; audio sync not audible (no audio output on the emulator) | works |
| PLY-13 | Quality dialog (HD): a cap below the file's bitrate reloads at the same position and keeps playing; Original goes back to direct play; the choice is remembered | #61, mpv-checklist | works | works | as phone: works | as phone: works | n/a | n/a |
| PLY-14 | Slow link (emulator at 3G): the state settles, no buffering and ready flapping | mpv-checklist | not tested: no 3G throttling done | not tested: no 3G throttling done | as phone: not tested: no 3G throttling done | as phone: not tested: no 3G throttling done | not tested: the emulator can only starve the link (1 kbit/s), see PLY-127 | not tested: the emulator can only starve the link (1 kbit/s); the flapping logged then followed seeks |
| PLY-15 | Chapters dialog with thumbnails seeks to the chapter; the button only shows when there are chapters | ce.31 (#25) | not tested: no chapters on the demo server | not tested: no chapters on the demo server | as phone: not tested: no chapters on the demo server | as phone: not tested: no chapters on the demo server | not tested: no chapters on the demo server | not tested: no chapters on the demo server |
| PLY-16 | Chapter markers on the seek bar | ce.31 | not tested: no chapters on the demo server | not tested: no chapters on the demo server | as phone: not tested: no chapters on the demo server | as phone: not tested: no chapters on the demo server | not tested: no chapters on the demo server | not tested: no chapters on the demo server |
| PLY-17 | Trickplay thumbnails while dragging the seek bar (phone) or seeking with Left/Right (TV); on TV they don't cover "Ends at" | #134 | works | works | as phone: works | as phone: works | unpolished #464: on the seek bar the thumbnail covers the episode title near the start | works with the controls hidden; the seek bar thumbnail not looked at |
| PLY-18 | Extras and local trailers play as a single video | #332, #129 | not tested: no extra or local trailer played | not tested: no extra or local trailer played | as phone: not tested: no extra or local trailer played | as phone: not tested: no extra or local trailer played | not tested: no extra or local trailer on the demo items | not tested: no extra or local trailer on the demo items |
| PLY-19 | A downloaded item plays from the local copy | 1.2.0-ce.1 | works | works | broken #454: a pending download plays from the unfinished file (black screen) | not tested: the stuck downloads were cancelled before the mpv pass | n/a | n/a |
| PLY-20 | Back out during loading and while playing: no crash, the stop is reported with the right position | #318, mpv-checklist | works (#461): Back during loading reports the stop at the start position, a resume position is kept; while playing unchanged | works (#461, software rendering): Back during loading reports the stop at the start position, a resume position is kept; while playing unchanged | works | as phone: works | works (#461): Back during loading sends one stop at the start position; while playing the stop has the right position | works (#461): as TV Exo |
| PLY-21 | Progress reaches the server while playing (dashboard shows the position); after `am force-stop` mid-episode the resume position is close to where it was | `PlayerViewModel` | works | works | as phone: works | as phone: works | works (#458): progress every 5 s and at a pause; after a force-stop it resumes within a second of the pause | works (#458): as TV Exo |
| PLY-22 | At stop: past 90 % marks watched, under 10 % resets the position, in between saves it | `JellyfinRepositoryImpl` | works | works | as phone: works | as phone: works | works | not tested: thresholds only checked on ExoPlayer |
| PLY-23 | Media session: `dumpsys media_session` shows PLAYING, a position and the right title; the queue is there | #304, mpv-checklist | works | works | works | works | works | works |
| PLY-24 | A file the backend cannot play shows an error (phone: message, TV: dialog with a way out) instead of buffering forever | #306, #131 | works: a stream that cannot be opened (connection refused) shows the message; no file the backend cannot play | works (#446): a stream that cannot be opened shows the message, 10 of 10; no file the backend cannot play | as phone: not tested: no file the backend cannot play | works (#446): a stream that cannot be opened shows the message, 10 of 10; no file the backend cannot play | works: dialog with Exit focused (dead proxy) | works: same dialog |
| PLY-25 | The screen stays on while playing |  | works | not tested: not run on mpv (same activity flag) | as phone: works | as phone: not tested: not run on mpv (same activity flag) | works: KEEP_SCREEN_ON while playing and paused | not tested: not checked on mpv (same screen flag) |
| PLY-26 | Time to the first frame from pressing Play: write down the seconds | #267, #296 | works: 6.7 s, 2.6 s, 2.8 s | works: 1.9 s, about 0.5 s local, under 1 s | works: 2.7 s, 13.1 s after a cold boot | works: 2.0 s | works: 3.7 s, 3.2 s, about 3 s | works: 3.0 s warm, 6 s cold after a crash |
| PLY-27 | Title line shows the series, S1:E2 and the episode name |  | works (#452): the series name above "S1:E6 · Crazy Handful of Nothin'"; a long name ellipsizes before the buttons | works (#452): as phone, checked in a phone-sized window on the tablet | works (#452): as phone; at 636 dp the title keeps 40% of the row and the buttons scroll ("S1:E1 · Und Au…", was "S1...") | works (#452): as tablet Exo | works | works |
| PLY-28 | TV overlay: hides after a few seconds while playing, stays while paused or a dialog is open | #124 | n/a | n/a | n/a | n/a | works | works |
| PLY-29 | TV clock and "Ends at" (follows the speed) | #124 | n/a | n/a | n/a | n/a | works | works |
| PLY-30 | TV "Up next" queue row (Down from the buttons) plays the picked episode | #131 | n/a | n/a | n/a | n/a | works | works |
| PLY-31 | The player closes when playback ends after the last episode, or at the end with autoplay off | 1.2.0-ce.0 | not tested: last episode not played to the end | works | as phone: not tested: last episode not played to the end | as phone: works | works | works |
| PLY-32 | mpv: video does not freeze after a seek, an audio track switch, the next episode, or screen off and on | #52 | n/a | works (software rendering) | n/a | works (software rendering) | n/a | works (software rendering) for a seek and the next episode; track switch and screen off not tested |

### Tracks and subtitles

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-33 | Audio track dialog lists the tracks and switches |  | works (#451): "AAC 1.0" for an untagged track, "English Stereo · AAC 1.0" and "Dutch 5.1 · AC3" on Tracks Movie, the same names as mpv | works (#451): as phone Exo, checked on the tablet | works (#451): as phone; switching to Dutch works | works (#451): as tablet Exo, switching works | not tested: the same function as phone (#451, unit tests), no TV device run | not tested: as TV Exo |
| PLY-34 | Subtitle track dialog lists the tracks and None, and switches |  | not tested: no subtitles on the demo server | not tested: no subtitles on the demo server | as phone: not tested: no subtitles on the demo server | as phone: not tested: no subtitles on the demo server | not tested: no subtitles on the demo server | not tested: no subtitles on the demo server |
| PLY-35 | The selected option in player dialogs is tinted with the accent | #244 | works | works | as phone: works | as phone: works | works | works |
| PLY-36 | Preferred audio language is picked at start | `preferredAudioLanguage` | not tested: no multi-audio item on the demo server | not tested: no multi-audio item on the demo server | as phone: not tested: no multi-audio item on the demo server | as phone: not tested: no multi-audio item on the demo server | not tested: no multi-audio item on the demo server | not tested: no multi-audio item on the demo server |
| PLY-37 | Preferred subtitle language is picked at start | `preferredSubtitleLanguage` | not tested: no subtitled item on the demo server | not tested: no subtitled item on the demo server | as phone: not tested: no subtitled item on the demo server | as phone: not tested: no subtitled item on the demo server | not tested: no subtitled item on the demo server | not tested: no subtitled item on the demo server |
| PLY-38 | Per-series track choice carries over on auto-advance (Track Test E01 to E04) | #115, track-test-series | not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) |
| PLY-39 | Per-series choice when starting an episode by hand, and "subtitles off" is remembered | #115, #120 | not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) |
| PLY-40 | The choice stays within its series (Other Show) and within the user | #120, track-test-series | not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) |
| PLY-41 | Per-series choice with downloaded episodes | #120 | not tested: needs the Track Test series (own server) | not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | as phone: not tested: needs the Track Test series (own server) | n/a | n/a |
| PLY-42 | External SRT and WebVTT subtitles show at the right time | ce.1 | not tested: no external subtitles on the demo server | not tested: no external subtitles on the demo server | as phone: not tested: no external subtitles on the demo server | as phone: not tested: no external subtitles on the demo server | not tested: no external subtitles on the demo server | not tested: no external subtitles on the demo server |
| PLY-43 | PGS, VobSub and DVB subtitles play without a transcode | #19 | not tested: needs own server | not tested: needs own server | as phone: not tested: needs own server | as phone: not tested: needs own server | not tested: needs own server | not tested: needs own server |
| PLY-44 | Subtitle style (colour, edge, background, font, size) applies to text subtitles | #24, #308 | not tested: no subtitled item on the demo server | not tested: no subtitled item on the demo server | as phone: not tested: no subtitled item on the demo server | as phone: not tested: no subtitled item on the demo server | not tested: no subtitled item on the demo server | not tested: no subtitled item on the demo server |
| PLY-45 | Override embedded styles: off keeps the ASS track's own look, on applies the app's style; `{\an8}` stays at the top | #300, #308 | not tested: needs own server | not tested: needs own server | as phone: not tested: needs own server | as phone: not tested: needs own server | not tested: needs own server | not tested: needs own server |
| PLY-46 | A subtitle style change while a video is open (for example from PiP) applies: write down whether at once or on the next start | `MpvSubtitleOptions` | not tested: no subtitled item on the demo server | not tested: no subtitled item on the demo server | as phone: not tested: no subtitled item on the demo server | as phone: not tested: no subtitled item on the demo server | n/a | n/a |
| PLY-47 | A direct stream with burned-in (transcoded) subtitles | #356 | not tested: needs own server | not tested: needs own server | as phone: not tested: needs own server | as phone: not tested: needs own server | not tested: needs own server | not tested: needs own server |

### Gestures, lock and orientation

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-48 | Single tap shows and hides the controls | `PlayerGestureHelper` | works | works | works | works | n/a | n/a |
| PLY-49 | Double tap: left rewinds, middle plays or pauses, right forwards | `PlayerGestureHelper` | works | works | works | works | n/a | n/a |
| PLY-50 | Brightness swipe (left) | | works | works | works | works | n/a | n/a |
| PLY-51 | Volume swipe (right) | | works | works | works | works | n/a | n/a |
| PLY-52 | Remember brightness: the next playback starts at the last brightness; leaving the player restores the system brightness | | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | n/a | n/a |
| PLY-53 | Horizontal seek swipe with the time and a trickplay thumbnail; seeks on release | | works | works | works | works | n/a | n/a |
| PLY-54 | Pinch to zoom (crop to fill) and back; mpv moves subtitles into the margins | | not tested: needs two-finger input | not tested: needs two-finger input | works | not tested: no time for the pinch on mpv | n/a | n/a |
| PLY-55 | Start maximized opens zoomed | | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | n/a | n/a |
| PLY-56 | Long-press Chapter: left goes to the previous chapter, right to the next | #50 | not tested: no chapters on the demo server | not tested: no chapters on the demo server | as phone: not tested: no chapters on the demo server | as phone: not tested: no chapters on the demo server | n/a | n/a |
| PLY-57 | Long-press 2× speed while held | #50 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | n/a | n/a |
| PLY-58 | On an expanded window, volume and brightness swipes only work within 240 dp of an edge | #289 | n/a | n/a | works | works | n/a | n/a |
| PLY-59 | Swipes do not fire in the system gesture area, also in split-screen and freeform | #55, #91 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | n/a | n/a |
| PLY-60 | Lock: blocks gestures and controls, keeps the orientation, unlock button works | `PlayerActivity` | works | works | works | works | n/a | n/a |
| PLY-61 | Orientation: phone-sized windows play in landscape; tablets keep the orientation they are held in | #289 | works | works | works | works | n/a | n/a |
| PLY-62 | Controls in a narrow window (split-screen): Next and Back stay visible, the rest scrolls | #101 | not tested: not reached in time | not tested: not reached in time | unpolished #452: title cut to "S1..."; since #455 the controls stay clear of the status bar and the taskbar (7 of 7 split entries, 2 of them with the bars over the window) | unpolished #452: title cut to "S1...", white status-bar strip in light theme; since #455 the controls stay clear of the bars (5 of 5 split entries, 1 with the bars over the window). Software rendering: mpv sometimes hangs in `surfaceChanged` on a split resize, on `main` too |
| PLY-63 | Rotating during playback keeps position and play state | | works | works | works | works | n/a | n/a |

### Keys

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-64 | Volume keys during playback change the volume, no crash (Android 10 and 11 especially) | #350 | works | not tested: no volume keys sent | works (keyboard Up/Down) | works (keyboard Up/Down) | works | not tested: no volume keys sent |
| PLY-65 | Media keys (play/pause, play, pause, next, previous, fast forward, rewind) via `adb shell input keyevent` | #310, `TvPlayerKeyHandler` | works | works | as phone: works | as phone: works | works; fast forward and rewind not tried | works; fast forward and rewind not tried |
| PLY-66 | Bluetooth headset: play/pause, next, volume, disconnect mid-playback pauses **[device]** | #350, #359 | not tested: Bluetooth, device row | not tested: Bluetooth, device row | as phone: not tested: Bluetooth, device row | as phone: not tested: Bluetooth, device row | not tested: Bluetooth, device row | not tested: Bluetooth, device row |
| PLY-67 | Hardware keyboard: Space plays and pauses, Left/Right seek, Up/Down volume, M mutes, L locks, Esc goes back | #310 | works | works; arrows, M not run on mpv (same key code) | broken #448: Space can close the player | broken #448: Space can close the player | n/a | n/a |
| PLY-68 | Hardware keyboard: Tab focuses the controls, then arrows and Space act on the focused one | #310 | works | works | unpolished #449: focus faint or invisible | not tested: same code as ExoPlayer | n/a | n/a |
| PLY-69 | Mouse: moving shows the controls, the wheel seeks | #310 | not tested: no mouse on the emulator | not tested: no mouse on the emulator | not tested: no mouse on the emulator | not tested: no mouse on the emulator | n/a | n/a |
| PLY-70 | TV, controls hidden: Left/Right seek at once with an indicator; holding speeds up | #124, `TvPlayerKeyHandler` | n/a | n/a | n/a | n/a | works | works |
| PLY-71 | TV: OK shows the controls; with "OK pauses" on it also pauses | #126 | n/a | n/a | n/a | n/a | works (#459): 150 ms presses, "OK pauses" off 0 of 10 paused, on 10 of 10 stayed paused | works (#459): as TV Exo |
| PLY-72 | TV: Back hides the controls (closes the queue row first), then leaves the player | #124 | n/a | n/a | n/a | n/a | works; Back with the queue open not tried | works; Back with the queue open not tried |
| PLY-73 | TV remote keys Stop (leaves), Captions (subtitle dialog), Audio track (audio dialog) | `TvPlayerKeyHandler` | n/a | n/a | n/a | n/a | works | works |
| PLY-74 | TV: the skip button and the Up next card take focus while the controls are hidden; OK presses, Back cancels the card | #271, #272 | n/a | n/a | n/a | n/a | works | works for the Up next card; the skip button not tried |

### Media segments

Use a debug build with `files/fake_segments.txt` where the server has no segments (`DebugSegments.kt`).

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-75 | Skip automatically: the intro is skipped without input | #20, #271 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | works | not tested: not run on mpv |
| PLY-76 | Ask: the skip button shows for the set duration after the segment starts; after that only with the controls (phone) or not at all (TV) | #271, #283 | works (fake segments) | works (fake segments) | as phone: works (fake segments) | as phone: works (fake segments) | works (fake segments); after its window it shows above the controls, as the code intends | not tested: not run on mpv |
| PLY-77 | Ignore: no button, no skip | #271 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | works | works |
| PLY-78 | Outro, recap, preview and commercial each follow their own setting | #271 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | works for intro, outro and recap; preview and commercial not tried | not tested: not run on mpv |
| PLY-79 | Seeking back into a segment shows the skip button again | `SegmentTracker` | works | not tested: not run on mpv | as phone: works | as phone: not tested: not run on mpv | works | not tested: not run on mpv |
| PLY-80 | An outro ending within the threshold of the end shows "Next episode" and goes to the next episode | #65, `SegmentTracker` | not tested: the Up next card took over | works for the last episode | as phone: not tested: the Up next card took over | as phone: works for the last episode | works; "Next episode" not pressed, the Up next card took over | works: the Up next card moved to the next episode |
| PLY-81 | Skipping an outro past 90 % marks the episode watched | #65 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | not tested: watched state after an outro skip not checked | not tested: not run on mpv |
| PLY-82 | Auto-skip "only in PiP": in PiP the segment is skipped, outside PiP it asks | #271 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | n/a | n/a |
| PLY-83 | The skip button is hidden in PiP and while the Up next card shows | `SegmentTracker` | works | works | as phone: works | as phone: works | works | works |

### Sleep timer

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-84 | Set 15 minutes (or 1 minute in a debug build): playback pauses on time and the controls show | #298 | works | works | as phone: works | as phone: works | works | works |
| PLY-85 | "End of episode": pauses at the end instead of going to the next episode | #298 | works | not tested: not run on mpv | as phone: works | as phone: not tested: not run on mpv | works | not tested: not run on mpv |
| PLY-86 | The timer button shows that it runs and the time left; Off cancels it | #298 | works; Off not pressed | not tested: not run on mpv | as phone: works; Off not pressed | as phone: not tested: not run on mpv | works | works; Off not pressed |
| PLY-87 | After the timer fired, autoplay waits until Play is pressed | #298 | works | not tested: not run on mpv | as phone: works | as phone: not tested: not run on mpv | works | not tested: not run on mpv |

### Up next, autoplay and still watching

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-88 | Up next card with a countdown 15 s before the end of an episode | #272 | works | works | works | works | works | works |
| PLY-89 | Up next card at the start of the outro when that comes first | #272 | works (fake outro) | works (fake outro) | as phone: works (fake outro) | as phone: works (fake outro) | works (fake outro) | works (fake outro) |
| PLY-90 | Play now starts the next episode; Cancel stops at the end of this one | #272 | works for Cancel; Play now not pressed | not tested: the countdown ran out | works for Play now | as phone: not tested: the countdown ran out | works for Cancel; Play now not pressed | works for Cancel; Play now not pressed |
| PLY-91 | The countdown stops while paused and follows the playback speed | #272 | not tested: not reached in time | works; speed not tested | as phone: not tested: not reached in time | as phone: works; speed not tested | works; speed not tested | not tested: not run on mpv |
| PLY-92 | Autoplay "server setting" follows the Jellyfin user's "Play next episode automatically" | #66 | works | works | works | works | works | works |
| PLY-93 | Autoplay "always" and "never" override it; with never, the episode is marked watched and the player closes | #66 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | not tested: not reached in time | not tested: not reached in time |
| PLY-94 | Offline, "server setting" keeps autoplaying | #66 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | n/a | n/a |
| PLY-95 | A short episode at the end of autoplay does not stall | #238 | not tested: no short episodes on the demo server | not tested: no short episodes on the demo server | as phone: not tested: no short episodes on the demo server | as phone: not tested: no short episodes on the demo server | not tested: no short episodes on the demo server | not tested: no short episodes on the demo server |
| PLY-96 | Still watching on: after the set number of auto-advanced episodes the prompt shows | #22, #349 | works (#367 run, after 1 episode) | works (#367 run, after 1 episode) | as phone: not tested: only the minutes path was run | as phone: not tested: only the minutes path was run | not tested: only the minutes path was run | not tested: only the minutes path was run |
| PLY-97 | Still watching: after the set minutes without input the prompt shows; seeking counts as input | #22, #70 | works | not tested: backend-independent, run on ExoPlayer | works | as phone: not tested: backend-independent, run on ExoPlayer | works: the prompt takes the next auto-advance, not mid-episode, as on phone | works: the prompt takes the next auto-advance, not mid-episode, as on phone |
| PLY-98 | Still watching: Continue goes on; the timeout leaves it paused | #22 | works (#367: the countdown keeps the time left after Home and back; #477: with background audio on, a return after the prompt fired does not prompt again and stays paused after the timeout) | works (#367: the countdown keeps the time left after Home and back; #477: with background audio on, a return after the prompt fired does not prompt again and stays paused after the timeout) | as phone: broken #367: countdown restarts after the app returns | as phone: not tested: phone UI code, run on ExoPlayer | works (#477: a prompt that fired while the app was away now shows the time left on return, not the full timeout; Continue, Stop and the timeout run) | works (#477: a prompt that fired while the app was away now shows the time left on return, not the full timeout; Continue, Stop and the timeout run) |
| PLY-99 | Still watching off (the default): no prompt, whatever the thresholds | #349 | works | not tested: not run on mpv | as phone: works | as phone: not tested: not run on mpv | works | not tested: not run on mpv |

### Picture-in-picture and background audio

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-100 | PiP button: the video goes into a PiP window and keeps playing | | works | works | works | works | n/a | n/a |
| PLY-101 | PiP gesture on: going Home while playing enters PiP | `playerPipGesture` | works (#480: also follows the setting changed with the player open, in PiP or in the background) | works (#480: also follows the setting changed with the player in PiP; in the background run on ExoPlayer only) | broken #447: Home enters PiP after one use of the PiP button | broken #447: Home enters PiP after one use of the PiP button | n/a | n/a |
| PLY-102 | In PiP the controls, skip button and Up next card are hidden; zoom is off and comes back after | `PlayerActivity` | works; zoom not tested | works | works | works | n/a | n/a |
| PLY-103 | Closing the PiP window stops playback (with background audio on, audio goes on) | #79 | works | works | as phone: works | as phone: works | n/a | n/a |
| PLY-104 | Background audio on: audio goes on with the screen off and the app in the background; lock-screen artwork and controls work | #36, #80 | works; lock screen not checked | works (#460): audio goes on with the screen off; lock screen not checked | as phone: works; lock screen not checked | works (#460): audio goes on with the screen off; lock screen not checked | n/a | n/a |
| PLY-105 | Background audio: swiping the app away keeps the audio; the notification brings the player back | #118 | works | not tested: background audio off for the mpv pass | as phone: works | as phone: not tested: background audio off for the mpv pass | n/a | n/a |
| PLY-106 | Background audio off: leaving the app pauses; coming back resumes at the same position | `BasePlayerActivity` | works | works | works | as phone: works | n/a | n/a |

### Formats

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-107 | H.264 direct play | #356 | works | works | as phone: works | as phone: works | works | works |
| PLY-108 | HEVC direct play | #356 | not tested: needs own server | not tested: needs own server | as phone: not tested: needs own server | as phone: not tested: needs own server | not tested: needs own server | not tested: needs own server |
| PLY-109 | AV1 | #356 | not tested: needs own server | not tested: needs own server | as phone: not tested: needs own server | as phone: not tested: needs own server | not tested: needs own server | not tested: needs own server |
| PLY-110 | HDR10 shows as HDR on an HDR display; brightness is normal after leaving the player **[device]** | #359 | not tested: emulator, needs own server | not tested: emulator, needs own server | as phone: not tested: emulator, needs own server | as phone: not tested: emulator, needs own server | not tested: emulator, needs own server | not tested: emulator, needs own server |
| PLY-111 | HDR10+ **[device]** | #282, #359 | not tested: emulator | not tested: emulator | as phone: not tested: emulator | as phone: not tested: emulator | not tested: emulator, needs own server | not tested: emulator, needs own server |
| PLY-112 | A transcode (HLS), for example with a quality cap | #61 | works | works | as phone: works | as phone: works | not tested: needs own server (no quality dialog on TV to force a transcode) | not tested: needs own server (no quality dialog on TV to force a transcode) |
| PLY-113 | Atmos (E-AC-3 JOC) on a phone: plays with sound, no crash | #59, #97 | not tested: needs own server | not tested: needs own server | as phone: not tested: needs own server | as phone: not tested: needs own server | n/a | n/a |
| PLY-114 | Atmos passthrough to a soundbar or receiver **[device]** | #59, #359 | n/a | n/a | n/a | n/a | not tested: emulator | not tested: emulator |
| PLY-115 | Audio decoder fallback: when the platform decoder fails, playback goes on with ffmpeg at the same position | #97, #99 | not tested: not run | n/a | as phone: not tested: not run | n/a | not tested: no failing platform decoder on the emulator | n/a |
| PLY-116 | Dolby Vision direct play on a capable device **[device]** | #96, #100 | not tested: emulator | n/a | as phone: not tested: emulator | n/a | not tested: emulator | n/a |
| PLY-117 | Dolby Vision on mpv (always direct-played) **[device]** | `DeviceProfileBuilder` | n/a | not tested: emulator | n/a | as phone: not tested: emulator | n/a | not tested: emulator |
| PLY-118 | Dolby Vision with "Direct play Dolby Vision" off, or on a device without DV: transcoded, no green and purple picture | #96, ce.30 | not tested: needs own server | n/a | as phone: not tested: needs own server | n/a | not tested: needs own server | n/a |
| PLY-119 | Display refresh rate vote: `dumpsys SurfaceFlinger` shows a 23.98 Hz vote for a 24 fps video | #338, mpv-checklist | works | works | as phone: works | as phone: works | works: 29.97 Hz vote for the 29.97 fps movie | works: 23.98 Hz vote |
| PLY-120 | 24 fps playback on a 120 Hz phone runs at a matching rate, not 120 Hz **[device]** | #338, #359 | not tested: emulator | not tested: emulator | n/a | n/a | n/a | n/a |
| PLY-121 | Battery and heat over a 30-minute episode **[device]** | #359 | not tested: emulator | not tested: emulator | n/a | n/a | n/a | n/a |
| PLY-122 | Downloaded file playback (offline and online) | #356 | works | works | as phone: works | as phone: works | n/a | n/a |
| PLY-123 | Match display refresh rate on TV switches the display mode to the video's frame rate, and back after **[device]** | #139 | n/a | n/a | n/a | n/a | not tested: emulator (one display mode) | not tested: emulator (one display mode) |

### Interruptions and lifecycle

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-124 | Audio focus loss (incoming call, another app plays audio) pauses; write down whether it resumes after | `MPVPlayer`, `PlayerViewModel` | works | works | as phone: works | as phone: works | not tested: no other audio app or call on the TV emulator | not tested: no other audio app or call on the TV emulator |
| PLY-125 | Unplugging headphones pauses | 1.2.0-ce.4 (#114) | not tested: no headphones on the emulator | not tested: no headphones on the emulator | as phone: not tested: no headphones on the emulator | as phone: not tested: no headphones on the emulator | not tested: no headphones on the emulator | not tested: no headphones on the emulator |
| PLY-126 | Screen off and on during playback | #52 | works | works (#460: with background audio off it could hang the app before) | as phone: works | works (#460: as phone) | not tested: screen off not tried | not tested: screen off not tried |
| PLY-127 | Network lost mid-playback, then back: what happens, and does it recover | #356 | works (#450): at the break "Connection lost. Playback continues when the network is back." with a spinner; plays on at the same position within about 5 s of the network returning (airplane mode for 2.5 min); a pause while waiting stays paused; leaving while waiting loads nothing; with the server unreachable it retries every 15 s and gives up after 10 min with the old error | works (#474): as Phone Exo: the waiting state at the break over the last frame; plays on at the break position 1 to 2 s after the network returns (direct play and a 720 kbps HLS transcode); Play while still offline tries again and keeps waiting; a pause or a seek while waiting is kept; audio, subtitle and speed survive the reload. Tested in a phone-sized window (2400x1080, 420 dpi) on the tablet emulator, not on a phone. Left: a 6 s drop on an HLS transcode still ends in the waiting state instead of playing through (ffmpeg skips the segments). Since #460 a stalled stream picks up again 35 to 45 s after the network returns (was over 70 s) | works (#450): as phone; a 10 s drop never stops playback | works (#474): as Phone mpv; leaving while waiting (Back, Back during the reload, Home and back, rotate, PiP) 3 times each: no hang, the stop at the break position. After Home or a rotation the picture can be black until playback resumes | unpolished #463: "Source error" dialog without Retry; since #450 the player loads again by itself and the dialog closes when playback is back (not checked on a TV device) | broken #460: on a starved link the picture freezes with no buffering or error and does not recover |
| PLY-128 | TV Home button during playback pauses; returning resumes | `TvPlayerScreen` | n/a | n/a | n/a | n/a | works (#462): playing before Home plays again on return; paused before returns paused with the controls up and Play focused; a media play/pause at Home is kept | works (#462): as TV Exo |
| PLY-129 | Process death in the player with a dialog open (tracks, speed, chapters, still watching): no crash on restore | ce.1 | works | works without a dialog; with a dialog not tested | as phone: works | as phone: works without a dialog; with a dialog not tested | n/a | n/a |
| PLY-130 | Starting another item while the player is open (new intent, Watch Next) replaces playback cleanly | #187, #231 | not tested: not reached in time | not tested: not reached in time | as phone: not tested: not reached in time | as phone: not tested: not reached in time | works: a Watch Next intent replaced the movie cleanly | not tested: not run on mpv |

### Wide windows and foldables

| ID | Feature: what to do, and what "works" means | Source | Phone Exo | Phone mpv | Tablet Exo | Tablet mpv | TV Exo | TV mpv |
|---|---|---|---|---|---|---|---|---|
| PLY-131 | Roomier controls on an expanded window; they resize when the window does | #301, #313 | n/a | n/a | works | works | n/a | n/a |
| PLY-132 | Episode side sheet: lists the season's episodes, switches episode without leaving the player; Back closes it | #314 | n/a | n/a | works since #456 and #523: the top-row buttons under the sheet, the seek row, the skip button and the transport buttons the sheet covers or cuts fade out while it is open (checked at 1024, 841, 1280 and 1600 dp wide) | works since #456 and #523: the same controls fade out (1280, 1024 and 841 dp, software rendering) | n/a | n/a |
| PLY-133 | Tabletop posture on a foldable: video above the fold, controls below | #313 | n/a | n/a | works | not tested: fold pass on ExoPlayer only | n/a | n/a |
| PLY-134 | Playback survives a fold, an unfold and a window resize | test matrix | n/a | n/a | works for split-screen, a resize and rotation (#455: the controls stay clear of the bars); fold and unfold not tested since #455: no foldable AVD, the unfold fix (hide the bars again on a configuration change) is from the code only | works for a resize; fold and unfold not tested, the #455 unfold fix is from the code only. Software rendering: mpv sometimes hangs in `surfaceChanged` on a split resize, on `main` too |

## 9. TV system integration

All rows are TV only.

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| TVS-01 | Watch Next row on the Android TV home screen shows Continue Watching and Next Up items | #138 | n/a | n/a | works |
| TVS-02 | Picking a Watch Next program resumes it in the player, with its page underneath; Back goes to the page, then Home | #138 | n/a | n/a | not tested: the demo server's Continue Watching and Next Up went empty |
| TVS-03 | Starting a Watch Next program while the player is open does not crash | #187 | n/a | n/a | not tested: the demo server's Continue Watching and Next Up went empty |
| TVS-04 | A program removed from the row stays away until it is watched again | #138 | n/a | n/a | not tested: the demo server's Continue Watching and Next Up went empty |
| TVS-05 | The row updates after watching something | #138 | n/a | n/a | not tested: the demo server's Continue Watching and Next Up went empty |
| TVS-06 | Watch Next setting off: the app's programs leave the row | #138 | n/a | n/a | works |
| TVS-07 | After a user switch, a program of the other user does not play as the current user | `WatchNextLink` | n/a | n/a | not tested: needs own server (one user) |
| TVS-08 | A locked profile asks its PIN before a Watch Next program plays | #299 | n/a | n/a | not tested: no Watch Next program to pick |
| TVS-09 | Screensaver shows random backdrops from the library, with logo and clock | #140, #235 | n/a | n/a | works; title text, no logo seen |
| TVS-10 | Screensaver settings (from the system's screensaver settings): source, interval, clock | #140 | n/a | n/a | works: label on the left, value on the right of each row (#422) |
| TVS-11 | Screensaver with no user logged in: a plain screen, no crash | `ScreensaverService` | n/a | n/a | works |
| TVS-12 | The Ephyra banner and app name in the Android TV launcher | #171, #172 | n/a | n/a | works |
| TVS-13 | Every screen keeps clear of the TV safe area; nothing clipped by overscan at 1080p and 4K; text readable from the couch | #127, #355 | n/a | n/a | works: #423 fixed Home, the library counter and the login screen at 1080p; #540 the focused About buttons, the licence text and the login screen's server button; #539 the drawer's icons, avatar and labels (from 48 dp, collapsed and expanded), checked on the 4K AVD with its 1920×1080 size override (960×540 dp, as a 1080p TV) |
| TVS-14 | The 32-bit (`armeabi-v7a`) APK installs and starts on the Xiaomi box **[device]** | ADR 0006, #359 | n/a | n/a | not tested: emulator |
| TVS-15 | Pressing Home and coming back to the app returns to the same screen and focus | | n/a | n/a | works |
| TVS-16 | D-pad latency on Home and in the player feels immediate **[device]** | #359 | n/a | n/a | not tested: emulator |

## 10. Lifecycle and system

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| LIF-01 | Rotation on every screen keeps the screen, its input and its scroll position | #353 | works | broken #387 at 8 inch: rotation closes the search; the Season list keeps its position and scrim since #435 | n/a |
| LIF-02 | Background and return keeps the screen and scroll position; pages reload only when stale | #268 | works | works | works |
| LIF-03 | Process death (`am kill` in the background) restores the screen without a crash | #353 | works (#371): the restored library loads, on top and under a movie page | works; broken #371 at 8 inch and fold: library spins after `am kill` | works |
| LIF-04 | Split-screen at 600 dp: every screen stays usable | #288, test matrix | works since #392: in a half-height split-screen window the server name ends in an ellipsis instead of being clipped | episode and movie pages work at 600 dp since #429: the episode's still goes above the text, a movie's last button wraps to a second line; works at 8 inch and fold | n/a |
| LIF-05 | Free-form window resize: layouts follow the width | #288 | n/a | works | n/a |
| LIF-06 | Fold and unfold keeps the screen and state | test matrix | n/a | works on the fold since #429: the Season detail pane stacks the still above the text at the unfolded 841 dp width (checked as a 841 dp window on the 10.5 inch emulator); the half-opened posture is not tested on a device, the pane follows its own width and a unit test covers a ~420 dp pane; 10.5 and 8 inch are not foldables | n/a |
| LIF-07 | Font scale 1.3: no text cut off or overlapping | #353 | works | works | works (#423 fixed: Home card titles take one line and stay inside the safe area at 1.3) |
| LIF-08 | A right-to-left language (Arabic or Hebrew): layouts mirror, nothing overlaps | #353 | works (#401): arrows mirrored, English overview left to right and not clipped | works (#401): same drawables as the phone, checked on the phone | works |
| LIF-09 | CE screens are translated: no English left in a non-English language | #106, #110 | works | unpolished: upstream Player strings untranslated (Dutch); works at 8 inch (Arabic) | unpolished: upstream strings untranslated |
| LIF-10 | Edge-to-edge: no content under the status bar, navigation bar or floating headers | #94, #357 | works | works: the Season detail pane has its own band, and no grey band without artwork, since #437; works at 8 inch and fold; the person page's back and home buttons are clear of the photo since #436 | n/a |
| LIF-11 | Predictive back on Android 16 and 17 | #357 | not tested: Android 14 | not tested: Android 14 | n/a |
| LIF-12 | Notification permission asked on Android 13+; downloads work without it | ce.32, `MainActivity` | works (#372): asked when a download starts, never on a start, rotation, relaunch, language change or return; the download runs after Allow and after one or two denials, and Allow shows its notification | works; downloads without it not tested | n/a |
| LIF-13 | Local network permission on Android 17: asked in setup, a one-time prompt for existing users, the app works after a denial and explains why it needs it | 1.2.0-ce.0 | works on Android 17 (API 37); #376 fixed: after a denial Add server says that local network access is off and where the system lists it ("Nearby devices"), a LAN address that fails names the permission instead of only "Server not found", and the text after the second denial names the system's setting. An internet server still connects | not tested: Android 14 | not tested: Android 14 |
| LIF-14 | The debug and beta builds are labelled "Ephyra Debug" and "Ephyra Beta" | #172 | works; beta not tested | works | works |
| LIF-15 | Screen reader (TalkBack) reads the back, sort and main buttons | ce.26 | works after #441 in the accessibility tree (uiautomator): Home's Search and Profile, the Latest rows' arrow ("View all"), the watched tick ("Watched", also on movies and shows) and the unwatched count ("5 unwatched") are labelled; TalkBack not switched on | works after #441, tested on the phone (same components): the Home header buttons are labelled; TalkBack not run | not tested: TalkBack needs a system setting change |
| LIF-16 | Cold start time and scrolling feel against `docs/performance.md` **[device]** | #259, #269 | not tested: emulator | not tested: emulator | not tested: emulator |
| LIF-17 | No crash or ANR in `logcat` during the whole run | #361 | broken #381: two crashes (SUP-15) | broken #381: two crashes (SUP-15); no app crash at 8 inch and fold | works |

## 11. Upgrade and migration

Install the older release, set it up (login, non-default settings, two downloaded episodes, a
resume position), then install the build under test over it.

| ID | Feature: what to do, and what "works" means | Source | Phone | Tablet | TV |
|---|---|---|---|---|---|
| UPG-01 | 2.0.0 to current: still logged in, same server and users | #358 | works | | not tested: TV not in the #358 run |
| UPG-02 | 2.0.0 to current: the non-default settings are kept | #358 | works; dynamic colours default and Favorites row changed (see upgrade-path.md) | | not tested: TV not in the #358 run |
| UPG-03 | 2.0.0 to current: downloads are listed and play | #358 | works | | n/a |
| UPG-04 | 2.0.0 to current: resume positions are kept | #358 | works | | not tested: TV not in the #358 run |
| UPG-05 | Step by step 2.0.0, 2.1, 2.2, 2.3, current: the same four checks at each step | #358 | works; one emulator ANR on 2.2.0, not reproduced | | not tested: TV not in the #358 run |
| UPG-06 | 2.3.0 to current | #358 | works | | not tested: TV not in the #358 run |
| UPG-07 | Findroid CE (1.2.0-ce.6) to current: installs over it, login kept | 2.0.0 | works; downloads and other settings not checked | | not tested: TV not in the #358 run |
| UPG-08 | Room migrations to schema 17 and 18 (profile lock columns): a profile can be locked after the upgrade | #299, #330 | works; pattern lock not tried | | not tested: TV not in the #358 run |
| UPG-09 | Old media segment switches become per-type actions: an install with auto-skip on for intros shows Intro = Skip | #271 | works | | not tested: TV not in the #358 run |
| UPG-10 | mpv audio output stored as `aaudio` is moved to `audiotrack` once; picking `aaudio` again afterwards sticks | ce.3 | not tested: needs the mpv backend | | not tested: TV not in the #358 run |
| UPG-11 | Home rows start from the old Display switches until the layout is changed | #142 | works for the 2.0.0 Home rows; the older Display-switch path not tested | | not tested: TV not in the #358 run |
| UPG-12 | Still watching after the upgrade: off, with the thresholds kept | #349 | works | | not tested: TV not in the #358 run |
| UPG-13 | Phone APK replaced by the TV APK on a box: login and settings kept, a higher versionCode | ADR 0005, #173 | n/a | n/a | not tested: TV not in the #358 run |
| UPG-14 | Stable and beta installed side by side, each with its own data | #83, #98 | not tested: no beta install | | not tested: TV not in the #358 run |
| UPG-15 | Downgrade to the previous release: write down what is lost (the login is known to go) | #358 | works: across a Room version the servers, login and downloads list are lost; the files left on disk are removed by the daily sweep once a day old (#379), see upgrade-path.md | | not tested: TV not in the #358 run |
| UPG-16 | Every Room schema version has an exported schema or a migration test to compare against | `data/schemas` | works: schemas 2 to 18 exported; `ServerDatabaseMigrationTest` migrates each one to the current schema, with data from 12, 14, 16 and 18 (#380) | works: same `data` module and unit test (#380) | works: same `data` module and unit test (#380) |

## Smoke set for the Android version matrix (#357)

The rows #357 runs on each API level: SUP-06, SUP-08, HOM-06, BRW-07, PLY-01 (both backends),
PLY-04, PLY-64, PLY-65, PLY-100, DLD-02, OFF-05, LIF-01, LIF-12, SET-05, and on 36/37 also LIF-10,
LIF-11 and LIF-13.
