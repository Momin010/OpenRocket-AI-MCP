# OpenRocket AI Bridge (MCP)

OpenRocket can expose itself to AI agents (Claude Code, or any
[Model Context Protocol](https://modelcontextprotocol.io) client) over a small local
HTTP server. The agent can read the design you have open, run simulations, and edit
components — and the changes appear **live** in the running GUI.

## Quick start

1. **Launch OpenRocket** (needs JDK 17 to build — see *Building* below).
2. Open **Tools → AI Copilot (MCP bridge)…**. A companion window appears.
3. Click **Start**. The window shows the connect command and a live feed of every
   tool the agent calls.
4. In a terminal, connect Claude Code:

   ```bash
   claude mcp add --transport http openrocket http://127.0.0.1:8723/mcp
   ```

   If you set a token in the panel, append:
   `--header "Authorization: Bearer <token>"`.

That's it — ask the agent to "list the components of my rocket" or "add a nose cone and
a body tube, give it a C6 motor, and tell me the apogee."

### Auto-start (headless / unattended)

Start the bridge automatically at launch with system properties:

```bash
./gradlew run -Dopenrocket.mcp.autostart=true        # start on port 8723
             -Dopenrocket.mcp.panel=true             # also open the live panel
             -Dopenrocket.mcp.port=8723               # override the port
             -Dopenrocket.mcp.token=secret            # require a bearer token
```

When `openrocket.mcp.autostart=true`, the modal welcome dialog is skipped so an agent
session is never blocked.

## Security

The server binds to **127.0.0.1 only** (never your network). For extra safety set a
token in the panel (or via `-Dopenrocket.mcp.token`); requests must then carry
`Authorization: Bearer <token>`.

## Tools

| Tool | Purpose |
|------|---------|
| `list_open_designs` | List open designs and their `designIndex` |
| `new_design` | Create a new empty rocket in a new window |
| `open_file` / `save_file` | Open / save `.ork` files (headless, no dialogs) |
| `save_screenshot` | Save a PNG of the design view (2D schematic side/back/top, or 3D) |
| `animate_flight` | Open an animated 2D playback of the rocket flying its trajectory |
| `render_flight_video` | Photoreal 3D MP4 of the launch via Blender (real rocket model, trees/hills for depth, parachute, exhaust). Multi-camera cuts by default, or set `camera` (ground/chase/tracking/orbit/onboard/recovery) for a single-angle clip; `scene` day/sunset/space |
| `get_component_tree` | Full component tree (ids, types, names, nesting) |
| `get_component` | Every readable parameter of one component |
| `get_stability` | CG, CP, stability margin (calibers), diameter, length, mass |
| `list_component_types` | Component types that `add_component` accepts |
| `add_component` | Add a component of any type under a parent |
| `set_component` | Set any parameter(s) of a component |
| `delete_component` | Delete a component (and its children) |
| `list_materials` / `set_material` | List materials and set a component's material by name |
| `search_presets` / `apply_preset` | Find real commercial catalog parts and load them into a component |
| `set_cluster` | Turn an InnerTube into a motor cluster (3-ring, 4-ring, …) |
| `list_flight_configs` / `add_flight_config` / `select_flight_config` | Manage flight configurations |
| `list_simulations` / `add_simulation` / `delete_simulation` | Manage simulations |
| `run_simulation` / `get_simulation_results` | Run a flight, read apogee/velocity/descent/warnings (SI units) |
| `get_flight_data` | Downsampled flight time-series (+ optional CSV export) |
| `export_design` | Export to RockSim (.rkt), OpenRocket (.ork), OBJ (3D print), SVG (laser cut), RASAERO (.CDX1) |
| `set_ignition` / `set_separation` | Motor ignition event/delay and stage separation event/delay |
| `set_appearance` | Set a component's colour / shine |
| `set_fin_points` | Set a freeform fin profile from [x,y] points |
| `add_custom_expression` | Add a custom flight-data expression |
| `component_mass_analysis` / `component_aero_analysis` | Per-component mass+CG / CP+drag breakdown |
| `set_deployment` | Recovery device deploy event/altitude/delay |
| `move_component` / `duplicate_component` | Reparent or duplicate a component |
| `set_simulation_options` | Set launch conditions (rod, wind, altitude, temperature, …) |
| `add_simulation_extension` | Attach extensions (AirStart, StopSimulation, RollControl, …) |
| `optimize_parameter` | Auto-tune a component parameter for max/target apogee or target stability |
| `search_motors` | Search the thrust-curve motor database |
| `set_motor` | Assign a motor to a mount (auto-creates a flight configuration) |

## Autonomous design: the `/goal` command

`docs/goal-command.md` is a Claude Code slash command that drives these tools to design a
complete rocket from a natural-language spec — build, check stability, simulate, size recovery,
and iterate until the spec is met. Install it with:

```bash
mkdir -p .claude/commands && cp docs/goal-command.md .claude/commands/goal.md
```

Then, with the bridge connected, run e.g.
`/goal a stable C-motor rocket that reaches ~150 m and lands at ~4 m/s`.

**Multi-stage, parallel stages and pods** need no special tool: `add_component` adds an
`AxialStage` under the rocket (a booster), or a `ParallelStage` / `PodSet` under a body component.
A 2-stage rocket simulates with staged ignition out of the box.

`get_component` / `set_component` are **generic** — they introspect each component's
bean properties, so any parameter a human can edit in the GUI is reachable by name
(e.g. `length`, `aftRadius`, `thickness`, `finCount`, `sweep`). Use `get_component`
first to discover the exact names.

All edits run on the Swing event thread with an undo position, so they render live in
the open window and can be undone with Ctrl/Cmd-Z.

## How it works

- The server lives in `swing/src/main/java/info/openrocket/swing/mcp/`.
- It implements the MCP *Streamable HTTP* transport on the JDK's built-in
  `com.sun.net.httpserver` (no new runtime dependencies beyond Gson).
- `OpenRocketTools` is the tool layer; it drives the same `OpenRocketDocument` /
  `Rocket` / `Simulation` model the GUI uses, via `BasicFrame.getAllFrames()`.

## Building

OpenRocket targets **Java 17**. The Gradle build also has to *run on* a JDK it
supports (≤ 24/25) — JDK 26 fails with `Unsupported class file major version 70`.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # or your JDK 17 path
git submodule update --init --recursive            # one-time: component database
./gradlew run                                      # launch the GUI
```
