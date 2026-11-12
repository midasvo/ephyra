# ADR 0002: Navigation with a side drawer

**Status:** Accepted (2026-11-02, in #121)

## Context

TV now uses a top `TabRow` (Search, Home, Libraries) with a profile button that opens Settings ([1-walkthrough.md](../1-walkthrough.md), F10 and F11):

- tabs switch only on OK;
- the Libraries screen takes focus away from the tab row when it loads;
- libraries are one level deeper than needed (tab → grid of libraries → library);
- Back on Home exits the app right away.

Wholphin uses a collapsed icon rail on the left that expands into a drawer, with each library in it by name, and Back on Home opens the drawer ([2-wholphin.md](../2-wholphin.md) §4). Google TV and most streaming apps use the same pattern.

## Options

1. **Keep the top tabs and fix them** (switch on focus, add the libraries as tabs). Few changes, but the row becomes crowded with more than about 4 libraries, and Search, Favorites and Settings still have no natural place.
2. **Side drawer** (`androidx.tv.material3.ModalNavigationDrawer`, available in our `tv-material` 1.1.0).
3. **A hybrid:** a drawer for the sections, top tabs inside a library (Recommended / Library / Genres), like Wholphin.

## Decision

Option 3, built in two steps.

**Step 1 (G9):** a drawer, collapsed to an icon rail by default and expanding when it has focus:

- profile (user + server; OK opens the user switch), Search, Home, Favorites, then each library by name, then Settings at the bottom;
- the libraries come from `MediaViewModel`, in server order;
- the content area changes on OK, not on focus. That keeps us from loading a library just because the user moved past it.

**Back behaviour:**

| Where | Back does |
|---|---|
| Any screen other than a drawer destination | One level up (as now) |
| A drawer destination other than Home | Go to Home |
| Home, drawer closed | Open the drawer with focus on Home |
| Drawer open | Exit the app |

**Step 2 (with G15):** inside a library, tabs for *Library* (grid) and, if wanted, *Genres*. A "Recommended" tab is optional and can wait.

## Consequences

- Replaces `MainScreen` and `MediaScreen` with CE screens (ADR 0001). The "Libraries" grid as its own page disappears.
- Search (G4) and Favorites (G17) get their place in the drawer, so they don't need a tab.
- An app exit always needs two presses of Back from Home, which matches what Android TV users expect.
- With many libraries the drawer has to scroll, and focus restore (ADR 0003) also applies inside the drawer.

## Safe area (added 2026-11-11, #539)

The drawer from #127 was padded 12 dp from the left edge, so its icons sat at 28 dp (32 dp expanded), inside the 48 dp margin a TV may cut off.

**Decision:** content inside the safe area, background may bleed. The drawer's icons, the profile avatar and the labels start at 48 dp or more; the drawer's surface and the focus pill may reach into the margin. Vertically the drawer already keeps the 27 dp.

- The items start 36 dp in (`DrawerStartPadding`): tv-material puts the icon 16 dp into a collapsed item (20 dp expanded), and the 32 dp avatar overflows its 24 dp box by 4 dp. At 1080p that is the avatar at 48 dp, the icons at 52 dp (56 dp expanded), the labels at 100 dp, the pill at 36–92 dp (36–292 dp expanded).
- The pages beside the drawer keep their 80 dp inset (`ClosedDrawerWidth`) and their own 48 dp padding, so their content stays at 128 dp. The collapsed rail is 92 dp wide and covers 12 dp of that padding, where there is no content; a row scrolled sideways is cut at 92 dp instead of 80 dp. The rail ends at the pill, so the glow of a focused first card is not cut.
- Expanded, the surface keeps 12 dp after the pill and grows from 280 to 304 dp; the items keep tv-material's 256 dp, so the labels truncate where they did.
- The icons still move 4 dp to the right when the drawer opens, as before.
- The profile item is a CE item 64 dp high (#567), so the server name fits under the user name; tv-material's drawer item is fixed at 48 dp. Same pill, colours, ring and positions; the list below it starts 16 dp lower.
- An expanded item now ends at 292 dp, past the right edge of a 150 dp poster at 128 dp (Favorites). When Right finds nothing right of the item, the drawer enters the content instead, so focus does not stay in the drawer.
