# 🚀 OpenRocket-AI

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
![Java 17](https://img.shields.io/badge/Java-17-orange.svg)

**An AI-integrated fork of [OpenRocket](https://openrocket.info/)** — the free, fully-featured
model-rocket simulator — with a built-in **MCP (Model Context Protocol) bridge** so AI agents like
**Claude Code** can design, simulate, analyse, optimise, screenshot and *fly* your rockets, live,
right inside the app.

It's the same OpenRocket you know (same GPLv3 license, same engine), plus a port the AI connects to.

> Not affiliated with or endorsed by the OpenRocket project. This is a community fork.

---

## ✨ What's new in this fork

- **AI Copilot bridge** — an embedded local MCP server (loopback-only). Toggle it from
  **Tools → AI Copilot (MCP bridge)**, watch a live feed of every action the AI takes.
- **40+ MCP tools** covering the whole workflow: full component CRUD, materials, real catalog
  parts, motors, clustering, multi-stage, ignition/separation, recovery, **stability & mass/aero
  analysis**, simulations, flight configs, **parameter optimisation**, custom expressions,
  simulation extensions, and exports (RockSim/OpenRocket/OBJ/SVG/RASAERO/CSV).
- **`save_screenshot`** — the AI captures the design view (2D schematic or 3D) as a PNG at each
  iteration stage.
- **`animate_flight`** — watch the rocket fly its simulated trajectory from launch to landing.
- **`/goal` autonomous designer** — give a spec, the AI builds a complete, stable, flyable rocket
  end-to-end (see [`docs/goal-command.md`](docs/goal-command.md)).

## 📦 Install

Download a build for your platform from the [**Releases**](../../releases) page:

| Platform | File |
|----------|------|
| macOS | `OpenRocket-AI-<version>-macos.dmg` |
| Windows | `OpenRocket-AI-<version>-windows.exe` (installer) |
| Linux | `OpenRocket-AI-<version>-linux.deb` |
| Any (needs Java 17) | `OpenRocket-AI-<version>.jar` → `java -jar OpenRocket-AI-<version>.jar` |

The native bundles include their own Java runtime — no separate Java install needed.

## 🤖 Using the AI bridge

1. Launch OpenRocket-AI, open **Tools → AI Copilot (MCP bridge)**, click **Start**.
2. Connect your agent:
   ```bash
   claude mcp add --transport http openrocket http://127.0.0.1:8723/mcp
   ```
3. Ask away — *"add a nose cone and body tube, give it a C6, make it stable, and tell me the
   apogee"* — or run the autonomous designer: *"/goal a stable C-motor rocket reaching ~150 m"*.

Full tool reference and security notes: [`docs/ai-bridge.md`](docs/ai-bridge.md).

### Teaching any Claude to use it (Skill)

Install the bundled [Agent Skill](skills/openrocket-ai/SKILL.md) so Claude always knows how to
drive the bridge. **No clone needed** — fetch it straight from GitHub:

```bash
mkdir -p ~/.claude/skills/openrocket-ai && \
  curl -fsSL https://raw.githubusercontent.com/Momin010/OpenRocket-AI-MCP/master/skills/openrocket-ai/SKILL.md \
  -o ~/.claude/skills/openrocket-ai/SKILL.md
```

> Already cloned the repo? From its root you can instead run
> `cp -r skills/openrocket-ai ~/.claude/skills/`.

## 🛠️ Build from source

Requires **JDK 17** (the Gradle build will not run on newer JDKs):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS; use your JDK 17 path elsewhere
git submodule update --init --recursive            # one-time: component database
./gradlew run                                      # launch the GUI
./gradlew shadowJar                                # build the cross-platform fat JAR
```

To build native installers locally (uses the JDK's `jpackage`):

```bash
./gradlew shadowJar
mkdir -p staging && cp build/libs/OpenRocket-*.jar staging/OpenRocket-AI.jar
jpackage --input staging --name OpenRocket-AI --main-jar OpenRocket-AI.jar \
  --main-class info.openrocket.swing.startup.OpenRocket --app-version 1.0.0 \
  --type dmg --dest dist        # or: msi (Windows), deb (Linux), app-image (portable)
```

CI builds installers for macOS/Windows/Linux on every `v*` tag — see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

## 📜 License & credits

OpenRocket-AI is licensed under the **GNU GPL v3**, the same as upstream OpenRocket. All credit for
the simulator goes to the OpenRocket project and its contributors. The original project README is
preserved at [`README-OpenRocket-upstream.md`](README-OpenRocket-upstream.md).

- Upstream: https://github.com/openrocket/openrocket
- OpenRocket website & docs: https://openrocket.info/ · https://openrocket.readthedocs.io/
