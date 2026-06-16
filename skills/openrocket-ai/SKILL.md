---
name: openrocket-ai
description: Design, simulate, analyse, optimise and visualise model rockets by driving a running OpenRocket instance over its MCP bridge. Use whenever the user wants to build or modify a rocket, run flight simulations, check stability, pick motors, optimise a design, take design screenshots, or watch a 3D/animated flight — in OpenRocket.
---

# OpenRocket AI

You can control a live OpenRocket instance through the `openrocket` MCP server. Everything a
human can do in OpenRocket — build components, set any parameter, run flight simulations, check
stability, assign motors, optimise, screenshot the design, animate the flight, export files — is
available as a tool.

## Connect (one time)

OpenRocket must be running with the bridge started: **Tools → AI Copilot (MCP bridge) → Start**
(or launch with `-Dopenrocket.mcp.autostart=true`). Then register it:

```bash
claude mcp add --transport http openrocket http://127.0.0.1:8723/mcp
```

The tools then appear as `mcp__openrocket__*`. Confirm with `list_open_designs`.

## The tools (40+)

- **Designs/files:** `list_open_designs`, `new_design`, `open_file`, `save_file`, `export_design`
  (rocksim/openrocket/obj/svg/rasaero), `save_screenshot` (2D/3D PNG).
- **Components (full CRUD):** `get_component_tree`, `get_component`, `list_component_types`,
  `add_component`, `set_component` (any parameter by name), `delete_component`, `move_component`,
  `duplicate_component`, `set_material`/`list_materials`, `set_appearance`, `set_fin_points`,
  `apply_preset`/`search_presets` (real catalog parts), `set_cluster`.
- **Analysis:** `get_stability` (CG/CP/margin/mass), `component_mass_analysis`,
  `component_aero_analysis`.
- **Motors & staging:** `search_motors`, `set_motor`, `set_ignition`, `set_separation`,
  `set_deployment`.
- **Simulation:** `list/add/delete_simulation`, `run_simulation`, `get_simulation_results`,
  `get_flight_data` (+CSV), `set_simulation_options`, `add_simulation_extension`,
  `animate_flight` (watch it fly).
- **Flight configs:** `list/add/select_flight_config`.
- **Optimisation:** `optimize_parameter` (max/target apogee, target stability).
- **Custom:** `add_custom_expression`.

## Design workflow (do this seriously, iterate to spec)

1. `new_design` (or use the open one). Get the stage id from `get_component_tree`.
2. Build the airframe with `add_component` + `set_component`: nose cone, body tube, fins,
   parachute, launch lug. Read parameter names back with `get_component`.
3. `search_motors` → `set_motor` (auto-creates a flight configuration so it can fly).
4. **Iterate to spec** — never assert numbers, measure them:
   - `get_stability` → aim for ~1–2 calibers. Too low/negative: bigger fins / longer nose /
     nose mass. Too high: smaller fins. Or `optimize_parameter` objective `target_stability`.
   - `run_simulation` → compare `maxAltitude` to target; adjust motor/mass/drag or
     `optimize_parameter` objective `target_apogee`.
   - Size the parachute (`set_component` diameter) to a safe `groundHitVelocity` (~3–6 m/s).
   - Resolve every entry in the sim `warnings`.
5. `save_screenshot` at each milestone and reference the images for the user.
6. `animate_flight` to show the flight; `save_file` / `export_design` to finish.

## Tips

- `set_component` is generic — pass `{ "properties": { "length": 0.3, "aftRadius": 0.025 } }`.
  Use `get_component` first to discover exact names.
- All edits render live in the GUI and are undoable.
- Distances are metres from the nose; results are SI.
