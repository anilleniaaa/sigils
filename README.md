# Sigils: Enchanted Trim Templates (Fabric 1.21.11)

Ten armor trim templates, awakened. Each looks like its vanilla template (always glinting) and works
like a gem: several PASSIVES, three ABILITIES, soulbound to you.

## Controls
- Right-click once to bind it to you.
- Right-click: use the selected ability.
- F (swap hands) or sneak + right-click: switch ability.
- Hold WASD while using a dash/phase ability to dash THAT way (otherwise it follows where you look).
- Jump again in mid-air: double jump (Flow and Spire sigils).
- HUD (bottom right) shows your abilities with live cooldown bars.

Kill players to raise tier (3 kills = T2, 8 = T3): higher tiers hit harder and shave 10% per step off every
cooldown. Each death costs 3 kills. The mod must be installed on BOTH the server and every client.

## Abilities
| Sigil of  | Abilities                                            | Passives                                          |
|-----------|------------------------------------------------------|---------------------------------------------------|
| Bolt      | Thunder Strike / Storm Aura / Overcharge             | Haste I, lightning immunity                       |
| Flow      | Wind Step / Breezy Bash / Cyclone                    | Jump Boost I, fall immunity, double jump          |
| Vex       | Phase / Haunt / Specter Form                         | Night Vision, wall-suffocation immunity           |
| Wayfinder | Set Anchor / Recall / Bloodhound                     | Speed I, fall immunity                            |
| Ward      | Sonic Boom / Resonant Ward / Seismic Pulse           | Resistance I sneaking, sonic-boom immunity        |
| Tide      | Undertow / Riptide Rush / Tidal Wave                 | Water Breathing, drowning immunity                |
| Silence   | Hush / Dread / Soul Drain                            | Speed I sneaking, sonic-boom immunity             |
| Spire     | Skyward / Meteor Strike / Lift & Drop                | Slow Falling, fall immunity, double jump          |
| Raiser    | Fang Line / Fang Trap / Bloodlust                    | Strength I below 30% health                       |
| Dune      | Sandstorm / Quicksand / Scorch                       | Fire Resistance, sprint Speed I, cactus immunity  |

Obtain: shapeless craft = matching vanilla trim template + nether star + diamond block,
or /give @s sigils:bolt_sigil (etc.), or the Combat creative tab.

## BUILD THE JAR

### Option A: GitHub (nothing to install)
1. Make a free account at github.com and click New repository (name it e.g. `sigils`).
2. Upload this whole folder's contents (click "uploading an existing file", drag everything in,
   INCLUDING the hidden `.github` folder). Or with git:
       git init && git add . && git commit -m "sigils"
       git branch -M main
       git remote add origin https://github.com/YOURNAME/sigils.git
       git push -u origin main
3. Open the repo's **Actions** tab (click "I understand, enable workflows" if asked). The "build" workflow
   starts by itself (or click Run workflow). It takes about 2-4 minutes.
4. Open the finished run, scroll to **Artifacts**, download `sigils-jar`, unzip it.
5. Use `sigils-1.0.0.jar` (NOT the `-sources` one).

### Option B: on your own PC
1. Install JDK 21 (e.g. Eclipse Temurin 21) and Gradle 8.14 or newer.
2. In this folder run:   gradle build
3. The jar is at build/libs/sigils-1.0.0.jar

### Installing
Put `sigils-1.0.0.jar` in the `mods` folder of BOTH the client and the server, together with
Fabric Loader 0.18+ and Fabric API 0.141.1+1.21.11 for Minecraft 1.21.11.
