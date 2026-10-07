# BaseHider 1.0.0

Per-player, packet-only hiding of underground bases for **Purpur 26.2** (Java 25).
The real world is never modified: only packets sent to each client are changed.

## How it works

* **Rule.** Content whose *top* is at least `hide-distance-y` (30) blocks below the player is shown as AIR.
  It is shown again when it is less than `reveal-distance-y` (16) below the player. The 14-block gap is the
  hysteresis band.
* **One number per player.** Because the rule only depends on Y, the whole state of a player is a single
  integer, the *ceiling*: every 16x16x16 section with index <= ceiling is hidden for that player.
* **Block approximation (explicit).** Blocks are hidden per 16-block section. A section is hidden once its
  top block is >= 30 below the player. Result: nothing shallower than 30 blocks is ever hidden; blocks
  between 30 and 45 below may still be visible until their whole section qualifies. Exact per-block
  hiding would require rewriting every chunk each time the player moves a single block.
* **Entities are exact** (per entity Y, same thresholds, same hysteresis).
* **Chunk packets.** On `CHUNK_DATA`, hidden sections are replaced by a single-value AIR palette (tiny)
  and block entities inside them are removed from the packet. The chunk is registered with the ceiling it
  was sent with.
* **Already-sent chunks.** When the ceiling changes, only chunks whose recorded ceiling differs are touched,
  nearest first, within `transition-budget` per tick. Hide = one AIR multi-block-change per section (no world
  reads). Reveal = real blocks read from the loaded chunk, plus `Player#sendBlockUpdate` for block entities.
* **Block updates.** `BLOCK_CHANGE`, `MULTI_BLOCK_CHANGE`, `BLOCK_ENTITY_DATA`, `BLOCK_ACTION` (pistons, chests),
  `BLOCK_BREAK_ANIMATION` are dropped when inside a hidden section (the client already shows AIR).
  `EFFECT`, `SOUND_EFFECT`, `PARTICLE` are dropped for hidden positions (`filter-effects`).
* **Entities.** A spawn inside the hidden zone is cancelled; later packets for that id are cancelled; moving
  entities are re-checked once per `update-interval-ticks`. Reveal uses `Player#hideEntity` + `showEntity`, so
  the server itself sends a complete, correct spawn. Entities always keep existing for everyone else.
* **Threads.** Netty threads: packet filtering only (PacketEvents wrappers + `PlayerState`). Main thread: all
  Bukkit access (positions, chunks, entities, block reads). Shared state is `volatile` / synchronized on
  `PlayerState` / concurrent maps. No async work is used.

## Build

    mvn clean package

Output: `target/BaseHider-1.0.0.jar`

## Install

1. Put **PacketEvents 2.13.0 (Spigot build)** in `plugins/` (it supports Minecraft 26.2).
2. Put `BaseHider-1.0.0.jar` in `plugins/`.
3. Start Purpur 26.2 on Java 25. Look for `BaseHider enabled for worlds [world]`.
4. `/basehider status` shows counters and your own state.
5. Dependency errors: `PacketEvents is not installed/enabled` means step 1 is missing; "Unsupported API version"
   means `api-version` in plugin.yml does not match your build; Maven cannot resolve Purpur -> check the
   `https://repo.purpurmc.org/snapshots` repository and the `[26.2.build,)` range.

## Commands / permissions

`/basehider reload` and `/basehider status` need `basehider.admin` (default: op).

## Testing checklist

* Stand at Y=100 over a base at Y<=60: base invisible. Dig down: it appears when the section top is within 16 blocks.
* Fly up and down around the band (Y 70..84): no flicker (hysteresis).
* Second player standing next to the base sees it normally.
* Break/place/pistons/water in the hidden base: nothing reaches the high player (check with `/basehider status`: cancelled packets grow).
* Chest, furnace, sign, spawner in the base: not visible; visible again after descending.
* Villager/mob in the base: invisible from above, visible after descending, still exists for others.
* Chunk unload/load (fly away and back), relog, teleport (`/tp`), nether/end portal, death/respawn, server restart.
* `/basehider reload` with a changed `hide-distance-y`.

## Performance analysis (theoretical, not a measured benchmark)

* **Every `update-interval-ticks`:** one loop over online players: read Y, compare with the cached Y, iterate
  that player's tracked entities (hash lookups by UUID). No block access.
* **Only when Y crosses a section boundary band:** ceiling changes, nearby chunks are queued; work is capped by
  `transition-budget` per player per tick.
* **Only when a chunk is sent:** the chunk packet is decoded and re-encoded once per player (the dominant cost,
  proportional to chunk loads).
* **Cached:** per player the ceiling, a chunk->ceiling map (~400 entries at view distance 10), tracked entities
  (id, UUID, flag), and a bounded BlockData->state-id map. **Not cached:** chunk data, block states, worlds.
* **1 / 5 / 15 players:** cost grows linearly with players; idle players (not changing Y band, no chunk loads)
  cost one position read per second. Memory is a few hundred KB per player at most.

## Limitations (honest)

* This raises the cost of base detection; it cannot make it impossible. Remaining leaks include: light data in
  chunk packets and light updates (lit/dark patterns), server-side sounds not sent as sound packets, maps,
  the player list / tab, entity packets for ids the plugin never saw spawn, `SET_PASSENGERS` passenger lists,
  hacked clients using heuristics such as timing or world-border/chunk-load behaviour, and anything the server
  itself reveals (chat, plugins, coordinates).
* Chunks sent while a world is disabled (or before the join event) are not tracked, so after enabling a world
  via reload players should re-enter that world.
* If the plugin is disabled at runtime (not at server stop), blocks are revealed again but hidden entities stay
  hidden until the player relogs (Bukkit refuses `showEntity` for a disabled plugin).
* Heightmaps are not altered; they describe the surface, which stays visible.
* Not compiled by the author: build it with `mvn clean package` and report the first error if any.
