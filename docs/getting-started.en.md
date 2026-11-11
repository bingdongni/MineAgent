# Getting started and testing MineAgent v1.0.1

[简体中文](getting-started.md) · [Downloads](https://github.com/bingdongni/MineAgent/releases/tag/v1.0.1) · [Validation scope](research/validation-1.0.1.md)

## 1. Prepare a separate instance

Create a separate game instance and test world. Begin with only the required mods below; add other mods after the basic flow works. Before upgrading an existing world, stop the game/server, back up the entire world and configuration, and replace the old JAR. Do not keep several MineAgent versions installed.

| Requirement | Fabric (recommended) | NeoForge (experimental) |
| --- | --- | --- |
| Minecraft Java Edition | 1.21.1 | 1.21.1 |
| Java actually running the game | 21 | 21 |
| Loader | Fabric Loader >= 0.20.1 | NeoForge 21.1.x; regression baseline 21.1.72 |
| Additional dependency | Fabric API; validated baseline 0.116.17+1.21.1 | No Fabric API |
| Installable file | `mineagent-fabric-1.0.1.jar` | `mineagent-neoforge-1.0.1.jar` |

Install into the **active launcher's instance** `mods` directory. This may differ from the default `.minecraft/mods`. Multiplayer requires matching MineAgent versions and loaders on both client and server, with Fabric API on both Fabric sides. Client menus and keys require the client mod. Fabric, Forge and NeoForge JARs are not interchangeable.

The source ZIP/TAR.GZ is for development and review; do not put it in `mods`. Check the instance's Java setting or game log: the terminal's `java -version` may identify a different Java installation.

## 2. Verify downloads

Download the JAR and `release-artifacts.sha256` from the same Release. In PowerShell:

```powershell
Get-FileHash .\mineagent-fabric-1.0.1.jar -Algorithm SHA256
Get-Content .\release-artifacts.sha256
```

Compare with the line for the **same filename**, ignoring hexadecimal letter case. Use the NeoForge filename for that loader. Linux: `sha256sum <filename>`; macOS: `shasum -a 256 <filename>`. `sha256sum --check release-artifacts.sha256` requires all files listed in the manifest; otherwise missing downloads are reported as missing. Do not compare against another version or a local build's manifest.

## 3. Configure and create one companion

1. Enter the test world and stand on safe, open ground. Press `M`. Resolve conflicting bindings in Minecraft's Controls settings if needed.
2. Open Configure & Create, choose a distinct name such as `TestBuddy`, and select survival or creative. Avoid permanent-death Hardcore for your first test.
3. Choose the protocol actually supported by your service, enter its exact model ID, endpoint and your own key. The displayed model list is not an exhaustive whitelist; supported IDs can be entered manually.
4. Create the companion and run `/mineagent list`. One companion should appear in the world and one entry should be added. If it fails, read the chat error and troubleshooting table before retrying.

Creation wakes the model immediately. Automatically restored companions may also continue calls without a new chat message. Set a budget with your provider before using paid APIs or relays. `maxTokens` limits output per request, not the whole session's spending. MineAgent provides no model credits.

| Service | Protocol / provider | Endpoint and credentials |
| --- | --- | --- |
| Official or relay Chat Completions compatible API | `openai-compatible` | Service's API root or `/v1` root, with its required credentials |
| Responses, Anthropic Messages, Gemini or another protocol | Matching adapter from `/mineagent providers` | Match the actual protocol, regardless of model naming |
| Local native Ollama | `ollama` | Default `http://127.0.0.1:11434`; leave key empty if authentication is intentionally disabled; install a model supporting tool calls |
| Other local Chat Completions compatible servers | `openai-compatible` | Actual service endpoint; leave key empty only if intentionally unauthenticated |

The **Minecraft server machine** accesses the endpoint. For single player this is your computer; on a remote multiplayer server `127.0.0.1` means that server, not your PC. Use a trusted server and provider: the server administrator can access connection settings. The model must support structured tool calls to execute tasks reliably.

First launch creates `config/mineagent.json`. You can also stop the game and edit this file; use the server instance's file for multiplayer. This partial example overlays the LLM section onto defaults. Replace every placeholder locally; these are not usable credentials or a usable model:

```json
{
  "llm": {
    "provider": "openai-compatible",
    "apiKey": "REPLACE_LOCALLY",
    "model": "replace-with-exact-model-id",
    "baseUrl": "https://your-provider.example/v1",
    "temperature": 0.7,
    "maxTokens": 4096
  }
}
```

Use valid JSON without comments or trailing commas. In v1.0.1 malformed configuration is preserved while the runtime falls back to defaults. Repair and reload the file; fallback does not mean your original settings were accepted. After editing while in game:

```text
/mineagent reload
/mineagent config
/mineagent quick TestBuddy
```

`reload` affects global settings used for future companions. Existing companions and their restoration records retain their own connection tuple. To replace a companion's service, back up first, deliberately remove that companion, and create with the new settings; this is not a session-preserving hot switch. Avoid commands containing API keys because command history or server logs may retain them.

## 4. Complete and verify a small task

Press `C`, select `TestBuddy`, and ask: “Report your current position and inventory without changing the world.” Compare its observation with the game. Then ask: “Collect three dirt blocks nearby safely and report your inventory count.” Record the game mode: survival needs suitable reachable terrain and interaction permission, while creative has different item rules.

Verify actual movement, blocks and inventory. A model's completion message is not proof. If it loops, stalls or makes unexpected requests, end the test by removing the selected companion from its `C` chat screen, then troubleshoot. Start with one companion to make failures and spending easier to isolate.

## 5. Distinguish restoration from deliberate removal

| Action | Expected behavior |
| --- | --- |
| Leave the world / owner disconnects normally | Save the companion and unload its body; the same owner returning to the same world restores it |
| Rejoin the same test world | Check `/mineagent list` before creating again; there should be only one copy |
| Select a companion with `C` and remove it | Delete its restoration record; rejoining will not recreate it |
| `/mineagent remove` | Remove your primary companion; prefer explicit `C` selection with multiple companions |
| `/mineagent respawn` | Revive a dead primary companion in normal modes; Hardcore death is permanent |

Do not use `remove` to test restoration. Back up before upgrading, downgrading or reporting issues. Restoration depends on the same owner UUID and world; changing accounts or server authentication mode may change the UUID. v1.0.1 retains v1.0.0's memory format 15.

## 6. Troubleshooting

| Symptom | First checks |
| --- | --- |
| Dependency or Java error before loading | MC 1.21.1, actual Java 21, Loader >= 0.20.1, Fabric API for 1.21.1 |
| No menu or commands | Active instance, matching client/server JARs, loaded mod and key conflicts |
| NeoForge creation throws `UnsupportedOperationException` | Old 1.0.0/duplicate JARs; replace with 1.0.1 and restart fully; record exact NeoForge version |
| Missing key, invalid model, 401/403/404 | Protocol, exact model ID, API endpoint, authentication and permissions; defaults do not supply a model service |
| Local connection refused / timeout | Service running, reachable from the server machine, sufficient model runtime resources |
| 429 / unexpected cost | Provider limits and billing; remove the companion to end the test instead of repeatedly recreating |
| Old model after reload | Existing companions keep independent settings; recreate to use the new global settings |
| Configuration parsing failure | Preserve and repair JSON, then reload; do not publish files containing credentials |
| No safe spawn / duplicate name | Open safe ground, existing companion list and distinct names |
| Chat works but actions fail | Model tool support, permissions, visible/reachable targets; reproduce with one simple task |

## 7. Report a real test

Use [user test feedback](https://github.com/bingdongni/MineAgent/issues/new?template=user_test.yml) for successful, failed and unattempted steps, or the [bug report template](https://github.com/bingdongni/MineAgent/issues/new?template=bug_report.yml) for a reproducible defect. Include exact mod, loader and Java versions, minimal mod list, single/multiplayer, game mode, protocol/model ID, original task, expected and actual behavior. Record installation, creation, small-task completion, rejoin/restoration and deliberate removal separately. Download events are not independent users.

Attach short sanitized logs or a recording and reproducibility notes. Do not share API keys, authentication headers, complete configuration or worlds. `config/mineagent.json`, `<world>/data/mineagent_companions.json` and world-local `mineagent_memory` content may contain credentials or private context.

Native automated scenarios verify lifecycle and production components. They do not establish your actual model's autonomous success rate, arbitrary modpack compatibility or billing savings. Record real-model results separately from engineering regressions that do not call a model.
