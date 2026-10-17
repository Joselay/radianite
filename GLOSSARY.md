# Glossary

Domain language for Radianite. Module names in code follow these terms.

## Domain

**Daily Store**: the four skins Riot offers an account each day, rolling over at 00:00 UTC. Also carries the **Night Market** and **featured bundles** when they're running. `DailyStore`.

**Night Market**: a periodic set of discounted skin offers, shown with the Daily Store while it runs. `NightMarketOffer`.

**Featured bundle**: a bundle on sale in the store. The welcome screen shows the ones the last store fetch saw, kept across sign-outs. `StoreBundle` (in the store), `FeaturedBundle` (welcome screen).

**Wallet**: the account's VP, Radianite Points and Kingdom Credits balances.

**Skin**: a weapon skin as the app shows it, at one of its levels: `StoreSkin`. Riot's store and loadout refer to skin *levels*; the catalog maps a level back to its skin.

**Match**: a finished game, reduced to what the app shows (`MatchRecord`), and seen from one player's point of view (`MatchSummary`). A **live match** is the agent select or game the player is in right now.

**Ranked rating change**: how one competitive game moved the player's rank (`RankedRatingChange`). The newest ones are attached to their matches and drawn as the career's **RR trend**.

**Career**: the player's identity, account level, rank this act, past act ranks, battle pass and RR trend.

**Leaderboard**: the region's competitive ranking for the current act. Players can hide their name; they show as "Secret Agent". `LeaderboardEntry`.

**Collection**: the player's equipped loadout, owned skins and owned agents.

**Content**: names and art for the IDs Riot returns (agents, maps, ranks, acts, queues, weapons, titles, battle passes, skins, tiers, bundles), from the community-run valorant-api.com, per game version.

**Session**: the signed-in account's Riot tokens, shard and region (`RiotSession`). Renewed silently with the Riot cookies the sign-in page set; when that fails the session has **expired** and the user signs in again.

## Modules

**Riot player data** (`RiotPlayerData`): every call to Riot's game endpoints for the signed-in account. Hides the session, client version, hosts, headers, token renewal and expiry. Tests substitute the transport through the `OkHttpClient`.

**Account session** (`Account`): who is signed in. Restores, completes and ends sign-in, and clears the account's local data when it ends.

**Content** (`Content`): the current patch's names and art, with version discovery and per-version caching. Falls back to the last index when valorant-api.com is unreachable.

**Store** (`StoreRepository`): the account's Daily Store with its wallet, cached until it rotates.

**Match History** (`MatchHistory`): the account's matches from its point of view. Pages through them, caches finished matches and attaches ranked rating changes.

**Local store** (`LocalStore`): every file the app keeps, declared once in `Slots` with its lifetime: account data is cleared on sign-out, device data survives it.

**Failure** (`Failure`): why a load failed, in the terms the app reacts to: expired, maintenance, rate-limited, offline, a Riot error, or other.
