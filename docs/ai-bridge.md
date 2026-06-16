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
| `get_component_tree` | Full component tree (ids, types, names, nesting) |
| `get_component` | Every readable parameter of one component |
| `list_component_types` | Component types that `add_component` accepts |
| `add_component` | Add a component of any type under a parent |
| `set_component` | Set any parameter(s) of a component |
| `delete_component` | Delete a component (and its children) |
| `list_simulations` / `add_simulation` / `delete_simulation` | Manage simulations |
| `run_simulation` / `get_simulation_results` | Run a flight, read apogee/velocity/etc. (SI units) |
| `search_motors` | Search the thrust-curve motor database |
| `set_motor` | Assign a motor to a mount (auto-creates a flight configuration) |

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
