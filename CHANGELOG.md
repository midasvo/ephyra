# Changelog

All notable changes to **Ephyra** (formerly Findroid CE, `nl.midasvo.findroid.ce`) are documented here.

Ephyra started as a fork of [Findroid](https://github.com/jarnedemeulemeester/findroid). From
`v2.0.0` on, releases have their own version numbers and follow
[Semantic Versioning](https://semver.org/): a new feature raises the minor version, a fix the patch
version. Changes merged in from Findroid are listed under _From Findroid_, routine dependency bumps
and translation updates under _Maintenance_.

Up to `v1.2.0-ce.6`, releases were called Findroid CE and tagged `v<findroid-version>-ce.<N>`,
following the Findroid version they were based on.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Maintenance
- libmpv now comes from Ephyra's own fork, `io.github.midasvo:libmpv` 1.1.0: mpv 0.42.0,
  FFmpeg 9.0.2, libplacebo 7.387.0, dav1d 1.5.4, libass 0.17.5 and mbedtls 3.6.7, with security
  fixes in libass, mbedtls and FFmpeg. Network reads stay on FFmpeg, and mpv no longer writes its
  verbose messages, which contain stream URLs, to logcat (#654).

## [v2.5.0] — 2026-11-13

Ephyra can cast. The new proprietary variant of the phone app adds Google Cast: pick a
Chromecast or Google TV from the top bar and the phone becomes the remote. The libre variant
stays free of Google code, and the two install over each other. When the server doesn't answer,
Home now says so and takes you to your downloads.

### Added
- Cast: a proprietary variant of the phone app with Google Cast. Pick a device from the Cast
  button in the top bar and cast from any item; a mini player follows you while you browse and
  the Cast player has seek, previous and next, volume, audio and subtitle tracks, chapters with
  thumbnails and skip segments. On a tablet the Cast player sits beside the page. Servers on
  plain HTTP can cast too (#626).
- Releases and betas carry both phone variants: `-libre-` without Google code and
  `-proprietary-` with Cast. They share the application ID and signing key, so switching keeps
  your login, settings and downloads (#641).
- Home: while the server doesn't answer, a banner says so and leads to Downloads, which open and
  play without it. It goes by itself when the server is back, and stays away in Offline Mode
  (#281).

### Fixed
- Phone: an episode's page shows the show's backdrop again (#630).

### Maintenance
- Gradle 9.9.1 and kotlinx-serialization 1.13.0.

## [v2.4.5] — 2026-11-12

Mostly player fixes, and many of them are about files the server has to convert: you can now
pick another audio track or a subtitle from inside the file while it transcodes, and the audio
language chosen for a series is used there too. On TV a lost connection says so and every
playback problem has "Try again". Continue Watching and Next Up show the wide art with the
title on it.

### Changed
- Home: the Continue Watching and Next Up cards show the title art of the movie or series, as
  the web client does. "Use episode images" under Interface > Home brings the episode images
  back. Phone and TV (#608).
- Player: on the phone the seek bar's thumbnails take half the memory and the next ones are
  loaded ahead, so they show sooner while scrubbing (#615).
- TV: the app starts and scrolls more smoothly right after an install or update, from a
  baseline profile (#286).

### Fixed
- Player: while the server transcodes, the audio dialog lists all of the file's audio tracks
  and picking one switches to it. A track picked by hand in a movie is kept through a quality
  switch (#596).
- Player: the audio language saved for a series is also used when the server transcodes,
  where the server's default track used to play (#580).
- Player: the subtitles inside the file can be picked while the server transcodes (#597).
- Player: Play on a show gives its episode the seek bar thumbnails and chapters, and an
  episode without thumbnails no longer shows those of the one before (#594).
- Player: moving on from the closing outro, by a skip, the "Up next" card or autoplay, marks
  the episode watched (#616).
- Player: a file that fails to open is no longer counted as played on the server (#601).
- Player: the server's dashboard shows a transcode as a transcode instead of direct play
  (#599).
- Player (mpv): a file that cannot be opened gives one error that says what went wrong,
  instead of two to four. When the server can't be reached the player waits for the network
  and loads again, as ExoPlayer does (#576).
- Player (mpv): leaving the player, or turning the screen off, could freeze the app on some
  devices (#460).
- Player: the episode list can be used from a keyboard: focus starts on the playing episode
  and the arrows move through the list instead of changing the volume (#588).
- TV: when the connection drops the player says "Connection lost" and continues by itself when
  the network is back. Every playback problem has "Try again", and Play after an error loads
  the item again (#463).
- TV: after Home or standby the player plays on if it was playing, instead of sitting paused
  with nothing on screen (#462).
- TV: the seek bar's thumbnail no longer covers the title, the skip button or the "Up next"
  card (#464).
- Downloads: downloaded external subtitles are finished properly, also the ones earlier
  versions left half-named (#573).
- Home: on Android 17, with local network access turned off, the app says so and offers
  "Allow access" instead of "Error loading data" (#571).
- Login: a failed Quick Connect says why in plain words: the code expired, or Quick Connect is
  turned off on the server. Add server no longer shows technical error text either (#606).

## [v2.4.4] — 2026-11-12

Playback with ExoPlayer starts faster for files with Dolby Digital Plus audio in an mp4: they
now play directly instead of going through the server, which on a TV box could take ten seconds
or more before a picture. The rest are small fixes to the phone player, offline Home and the
item pages.

### Fixed
- Player (ExoPlayer): an mp4 with Dolby Digital Plus (E-AC-3) audio plays directly. When a file
  does go through the server, HEVC video is passed on as it is where the device can decode it,
  instead of being re-encoded, and leaving the player ends the server's conversion at once
  instead of a minute later (#589).
- Player: the first key after a tap on the player works. Space pauses and an arrow seeks,
  where that first key used to do nothing (#448).
- Player: in a window too narrow for all the buttons in the top row, the row starts at its end,
  so audio, subtitles and episodes are in view, and a fading edge shows there is more (#456).
- Player: at a large font size the title no longer leaves the window at the top (#585).
- Home: offline with nothing downloaded, Home says so and points to Downloads (#585).
- Item pages: the back and home buttons are readable on a page without artwork, also in the
  light theme (#585).

## [v2.4.3] — 2026-11-12

A quick follow-up to v2.4.2. Progress watched from a download after the network dropped in the
middle of playback was sent to the server and then overwritten by the server a few minutes
later; that is fixed. Downloads finish and tidy up more reliably, the players recover better
after a lost connection, and tablets get a switch to keep the player in landscape.

### Added
- Player: "Keep the player in landscape" in the player settings, off by default, for tablets and
  unfolded foldables. Where the display does not allow it, the player follows the device (#363).

### Fixed
- Downloads: progress synced after the network was lost during playback is no longer replaced
  by an older position a few minutes later. The app now ends the playback session the server
  still had open before it sends the progress (#584).
- Downloads: the download service runs until the queue is done and leaves no notification
  behind, and it waits for the queue to restore when the system restarts it (#560).
- Downloads: finished files and artwork that the database lost track of are cleaned up (#379).
- Offline: Home no longer shows the remembered online rows, which could not be opened (#428).
- Player: on mpv, Play and the wait for the network load the item again after a lost connection
  (#474).
- Player: the media notification no longer crashes a player in the background (#568).
- Player: a pause made in the background stays after the return, a repeated end-of-item report
  no longer prompts again, and auto-enter picture-in-picture follows a gesture setting changed
  while the player is open (#477, #480).
- Player: the seek bar no longer shows through the episode sheet (#523).
- Setup: Add server says that local network access is off when that is what blocks it (#376).
- TV: the player shows a buffering indicator, and pause works while it buffers (#486).
- TV: the still-watching prompt counts down to the player's own deadline (#477).
- TV: the drawer's icons and labels keep inside the safe area, Right from the expanded drawer
  reaches a poster beside it, and its profile item shows the server name under the user (#539,
  #567).
- TV: the login and add-server fields open the keyboard on OK instead of on focus, Left during
  a cold start opens the drawer on Home, and the overview has no focus ring while its full text
  is open (#583).

## [v2.4.2] — 2026-11-11

A second release of fixes from the run-through of the app on phone, tablet and TV. What you
watch from a download while the server cannot be reached is now sent to the server as soon as it
is back, without restarting the app, and it is no longer lost when you delete the download first.
The player picks up by itself when the network returns. And many smaller things on the TV, the
tablet layouts, Home, the setup screens and the settings read and behave as they should.

### Fixed
- Downloads: progress, watched state and favourites changed while the server could not be reached
  are sent when it is reachable again, not at the next app start. The app notices on its next
  request to the server, when it comes to the foreground and when the network changes (#552).
- Downloads: deleting a download before its progress was sent no longer loses that progress
  (#552).
- Downloads: a download queued without a network waits for it, the item page says "Waiting for
  network" like the Downloads row, and the Downloads header counts failed downloads apart (#530,
  #558, #546).
- Player: playback loads again by itself when the network comes back, and the connection-lost
  line covers no control (#450).
- Player: track names are readable and the same on both backends, and the phone player's title
  names the series (#451, #452).
- Player: the controls stay clear of the system bars in split-screen, and the buttons under the
  episode sheet are hidden while it is open (#455, #456).
- Home: a server renamed on the server is renamed in the app, the server name keeps its room
  when a refresh fails, and a pull to refresh shows one loading indicator (#543, #392, #391).
- Home: a new first item in a row shows at the start, Back from a search opened on Home returns
  to Home, and the carousel counts its pages by the window's width (#393, #394, #438).
- Detail pages: the episode page links to its show, the watched button fills when watched, cast
  without a photo shows a person icon, and premiere dates keep the four-digit year (#395, #397,
  #396, #414).
- Detail pages: items without artwork get no empty scrim or backdrop, on the page and on
  Suggestions cards (#433, #437).
- Accessibility: the Home buttons and the badges on cards have labels, keyboard focus is visible
  on the carousel and the cards, and Tab skips plain text on item pages and the hidden bottom
  bar (#441, #398, #442).
- Right-to-left languages: arrows are mirrored and server text keeps its own direction (#401).
- Setup: the username is trimmed before logging in, "Server not found" clears when the address
  is changed, the Addresses screen says why a removal or an addition did nothing, and the
  server's login disclaimer is shown as text instead of HTML (#516, #443, #390, #375).
- Settings: the player backends have names, number settings say "1 episode" and "No limit", and
  the still-watching timeout is at least 5 seconds (#399, #400, #559).
- Wording: the delete download dialog says "from your device", and Latin American Spanish reads
  "Continuar viendo" (#402, #403).
- Profiles: the profile sheet opens fully in landscape (#440).
- Tablet: the open search docks beside the rail on wide windows, the Season list keeps its
  position over a rotation, the person page starts below its back and home buttons, and the rows
  of seven screens keep to their designed widths (#439, #435, #436, #434).
- TV: a long overview or biography opens in full, and the person page's first row fits (#545,
  #508).
- TV: the movie page shows Resume, Restart and the time left, and drops its spinner once the
  movie has loaded; a season without episodes says so instead of spinning forever (#412, #544,
  #505).
- TV: focus is visible and goes where you expect: on Retry, the licence text, the About, login
  and sort dialog buttons, back from Settings, a licence and the Users screen, down to a cast or
  seasons row below the screen, and off the login screen's server button (#540, #418, #533, #424,
  #541, #514, #557).
- TV: Home, the library counter and the login screen keep out of the overscan margin, the Home
  hero shows the overview for every row, and Back on Home opens the drawer on Home (#423, #410,
  #411).
- TV: Retry on Home also loads the drawer's libraries, and a failed search says why and offers
  Retry (#513, #417).
- TV: a season card without its own poster shows the show's, an undetermined language is left
  out of the audio chip, and the credits under the buttons wrap (#416, #415, #413).
- TV: OK after the remote's number keys submits the PIN, and the drawer's lock badge follows the
  PIN at once (#409, #408).
- TV settings: Left and Back from a details pane return to its row, millisecond settings step by
  whole seconds, the screensaver settings keep label and value apart, and the mpv options page
  has no empty "Advanced" heading (#419, #420, #422, #421).

## [v2.4.1] — 2026-11-11

A release of fixes, from a full run-through of the app on phone, tablet and TV. Playback copes
better with a lost or stalled connection, above all on mpv. Downloads are only offered where the
server allows them and say why one was refused. Artwork you have already seen is shown from the
cache instead of being checked with the server again. And the detail pages are tidier: the file
details moved behind an info chip, and the credits are off until you ask for them.

### Changed
- Movie and episode pages: an info chip at the end of the badge row opens a Media info sheet
  with the file, its video, audio and subtitle tracks, and each version when there are several.
  It replaces the "Display Extra Info" setting and the lines of text it put on the page (#510).
- Movie and show pages: genres are one line under the overview, and the director, writers and
  studios a small table. That table is now off by default: turn on "Director and writers" and
  "Studios and networks" in Settings → Interface → Detail pages to see it, on TV too (#510).

### Fixed
- Artwork: posters older than a year were checked with the server every time they were shown,
  and stayed grey when the server could not be reached. They now come from the cache (#370).
- Player: a lost connection on mpv is reported as an error, instead of closing the player,
  marking the item watched or starting the next episode (#445).
- Player: a stalled stream on mpv recovers or reports an error after 30 seconds, instead of
  freezing for good (#460).
- Player: a stream that fails to open on mpv is reported, and picking another episode in the
  player works again (#446, #453).
- Player: the phone player closes when nothing could be loaded, instead of staying black (#374).
- Player: an episode whose download has not finished is streamed instead of failing (#454).
- Player: the stop is reported to the server for an item you leave while it is still loading
  (#461).
- Player: the "still watching?" countdown shows the time really left (#367).
- Player: keyboard focus is visible on the controls, and Space no longer presses a control the
  keyboard did not choose (#448, #449).
- Player: auto-enter picture-in-picture stays off when the PiP home gesture is off (#447).
- Playback through a server on a sub-path: the address and the transcode path are joined with
  one slash (#444).
- Downloads: Download is only offered where the server allows it, and a refused download says
  why (#373, #383).
- Downloads: the notification permission is asked for when a download starts (#372).
- Downloads: a cancelled download that was not running is removed (#426).
- Home: a failed load is explained in plain words instead of a stack trace, and a change to the
  Home rows shows without a pull to refresh (#377, #431).
- My media: an error with Retry when the network drops, loading again when it returns; the
  search stays open after a rotation or theme switch; Tab skips the closed search bar (#427,
  #387, #430).
- Library: it loads again after the app was killed in the background (#371).
- Detail pages: a cast card's name and role open the person, as its picture does (#389).
- Tablet: an episode's still is stacked above its text in a narrow pane, and the buttons wrap
  (#429).
- Setup: the server address is trimmed before connecting, adding a saved server again keeps its
  user signed in, Login opens empty after a sign-out, and a cancelled Quick Connect code is
  cleared (#404, #425, #388, #382).
- Profiles: removing the current user in Settings no longer crashes the app (#381).
- Settings: the licence text opens on the phone, and the device profile can be exported on
  Android 11 and later (#384, #385).
- The app tells the server its real version (#386).
- TV: an error with Retry when Home or an item page fails to load, instead of a blank page or an
  endless spinner (#405).
- TV: the person page opens at the top, with the name and the whole biography (#407).
- TV: playback progress is reported from the TV player, and the OK press that shows the player
  controls no longer also acts on them (#458, #459).

### Maintenance
- Migration tests for every released database schema (#380).

## [v2.4.0] — 2026-11-10

A fix for Android 10 and 11 first: the player crashed there as soon as you pressed a volume key
or Back, and no longer does. The "still watching?" prompt gets a switch and is now off until you
turn it on. On TV the settings are one tidy list, number settings can finally be changed with the
remote, and the Home hero shows long titles in full.

### Added
- TV: number settings, such as the "still watching?" thresholds, can be changed with steps in the
  details pane (#127).

### Changed
- "Still watching?" prompt: a switch in Settings → Player, off by default. If you used the
  prompt, switch it on again; the thresholds keep their values (#349).
- TV settings: one list layout for every page, with the options or the description of the
  focused setting on the right. Focus returns to the row you left (#347).

### Fixed
- The player crashed on Android 10 and 11 when a volume key, Back or most other keys were
  pressed during playback, on both ExoPlayer and mpv (#350).
- A leftover manifest entry for a download receiver that no longer exists could crash the app
  when a system download finished (#350).
- TV: the Home hero title shrinks to fit, on up to two lines, instead of being cut off (#346).
- TV: the Home hero falls back to the season's backdrop or the episode's picture when the show
  has no backdrop (#348).

### Maintenance
- The licence list in the app is built from texts in the repository instead of downloads during
  the build, so F-Droid can reproduce the release APK (#364).

## [v2.3.0] — 2026-11-09

Profiles can be locked, the player learns a few manners, and item pages show much more of what
your server knows. A profile can now sit behind a PIN, or a pattern on the phone, that never
leaves the device. The player gets a sleep timer and an "Up next" card with a countdown at the
end of an episode. Movie and show pages add the critic score, tagline, studios, every trailer,
extras, collections and "More like this", each with a switch if you'd rather not see it. mpv gets
a round of fixes and now follows your subtitle style, and something you downloaded opens even
when the server doesn't answer.

### Added
- Profile lock: an optional 4 to 6 digit PIN per profile, asked when switching to the profile and
  when the app starts in it. It is stored on the device only (#299).
- Profile lock with a pattern on the phone, as an alternative to the PIN (#330).
- Sleep timer on phone and TV: 15 to 90 minutes, or until the end of the episode (#298).
- "Up next" card with a countdown in the last seconds of an episode, or from the start of the
  outro, on phone and TV (#272).
- Movie and show pages show what Jellyfin already sends: the Rotten Tomatoes score, tagline,
  studios or networks, every trailer, the air schedule of a running show, the watch count and
  last played date, a 3D badge, extras, collections and "More like this"; links to IMDb, TMDB,
  TVDB and Trakt on the phone (#332).
- Subtitle style: an "Override embedded styles" switch that applies your style to tracks that
  bring their own, such as ASS (#300).
- mpv follows the subtitle style settings: colour, outline, background, font and size (#308).

### Changed
- Settings → Interface → Detail pages: one page with a switch for each part of a movie or show
  page. "Display cast and crew" and "Display Extra Info" moved there (#332).
- Downloaded movies and episodes open and play from the local copy when the server fails or
  takes more than three seconds to answer (#281).
- Playback starts a little sooner: media streams over the connection the app already has open,
  and the saved audio and subtitle choice is applied with the first track selection instead of
  after it (#296).

### Fixed
- Phones switch the display to the video's frame rate again, so 24 fps video no longer plays at
  the panel's maximum refresh rate (#338).
- Tablet: the title and text of a movie, show or episode page sit under the backdrop instead of
  over it, where bright artwork made them hard to read (#339).
- The app's session disappears from the server's dashboard when the app closes or a login is
  removed, instead of staying listed until the server restarts (#336).
- The PIN prompt stays usable with the keyboard up, and Change PIN on TV no longer keeps the old
  PIN (#329).
- Show backdrops and logos are requested with the series' own image tags, so a series without a
  logo no longer causes a failed request (#319).
- A show that airs every day reads "Airs daily" (#332).
- mpv: play and pause survive loading and item changes (#305); a file mpv cannot open reports an
  error instead of buffering forever (#306); lock screen and Bluetooth controls get the position
  and the queue (#304); no more crash when backing out of the player (#318).

### Maintenance
- Dependencies: Material 3 adaptive 1.3.0 (#337).

## [v2.2.0] — 2026-11-08

Ephyra gets its own look, a real tablet layout and a lot more speed. The app now wears the
Bioluminescence identity: deep navy chrome, cyan only for what is alive (playback, downloads,
focus), violet for what is yours (favourites, watched, ratings), the Outfit typeface and round
pill buttons; Material You dynamic colours are now an opt-in instead of the default. On a tablet,
an unfolded foldable or a split-screen window the same app lays itself out for the width: a
navigation rail, movie and show pages with the poster beside the text over a full-width backdrop,
seasons as a list with the selected episode next to it, and a player that keeps the orientation
you hold it in, with an episode panel, keyboard and mouse support and tabletop posture on
foldables. Under the hood a performance pass across the whole app makes cold start, page loads,
scrolling and playback start noticeably quicker, on TV as much as on the phone.

### Added
- Tablet and wide-window layouts: navigation rail, hero item pages for movies, shows and
  episodes, seasons as list and detail side by side, wider cards and grids, Downloads in two
  columns, readable text widths everywhere (#288, #290, #291, #297).
- Player on wide windows: free orientation, volume and brightness swipes only near the edges,
  roomier controls, an episode panel to switch episodes without leaving playback (#289, #301,
  #314).
- Player keyboard and mouse: Space, arrows, M (mute), L (lock), Esc, media keys; the mouse shows
  the controls and the wheel seeks; Ctrl+F opens search (#310).
- Foldables: in tabletop posture the video sits above the fold and the controls below it; the
  controls resize when the window does (#313).
- Profiles: user faces in the chrome, one-tap switching between users, default avatars (#248).
- Media segments (intro, outro, recap, preview, commercial): one action per type, skip, ask or
  ignore; the skip button follows the segment window on phone and hides after its window on TV
  (#283).
- HDR10+ and Dolby Atmos badges on movie and episode pages (#282).
- Home: a switch for the remembered Home rows and a Clear cache row (#270).
- About screens for phone and TV with install, server and device info (#223).
- A swimming jellyfish on the welcome screen, specks on empty states and the TV screensaver
  (#227, #235).

### Changed
- The Bioluminescence identity: the Ephyra palette is the default and dynamic colours are an
  opt-in (#225); colour tokens (#224); Outfit for display, headline and title text (#228);
  round shape roles and pill actions (#226); violet for favourite, watched and rating (#229);
  a glow and a cyan focus ring on TV (#233, #242); buoyant motion and transitions (#234); TV item
  pages fade to the surface colour (#237); the storage summary's Other segment is neutral (#246);
  player dialogs tint the selected option with the accent (#244).
- Performance: codec probe, database and sync jobs off the cold-start path (#260, #261); show,
  season, person, movie and episode pages load their parts concurrently (#262, #274); fewer
  recompositions in the TV player, Season and Main (#263); the Downloads screen loads in one
  batch and polls less (#264, #277, #279, #293); batched database lookups, bigger library pages
  and a lazier letter index (#265); Compose stability config and a carousel that pauses while
  you scroll (#266); playback starts sooner (#267); screens reload on resume only when stale,
  overlapping loads are cancelled and Home rows are cached (#268, #273, #275); a baseline
  profile ships with the app (#269, #285); artwork loads at display size (#294); TV backdrops
  are capped at 1280 px (#278).
- The no-op "lazy trickplay" developer toggle is gone (#276).

### Fixed
- mpv reaches the ready state on the first restart when no seek is pending (#312).
- Each player gets its own media session id (#231).
- The "still watching?" prompt shows on TV (#243).
- Status-bar icons stay readable over backdrops on item pages (#291, #297).
- The HDR10+ wordmark is drawn at 16 dp so "10+" stays legible (#282).

### Maintenance
- Dependencies: Android Gradle plugin 9.5.1, Gradle 9.9.0, KSP 2.3.13, Navigation 3 1.2.1,
  fragment-ktx 1.9.1, ZXing 3.5.4, GitHub Actions majors; Renovate configured and JDK pinned to
  21 (#198–#207, #247).
- Repository: Google Play metadata and screenshots, LF line endings enforced, the website and
  README redone with new screenshots and a tablet section (#208, #311, #315).

## [v2.1.0] — 2026-11-06

A dedicated Favorites destination across phone, tablet, and Android TV, smoother TV navigation,
and official F-Droid inclusion support with reproducible builds.

### Added
- Dedicated Favorites tab in the main navigation on phone, tablet, and Android TV. Access your
  favorite movies and shows directly from the navigation bar without digging through libraries (#188).
- TV: Toggle in Settings → Interface to show or hide the Favorites destination in the navigation drawer (#188).
- F-Droid: Reproducible build metadata and packaging recipe for official F-Droid distribution (#176).

### Fixed
- TV: Eliminated navigation stutter, focus jumping, and layout shifting during Leanback remote navigation (#190).
- TV: Improved Hero item focus restoration and safe focus requester handling.
- Logging: Ensure warnings and errors are consistently logged across all build flavors.

### Maintenance
- Configure Renovate dependency dashboard, manager presets, and ktfmt formatting pin (#194).

## [v2.0.0] — 2026-11-03

**Findroid CE is now Ephyra.** Same app, new name and icon. The update installs over Findroid CE as
usual: the application ID stays `nl.midasvo.findroid.ce`, so your login, settings and downloads are
all still there. Nothing else changes in how the app works.

From here on Ephyra has its own version numbers, so this release is 2.0.0 rather than another
1.2.0-ce release. The next ones follow [Semantic Versioning](https://semver.org/): 2.1.0 for new
features, 2.0.1 for fixes.

### Changed
- The app is called Ephyra, with a new icon: a young jellyfish whose tentacles fork. The launcher,
  the welcome screen, the TV banner and the Jellyfin dashboard all show the new name. The beta is
  now "Ephyra Beta" (#172).
- The release APKs are named `ephyra-v<version>-libre-<abi>.apk` and
  `ephyra-v<version>-libre-tv-<abi>.apk`. Obtainium filters that match the end of the name, such as
  `-libre-arm64-v8a\.apk$` and `.*-tv-armeabi-v7a\.apk`, keep working (#172).
- The device profiles the app reports to the server are now called "Ephyra Device Profile" and
  "Ephyra Download Profile" (#172).
- Ephyra has its own version numbers, starting at 2.0.0, instead of following Findroid's. Betas are
  now named after the next version, such as `2.0.1-beta.12` (#181).
- The project moved to [github.com/midasvo/ephyra](https://github.com/midasvo/ephyra), and the About
  screen links there. The old `midasvo/findroid-ce` address redirects, so Obtainium keeps finding
  updates (#174).

## [v1.2.0-ce.6] — 2026-11-03

Android TV is stable. One release after the TV app first shipped, it has what a living-room
Jellyfin client needs: Continue Watching on the TV's own home screen, trickplay while seeking,
refresh rate switching, cast and person pages, theme music and a screensaver, all built for the
remote and tested on a 32-bit Xiaomi box. And on phone and TV alike, you choose which Home rows
show, and in what order.

### Added
- Home rows you arrange yourself: Settings → Interface → Home rows lists Suggestions, Continue
  Watching, Next Up, Latest per library and a new Favorites row. Hide any of them or move them up
  and down, on phone and on TV. The layout is kept per server and user, and starts from your old
  Display settings, so nothing changes until you change it (#142).
- TV: Continue Watching and Next Up appear in the Android TV home screen's Watch Next row. Picking
  one resumes it straight away, with its page underneath. Items you remove from the row stay away
  until you watch them again. A switch under Settings → Interface turns it off (#138).
- TV: trickplay thumbnails while seeking, both with the controls shown and when seeking with
  Left/Right. Thumbnails are decoded one at a time, so they fit in a 32-bit box's memory (#134).
- TV: Match display refresh rate switches the display to the video's frame rate during playback
  (#139).
- TV: a cast row on movies and shows, and a page per person with their work (#136).
- TV: a series' theme song plays softly on its show page, off by default. It fades out when you
  leave, play something or the screensaver starts, and stays quiet when another app is playing
  audio (#141).
- TV: a screensaver with random backdrops from your whole library (#140).

### Changed
- TV: Home feels quicker. The backdrops of the cards next to the focused one load ahead of time,
  so moving along a row no longer waits on the network, and the image cache on TV is 100 MB unless
  you picked a size (#155).
- TV: settings that only make sense on a phone, such as gestures, are hidden (#133).

### Fixed
- TV: the next-episode threshold setting can be reached again (#133).
- TV: the trickplay thumbnail no longer covers the "Ends at" time (#134).

### Maintenance
- Synced with upstream findroid: Korean and Portuguese (Brazil) translations.
- README with an Android TV section and screenshots (#167).

## [v1.2.0-ce.5] — 2026-11-02

The first CE release with an Android TV app: its own Home, library, search, season and player
screens built for the remote. Plus CE branding throughout.

### Added
- Android TV APKs ship with every release, as `findroid-ce-v<version>-libre-tv-<abi>.apk`. 32-bit TV
  boxes such as the Xiaomi Mi Box need `armeabi-v7a`. The TV app has CE's own screens (#121):
  - A side navigation drawer, and layouts that keep clear of the TV's safe area (#127).
  - Home with a hero area for the focused item: logo or title, info line, overview and backdrop.
    Rows refresh in the background without reshuffling, and focus comes back to the same item. A
    Suggestions row replaces the carousel (#132).
  - A library grid with an A–Z jump bar when sorting by name, a position counter, the year under
    each title, and title cards for items without artwork (#135).
  - A favorites page (#137) and a search screen (#128).
  - Episodes open in their season instead of playing right away, with the episode's details,
    media chips, Play/Resume, Restart, Watched, Favourite, a version picker and season chips (#130).
  - A player built for the remote: seeking, media keys, audio and subtitle dialogs, the series and
    episode name, elapsed and remaining time, "Ends at", a clock, previous/next, and an "Up next"
    queue. Playback errors show a message with a way out instead of a black screen (#124, #125,
    #131).
  - Focus is restored when you come back to the library, a show or a season (#124).
  - Dates follow the app language, and shows get a shuffle button (#141).

### Changed
- The remaining Findroid names and upstream links are replaced with CE branding (#122).

### Fixed
- TV: collections open, trailers play, and every player setting can be reached (#129). Episodes in
  a collection open in their season (#129).
- TV: focus stays put when you switch drawer destinations (#127).

### Maintenance
- Synced with upstream findroid: Portuguese (Brazil) translations.
- Android TV research and roadmap in `docs/tv-research`.

## [v1.2.0-ce.4] — 2026-11-02

Background audio, audio and subtitle choices that carry over to the next episode, and Smart
Downloads that follow what you watch.

### Added
- Background audio, off by default. Turn it on under Settings → Player → Background playback, and
  playback carries on with the screen off or the app in the background, with artwork and controls
  on the lock screen. Closing the picture-in-picture window or swiping the app away keeps the audio
  going, and the media notification brings the player back. The video is switched off in the
  background to save battery (#36, #79, #80, #118).
- The audio and subtitle track you pick, or subtitles off, now carry over to the next episodes of
  that series. This works on auto-advance and when you start an episode yourself, on ExoPlayer and
  mpv, and for downloads. Each user on the device keeps their own choice (#115, #120).
- Two optional settings from a review of the Moonfin client: smaller artwork requested from the
  server at display size, and a "Max days in Next Up" limit for Home. Both are off by default
  (#114, #117).

### Changed
- Smart Downloads now follows what you watch, not when a download finishes. After playback it
  keeps a set number of unwatched episodes after the furthest one you watched downloaded, across
  seasons. It removes the episodes it downloaded itself once you have watched them. "Episodes to
  keep ready" sets how many (3 by default) (#116, #119).
- Search waits 300 ms after typing and returns at most 50 results. Media requests get longer
  timeouts, and playback pauses when headphones are unplugged, on ExoPlayer and mpv (#114, #117).
- Home keeps the rows and libraries that loaded when others fail (#114, #117).

### Fixed
- Download status lines in some languages no longer run past the edge of the row (#113).

### Maintenance
- `scripts/make-track-test-series.sh` generates a short test series with several audio and subtitle
  tracks, explained in `docs/track-test-series.md`.

## [v1.2.0-ce.3] — 2026-11-01

CE's own text in every language, a lighter Continue Watching query, and download rows that stay
readable.

### Added
- Everything CE adds is now translated into all 36 languages, so these screens no longer fall back
  to English. The translations are machine-assisted, so corrections are welcome (#106, #110).
- "Display cast and crew" setting under Settings → Interface, on by default. Turning it off hides
  actors, directors and writers on movie, show and episode pages, on phone and TV (#104, #105).

### Changed
- Continue Watching now asks the server for video only, which a Jellyfin report says stops it
  scanning the whole library for this row. In a test against one server, a Home refresh got about
  70 ms faster and a cold start didn't change; large libraries may gain more. Thanks @DrHurt (#109,
  #112).

### Fixed
- Active download rows no longer cut off the time left on narrow phones: the size and time left now
  have a line each. In multi-select, a movie's size no longer disappears behind "...". "Less than a
  minute left" is now "Under 1 min left" in English, and some translations may still be cut off
  (#111).

### Maintenance
- The README has screenshots of CE's own screens again and an updated feature list (#107, #108).
- Only the 3 newest beta prereleases are kept (#103).

## [v1.2.0-ce.2] — 2026-11-01

Picking between versions, playback that carries on into the next season, Dolby Vision direct play
and Atmos on phones, and a round of phone UI work.

### Added
- Version picker. Movies and episodes with more than one version now ask which one to play, for
  both Play and play from beginning. On TV this works on the movie screen. Items with a single
  version start straight away, as before (#28, #93).
- Finishing the last episode of a season continues into the next one, and the Next button works
  there too. Seasons with only missing episodes are skipped, and specials don't roll over into
  season 1 (#29, #92).
- "Direct play Dolby Vision" player setting, on by default (#96, #100).
- A partly downloaded season or show now offers both download and delete, so you can finish the
  download instead of only removing it (#94).
- Error screens with Retry. Movie, show, season, episode and person pages used to spin forever on a
  failed load, and Favorites and Collection stayed blank. Search, an empty Home and Downloads show
  the same error, and a failed background reload keeps what's on screen (#95).

### Changed
- Dolby Vision is direct-played when the device can show it: the decoder, profile, level, container
  and display must all support it. Anything else is transcoded as before. Profiles 5 and 10.0 have
  no fallback layer, so their transcode shows green and purple; on capable devices that's now
  avoided (#96, #100).
- Phones now try the platform audio decoder first, so those with a working Atmos decoder get spatial
  audio and hardware decoding again. If an audio decoder fails during playback, on phone or TV, the
  player switches to ffmpeg at the same position and remembers that decoder for next time. Phones
  with no Atmos decoder stay on ffmpeg for E-AC-3 (#97, #99).
- Season lists use the Downloads row style: titles on up to two lines, a play button on the artwork,
  watch progress and a download button that can stop a download. A failed download retries at its
  chosen quality instead of asking again (#94).
- Home, movie, show, season and episode pages refresh after you leave the player (#95).
- Favorites, Collection, search, Library and Home have proper empty states, and "No search results"
  no longer flashes while typing. Library has a first-load spinner, and a "Show watched" button when
  the watched filter hides everything (#95).
- The downloaded badge has its own download icon, so it no longer looks like the watched check. The
  watched check no longer uses the error colour, and Favorites uses the same heart as the item
  buttons. My media has at least two columns, like Library (#95).

### Fixed
- The check mark on an episode no longer deletes the download on one tap. Removing or cancelling
  now shows Undo, and the delete still finishes if you leave the screen (#94).
- Artwork moves on to the next image when one fails to load, and seasons without a poster use the
  show's (#94).
- Content no longer runs through the status bar and floating headers on Home, My media and item
  pages (#94).
- Home no longer opens partly scrolled when the carousel loads last (#95).
- Player controls stay usable in a narrow window such as split-screen: the Next button no longer
  gets cut off, the back button is always visible, and the other buttons scroll when they don't fit
  (#101).
- Swipe gestures no longer fire inside the system gesture area in split-screen and freeform windows
  (#55, #91).
- Stable and beta builds no longer log each other out. Beta builds now have their own device ID; log
  in once more on the beta to separate them fully (#98).

### Maintenance
- From upstream: Android Gradle Plugin 9.5.0, Jellyfin SDK 1.9.4, fastlane update.

## [v1.2.0-ce.1] — 2026-10-29

A redesigned Downloads screen, and a beta channel you can follow in Obtainium.

### Added
- Downloaded show page. Tapping a show on the Downloads screen lists its downloaded episodes by
  season, with sizes, swipe to remove and "Remove all downloads" (#84, #81).
- Multi-select on the Downloads screen. Tap "Select" or long-press a movie or show, then remove
  everything selected at once.
- Play from the Downloads screen. The ▶ on movie and episode artwork resumes playback straight
  away, from the downloaded copy.
- Undo instead of confirmation dialogs. Cancelling or removing a download shows an Undo snackbar.
  A cancelled download keeps running until the snackbar goes away, so undoing loses no progress
  (#82, #84).
- Beta channel. Builds of `main` and of work in progress are published as GitHub prereleases and
  install as a separate app, "Findroid CE Beta", with its own data. The README explains how to
  follow them in Obtainium (#83, #85, #86, #87).

### Changed
- The Downloads screen is now one scrolling list instead of tabs: a storage summary, then
  Downloading, TV Shows and Movies. Sort by recently downloaded, name or size from the ⇅ menu
  (#84, #81).
- Active downloads show a progress ring you can tap to stop, and one status line such as
  "164 MB of 498 MB · 3 min left", "Waiting for network" or the failure reason. The section header
  shows the count and combined speed, and the time-left estimate is smoothed so it settles instead
  of jumping around.
- Finished downloads get a check mark and then move into the library on their own; "Clear
  completed" is gone. Completing, cancelling and removing a download give a short haptic tick.
- Movies and shows on the Downloads screen have 16:9 artwork with watch progress, plus year,
  runtime and size (episode count and size for shows).
- The storage card shows a bar split into movies, TV shows, other files and free space, turns red
  under 10 % free, and opens the download settings. An empty Downloads screen explains what goes
  there and links to the library.

### Fixed
- In the download queue, the percentage no longer wraps onto several lines, red no longer shows at
  the corners of the cards, a long series name no longer hides which episode is downloading, and
  failed downloads show a retry icon instead of a download arrow (#82).

## [v1.2.0-ce.0] — 2026-10-28

An upstream sync to findroid 1.2.0, a download quality picker, and a batch of player fixes.

### Added
- Download quality picker. Every download (movie, episode, season or whole show) now asks for a
  quality: "Original quality" or a bitrate from 4K 120 Mbps down to 360p 720 kbps. Files above the
  chosen bitrate are converted by the server; files already under it download as the original.
  Retries and Smart Downloads keep the chosen quality, and subtitles are saved as separate files,
  image-based ones (PGS, VobSub) included. Thanks @jorritsmit (#75, #74).
- Permission requests, from upstream. On Android 17 the app asks for local network access, which it
  needs to reach a server on your home network. New installs ask during setup, existing users get a
  one-time prompt, and Settings has a new Permissions section. Phone and TV.
- "Play next episode automatically" setting under Settings → Player, on phone and TV. By default
  it follows the Jellyfin account's "Play next episode automatically" playback setting, which
  Findroid used to ignore; "Always" and "Never" override it on this device. With autoplay off,
  playback stops at the end of an episode and the episode is marked as watched; on the phone the
  player closes. An outro set to skip automatically no longer starts the next episode either. The
  next button, the outro "Next episode" button and remote controls still go to the next episode.
  Offline, "Use server setting" keeps autoplaying (#66, upstream #1050).

### Changed
- **Android 10 is now the minimum; Android 9 is no longer supported.** The app now targets
  Android 17. From upstream.
- The home screen asks the server for less. The Continue watching, Next up and Latest rows request
  one poster and one backdrop per item instead of every image, and skip a count nobody uses, which
  helps most on large libraries and slow servers. Thanks @DrHurt for the pointer (#77, #73).
- Release APKs for phone are now named `findroid-ce-v<version>-libre-<abi>.apk`.

### Fixed
- Opening a movie, show or episode no longer crashes on Jellyfin 12 servers when an actor has more
  than one role. Jellyfin 12 sends one entry per role; these are now merged into one cast card with
  the roles joined by " / ", and writers and directors are no longer listed twice (#64).
- With the mpv player, seeking now counts as activity for the "Are you still watching?" prompt, so
  it no longer appears while you are skipping around. mpv was reporting seeks in a way the player
  screen didn't listen for (#70).
- The server now learns that an episode stopped whenever playback moves to another one, not only
  on the automatic advance at the end. This covers the next and previous buttons, the chapter-skip
  gesture, the outro "Next episode" button, and media-session or remote controls, on phone and TV
  and with both ExoPlayer and mpv. Jellyfin no longer shows the old episode as still playing, and
  plugins such as Trakt and Playback Reporting see the stop. Skipping the outro past 90 % of the
  runtime now marks the episode as watched (#65, #31).
- On TV, the player now closes when playback ends (after the last episode, or at the end of an
  episode with autoplay off) instead of sitting paused on the last frame.
- On TV, media-segment skip buttons and automatic skipping (intros, outros, recaps…) now work; the
  TV player never checked the playback position against the segments.
- The progress bar for a lower-quality download now estimates the converted file's size instead of
  the original's. A 20 GB film capped at 3 Mbps used to show "2.7 GB / ~20 GB" and stall near 13 %
  before jumping to done (#76).
- The keyboard no longer covers text fields; the screen resizes around it instead. From upstream.

### Maintenance
- From upstream: Jellyfin SDK 1.9.3, kotlinx-serialization 1.12.0, OkHttp 5.5.1, Gradle 9.8.2.

## [v1.1.0-ce.5] — 2026-10-15

Community contributions plus an upstream sync that moves to Navigation 3 and Room 3.

### Added
- Video quality selector in the phone player (HD button). Picking a bitrate asks the server to
  transcode to it, "Original quality" goes back to direct play, and the choice is remembered. Works
  on both ExoPlayer and mpv; mpv can now transcode when a cap is set. Thanks @jorritsmit (#61).

### Fixed
- The season screen's download button now shows up. Episodes were fetched without the field that
  says they can be downloaded. Thanks @kaixuantan (#62).
- Dolby Digital Plus Atmos (E-AC-3 JOC) files no longer crash ExoPlayer on phones whose hardware
  decoder rejects Atmos frames (seen on Pixel 8). Phones now decode audio with the bundled ffmpeg
  first; Android TV keeps the hardware decoder so passthrough to soundbars and receivers still
  works. Thanks @jorritsmit (#59).
- Transcoded downloads (for example Dolby Vision) now also save the video's built-in subtitle
  tracks as separate files, and subtitle downloads that fail are retried. Thanks @jorritsmit (#60).
- Season cards on the home screen show season details, from upstream.

### Changed
- Navigation moved from Navigation 2 to Navigation 3, from upstream. CE's downloads-only show and
  season screens and the subtitle style screen were carried over.
- Database moved to Room 3, from upstream. The schema stays at version 11, so existing installs and
  downloads upgrade in place.

### Maintenance
- Synced with upstream findroid: Jellyfin SDK `1.9.2`, Kotlin `2.4.21`, Gradle `9.8.1`,
  androidx.paging `3.5.3`, work-runtime `2.12.0`, androidx.core `1.19.1`, aboutlibraries `15.3.0`,
  slf4j `2.0.20`, fastlane `2.241.0`.
- CE stays on JDK 21 rather than upstream's move to JDK 17, and ktfmt stays pinned to `0.26.0`.

## [v1.1.0-ce.4] — 2026-09-13

Upstream sync only — no CE behaviour changes.

### Changed
- Back-gesture animations now follow through on the predictive back preview instead of snapping at
  the end of the gesture, from upstream.

### Maintenance
- Synced with upstream findroid: Kotlin `2.4.20`, KSP `2.3.12`, Android Gradle Plugin `9.4.0`,
  androidx.compose `1.12.1`, media3 `1.11.1`, navigation-compose `2.10.1`, Room `2.8.5`,
  appcompat `1.8.0`, coil `3.6.2`, okhttp `5.5.0`, aboutlibraries `15.2.0`, slf4j `2.0.19`.
- Portuguese translation updates.
- ktfmt stays pinned to `0.26.0` rather than taking upstream's bump to `0.27.0`. The newer plugin
  bundles ktfmt `0.64`, whose formatting differs from `0.62` across the whole tree — upstream's own
  `main` is currently red on lint for this, because their bump touched only the version catalogue
  and the lint workflow only runs on `.kt`/`.kts` changes. Recorded as CE-owned in the
  upstream-sync playbook.

## [v1.1.0-ce.3] — 2026-09-01

Fixes the mpv video freeze after seeking, and an upstream sync.

### Fixed
- **mpv: video no longer freezes after seeking, switching audio track, advancing to the next episode, or turning the screen off and on.** The audio output default returns to `audiotrack`; the `aaudio` default introduced upstream freezes the video pipeline after any operation that flushes it while audio keeps playing. Reported and fixed by [@Emeseis](https://github.com/midasvo/ephyra/pull/53) (#52); tracked upstream as [findroid#1246](https://github.com/jarnedemeulemeester/findroid/issues/1246) and [mpv-android#1283](https://github.com/mpv-android/mpv-android/issues/1283), both still open.
- **If you already had the audio output set to `aaudio`, this release moves you to `audiotrack` once.** A stored preference wins over a changed default, and simply opening the Audio output picker used to store a value — so the fix above would not have reached most affected installs on its own. This runs a single time: if you deliberately select `aaudio` again afterwards, it stays. Settings → Player → mpv → Audio output.
- Select settings no longer save a value when you tap the option that is already selected. Doing so used to pin that preference to whatever the default was that day, silently blocking any later change to it — for every dropdown setting in the app, phone and TV.

### Maintenance
- Synced with upstream findroid: Gradle `9.7.1`, Android Gradle Plugin `9.3.2`, KSP `2.3.11`, androidx.paging `3.5.1`, aboutlibraries `15.1.1`.
- Portuguese translation updates.
- The mpv audio output default is recorded as CE-owned in the upstream-sync playbook, so a future sync does not silently restore upstream's `aaudio`.

## [v1.1.0-ce.2] — 2026-08-07

Upstream sync only — no CE behaviour changes.

### Maintenance
- Synced with upstream findroid: Jellyfin SDK `1.8.12`, Android Gradle Plugin `9.3.1`, slf4j-api `2.0.18`, Ruby `4.0.6`.

## [v1.1.0-ce.1] — 2026-07-23

Results of a full codebase audit (scan → triage → fix): 30 targeted commits across all modules.

### Fixed
- Downloaded episodes now show their download state in search, favorites, resume and latest lists while online.
- Home screen sections no longer intermittently disappear due to a state race between the parallel section loaders.
- The "Are you still watching?" prompt can actually trigger during episode auto-advance — its counter was being reset by the auto-advance seek itself.
- Player dialogs (track selection, speed, chapters, still-watching) no longer crash the app when restored after process death; added the `fragment-ktx` dependency this requires.
- External WebVTT subtitles are parsed as WebVTT instead of being fed to the SubRip parser.
- Premiere dates no longer display one day early in timezones west of UTC.
- Resolution badge: sub-720p sources are labeled SD instead of HD, and above-4K content is no longer labeled SD.
- Playback-stop reporting no longer sends a bogus played percentage when the player is released before the duration is known.
- mpv: video frame rate no longer reports the pixel width, and multi-item playlist inserts keep their order.
- TV: DPAD presses on the show screen no longer register twice, and the multi-select settings panel no longer shows the previous preference's checkboxes when moving focus between two multi-selects.
- Progress bars guard against items with unknown runtime (no more infinite-width layout).
- Missing posters/backdrops fall back to the placeholder instead of loading a broken "/null" URI.
- The home pull-to-refresh spinner now stays visible until the reload actually finishes.
- Setup: a server reporting no id shows a friendly error instead of crashing; Quick Connect failures are surfaced instead of silently swallowed; wrong-password detection uses the typed 401 exception instead of message matching; manually added server addresses are normalized, deduplicated, and failures are shown in the UI.
- Settings: subtitle font scale is clamped on the generic (TV) settings path too.

### Changed
- Home screen fetches "latest media" per library in parallel, and the show screen fetches season episodes in parallel — both were sequential network calls that scaled with library/season count.
- Room: added indices on `sources.itemId` and `mediastreams.sourceId` (schema v11, auto-migration) to avoid full-table scans on every list render.
- Downloads screen no longer queries each show's episodes twice per load, the images download worker reuses the shared OkHttp client, and the settings file editor does its I/O off the main thread.

### Maintenance
- Removed dead code: the unused TV track-selector dialog and helper, two unused DAO queries, and unused `onClick` fields on number-input preferences; the download poll loop now uses its named constant.

## [v1.1.0-ce.0] — 2026-07-23

### Changed
- Synced with upstream findroid `1.1.0` (up from `1.0.2`) — the CE tag base bumps accordingly and the iteration counter resets to 0.

### Added
- mpv preference now migrates automatically to the new player-backend preference; mpv settings are grouped together, and the config editor auto-focuses on open (ported from upstream).

### Maintenance
- Upstream's `DownloadManager` cursor-leak fix (#1227) doesn't apply to CE — we already replaced that backend with our own OkHttp-based download engine.
- Updated fastlane, `actions/checkout` (v7), aboutlibraries (v15), androidx.hilt (1.4.0), androidx.lifecycle (2.11.0), androidx.core (1.19.0), androidx.compose (1.11.4), Hilt (2.60.1), Kotlin, and the Android Gradle plugin (9.3.0); compile SDK/build tools bumped to 37.
- Spanish and Portuguese (Brazil) translation updates.

## [v1.0.2-ce.36] — 2026-07-12

### Maintenance
- Synced with upstream findroid (German translation updates).

## [v1.0.2-ce.35] — 2026-07-08

### Maintenance
- Synced with upstream findroid up to `1088a203` (Russian translation updates).
- Formalized the upstream-sync/release playbook as a Claude Code skill.

## [v1.0.2-ce.34] — 2026-07-01

### Added
- mpv `input.conf` editor and a dedicated mpv config editor, with mpv options split out into their own settings section (ported from upstream #1232).
- Configurable long-press player gesture — choose Chapter, 2× speed, or Disabled (#50).

### Fixed
- Kept the CE device-profile direct-play logic working after upstream removed the `playerMpv` boolean in favor of a `playerBackend` preference.

### Maintenance
- Synced with upstream findroid up to `07ac8919`.
- Updated Gradle (9.6.1) and Jetpack Compose (1.11.3); routine dependency bumps (#45) and translation updates.

## [v1.0.2-ce.33] — 2026-06-23

### Added
- Hide-watched filter in the library view, persisted and shared across libraries (#40, ported from upstream #1231).
- Long-press to copy text on the movie/show/episode/season/person detail screens (#42, ported from upstream #1229).

### Changed
- Replaced the Android `DownloadManager` backend with an OkHttp-based resumable download engine (#26, #27, #38).

## [v1.0.2-ce.32] — 2026-06-22

### Fixed
- Reworked the download orphan sweep: correct subtitle deletion, unmounted-SD purge, and non-recursive scanning (P0).
- Kept pending-download rows on a transient repository-resolve failure at startup (P1).
- Cancel paused downloads correctly and fixed an `ensurePump` race.
- Handled the data-sync foreground-service timeout and requested `POST_NOTIFICATIONS` at runtime.

### Maintenance
- Updated Coil, OkHttp, and Kotlin.

## [v1.0.2-ce.31] — 2026-06-09

### Added
- Deliver PGS, VobSub, and DVB subtitles without transcoding (#19).
- Configurable subtitle styling — colors, outline, font, and size (#24).
- Per-type skip / ask / ignore actions for media segments (#20).
- Surface chapters in the player UI (#25).
- "Are you still watching?" inactivity prompt (#22).
- Lazy trickplay loader behind a developer toggle (#21).
- Export device profile action on the About screen (#23).

### Maintenance
- Updated Jellyfin SDK (1.8.11), Gradle (9.5.1), KSP, and other dependencies.
- New and updated translations: Spanish (Latin America), Azerbaijani, Turkish.

## [v1.0.2-ce.30] — 2026-05-19

### Added
- Transcode Dolby Vision so it plays everywhere.

### Fixed
- Stop the download queue speed/ETA from flashing.

## [v1.0.2-ce.29] — 2026-05-19

### Fixed
- Re-resolve the download repository per use so downloads survive an online/offline mode switch.

## [v1.0.2-ce.28] — 2026-05-19

### Added
- Auto-enable offline mode when there is no connectivity.

## [v1.0.2-ce.27] — 2026-05-19

### Fixed
- Prevent a crash on startup when offline.

### Maintenance
- Updated dependencies and the Jellyfin SDK.

## [v1.0.2-ce.26] — 2026-05-09

### Fixed
- Accessibility labels on the back/sort buttons; stable `LazyColumn` keys.
- Clear the library loading state on both success and error.
- Trim whitespace from server address and username on submit.
- Avoid a `NoSuchElementException` on an empty title stack in settings.
- Buffer player events and guard the post-playback-stop math.
- Guard picture-in-picture params against an unknown video size.
- Handle a missing `currentServer` in `getDownloads`.
- Forward all season actions to the ViewModel (TV).

### Changed
- Enabled Gradle parallel builds + caching and explicit non-transitive R class.
- Scoped the release tag trigger to `v*-ce.*`.
- Internal cleanups: tighter ViewModel visibility, `val` section items, Timber over `println`, dead-file removal.

## [v1.0.2-ce.25] — 2026-05-09

### Maintenance
- Dependency updates: Compose, Kotlin, KSP, Android Gradle Plugin (9.2.0), Gradle (9.5.0), navigation-compose.
- Bulgarian translation.

## [v1.0.2-ce.24] — 2026-04-23

### Changed
- Updated mpv and switched to system fonts.
- Added the upstream-sync workflow.

### Maintenance
- French translations and dependency updates.

## [v1.0.2-ce.1 – v1.0.2-ce.23] — 2026-03-28 … 2026-04-10

Initial CE fork off findroid `1.0.2`. These rapid early iterations established the fork: the
`nl.midasvo.findroid.ce` application ID, downloads and SD-card storage improvements, and ongoing
tracking of upstream. See the git history between the corresponding tags for commit-level detail.

[v2.5.0]: https://github.com/midasvo/ephyra/releases/tag/v2.5.0
[v2.4.5]: https://github.com/midasvo/ephyra/releases/tag/v2.4.5
[v2.4.4]: https://github.com/midasvo/ephyra/releases/tag/v2.4.4
[v2.4.3]: https://github.com/midasvo/ephyra/releases/tag/v2.4.3
[v2.4.2]: https://github.com/midasvo/ephyra/releases/tag/v2.4.2
[v2.4.1]: https://github.com/midasvo/ephyra/releases/tag/v2.4.1
[v2.4.0]: https://github.com/midasvo/ephyra/releases/tag/v2.4.0
[v2.3.0]: https://github.com/midasvo/ephyra/releases/tag/v2.3.0
[v2.2.0]: https://github.com/midasvo/ephyra/releases/tag/v2.2.0
[v2.1.0]: https://github.com/midasvo/ephyra/releases/tag/v2.1.0
[v2.0.0]: https://github.com/midasvo/ephyra/releases/tag/v2.0.0
[v1.2.0-ce.6]: https://github.com/midasvo/ephyra/releases/tag/v1.2.0-ce.6
[v1.2.0-ce.5]: https://github.com/midasvo/ephyra/releases/tag/v1.2.0-ce.5
[v1.2.0-ce.4]: https://github.com/midasvo/ephyra/releases/tag/v1.2.0-ce.4
[v1.2.0-ce.3]: https://github.com/midasvo/ephyra/releases/tag/v1.2.0-ce.3
[v1.2.0-ce.2]: https://github.com/midasvo/ephyra/releases/tag/v1.2.0-ce.2
[v1.2.0-ce.1]: https://github.com/midasvo/ephyra/releases/tag/v1.2.0-ce.1
[v1.2.0-ce.0]: https://github.com/midasvo/ephyra/releases/tag/v1.2.0-ce.0
[v1.1.0-ce.5]: https://github.com/midasvo/ephyra/releases/tag/v1.1.0-ce.5
[v1.1.0-ce.4]: https://github.com/midasvo/ephyra/releases/tag/v1.1.0-ce.4
[v1.1.0-ce.3]: https://github.com/midasvo/ephyra/releases/tag/v1.1.0-ce.3
[v1.1.0-ce.2]: https://github.com/midasvo/ephyra/releases/tag/v1.1.0-ce.2
[v1.1.0-ce.1]: https://github.com/midasvo/ephyra/releases/tag/v1.1.0-ce.1
[v1.1.0-ce.0]: https://github.com/midasvo/ephyra/releases/tag/v1.1.0-ce.0
[v1.0.2-ce.36]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.36
[v1.0.2-ce.35]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.35
[v1.0.2-ce.34]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.34
[v1.0.2-ce.33]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.33
[v1.0.2-ce.32]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.32
[v1.0.2-ce.31]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.31
[v1.0.2-ce.30]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.30
[v1.0.2-ce.29]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.29
[v1.0.2-ce.28]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.28
[v1.0.2-ce.27]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.27
[v1.0.2-ce.26]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.26
[v1.0.2-ce.25]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.25
[v1.0.2-ce.24]: https://github.com/midasvo/ephyra/releases/tag/v1.0.2-ce.24
