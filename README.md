# Carrera de Conejos

A hotseat race for two players on one computer: two rabbits, one shared 15×9
board, one carrot. Rock–paper–scissors decides who starts, a die sets the steps,
and the race is won by landing **exactly** on the carrot.

Java 25 + Swing, no external runtime services. The interface is in Spanish
(LatAm).

## Requirements

- **JDK 25** (the build declares a Java 25 toolchain; older JDKs will not run the
  classes).
- Windows, macOS or Linux. The Gradle wrapper downloads Gradle 9.9.1 on the
  first build; after that the build works offline.

## Build and run

```powershell
.\gradlew.bat run
```

```bash
./gradlew run
```

Run from the repository root — the game reads `assets/` from the working
directory.

To build a runnable distribution:

```powershell
.\gradlew.bat installDist
build\install\rabbit-race\bin\rabbit-race.bat   # keep assets/ beside the folder
```

## How to play

| Moment | What to do |
| --- | --- |
| Título | Type a six-character seed (0–9, A–Z; I/L become 1, O becomes 0), then `Nueva partida` (Enter in the field also starts). |
| RPS | Player 1: `1` / `2` / `3`; Player 2: `7` / `8` / `9` — or click your own hand. Choices stay hidden until both commit; the reveal shakes briefly and any key/click skips it. |
| Your turn | `Tirar` or `Space` rolls. The die tumbles, settles on its pips and unlocks movement. |
| Moving | Click a highlighted tile, or `WASD` / arrow keys. Steps are orthogonal; you cannot stand on a blocking tile or on the other rabbit. |
| Ending a turn | Spend the budget, or `Terminar turno` / `Space` after at least one step. At zero steps the turn cannot end. |
| Pause and settings | `Esc` pauses; the gear (or `Ajustes`) opens settings. Sliders move with ←/→, `Home`/`End`. |

A rematch keeps the game going with a new seed, and the previous loser rolls
first.

## Project layout

```
src/main/java/rabbitrace/   game rules, rendering, screens, audio, settings
src/test/java/rabbitrace/   unit tests
assets/                     manifest, placeholder sprites and ground, template board
docs/CREDITS.md             credits generated from the manifest
```

## Assets

The shipped art is procedural placeholder work (CC0). `docs/CREDITS.md` records
every non-CC0 slot; it is generated from `assets/manifest.json`.

## License

MIT. See `LICENSE`. The shipped placeholder art is CC0; `docs/CREDITS.md` records
any non-CC0 slot.
