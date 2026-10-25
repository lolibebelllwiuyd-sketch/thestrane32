# ZeinAC - anti-xray + anti-freecam for Zein SMP (Paper)

Evidence first, punishment second. ZeinAC never looks at client names or brands. It uses only what the server can verify or measure.

| Module | What it does | Needs |
|---|---|---|
| **Anti-Freecam** (`anti-freecam`) | Checks every block break / use / place against the player's real server-side position: reach, line of sight (outline ray cast, 15 sample points) and look direction, with latency tolerance. Impossible actions are cancelled (level 3, safe-mode off) and logged with a decaying violation level | Paper |
| **Hide module** (`hide`) | Server never sends anything under Y16 to players on the surface; underground players only get it within 3 chunks. Caps what Freecam/X-ray can see | PacketEvents (optional) |
| **Anti-Xray statistics** (`anti-xray`) | Separates ores that were already visible from ores revealed by the player's own digging, then tests the result against luck (Poisson), the player's own history, and how often fresh branches end exactly at hidden ore (binomial). Evidence only | Paper |
| Alerts / storage | Cooldown-limited staff alerts, SQLite evidence log on its own thread, optional Discord webhook | SQLite (bundled with Paper) |
| Level 4 escalation | Configurable commands, staff-triggered (`/zeinac escalate`). Statistical X-ray evidence can never trigger it | off by default |

## Install

1. Optional but recommended: install **PacketEvents** (Spigot/Paper build, the same one XrayShield used) for the hide module.
2. Drop `ZeinAC-1.0.0.jar` into `plugins/`, start the server once, stop it.
3. Enable Paper's anti-xray as described in `docs/PAPER_ANTIXRAY.md`.
4. Leave `general.safe-mode: true`, play/test for a day or two, review `/zeinac alerts recent 20` and `/zeinac evidence <player>`.
5. When the alerts look right, set `general.safe-mode: false` and `/zeinac reload`.

## Build

Requirements: JDK 21 and Maven 3.9+ (internet access for the first build).

```
mvn clean package
```
The plugin JAR is `target/ZeinAC-1.0.0.jar`.

* No PacketEvents API available or you want to skip the hide module: `mvn clean package -Pno-packetevents`
* Different Paper version: change `paper.version` in `pom.xml` (the code uses only stable Paper API 1.21.x calls).

## Commands and permissions

| Command | Permission | Purpose |
|---|---|---|
| `/zeinac status` | `zeinac.admin` | Version, level, safe-mode, module and database state |
| `/zeinac alerts [toggle \| recent [n]]` | `zeinac.alerts` | Mute/unmute your alerts, or list the latest in memory |
| `/zeinac inspect <player>` | `zeinac.inspect` | Live counters, VL, suspicion, per-ore-group stats (player must be online) |
| `/zeinac evidence <player> [n]` | `zeinac.inspect` | Stored alerts from the database (works offline) |
| `/zeinac reload` | `zeinac.admin` | Reload config (invalid config keeps the old one) |
| `/zeinac exempt <player>` / `unexempt` | `zeinac.exempt` | Persistent exemption for a testing account |
| `/zeinac escalate <player>` | `zeinac.admin` | Run the configured escalation commands (level 4 only) |

Alias: `/zac`. `zeinac.bypass` (default false) makes an account invisible to every check. Do not give it to normal staff, they should be tested too.
`zeinac.admin` includes alerts, inspect and exempt.

## How to read an alert

```
[ZeinAC] Steve » xray/ORE_STATS_DIAMOND HIGH score 14.2 @world -120 -54 300
  diamond: hidden=9/420 blocks exp=1.05 p=2.1e-06 | own p=4.0e-05 | branches=11 hits=6 p=1.3e-05 | exposed=1 chunks=7 families=rate+behaviour
```
* `hidden=9/420`: 9 vein discoveries revealed only by his own digging, in 420 stone blocks. `exp=1.05`: what luck predicts.
* `own p`: compared with this player's earlier history. `branches/hits`: of 11 fresh branches, 6 ended at hidden diamonds.
* `families=rate+behaviour`: both independent families fired, which is what makes it HIGH. Rate only is at most MEDIUM and needs a very large score.

```
[ZeinAC] Alex » freecam/NO_LOS MEDIUM score 3.0 @world 10 12 -4
  NO_LOS dist=3.10 max=6.00 angle=0 block=CHEST action=use
```
This means the server proved that no clear line exists from Alex's body to that chest. A single event can be a rare edge case (see below); sustained VL is the signal.

## Tuning

* Too many freecam LOW/MEDIUM alerts from honest players: raise `anti-freecam.vl.alert`, `reach.extra`, `look-max-degrees`, or turn `look-check` off. Report the block type from the detail line.
* Strip miners flagged on behaviour: raise `anti-xray.branch.null-hit-probability`.
* Baselines wrong for your seed: `expected-per-1000` values are priors only. With `learn-from-server: true` they adapt after a few tens of thousands of mined blocks. Compare `/zeinac inspect` for several honest players.
* Plugins that add reach (e.g. extended reach enchants): raise `anti-freecam.reach.extra`.
* VeinMiner/TreeCapitator style plugins: keep `multi-break.enabled: false`.

## Known limitations and false-positive sources

* **Plain Freecam cannot be detected.** It only moves the client's camera. ZeinAC prevents the unfair *actions* and limits the *information* the client receives, nothing more.
* Entities (mobs, item frames, dropped items) underground are still sent to clients; the hide module only rewrites blocks. Tile-entity data of chests in hidden chunks is still included in the chunk packet (the vanilla client discards it, a modified client could read it).
* Proximity radius: inside 3 chunks of an underground player everything is real, so an X-ray client still sees ores there unless Paper's anti-xray hides them. That is why both layers are needed.
* Line of sight uses the server's block outlines. Odd shapes (fence tops, walls, some modded or resource-pack-only models, Geyser/Bedrock touch aiming) can produce rare false positives. LOS fails open above the per-second budget.
* Latency: accepted from any pose in the last 350 ms plus a 1.5-block reach margin. Extreme lag spikes (>350 ms jitter with fast movement) may still trigger single LOW events.
* X-ray statistics need volume: below `min-counted-blocks` / `min-hidden-discoveries` nothing is evaluated. A careful cheater who mines only a few ores per hour stays below the thresholds by design (false-positive safety).
* Hidden-ore classification assumes the player digs the tunnels. Ores exposed by another player's TNT/quarry just before are "already visible", which only makes the check more lenient.
* Simulation (see `docs/TEST_PLAN.md`): with defaults honest players trip the rate indicator in ~0.5% of very long sessions (LOW, not a staff alert), and an X-ray user at 6x normal rate is flagged in ~80% of 800-block sessions.
* Reload resets in-memory windows. Restart keeps lifetime counters, baselines and exemptions.

## Project layout

```
src/main/java/me/zein/zeinac
  ZeinAC.java                main class, wiring, reload
  config/Settings.java       validated immutable config snapshot
  core/                      Bukkit-free logic (statistics, geometry, path tracker, windows) - unit tested
  freecam/                   FreecamModule (events), InteractionValidator (reach/LOS/look), FreecamState
  xray/XrayModule.java       mining statistics
  hide/                      HideSnapshot + HideService (core) ; hide/impl = PacketEvents code (optional)
  alert/                     AlertManager, EscalationManager, WebhookNotifier
  storage/Database.java      SQLite on a dedicated thread
  exempt/, enforce/, command/, model/, util/
src/main/resources           plugin.yml, config.yml
src/test/java                JUnit 5 tests for core/
docs/                        PAPER_ANTIXRAY.md, TEST_PLAN.md
```

## Verification status (read this)

This source was written and checked without access to the Paper and PacketEvents jars:

* The Bukkit-free logic (`core/`, `HideSnapshot`) compiled and its 26 unit tests passed.
* The whole plugin (47 classes) compiled against hand-written stand-ins of the Bukkit, Adventure and PacketEvents APIs. That catches typos and type errors in this code, but not mistakes in how those APIs are actually named.
* It has **not** been built with Maven against real Paper 1.21.1 / PacketEvents 2.12.2 and **not** run on a server. If `mvn package` reports an error, it will most likely be in `hide/impl/` (PacketEvents method names such as `createBlockState`, `EncodedBlock#getY`) - build with `-Pno-packetevents` to confirm the rest, and send me the error text.
* SQLite code uses plain `java.sql` against the JDBC driver bundled with Paper and was not executed here.
