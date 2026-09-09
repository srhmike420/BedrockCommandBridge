# BedrockCommandBridge

Paper plugin for Geyser-Spigot that filters the Java command tree Geyser translates into Bedrock command autocomplete. Hooks Pl-Hide/Pro or Standalone.

## Target
- Paper 26.2
- Java 21+ bytecode (runs on Java 25)
- Current Geyser API (pom currently references 2.11.2-SNAPSHOT)

## Important limitation
Geyser's public `ServerDefineCommandsEvent` permits removing commands from the translated Java command set, not inventing commands that the Java server never supplied. BedrockCommandBridge therefore preserves and filters Geyser's real Brigadier-derived autocomplete. It does not fabricate missing root commands.

## Command sources
`config.yml` uses independent enable switches instead of a source mode:
- `plhide-hook.enabled`: reads `groups.<group>.tabcomplete` from Pl-Hide-Pro's config
- `standalone.enabled`: uses `commands.yml`
- If both are enabled, their allowed command lists are merged.
- If both are disabled, BedrockCommandBridge passes Geyser's command tree through unchanged.

Pl-Hide-Pro remains responsible for command execution blocking. This plugin only affects what remains visible in Geyser's Bedrock autocomplete.

## Commands
- `/bedrockcommands status`
- `/bedrockcommands reload`
- `/bedrockcommands dump <player>` - shows the last command roots Geyser exposed for that Bedrock UUID

Permission: `bedrockcommandbridge.admin`

## Standalone groups
Set `mode: allowlist` in `commands.yml`. Group permission is `bedrockcommandbridge.group.<group>`. If an `op` group exists, operators use it automatically. Inheritance and priorities are supported.

Entries such as `warp *` and `shop search` are reduced to their root (`warp`, `shop`) for visibility filtering; Geyser retains the actual Java Brigadier argument/subcommand tree for autocomplete.
