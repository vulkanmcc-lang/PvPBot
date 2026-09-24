Drop Sponge schematic files (.schem) in this folder.

These are what WorldEdit's //copy + //schem save produces by default, and what
most schematic sites distribute. Version 2 is the target; version 3 files also
load.

The older MCEdit .schematic format (numeric block IDs) is NOT supported --
those IDs stopped meaning anything in 1.13.

At runtime this folder is copied to plugins/PvPBot/schematics/ -- put your
files THERE, not back inside the jar.

  /pvpbot schematic list
  /pvpbot schematic info <name>
  /pvpbot schematic preview <name> [seconds]   (client-side only, places nothing)
  /pvpbot faction schematic [faction] <name> build
  /pvpbot faction schematic [faction] <name> status
  /pvpbot faction schematic [faction] <name> stop
