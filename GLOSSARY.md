# Glossary

Domain language for Radianite. Module names in code follow these terms.

## Domain

**Daily Store**: the four skins Riot offers an account each day, rolling over at 00:00 UTC. Also carries the **Night Market** and **featured bundles** when they're running. `DailyStore`.

**Night Market**: a periodic set of discounted skin offers, shown with the Daily Store while it runs. `NightMarketOffer`.

**Featured bundle**: a bundle on sale in the store. The welcome screen shows the ones the last store fetch saw, kept across sign-outs. `StoreBundle` (in the store), `FeaturedBundle` (welcome screen). Its **contents** are its skins, with their variants, and the buddies, sprays, player cards and titles that come with them, each with its price in the bundle and on its own: `BundleContents`.

**Accessory Store**: the weekly offers of buddies, sprays, player cards and titles sold for Kingdom Credits, part of the same storefront as the Daily Store. `AccessoryOffer`.

**Wallet**: the account's VP, Radianite Points and Kingdom Credits balances.

**Skin**: a weapon skin as the app shows it, at one of its levels: `StoreSkin`. Riot's store and loadout refer to skin *levels*; the catalog maps a level back to its skin.

**Match**: a finished game, reduced to what the app shows (`MatchRecord`), and seen from one player's point of view (`MatchSummary`). Only finished matches: the app never reads a match while it's being played.

**Ranked rating change**: how one competitive game moved the player's rank (`RankedRatingChange`). The newest ones are attached to their matches and drawn as the career's **RR trend**.

**Career**: the player's identity, account level, rank this act, past act ranks, battle pass and RR trend.

**Leaderboard**: the region's competitive ranking for the current act. Players can hide their name; they show as "Secret Agent". `LeaderboardEntry`.

**Map pool**: the maps ranked plays this act. The Maps tab lists them first, then the maps out of it.

**Best comp**: the one comp that wins most on a map in Radiant ranked games, from vstats.gg, in `team_comps.json` (`CompGuide`, one `MapGuide` per map). Each map takes the newest act with at least 5,000 Radiant team games on it, since early in an act few players have reached Radiant. Only comps at least 1 in 100 of those teams played, and at least 100 times, count; of those, the best has the highest win rate at the low end of its 95% confidence interval, so a lucky streak over a few games can't top it. `scripts/best_comps.py` writes the file. `BestComp`.

**Map play**: how a map has gone in the player's own competitive games on the phone: their record and the agents they played. `MapPlay`.

**Radianite upgrade**: a skin level or chroma bought with Radianite Points once the skin is owned (`RadianiteUpgrade`). A skin's first level and first chroma come with it. What a skin's locked upgrades cost is its **Radianite to max**; for one skin with what's unlocked, `SkinUpgrades`.

**Collection**: the player's equipped loadout, owned skins and owned agents, with each skin's store price and the collection's total **value** in VP.

**Wishlist**: skins the user is waiting for, kept for the device rather than an account. A wishlisted skin in any signed-in account's Daily Store or Night Market is **announced** with a notification, once per rotation. `Wishlist`.

**Content**: names and art for the IDs Riot returns (agents, maps, ranks, acts, queues, weapons, titles, battle passes, skins, tiers, bundles), from the community-run valorant-api.com, per game version.

**Session**: one account's Riot tokens, shard and region (`RiotSession`). Renewed silently with that account's Riot cookies, copied from the sign-in page when it signed in (`AccountCookies`); when that fails the session has **expired** and the account signs in again.

**Saved account**: an account signed in on this device (`SavedAccount`). Several can be; one at a time is **shown**.

## Modules

**Riot player data** (`RiotPlayerData`): every call to Riot's game endpoints for one signed-in account. Read-only: nothing acts on the account or reveals what the game hides. Hides the session, client version, hosts, headers, token renewal and expiry. Tests substitute the transport through the `OkHttpClient`.

**Riot sign-in** (`RiotSignIn`): turns tokens from Riot's sign-in redirect into a session, for a new sign-in and for every renewal.

**Account session** (`Account`): who is signed in and which account is shown. Restores, adds, switches and ends sign-ins, and clears an account's local data when it ends.

**Content** (`Content`): the current patch's names and art, with version discovery and per-version caching. Falls back to the last index when valorant-api.com is unreachable.

**Store** (`StoreRepository`): the account's Daily Store with its wallet, cached until it rotates.

**Match History** (`MatchHistory`): the account's matches from its point of view. Pages through them, caches finished matches and attaches ranked rating changes.

**Local store** (`LocalStore`): every file the app keeps, declared once in `Slots` with its lifetime: account data lives in that account's own store (`forAccount`) and is cleared on sign-out; device data survives it.

**Store sync** (`StoreSync`): fetches every signed-in account's store in the background after each rotation, feeding the home-screen widget (`StoreWidget`) and wishlist announcements.

**App updates** (`AppUpdates`): new versions of the app, published as GitHub releases tagged with the version ("v1.2.3", an `AppVersion`). Offers a newer one once per release, in the app and as a notification (`UpdateCheck`), downloads its APK and hands it to Android's installer. Release builds only; every release is signed with the same key.

**Team comps guide** (`TeamComps`): the best comps file, shipped with the app and updated from the app's GitHub repository, so new numbers need no release. The newest of the shipped, saved and downloaded copies wins.

**Failure** (`Failure`): why a load failed, in the terms the app reacts to: expired, maintenance, rate-limited, offline, a Riot error, or other.
