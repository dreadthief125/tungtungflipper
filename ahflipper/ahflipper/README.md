# AH Flipper (Fabric, client-side)

1. Generate a Fabric 26.2 project at https://fabricmc.net/develop/template/ (package `dev.flipper`, Java version it asks for).
2. Replace its `src/` with this one (and merge fabric.mod.json), `./gradlew build`, drop the jar in `mods/`.
3. In game: `/flip on`, wait ~30 min for sales history, `/flip status`. Alerts only by default; `/flip auto on` lets it click.

Verify on the server before trusting auto mode (all in config/ahflipper.json): button/menu names, fees, the
"[Auction] ... bought" and "You purchased" chat strings. Version-specific Minecraft calls are all in GameBridge.java.
