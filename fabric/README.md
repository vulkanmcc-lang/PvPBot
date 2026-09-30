# PvPBot Voice Link (Fabric client mod)

Give PvPBot bots orders by talking in Simple Voice Chat.

## Install (player)

1. Fabric Loader for Minecraft 1.21.11, plus **Fabric API** and **Simple Voice Chat**.
2. Put `pvpbot-voicelink-*.jar` in `mods/`.
3. In game, run `/voicelink setup` once. It downloads the offline English
   speech model (~40 MB, [Vosk](https://alphacephei.com/vosk/models)) into
   `config/pvpbot-voicelink/models/`.
4. Join a server running the PvPBot plugin. Voice orders only work for
   **faction leaders** (`/pvpbot faction leader add <you> <faction>`) and only
   reach the bots of the factions you lead. Everyone else is ignored.

`/voicelink` shows status, `/voicelink on|off` toggles it.

## What you can say

| | examples |
|---|---|
| Attack | everyone kill *name* · focus *name* · take out *name* · all on *name* · *name* is our target · don't let *name* escape · everyone kill him *(whoever you're looking at)* · kill the closest guy · red team attack blue |
| Rush | push them · rush them now |
| Stand down (20 s, works with or without factions) | guys stop fighting · hold your fire · everyone chill · nobody fight · calm down guys |
| Come | come to me · get over here · everyone regroup · stay together · don't split up |
| Follow (60 s) | follow me · stay with me · follow me in |
| Push forward | push forward · move up · let's go · go go go |
| Hold position | wait here |
| Alert | watch out · behind you · they're coming · get ready |
| Formation | everyone go behind me · get behind me · fall in *(grid behind you, follows you until:)* break formation · at ease |
| Mine | everyone mine the area · dig here *(the area you're looking at, 11×11, dug top-down with pickaxes/shovels)* |
| Destroy | everyone destroy the area · blow it up *(15×15; bots with TNT + flint & steel blast it, the rest use tools)* |
| Stop mining | everyone stop mining · stop digging · stop destroying |
| Pillar to | everyone pillar to *name* · tower up to *name* · get to him *(walk / bridge / pillar up to them with blocks, then fight)* |
| Bow | everyone bow *name* · shoot *name* · use your bows on *name* *(bots with a bow + arrows keep range and shoot; the rest go melee)* |
| Armor | everyone put your armor on · gear up · everyone take your armor off |
| One bot | start with its name: *Andy come here* · *Nexar Void follow me* · *Kevin kill Steve* |

Names don't have to be pronounceable: `NexarVo1d` works as "nexar void" (or
just "nexar"), `Steve123` as "steve", `xDarkKnightx` as "dark knight",
`Pinapple` as "pineapple" / "pine apple" (or even a misheard "by an apple"). The
server fuzzy-matches what you said against the players and bots online.

Start with "everyone" / "guys" / a faction name, or open the sentence with
the command. If you address the bots and nothing happens, the action bar
shows what the speech recogniser heard, so you can see which word it got wrong. Single words like "stop" or "wait" need an address
("everyone stop") so normal talk doesn't trigger them.

## Privacy

Speech recognition runs **on your PC**; audio never leaves it. Only
sentences that look like bot orders (addressed to the bots, or opening
with a command word) are sent to the server, as text.

## How it connects

- Hooks Simple Voice Chat's client API (`ClientSoundEvent`) to read your
  own microphone while you're transmitting.
- Sends recognised orders on the `pvpbot:voice` plugin channel:
  `[byte 1][VarInt length][UTF-8 text]`.
- Receives the server's command words and faction names on
  `pvpbot:voice_words`, and only forwards sentences that use them.

## Build

```
./gradlew build
```

Building needs a **Java 25** JDK (current Fabric Loom requires it to run
Gradle); the mod itself targets Java 21 like Minecraft 1.21.11.

CI picks the newest Loader / Yarn / Fabric API / Loom for
`minecraft_version` in `gradle.properties` automatically.
