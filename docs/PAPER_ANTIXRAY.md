# Paper's built-in anti-xray (use it together with ZeinAC)

ZeinAC does **not** replace Paper's ore obfuscation. The two do different jobs:

| Layer | What it does | Who does it |
|---|---|---|
| Ore obfuscation | Client never receives the position of ores that are not exposed to air | Paper (`anti-xray`) |
| Underground hiding | Client receives no caves/bases/chests under Y16 while on the surface, and only nearby ones while underground | ZeinAC `hide` module (needs PacketEvents) |
| Interaction validation | Server refuses break/use/place through walls or out of reach | ZeinAC `anti-freecam` |
| Behaviour statistics | Flags players whose mining results cannot be explained by luck | ZeinAC `anti-xray` |

## Recommended settings (Paper 1.21.x: `config/paper-world-defaults.yml`, or per world in `paper-world.yml`)

```yaml
anti-xray:
  enabled: true
  engine-mode: 2          # 1 = hide ores behind stone; 2 = also sprinkle fake ores (defeats "x-ray by exclusion")
  max-block-height: 64    # only blocks at or below this Y are processed (keeps the CPU cost low)
  update-radius: 2        # how far around a changed block hidden blocks are re-sent
  lava-obscures: false
  use-permission: false   # true lets you exempt staff with the permission paper.antixray.bypass
  hidden-blocks:
    - copper_ore
    - deepslate_copper_ore
    - gold_ore
    - deepslate_gold_ore
    - iron_ore
    - deepslate_iron_ore
    - coal_ore
    - deepslate_coal_ore
    - lapis_ore
    - deepslate_lapis_ore
    - mossy_cobblestone
    - obsidian
    - chest
    - diamond_ore
    - deepslate_diamond_ore
    - redstone_ore
    - deepslate_redstone_ore
    - clay
    - emerald_ore
    - deepslate_emerald_ore
    - ender_chest
  replacement-blocks:
    - stone
    - oak_planks
    - deepslate
```

For the Nether use a separate block in `paper-world.yml` for `world_nether` with `engine-mode: 1`, `max-block-height: 128`
and `hidden-blocks: [ancient_debris, nether_gold_ore, nether_quartz_ore]`, `replacement-blocks: [netherrack]`.
Check the exact key names against your Paper version's generated file; they have been stable but are version-dependent.

## Limitations of server-side ore obfuscation (be honest with your players)

* It does not *detect* anyone. It only reduces what a cheating client can learn.
* An ore becomes visible the moment it touches air, so tunnelling next to hidden ore still reveals it. That is exactly the case ZeinAC's statistics look at.
* Engine mode 2 costs more CPU and bandwidth than mode 1; on 5-15 players that is negligible.
* Plugins that change blocks without normal updates (some world editors, large explosions) can cause brief ore "pop-in".
* Hidden-blocks lists cover blocks, not entities, so mobs, item frames and dropped items underground are still sent.
