package com.pvpbot;

public class BotSettings {
    public BotSettings() {}

    public BotSettings(BotSettings other) {
        this.strafing = other.strafing;
        this.sTapping = other.sTapping;
        this.wTapping = other.wTapping;
        this.sprint = other.sprint;
        this.criticals = other.criticals;
        this.normalHits = other.normalHits;
        this.shielding = other.shielding;
        this.autoSword = other.autoSword;
        this.reach = other.reach;
        this.attackCooldownTicks = other.attackCooldownTicks;
        this.strafeSpeed = other.strafeSpeed;
        this.targetRange = other.targetRange;
        this.bridging = other.bridging;
        this.speedBridging = other.speedBridging;
        this.clutching = other.clutching;
        this.restocking = other.restocking;
        this.openDoors = other.openDoors;
        this.webEscape = other.webEscape;
        this.useTerrainMemory = other.useTerrainMemory;
        this.pathfinding = other.pathfinding;
        this.crowdAvoidance = other.crowdAvoidance;
        this.tunnelWhenStuck = other.tunnelWhenStuck;
        this.leaderLeash = other.leaderLeash;
        this.punishCrit = other.punishCrit;
        this.jumpReset = other.jumpReset;
        this.hitSelect = other.hitSelect;
        this.critDeflect = other.critDeflect;
        this.outspacing = other.outspacing;
        this.circleStrafe = other.circleStrafe;
        this.shieldBackstab = other.shieldBackstab;
        this.cartPvp = other.cartPvp;
        this.xbowCart = other.xbowCart;
        this.cartCooldownTicks = other.cartCooldownTicks;
        this.autoEat = other.autoEat;
        this.fleeHealthThreshold = other.fleeHealthThreshold;
        this.returnHealthThreshold = other.returnHealthThreshold;
        this.fleeDistance = other.fleeDistance;
        this.preChaseHeal = other.preChaseHeal;
        this.preChaseHealHealth = other.preChaseHealHealth;
        this.preChaseHealDistance = other.preChaseHealDistance;
        this.difficulty = other.difficulty;
        this.missChance = other.missChance;
        this.aimNoise = other.aimNoise;
        this.criticalFallTicks = other.criticalFallTicks;
        this.bhop = other.bhop;
        this.shieldBreak = other.shieldBreak;
        this.shieldBreakChance = other.shieldBreakChance;
        this.rotationSpeed = other.rotationSpeed;
        this.pitchSpeed = other.pitchSpeed;
        this.aimEase = other.aimEase;
        this.reactionTicks = other.reactionTicks;
        this.botsUseInvis = other.botsUseInvis;
        this.hitCoordination = other.hitCoordination;
        this.cartDefense = other.cartDefense;
        this.pearling = other.pearling;
        this.hostile = other.hostile;
        this.wanderWithoutFaction = other.wanderWithoutFaction;
        this.noAutoTargetWhileIdle = other.noAutoTargetWhileIdle;
        this.assistOnly = other.assistOnly;
        this.patrolEngage = other.patrolEngage;
        this.threatTargeting = other.threatTargeting;
        this.investigate = other.investigate;
        this.patrolEngageRange = other.patrolEngageRange;
        this.frozen = other.frozen;
        this.invisibilityConfusion = other.invisibilityConfusion;
        this.elytraMacing = other.elytraMacing;
        this.maceSmash = other.maceSmash;
        this.breachSwap = other.breachSwap;
        this.lungeSwap = other.lungeSwap;
        this.teamTarget = other.teamTarget;
        this.teamTargetRadius = other.teamTargetRadius;
        this.lavaClutchStunts = other.lavaClutchStunts;
    }

    private boolean strafing = true;
    private boolean sTapping = true;
    private boolean wTapping = false;
    private boolean sprint = true;
    private boolean criticals = true;
    private boolean normalHits = false;

    private boolean shielding = true;
    private boolean autoSword = true;
    private double reach = 3.0;
    private int attackCooldownTicks = 10;
    private double strafeSpeed = 0.8;

    private double targetRange = 48.0;

    private boolean bridging = true;
    private boolean speedBridging = false;
    private boolean clutching = true;
    private boolean restocking = true;
    private boolean openDoors = true;
    private boolean webEscape = true;
    private boolean useTerrainMemory = true;
    private boolean pathfinding = true;
    private boolean crowdAvoidance = true;
    private boolean tunnelWhenStuck = true;
    private double leaderLeash = 24.0;

    private boolean punishCrit = true;
    private boolean jumpReset = true;
    private boolean hitSelect = true;
    private boolean critDeflect = true;
    private boolean outspacing = true;
    private boolean circleStrafe = true;
    private boolean shieldBackstab = true;

    private boolean cartPvp = true;
    private boolean xbowCart = true;
    private int cartCooldownTicks = 200;

    private boolean autoEat = true;
    private double fleeHealthThreshold = 8.0;
    private double returnHealthThreshold = 16.0;
    private double fleeDistance = 15.0;

    private boolean preChaseHeal = true;
    private double preChaseHealHealth = 14.0;
    private double preChaseHealDistance = 12.0;

    private BotDifficulty difficulty = BotDifficulty.NORMAL;
    private double missChance = 0.10;
    private double aimNoise = 3.0;

    private int criticalFallTicks = 2;

    private boolean bhop = true;

    private boolean shieldBreak = true;

    private boolean botsUseInvis = false;

    private boolean hitCoordination = true;

    private boolean cartDefense = true;

    private boolean pearling = true;

    private boolean threatTargeting = true;

    private boolean investigate = true;

    private boolean patrolEngage = true;

    private double patrolEngageRange = 20.0;

    private boolean hostile = true;

    private boolean wanderWithoutFaction = false;

    private boolean lavaClutchStunts = false;

    private boolean noAutoTargetWhileIdle = false;

    private boolean assistOnly = false;

    private boolean frozen = false;

    private boolean invisibilityConfusion = true;

    private boolean teamTarget = true;

    private double teamTargetRadius = 25.0;

    private double rotationSpeed = 13.0;

    private boolean elytraMacing = true;

    private boolean maceSmash = true;

    private boolean breachSwap = true;

    private boolean lungeSwap = true;

    private double pitchSpeed = 8.0;

    private double aimEase = 0.26;

    private int reactionTicks = 4;
    private double shieldBreakChance = 0.5;

    public void applyDifficulty(BotDifficulty diff) {
        this.difficulty = diff;
        switch (diff) {
            case EASY -> {
                reach = 2.5;
                criticalFallTicks = 5;
                bhop = false;
                shieldBreakChance = 0.15;
                attackCooldownTicks = 13;
                strafeSpeed = 0.5;
                missChance = 0.25;
                aimNoise = 6.0;
                sTapping = false;
                wTapping = false;
                rotationSpeed = 9.0;
                pitchSpeed = 5.5;
                aimEase = 0.16;
                reactionTicks = 6;
            }
            case NORMAL -> {
                reach = 2.7;
                criticalFallTicks = 3;
                bhop = true;
                shieldBreakChance = 0.35;
                attackCooldownTicks = 11;
                strafeSpeed = 0.8;
                missChance = 0.10;
                aimNoise = 3.0;
                sTapping = true;
                wTapping = false;
                rotationSpeed = 13.0;
                pitchSpeed = 8.0;
                aimEase = 0.26;
                reactionTicks = 4;
            }
            case HARD -> {
                reach = 2.8;
                criticalFallTicks = 2;
                bhop = true;
                shieldBreakChance = 0.6;
                attackCooldownTicks = 10;
                strafeSpeed = 1.0;
                missChance = 0.03;
                aimNoise = 1.0;
                sTapping = true;
                wTapping = true;
                rotationSpeed = 22.0;
                pitchSpeed = 13.0;
                aimEase = 0.38;
                reactionTicks = 2;
            }
            case EXPERT -> {
                reach = 3;
                criticalFallTicks = 1;
                bhop = true;
                shieldBreakChance = 0.9;
                attackCooldownTicks = 10;
                strafeSpeed = 1.2;
                missChance = 0.0;
                aimNoise = 0.0;
                sTapping = true;
                wTapping = true;
                rotationSpeed = 30.0;
                pitchSpeed = 18.0;
                aimEase = 0.48;
                reactionTicks = 1;
            }
        }
    }

    public boolean isStrafing() { return strafing; }
    public void setStrafing(boolean strafing) { this.strafing = strafing; }

    public boolean isSTapping() { return sTapping; }
    public void setSTapping(boolean sTapping) { this.sTapping = sTapping; }

    public boolean isWTapping() { return wTapping; }
    public void setWTapping(boolean wTapping) { this.wTapping = wTapping; }

    public boolean isSprint() { return sprint; }
    public void setSprint(boolean sprint) { this.sprint = sprint; }

    public boolean isCriticals() { return criticals; }
    public void setCriticals(boolean criticals) { this.criticals = criticals; }

    public boolean isNormalHits() { return normalHits; }
    public void setNormalHits(boolean normalHits) { this.normalHits = normalHits; }

    public boolean isShielding() { return shielding; }
    public void setShielding(boolean shielding) { this.shielding = shielding; }

    public boolean isAutoSword() { return autoSword; }
    public void setAutoSword(boolean autoSword) { this.autoSword = autoSword; }

    public double getReach() { return reach; }
    public void setReach(double reach) { this.reach = reach; }

    public int getAttackCooldownTicks() { return attackCooldownTicks; }
    public void setAttackCooldownTicks(int ticks) { this.attackCooldownTicks = ticks; }

    public double getStrafeSpeed() { return strafeSpeed; }
    public void setStrafeSpeed(double strafeSpeed) { this.strafeSpeed = strafeSpeed; }

    public double getTargetRange() { return targetRange; }

    public boolean isBridging()        { return bridging; }
    public boolean isSpeedBridging()   { return speedBridging; }
    public void setSpeedBridging(boolean v) { this.speedBridging = v; }
    public boolean isClutching()       { return clutching; }
    public boolean isRestocking()      { return restocking; }
    public boolean isOpenDoors()       { return openDoors; }
    public boolean isWebEscape()       { return webEscape; }
    public boolean isUseTerrainMemory(){ return useTerrainMemory; }
    public boolean isPathfinding()     { return pathfinding; }
    public boolean isCrowdAvoidance()  { return crowdAvoidance; }
    public boolean isTunnelWhenStuck() { return tunnelWhenStuck; }
    public double  getLeaderLeash()    { return leaderLeash; }

    public boolean isPunishCrit()      { return punishCrit; }
    public boolean isJumpReset()       { return jumpReset; }
    public boolean isHitSelect()       { return hitSelect; }
    public boolean isCritDeflect()     { return critDeflect; }
    public boolean isOutspacing()      { return outspacing; }
    public boolean isCircleStrafe()    { return circleStrafe; }
    public boolean isShieldBackstab()  { return shieldBackstab; }
    public boolean isCartPvp()         { return cartPvp; }
    public boolean isXbowCart()        { return xbowCart; }
    public int     getCartCooldownTicks() { return cartCooldownTicks; }
    public void setTargetRange(double targetRange) { this.targetRange = targetRange; }

    public boolean isAutoEat() { return autoEat; }
    public void setAutoEat(boolean autoEat) { this.autoEat = autoEat; }

    public double getFleeHealthThreshold() { return fleeHealthThreshold; }
    public void setFleeHealthThreshold(double v) { this.fleeHealthThreshold = v; }

    public double getReturnHealthThreshold() { return returnHealthThreshold; }
    public void setReturnHealthThreshold(double v) { this.returnHealthThreshold = v; }

    public boolean isPreChaseHeal() { return preChaseHeal; }
    public void setPreChaseHeal(boolean v) { this.preChaseHeal = v; }

    public double getPreChaseHealHealth() { return preChaseHealHealth; }
    public void setPreChaseHealHealth(double v) { this.preChaseHealHealth = v; }

    public double getPreChaseHealDistance() { return preChaseHealDistance; }
    public void setPreChaseHealDistance(double v) { this.preChaseHealDistance = v; }

    public double getFleeDistance() { return fleeDistance; }
    public void setFleeDistance(double v) { this.fleeDistance = v; }

    public double getMissChance() { return missChance; }
    public double getAimNoise() { return aimNoise; }

    public int getCriticalFallTicks() { return criticalFallTicks; }
    public void setCriticalFallTicks(int t) { this.criticalFallTicks = Math.max(0, t); }

    public boolean isBhop() { return bhop; }
    public void setBhop(boolean b) { this.bhop = b; }

    public boolean isShieldBreak() { return shieldBreak; }
    public void setShieldBreak(boolean b) { this.shieldBreak = b; }

    public double getShieldBreakChance() { return shieldBreakChance; }
    public void setShieldBreakChance(double c) { this.shieldBreakChance = Math.max(0, Math.min(1, c)); }
    public boolean isBotsUseInvis() { return botsUseInvis; }
    public void setBotsUseInvis(boolean v) { this.botsUseInvis = v; }

    public boolean isHitCoordination() { return hitCoordination; }
    public void setHitCoordination(boolean v) { this.hitCoordination = v; }

    public boolean isHostile() { return hostile; }
    public boolean isWanderWithoutFaction() { return wanderWithoutFaction; }
    public void setWanderWithoutFaction(boolean v) { this.wanderWithoutFaction = v; }
    public boolean isNoAutoTargetWhileIdle() { return noAutoTargetWhileIdle; }
    public void setNoAutoTargetWhileIdle(boolean v) { this.noAutoTargetWhileIdle = v; }
    public boolean isAssistOnly() { return assistOnly; }
    public void setAssistOnly(boolean v) { this.assistOnly = v; }
    public boolean isLavaClutchStunts() { return lavaClutchStunts; }
    public void setLavaClutchStunts(boolean v) { this.lavaClutchStunts = v; }
    public boolean isPatrolEngage() { return patrolEngage; }
    public boolean isThreatTargeting() { return threatTargeting; }
    public boolean isInvestigate() { return investigate; }
    public double getPatrolEngageRange() { return patrolEngageRange; }
    public void setHostile(boolean v) { this.hostile = v; }
    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean v) { this.frozen = v; }
    public boolean isInvisibilityConfusion() { return invisibilityConfusion; }
    public void setInvisibilityConfusion(boolean v) { this.invisibilityConfusion = v; }

    public boolean isPearling() { return pearling; }
    public void setPearling(boolean v) { this.pearling = v; }

    public boolean isTeamTarget() { return teamTarget; }
    public void setTeamTarget(boolean v) { this.teamTarget = v; }

    public double getTeamTargetRadius() { return teamTargetRadius; }
    public void setTeamTargetRadius(double v) { this.teamTargetRadius = v; }

    public boolean isCartDefense() { return cartDefense; }
    public void setCartDefense(boolean v) { this.cartDefense = v; }

    public double getRotationSpeed() { return rotationSpeed; }
    public void setRotationSpeed(double v) { this.rotationSpeed = Math.max(1.0, Math.min(90.0, v)); }

    public double getPitchSpeed() { return pitchSpeed; }
    public void setPitchSpeed(double v) { this.pitchSpeed = Math.max(1.0, Math.min(90.0, v)); }

    public double getAimEase() { return aimEase; }
    public void setAimEase(double v) { this.aimEase = Math.max(0.02, Math.min(1.0, v)); }

    public int getReactionTicks() { return reactionTicks; }
    public void setReactionTicks(int v) { this.reactionTicks = Math.max(0, Math.min(20, v)); }

    public boolean isElytraMacing() { return elytraMacing; }
    public void setElytraMacing(boolean v) { this.elytraMacing = v; }

    public boolean isLungeSwap() { return lungeSwap; }
    public void setLungeSwap(boolean v) { this.lungeSwap = v; }

    public boolean isBreachSwap() { return breachSwap; }
    public void setBreachSwap(boolean v) { this.breachSwap = v; }

    public boolean isMaceSmash() { return maceSmash; }
    public void setMaceSmash(boolean v) { this.maceSmash = v; }
    public BotDifficulty getDifficulty() { return difficulty; }

    public static final class Option {
        public final String key;
        public final String label;
        public final java.util.List<String> suggestions;
        private final java.util.function.Function<BotSettings, String> show;
        private final java.util.function.Function<BotSettings, String> raw;
        private final java.util.function.BiFunction<BotSettings, String, String> apply;

        Option(String key, String label, java.util.List<String> suggestions,
               java.util.function.Function<BotSettings, String> show,
               java.util.function.BiFunction<BotSettings, String, String> apply) {
            this(key, label, suggestions, show, show, apply);
        }

        Option(String key, String label, java.util.List<String> suggestions,
               java.util.function.Function<BotSettings, String> show,
               java.util.function.Function<BotSettings, String> raw,
               java.util.function.BiFunction<BotSettings, String, String> apply) {
            this.key = key;
            this.label = label;
            this.suggestions = suggestions;
            this.show = show;
            this.raw = raw;
            this.apply = apply;
        }

        public String display(BotSettings s) { return show.apply(s); }

        public String raw(BotSettings s) { return raw.apply(s); }

        public boolean isBoolean() { return suggestions == BOOL_VALUES; }

        public boolean isNumeric() { return suggestions != BOOL_VALUES; }

        public String set(BotSettings s, String raw) { return apply.apply(s, raw); }
    }

    private static final java.util.List<String> BOOL_VALUES = java.util.List.of("true", "false");

    private static final java.util.Map<String, Option> OPTIONS = buildOptions();

    public static java.util.Collection<Option> options() { return OPTIONS.values(); }

    public static Option option(String key) {
        return key == null ? null : OPTIONS.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    public static java.util.Set<String> optionKeys() { return OPTIONS.keySet(); }

    private static java.util.Map<String, Option> buildOptions() {
        java.util.LinkedHashMap<String, Option> m = new java.util.LinkedHashMap<>();

        addBool(m, "strafing",   "Strafing",     s -> s.strafing,   (s, v) -> s.strafing = v);
        addBool(m, "stapping",   "S-Tapping",    s -> s.sTapping,   (s, v) -> s.sTapping = v);
        addBool(m, "wtapping",   "W-Tapping",    s -> s.wTapping,   (s, v) -> s.wTapping = v);
        addBool(m, "sprint",     "Sprint",       s -> s.sprint,     (s, v) -> s.sprint = v);
        addBool(m, "bhop",       "Bhop",         s -> s.bhop,       (s, v) -> s.bhop = v);
        addBool(m, "criticals",  "Criticals",    s -> s.criticals,  (s, v) -> s.criticals = v);
        addBool(m, "normalhits", "Normal Hits",  s -> s.normalHits, (s, v) -> s.normalHits = v);
        addBool(m, "shielding",  "Shielding",    s -> s.shielding,  (s, v) -> s.shielding = v);
        addBool(m, "shieldbreak","Shield Break", s -> s.shieldBreak,(s, v) -> s.shieldBreak = v);
        addBool(m, "autosword",  "Auto Sword",   s -> s.autoSword,  (s, v) -> s.autoSword = v);
        addBool(m, "autoeat",    "Auto Eat",     s -> s.autoEat,    (s, v) -> s.autoEat = v);
        addBool(m, "bots-use-invis", "Bots Use Invis",
                s -> s.botsUseInvis, (s, v) -> s.botsUseInvis = v);
        addBool(m, "hitcoordination", "Hit Coordination",
                s -> s.hitCoordination, (s, v) -> s.hitCoordination = v);
        addBool(m, "cartdefense", "Cart Defense",
                s -> s.cartDefense, (s, v) -> s.cartDefense = v);
        addBool(m, "pearling", "Pearling",
                s -> s.pearling, (s, v) -> s.pearling = v);
        addBool(m, "teamtarget", "Team Target",
                s -> s.teamTarget, (s, v) -> s.teamTarget = v);
        addDouble(m, "teamtargetradius", "Team Target Radius", "%.0f blocks", 0.0, 128.0,
                java.util.List.of("0", "15", "25", "40"),
                s -> s.teamTargetRadius, (s, v) -> s.teamTargetRadius = v);
        addBool(m, "hostile", "Hostile",
                s -> s.hostile, (s, v) -> s.hostile = v);
        addBool(m, "wanderwithoutfaction", "Idle Wandering",
                s -> s.wanderWithoutFaction, (s, v) -> s.wanderWithoutFaction = v);
        addBool(m, "noautotargetwhileidle", "No Auto-Target While Idle",
                s -> s.noAutoTargetWhileIdle, (s, v) -> s.noAutoTargetWhileIdle = v);
        addBool(m, "assistonly", "Assist Only (never picks the fight)",
                s -> s.assistOnly, (s, v) -> s.assistOnly = v);
        addBool(m, "lavaclutchstunts", "Lava Clutch Stunts",
                s -> s.lavaClutchStunts, (s, v) -> s.lavaClutchStunts = v);
        addBool(m, "threattargeting", "Threat-Weighted Targeting",
                s -> s.threatTargeting, (s, v) -> s.threatTargeting = v);
        addBool(m, "investigate", "Investigate Last Known Position",
                s -> s.investigate, (s, v) -> s.investigate = v);
        addBool(m, "patrolengage", "Patrol Engages Enemies",
                s -> s.patrolEngage, (s, v) -> s.patrolEngage = v);
        addDouble(m, "patrolengagerange", "Patrol Engage Range", "%.0f blocks", 0.0, 64.0,
                java.util.List.of("0", "12", "20", "32"),
                s -> s.patrolEngageRange, (s, v) -> s.patrolEngageRange = v);
        addBool(m, "frozen", "Frozen (training dummy)",
                s -> s.frozen, (s, v) -> s.frozen = v);
        addBool(m, "invisibility-confusion", "Confused by invisibility",
                s -> s.invisibilityConfusion, (s, v) -> s.invisibilityConfusion = v);

        addBool(m, "bridging", "Bridge Over Gaps",
                s -> s.bridging, (s, v) -> s.bridging = v);
        addBool(m, "speedbridging", "Speed Bridge (crouch-tap style)",
                s -> s.speedBridging, (s, v) -> s.speedBridging = v);
        addBool(m, "clutching", "Clutch When Falling",
                s -> s.clutching, (s, v) -> s.clutching = v);
        addBool(m, "restocking", "Restock From Shulkers",
                s -> s.restocking, (s, v) -> s.restocking = v);
        addBool(m, "opendoors", "Open Doors",
                s -> s.openDoors, (s, v) -> s.openDoors = v);
        addBool(m, "webescape", "Escape Cobwebs",
                s -> s.webEscape, (s, v) -> s.webEscape = v);
        addBool(m, "terrainmemory", "Use Learned Terrain",
                s -> s.useTerrainMemory, (s, v) -> s.useTerrainMemory = v);
        addBool(m, "pathfinding", "Use Pathfinder",
                s -> s.pathfinding, (s, v) -> s.pathfinding = v);
        addBool(m, "crowdavoidance", "Route Around Other Bots",
                s -> s.crowdAvoidance, (s, v) -> s.crowdAvoidance = v);
        addBool(m, "tunnelwhenstuck", "Dig Out When Stuck",
                s -> s.tunnelWhenStuck, (s, v) -> s.tunnelWhenStuck = v);
        addDouble(m, "leaderleash", "Leader Leash", "%.0f blocks", 4.0, 256.0,
                java.util.List.of("16", "24", "48", "96"),
                s -> s.leaderLeash, (s, v) -> s.leaderLeash = v);

        addBool(m, "punishcrit", "P-Crit (punish crit)",
                s -> s.punishCrit, (s, v) -> s.punishCrit = v);
        addBool(m, "jumpreset", "Jump Reset",
                s -> s.jumpReset, (s, v) -> s.jumpReset = v);
        addBool(m, "hitselect", "Hit Select",
                s -> s.hitSelect, (s, v) -> s.hitSelect = v);
        addBool(m, "critdeflect", "Crit Deflection",
                s -> s.critDeflect, (s, v) -> s.critDeflect = v);
        addBool(m, "outspacing", "Outspacing",
                s -> s.outspacing, (s, v) -> s.outspacing = v);
        addBool(m, "circlestrafe", "Circle Strafing",
                s -> s.circleStrafe, (s, v) -> s.circleStrafe = v);
        addBool(m, "shieldbackstab", "Shield Backstab",
                s -> s.shieldBackstab, (s, v) -> s.shieldBackstab = v);

        addBool(m, "cartpvp", "TNT Cart PvP",
                s -> s.cartPvp, (s, v) -> s.cartPvp = v);
        addBool(m, "xbowcart", "Prefer X-Bow Cart",
                s -> s.xbowCart, (s, v) -> s.xbowCart = v);
        addInt(m, "cartcooldown", "Cart Cooldown", " ticks", 20, 1200,
                java.util.List.of("100", "200", "400"),
                s -> s.cartCooldownTicks, (s, v) -> s.cartCooldownTicks = v);

        addDouble(m, "reach", "Reach", "%.1f", 1.0, 6.0,
                java.util.List.of("2.5", "3.0", "3.5"),
                s -> s.reach, (s, v) -> s.reach = v);
        addInt(m, "cooldown", "Attack Cooldown", " ticks", 1, 40,
                java.util.List.of("10", "11", "13"),
                s -> s.attackCooldownTicks, (s, v) -> s.attackCooldownTicks = v);
        addDouble(m, "strafespeed", "Strafe Speed", "%.2f", 0.0, 2.0,
                java.util.List.of("0.5", "0.8", "1.0"),
                s -> s.strafeSpeed, (s, v) -> s.strafeSpeed = v);
        addDouble(m, "range", "Target Range", "%.1f", 1.0, 512.0,
                java.util.List.of("48", "128", "196", "256"),
                s -> s.targetRange, (s, v) -> s.targetRange = v);
        addPercent(m, "misschance", "Miss Chance",
                s -> s.missChance, (s, v) -> s.missChance = v);
        addPercent(m, "shieldbreakchance", "Shield Break Chance",
                s -> s.shieldBreakChance, (s, v) -> s.shieldBreakChance = v);
        addInt(m, "critfallticks", "Crit Fall Ticks", "", 0, 20,
                java.util.List.of("1", "2", "3"),
                s -> s.criticalFallTicks, (s, v) -> s.criticalFallTicks = v);

        addDouble(m, "aimnoise", "Aim Noise", "%.1f", 0.0, 30.0,
                java.util.List.of("0", "1", "3", "6"),
                s -> s.aimNoise, (s, v) -> s.aimNoise = v);
        addDouble(m, "rotationspeed", "Rotation Speed", "%.1f°/t", 1.0, 90.0,
                java.util.List.of("9", "13", "22", "30"),
                s -> s.rotationSpeed, (s, v) -> s.rotationSpeed = v);
        addDouble(m, "pitchspeed", "Pitch Speed", "%.1f°/t", 1.0, 90.0,
                java.util.List.of("5.5", "8", "13", "18"),
                s -> s.pitchSpeed, (s, v) -> s.pitchSpeed = v);
        addDouble(m, "aimease", "Aim Ease", "%.2f", 0.02, 1.0,
                java.util.List.of("0.16", "0.26", "0.38", "0.48"),
                s -> s.aimEase, (s, v) -> s.aimEase = v);
        addInt(m, "reactionticks", "Reaction Ticks", " ticks", 0, 20,
                java.util.List.of("1", "2", "4", "6"),
                s -> s.reactionTicks, (s, v) -> s.reactionTicks = v);

        addDouble(m, "fleehealth", "Flee Below", "%.1f HP", 0.0, 20.0,
                java.util.List.of("6", "8", "10"),
                s -> s.fleeHealthThreshold, (s, v) -> s.fleeHealthThreshold = v);
        addBool(m, "lungeswap", "Lunge Swap (spear gap-closer)",
                s -> s.lungeSwap, (s, v) -> s.lungeSwap = v);
        addBool(m, "breachswap", "Breach / Attribute Swap",
                s -> s.breachSwap, (s, v) -> s.breachSwap = v);
        addBool(m, "macesmash", "Mace Smash Attacks",
                s -> s.maceSmash, (s, v) -> s.maceSmash = v);
        addBool(m, "elytramacing", "Elytra Macing", s -> s.elytraMacing, (s, v) -> s.elytraMacing = v);
        addDouble(m, "returnhealth", "Re-engage At", "%.1f HP", 0.0, 20.0,
                java.util.List.of("14", "16", "18"),
                s -> s.returnHealthThreshold, (s, v) -> s.returnHealthThreshold = v);
        addDouble(m, "fleedistance", "Flee Distance", "%.1f", 1.0, 64.0,
                java.util.List.of("10", "15", "20"),
                s -> s.fleeDistance, (s, v) -> s.fleeDistance = v);
        addBool(m, "prechaseheal", "Heal Before Chasing",
                s -> s.preChaseHeal, (s, v) -> s.preChaseHeal = v);
        addDouble(m, "prechasehealth", "Heal-Before-Chase HP", "%.1f HP", 0.0, 20.0,
                java.util.List.of("10", "14", "16"),
                s -> s.preChaseHealHealth, (s, v) -> s.preChaseHealHealth = v);
        addDouble(m, "prechasedistance", "Heal-Before-Chase Range", "%.1f", 0.0, 64.0,
                java.util.List.of("8", "12", "20"),
                s -> s.preChaseHealDistance, (s, v) -> s.preChaseHealDistance = v);

        m.put("difficulty", new Option("difficulty", "Difficulty",
                java.util.List.of("EASY", "NORMAL", "HARD", "EXPERT"),
                s -> s.difficulty.name(),
                (s, raw) -> {
                    try {
                        s.applyDifficulty(BotDifficulty.valueOf(raw.toUpperCase(java.util.Locale.ROOT)));
                        return null;
                    } catch (IllegalArgumentException e) {
                        return "Use EASY, NORMAL, HARD or EXPERT.";
                    }
                }));
        return m;
    }

    private static void addBool(java.util.Map<String, Option> m, String key, String label,
                                java.util.function.Predicate<BotSettings> get,
                                java.util.function.BiConsumer<BotSettings, Boolean> set) {
        m.put(key, new Option(key, label, BOOL_VALUES,
                s -> get.test(s) ? "§aON" : "§cOFF",
                s -> String.valueOf(get.test(s)),
                (s, raw) -> {
                    String v = raw.toLowerCase(java.util.Locale.ROOT);
                    if (v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1")) {
                        set.accept(s, true);
                        return null;
                    }
                    if (v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0")) {
                        set.accept(s, false);
                        return null;
                    }
                    return "Expected true or false.";
                }));
    }

    private static void addDouble(java.util.Map<String, Option> m, String key, String label,
                                  String format, double min, double max,
                                  java.util.List<String> suggestions,
                                  java.util.function.ToDoubleFunction<BotSettings> get,
                                  java.util.function.ObjDoubleConsumer<BotSettings> set) {
        m.put(key, new Option(key, label, suggestions,
                s -> String.format(format, get.applyAsDouble(s)),
                s -> String.valueOf(get.applyAsDouble(s)),
                (s, raw) -> {
                    double v;
                    try {
                        v = Double.parseDouble(raw);
                    } catch (NumberFormatException e) {
                        return "Expected a number.";
                    }
                    if (v < min || v > max) return "Must be between " + min + " and " + max + ".";
                    set.accept(s, v);
                    return null;
                }));
    }

    private static void addInt(java.util.Map<String, Option> m, String key, String label,
                               String suffix, int min, int max,
                               java.util.List<String> suggestions,
                               java.util.function.ToIntFunction<BotSettings> get,
                               java.util.function.ObjIntConsumer<BotSettings> set) {
        m.put(key, new Option(key, label, suggestions,
                s -> get.applyAsInt(s) + suffix,
                s -> String.valueOf(get.applyAsInt(s)),
                (s, raw) -> {
                    int v;
                    try {
                        v = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        return "Expected a whole number.";
                    }
                    if (v < min || v > max) return "Must be between " + min + " and " + max + ".";
                    set.accept(s, v);
                    return null;
                }));
    }

    private static void addPercent(java.util.Map<String, Option> m, String key, String label,
                                   java.util.function.ToDoubleFunction<BotSettings> get,
                                   java.util.function.ObjDoubleConsumer<BotSettings> set) {
        m.put(key, new Option(key, label, java.util.List.of("0", "10", "35", "60"),
                s -> String.format("%.0f%%", get.applyAsDouble(s) * 100.0),

                s -> String.valueOf(get.applyAsDouble(s)),
                (s, raw) -> {
                    double v;
                    try {
                        v = Double.parseDouble(raw.endsWith("%") ? raw.substring(0, raw.length() - 1) : raw);
                    } catch (NumberFormatException e) {
                        return "Expected a percentage, e.g. 35.";
                    }

                    if (v > 1.0) v /= 100.0;
                    if (v < 0.0 || v > 1.0) return "Must be between 0 and 100.";
                    set.accept(s, v);
                    return null;
                }));
    }

    public void saveTo(org.bukkit.configuration.ConfigurationSection sec) {
        if (sec == null) return;
        for (Option o : OPTIONS.values()) {
            sec.set(o.key, o.raw(this));
        }
    }

    public void loadFrom(org.bukkit.configuration.ConfigurationSection sec) {
        if (sec == null) return;

        String diff = sec.getString("difficulty");
        if (diff != null) {
            Option d = option("difficulty");
            if (d != null) d.set(this, diff);
        }

        for (String key : sec.getKeys(false)) {
            if (key.equalsIgnoreCase("difficulty")) continue;
            Option o = option(key);
            if (o == null) continue;
            String v = sec.getString(key);
            if (v != null) o.set(this, v);
        }
    }

    public String toDisplayString() {
        StringBuilder sb = new StringBuilder("§6§lPvPBot Settings §7(" + OPTIONS.size() + " options)");
        for (Option o : OPTIONS.values()) {
            sb.append("\n§e ").append(o.label).append(": §f").append(o.display(this))
              .append(" §8(").append(o.key).append(')');
        }
        return sb.toString();
    }
}