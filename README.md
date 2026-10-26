# DiaSMP (Paper 1.21.11)

This is a Maven Java plugin project for Paper. It includes `/home`, `/sethome`, `/rtp`, `/team` commands, delayed teleports, combat tagging, custom golden-apple/cobweb recipes, faster brewing, villager pickup, and a string cooldown framework.

## Build
Requires Java 21 and Maven:
```bash
mvn package
```
Put `target/DiaSMP-1.0.0.jar` in your server's `plugins` folder and restart.

## Important notes
- Paper 1.21.11 requires a compatible Java runtime (Java 21 or newer, depending on your server distribution).
- Paper's anti-Xray is a **server setting**, not something a normal plugin can reliably replace. Configure `anticheat.anti-xray` in `config/paper-world-defaults.yml`, then restart.
- The plugin uses persistent YAML files for homes and teams.
- Team size includes the owner.
- `/team invite` and `/team join` use player names; invitations expire when the server restarts.
- Villager pickup is implemented as carrying a villager in a boat-like interaction? This starter uses sneak + right-click to pick up a villager and places it on right-click; test on a staging server first.
- Vanilla equipment (diamond/netherite) remains available; the plugin does not add weapons.
- The plugin does not disable the mace by itself in this starter; use a server item restriction plugin or add a tested item-use listener if you need hard enforcement.
- String cooldown behavior is enforced when using string to craft cobwebs; custom recipes are registered by the plugin.
