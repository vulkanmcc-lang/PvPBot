# PvPBot Voice Link (Fabric client mod)

Give PvPBot bots orders by talking in Simple Voice Chat.

## Install (player)

1. Fabric Loader for Minecraft 1.21.11, plus **Fabric API** and **Simple Voice Chat**.
2. Put `pvpbot-voicelink-*.jar` in `mods/`.
3. In game, run `/voicelink setup` once. It downloads the offline English
   speech model ([Vosk](https://alphacephei.com/vosk/models)) into
   `config/pvpbot-voicelink/models/`. The default is the large model
   (~1.8 GB, most accurate); `/voicelink model` lists the others and
   `/voicelink model small|medium|large|gigaspeech` switches (downloading it
   if needed - you keep using the old one until it's done). Vosk runs on the
   CPU, so any modern desktop handles the big models in real time.
4. Join a server running the PvPBot plugin. Voice orders only work for
   **faction leaders** (`/pvpbot faction leader add <you> <faction>`) and only
   reach the bots of the factions you lead. Everyone else is ignored.

`/voicelink` shows status, `/voicelink on|off` toggles it. Voice orders work
silently - no chat or action-bar read-outs (server admins can turn those on
for debugging with `voice-feedback: true` in the PvPBot config).

## What you can say

| | examples |
|---|---|
| Attack | everyone kill *name* *(one of your own bots: it's kicked from the faction first so the rest fight it)* · focus *name* · take out *name* · all on *name* · *name* is our target · don't let *name* escape · everyone kill him *(whoever you're looking at)* · kill the closest guy · red team attack blue |
| Rush | push them · rush them now |
| Stand down (until you order an attack again - kill / rush / bow / pillar to / weapons free) | guys stop fighting · hold your fire · everyone chill · nobody fight · calm down guys |
| Come | come to me · get over here · everyone regroup · stay together · don't split up |
| Follow (60 s) | follow me · stay with me · follow me in |
| Push forward | push forward · move up · let's go · go go go |
| Stay | everyone stay here · stay put · hold your position · don't move *(each bot keeps its spot - fights anyone who comes close, walks back - until the next order)* |
| Alert | watch out · behind you · they're coming · get ready |
| Formation | everyone go behind me · get behind me · fall in *(grid behind you, follows you until:)* break formation · at ease |
| Mine | everyone mine the area · dig here *(the area you're looking at, dug top-down with pickaxes/shovels - bigger the more bots dig it; about a third of the crew wander off digging their own tunnels: straight runs, 90° turns, staircases down, following ore veins; the rest branch off too once the pit is done)*. Several areas at once: *Andy mine the area* here, *red team mine the area* over there - each group keeps its own |
| Mine down to | everyone mine down to *name* · dig down to me · tunnel to *name* *(each bot digs its own way: straight down for the steep part, then a walkable staircase tunnel; if they move, the bots re-plan toward where they are now)* |
| Destroy | everyone destroy the area · blow it up *(15×15; bots with TNT + flint & steel blast it, the rest use tools)* |
| Stop mining | everyone stop mining · stop digging · stop destroying |
| Pillar to | everyone pillar to *name* · tower up to *name* · get to him *(walk / bridge / pillar up to them with blocks, then fight; bots take different routes)* |
| Bow | everyone bow *name* · shoot *name* · use your bows on *name* *(bots with a bow + arrows keep range and shoot; the rest go melee)* |
| Armor | everyone put your armor on · gear up · everyone take your armor off · everyone put your best armor on · everyone put your bad / worst armor on *(swaps every slot to the strongest / weakest piece they carry)* |
| Tunnel | everyone tunnel this way *(each bot digs its own 32-long tunnel the way you face - side by side, some level, some ramping up, some down)* |
| Build up | everyone build up · pillar up *(10 blocks up, every bot on its own column)* |
| Path | everyone make a path there · build a bridge there *(covered 3-wide bridge with rails and a roof to the block you look at, up to 64 long; the bots share the work)* · stop building |
| Build | everyone build me a *name* here *(a schematic you marked with `/pvpbot schematic mark <schematic> <name>` - the bots clear the space first, then build it column by column, each on its own part without waiting for the others; the block for each placement is put straight in their hand)* |
| Look | everyone look at me *(bots within 5 blocks of you back off to ~6 first; then they all stay where they are and keep watching you until the next order)* |
| Island bridge (End only) | everyone bridge to the next island · go to the next island *(finds the nearest other End island and bridges to it in end stone, 3 wide: the nearest bot leads out along the middle, sneaking, a block at a time; the rest fill the sides behind it. Bots don't push each other while bridging; one that falls anyway is put back on the bridge. "stop the bridge" stops it)* |
| Scatter | everyone scatter · spread out · split up · run away *(every bot runs off in a different direction)* |
| Take cover | everyone take cover · take shelter · build a roof · hide *(the bots near you build an end stone roof over the whole group - corner pillars and a flat roof 3 blocks over the ground, skipping anywhere that's already covered; bots further out, up to 50 blocks, sprint over. Everyone then holds a spot under the roof until the next order)* |
| One bot | start with its name: *Andy come here* · *Nexar Void follow me* · *Kevin kill Steve* |
| Commander | commander kill *name* *(the commander walks up to you, nods, then goes for them)* · commander come here · red commander kill *name*. Pick one with `/pvpbot faction commander <faction> <bot>` |

Every new order replaces the last one: bots drop whatever they were doing
(digging, building, holding a spot, following, a climb, a bow duel) and do the
new thing. Armor, "look at me" and "weapons free" are the exceptions - they
don't interrupt anything.

A bot fighting someone it can't reach (under stone, behind walls) digs its
way to them if they're level or below; if it can't, it drops them for a
while and goes back to its orders instead of standing there jumping.

Bots that end up stuck in a hole (a pit, a shaft, a dug-out area) while you
or the spot they were sent to is up above walk to the wall, pillar up beside
it and step off at the top.

Everything the bots build outside schematics - island bridges, paths,
pillars, build up, hole escapes, water plugs - is end stone (they're handed
some when they run out).

Mining near water: bots plug water beside or above a block before breaking
it, and a bot that ends up in water over the dig sinks to the bottom, digs 3
down, caps the hole above its head and keeps mining underneath.

Names don't have to be pronounceable: `NexarVo1d` works as "nexar void" (or
just "nexar"), `Steve123` as "steve", `xDarkKnightx` as "dark knight",
`Pinapple` as "pineapple" / "pine apple" (or even a misheard "by an apple"). The
server fuzzy-matches what you said against the players and bots online.

Start with "everyone" / "guys" / a faction name, or open the sentence with
the command. (With `voice-feedback: true` on the server, the action bar shows
what was heard when nothing matched.) Single words like "stop" or "wait" need an address
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
