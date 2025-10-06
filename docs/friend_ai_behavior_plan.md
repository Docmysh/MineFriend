# "The Friend" Entity AI Behavior Plan

*Target platform: Minecraft Forge 1.20.1*

This document details the behavioral design for the "Friend" entity across its four narrative phases. The objective is to make the entity feel increasingly invasive by combining rich telemetry, context-aware pathfinding, and staged behavioral unlocks.

## Core Systems (All Phases)

### Player Telemetry Logging
- **Scope:** Capture chat messages, repeated movement patterns (e.g., sprint-jumping), and building/crafting habits.
- **Storage:** Persist to a dedicated data file that phases 3 and 4 can replay to mimic the player.
- **Update cadence:** Append entries as events occur. Summaries (e.g., most-used block palette) should be recomputed every in-game day.

### Enhanced Pathfinding
- **Requirements:** Use a player-like navigation stack rather than standard mob goals.
- **Capabilities:** Door interaction, ladder use, trap avoidance, and slight randomization to avoid robotic straight-line paths.
- **Fallback:** If navigation fails three times, teleport to a safe node that remains outside the player's direct sightline.

### Chat Emulation
- When sending chat messages, the entity must halt movement for a configurable "typing" window to improve authenticity.

---

## Phase 1 – "The Observer"

| Attribute | Design |
|-----------|--------|
| **Spawn cadence** | Spawns once within first two in-game days when the player is inside a UI for \>5 s. Appears within a 30-block radius but outside direct view. |
| **Movement profile** | Trails the player at 10–15 blocks, matching stops/starts. Idle pacing includes slow walk cycles and occasional glances at the player. Path includes light variance and small-gap jumping. |
| **Interactions** | Low-probability "helpful" actions: punching nearby trees, replanting saplings. Avoids mining stone/ores. Runs toward the player when threatened; does not fight. |
| **Chat** | Limited friendly chatter. Stops moving during messages to sell the illusion of typing. |

### Implementation Notes
- Use a passive goal selector with low-frequency random stroll targets anchored near the player.
- Configure flee goals to redirect toward the player's position when hostiles aggro the entity.
- Learning focus is observational: log all player actions for future phases.

---

## Phase 2 – "The Stalker"

| Attribute | Design |
|-----------|--------|
| **Presence** | Body only manifests when watched. Spawns at render-distance edge (hilltops, cave mouths). Despawns when out of sight to maintain omnipresence illusion. |
| **Vanish trigger** | Player maintains crosshair focus \>3 s or moves within 20 blocks. Play subtle smoke particles on vanish. |
| **Movement** | None; remains stationary but keeps head locked on player position. |
| **Post-vanish behavior** | Executes delayed mimicry of last observed player action (e.g., breaking a coal block elsewhere). May place signs with dialogue from its pool along recent player routes. |

### Implementation Notes
- Leverage render-distance heuristics to choose spawn nodes with clear sightlines.
- Use timers to delay mimicry events; ensure actions happen off-screen to keep the effect uncanny.

---

## Phase 3 – "The Mimic"

| Attribute | Design |
|-----------|--------|
| **Spawn rules** | Can appear inside the player's base when they change rooms/floors, provided it remains outside immediate line of sight. |
| **Movement** | Replay learned player habits: sprint-jump cadence, door etiquette, preferred block routes. Walks invasive patrol paths, often stopping in doorways or just short of the player. |
| **Interactions** | Gains targeted block interactions: recolor beds, swap armor stand gear, replace chest inventories with curated loot (e.g., rotten flesh), and construct mocking structures based on cached player builds. Triggers auditory hallucinations tied to specific coordinates. |

### Implementation Notes
- Convert telemetry summaries into behavior weights (e.g., door left-open ratio).
- Mocking structure builds should use simplified schematics captured from player-made builds (e.g., bounding box snapshots).
- Ensure all intrusive actions respect cooldowns to avoid repetition fatigue.

---

## Phase 4 – "The Replacement"

| Attribute | Design |
|-----------|--------|
| **State** | Final form. Entity becomes invulnerable, non-interactive, and assumes ownership of the world. Player is reclassified as hostile. |
| **Routine** | Executes daily loops derived from player telemetry (bed → chest → crafting table → farm, etc.). Ignores the real player entirely. |
| **World manipulation** | Redirects mob hostility to the player, rebinds tamed animal ownership to itself, and performs a scripted monologue before issuing the kick command that removes the player. |

### Implementation Notes
- Treat the entity as the world anchor: mobs should target the player preferentially while ignoring the AI.
- The scripted monologue should use timed delays between lines for dramatic pacing before the final kick event.
- Continue logging to track post-replacement behavior for potential epilogues or analytics.

---

## Development Checklist

1. Implement telemetry service with serialization hooks for reuse across phases.
2. Build custom pathfinding goals with door/ladder support and randomized offsets.
3. Phase-specific controllers should be modular, enabling state transitions without respawning the entity manually.
4. Author dialogue pools and hallucination triggers in resource files for localization support.
5. Ensure compatibility with Forge 1.20.1 lifecycle events and data pack integration.

