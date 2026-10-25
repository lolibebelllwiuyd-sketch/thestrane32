# ZeinAC test plan

Run everything with `general.safe-mode: true` first, then repeat the "must be blocked" rows with safe-mode `false`.
Use two accounts: a **tester** (not exempt) and a **staff** account with `zeinac.alerts` and `zeinac.inspect`.
Enable `general.debug: true` while testing. `/zeinac inspect <tester>` shows the live counters.

## What the server can and cannot know

| Class | Examples | Mechanism |
|---|---|---|
| **Reliably prevented** | Break/open/place on a block out of reach or fully behind other blocks, blocks behind the player | Interaction validation (anti-freecam), level 3 |
| **Reliably prevented (information)** | Seeing underground caves/bases from the surface; seeing far underground chunks while mining | Hide module (PacketEvents) |
| **Only statistical** | X-ray ore finding | Rate + behaviour indicators; evidence only, never auto-punished |
| **Invisible to the server** | Plain Freecam camera movement; client-side rendering mods; looking at already-visible blocks | Not detectable. Not claimed. |

## Test cases

| # | Scenario | Expected |
|---|---|---|
| 1 | Branch mining (2-high tunnels, side branches every 3 blocks) for 30 min, honest play | Counters grow, no alert. Hidden-discovery rate near the expected value |
| 2 | Mining along a natural cave and picking visible ores | Ores counted as `exposed`, not `hidden`; no suspicion |
| 3 | Fortune III pickaxe on diamonds | Identical stats to without Fortune (veins are counted, not drops) |
| 4 | Lucky streak: 3 hidden diamonds in 300 blocks | Maybe `LOW` record at most, never a staff alert (needs rate score >= 8 or both families) |
| 5 | Walk in a long straight 1x2 tunnel, mine 100 blocks | Zero branches counted |
| 6 | Dig 10 short side branches, only 1 ends at ore | `branches=10 hits=1`, behaviour score ~0.2, no alert |
| 7 | Staff member with `zeinac.bypass` mines diamonds fast | No profile data, no alerts |
| 8 | Freecam (any client) camera flying around while the body stands still | Nothing logged; this is expected (invisible to the server) |
| 9 | Break a block behind a wall using a client that targets from the camera | `freecam/NO_LOS` (or `LOOK`/`REACH`); cancelled when safe-mode is off |
| 10 | Open a chest through a 1-block wall | `NO_LOS`, cancelled when enforcing |
| 11 | Break a block 4 blocks away in clear view | Always allowed |
| 12 | Break blocks while sprinting/elytra, strafing, mouse flicking | Allowed (pose history + lenient look angle) |
| 13 | Simulated lag: `tc qdisc add dev eth0 root netem delay 250ms 50ms` or a lag-switch client | No violations for legitimate play; check `/zeinac inspect` |
| 14 | Break through fences, panes, glass, leaves, slabs, stairs edges | Allowed (outline ray, 15 sample points). Report any false positive with the block names |
| 15 | Bedrock (Geyser) player with `.` prefix | LOOK check skipped, reach/LOS still enforced |
| 16 | Flood: click a hidden chest 200 times | Alert cooldown holds staff chat to one line per 30 s; DB gets one row per cooldown; LOS budget fails open above 40/s |
| 17 | `/zeinac exempt <tester>` then repeat #9 | Nothing recorded. `/zeinac unexempt` restores checks |
| 18 | Restart the server mid-session | Exemptions, lifetime counters and baseline reload; windows start empty |
| 19 | Delete/lock `plugins/ZeinAC/zeinac.db` while running | One warning per minute in console, plugin keeps working, `/zeinac status` shows `UNAVAILABLE` |
| 20 | Stand on the surface above Y34, then dig down below 34 | Hide module: no underground blocks while above; chunks re-sent a few per tick when you go below; no rubber-banding |
| 21 | Underground, check the world 4+ chunks away at Y<16 | Appears as plain stone/deepslate until you walk within 3 chunks |
| 22 | Remove PacketEvents and restart | Plugin starts, warns once, detection and validation still work |
| 23 | `/zeinac reload` with a typo in config.yml | "Reload failed, previous settings are still active" |
| 24 | Performance: 15 bots/players mining and moving for 10 min | Check `/timings` or Spark: ZeinAC should be far below 1% of tick time; DB queue stays near 0 in `/zeinac status` |

## Unit tests

`mvn test` runs 26 tests on the Bukkit-free logic: Poisson/binomial tails, violation decay, geometry, path/branch tracking,
rolling windows, baseline learning, hide rules. Those logic tests were executed during development.
