package com.pvpbot.ai;

import com.pvpbot.BotSettings;
import com.pvpbot.PvPBot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import java.util.List;

public class BotAIContext {
    public final PvPBot bot;
    public final BotSettings settings;
    public Player target;

    public Player factionCombatTarget = null;
    public int factionCombatTicks = 0;

    public static final int FACTION_COMBAT_MEMORY_TICKS = 200;

    public float forwardInput = 0.0f;
    public float strafeInput = 0.0f;
    public int findTargetCooldown = 0;

    public float pendingForwardInput = 0f;
    public float pendingStrafeInput = 0f;
    public int inputLatchTicks = 0;
    public int inputReactionTicks = 0;

    public int buffScanCooldown = 0;

    public int forceFullTicks = 0;

    public int descendPearlCooldown = 0;
    public int speedBridgePhase = 0;

    public int attackCooldown = 0;

    public int lastAttackTick = -1000;
    public int lastLandedAttackTick = -1000;

    public float attackChargeThreshold = 0.95f;
    public int criticalRetryTicks = 0;

    public String lastAttackGate = "n/a";
    public double lastAttackDistance = -1;
    public double lastAttackReachDist = -1;
    public boolean lastAttackHasLOS = false;
    public float lastAttackCharge = -1;
    public float lastAttackThreshold = -1;
    public boolean lastAttackCharged = false;

    public int critFallTicks = 0;

    public int shieldHitTicks = 0;
    static final double GRAVITY = 0.08;
    static final double DRAG = 0.98;
    public int inventoryPauseTimer = 0;
    public int strafeDirection = 1;
    public boolean inWater = false;

    public int waterExitTicks = 0;
    public int axeStunCooldown = 0;

    public int shieldStunnedTicks = 0;

    public int maceWindupTicks = 0;
    public int maceWindupCooldown = 0;
    public int maceWindupDelay = 0;
    public org.bukkit.entity.Projectile maceWindCharge = null;
    public int maceWindChargeTicks = 0;
    public int maceHoldAfterAttack = 0;

    public java.util.UUID cartingTeammate = null;
    public int cartingWarningTicks = 0;

    public int maceStunSlamPhase = 0;
    public int maceStunSlamPhaseTicks = 0;
    public int maceStunSlamCooldown = 0;

    public boolean ignoreSwingCharge = false;

    public int ticksSinceSwing = 0;
    public int breachSwapCooldown = 0;
    public int lungeSwapCooldown = 0;
    public int lungeChainRemaining = 0;
    public int lungeRetryTicks = 0;
    public int elytraApproachCooldown = 0;
    public int elytraApproachTicks = 0;
    public int elytraApproachBurn = 0;
    public int elytraIdleTicks = 0;
    public int agroCartCooldown = 0;
    public int agroCartWindow = 0;

    public double swingReachOverride = 0.0;
    public org.bukkit.Material lastMainHandType = org.bukkit.Material.AIR;

    public int corneredTicks = 0;

    public int fleeBlockedTicks = 0;

    public int holeEscapeTicks = 0;
    public double holeEscapeBestY = Double.NEGATIVE_INFINITY;

    public Player forcedTarget = null;

    public enum GuardMode {
        POST,

        LEADER
    }

    public GuardMode guardMode = GuardMode.POST;

    public Location guardAnchor = null;

    public double guardRadius = 16.0;

    public double guardLeash = 24.0;

    public float guardFacing = 0f;

    public int guardScanTicks = 0;
    public float guardScanYaw = 0f;

    public boolean guardReturning = false;

    public boolean isGuarding() {
        return guardAnchor != null;
    }

    public int holeCheckCooldown = 0;

    public boolean holeCheckCached = false;
    public int weaponSwitchCooldown = 0;
    public int critPhaseTicks = 0;
    public int cobwebCooldown = 0;
    public int breakingCobwebTimer = 0;
    int cobwebDefenseBreakTicks = 0;
    int cobwebBreakTarget = 11;
    int waterScoopTimer = 0;
    Location placedWaterLoc = null;
    int blockPlaceCooldown = 0;
    int blockBreakCooldown = 0;
    int blockBreakTimer = 0;
    public Location blockToBreak = null;
    public int splashPotionTimer = 0;

    public int pendingPotionSlot = -1;

    public int splashThrowSlot = -1;

    public final java.util.ArrayDeque<Integer> splashBuffQueue = new java.util.ArrayDeque<>();

    public int splashPotionPhase = 0;

    public int splashPotionDelayTicks = 0;
    public int jumpCooldown = 0;

    public int jumpBanTicks = 0;

    public double lastProgressDistance = -1.0;
    public int progressCheckTicks = 0;

    public boolean suppressSprint = false;

    public boolean idleWalking = false;
    public int idlePhaseTicks = 0;
    public org.bukkit.inventory.ItemStack potionOffhandBackup = null;
    public boolean potionOffhandPreserved = false;

    public org.bukkit.inventory.ItemStack elytraChestBackup = null;
    public int elytraChestSlot = -1;
    public boolean elytraEquippedForMace = false;

    int idleWanderTimer = 0;
    float idleStrafeDirection = 1;
    int idleStuckTicks = 0;

    Player lastDamager = null;
    int lastDamageTime = 0;
    static final int DAMAGE_MEMORY_TICKS = 40;

    Player assistTarget = null;
    int assistTargetTick = -1000;
    static final int ASSIST_MEMORY_TICKS = 120;

    static final double ASSIST_RANGE_FACTOR_SQ = 2.25;

    public boolean followHolding = false;
    double lastHealth = -1;
    boolean externallyNotifiedDamage = false;

    public double velX = 0.0;
    public double velZ = 0.0;
    public float  idleTargetYaw = 0f;

    boolean sTapActive = false;
    int sTapTimer = 0;

    public int knockbackRideTicks = 0;

    public int lastKnockbackArmTick = -100;
    boolean wtapPending = false;
    public int strafeFlipTicks = 0;
    public int avoidTicks = 0;
    public int avoidDir = 0;
    public float avoidStrafeOverride = Float.NaN;

    public float smoothedForwardInput = 0.0f;
    public float smoothedStrafeInput = 0.0f;
    public int inputChangeCooldown = 0;

    public int pathNodeStuckTicks = 0;
    public int lastPathNodeIndex = -1;
    public double lastPathNodeDistance = -1.0;
    public int pathBanTicks = 0;

    public int chaseBlockedTicks = 0;

    public int pathFailures = 0;

    public int pathDeferrals = 0;

    public int wallBumpTicks = 0;

    public int doorCooldown = 0;

    public int confusedTicks = 0;

    public int shieldPredictTicks = 0;
    public int shieldHoldTicks = 0;
    public int shieldFlickerTicks = 0;
    int enemyPredictCooldown = 0;
    double lastEnemyDist = Double.MAX_VALUE;

    public int smashThreatTicks = 0;

    public double smashEvadeX = 0.0;
    public double smashEvadeZ = 0.0;
    public int smashEvadeHold = 0;
    public int smashEvadeSign = 1;

    public int targetElevatedTicks = 0;

    public double pillarBaseY = 0.0;

    public boolean jumpAirborne = false;
    public int jumpFromX = Integer.MIN_VALUE;
    public int jumpFromY = Integer.MIN_VALUE;
    public int jumpFromZ = Integer.MIN_VALUE;
    public int failedJumps = 0;

    public int bridgeOverhangTicks = 0;

    public int edgeBlockedTicks = 0;

    public boolean jiggleIn = true;
    public int jigglePhaseTicks = 0;

    public float fightForwardInput = 0.35f;
    public float fightBackInput = -0.30f;
    public float fightStrafeScale = 1.0f;
    public int fightPauseTicks = 0;
    public int fightPauseCooldown = 0;
    public float lastTargetAttackCharge = 1.0f;

    public boolean inFightRange = false;

    public boolean aimLockedOnTarget = false;

    public int comboCount = 0;

    public int attackDeniedTicks = 0;

    public int knockbackReactionTicks = 0;
    public int strafeChangeTimer = 0;
    public int lastStrafeDir = 1;
    public double knockbackDirX = 0.0;
    public double knockbackDirZ = 0.0;
    public int opponentAttackingTicks = 0;
    public int potionUsedThisFight = 0;

    public double aimDriftAmount = 0.0;
    public int aimDriftChangeTimer = 0;

    public double panicAimingLevel = 0.0;

    public int weaponSwitchPendingSlot = -1;
    public int weaponSwitchDelayTicks = 0;

    public int movementInputStutterCooldown = 0;

    public int tickCounter = 0;

    public static final int LOOK_IDLE = 10;
    public static final int LOOK_TRAVEL = 30;
    public static final int LOOK_UTILITY = 50;
    public static final int LOOK_COMBAT = 70;
    public static final int LOOK_CRITICAL = 90;

    public float lookYaw = 0f;
    public float lookPitch = 0f;
    public int lookPriority = -1;
    public boolean lookSet = false;
    public boolean lookSnap = false;
    public float lookYawGain = -1f;
    public boolean lookYawOnly = false;
    public boolean lookPitchOnly = false;

    public void requestLook(float yaw, float pitch, int priority, boolean snap) {
        if (lookSet && priority <= lookPriority) return;
        lookYaw = yaw;
        lookPitch = pitch;
        lookPriority = priority;
        lookSnap = snap;
        lookSet = true;
        lookYawGain = -1f;
        lookYawOnly = false;
        lookPitchOnly = false;
    }

    public void requestLookYaw(float yaw, int priority) {
        float keepPitch = lookPitch;
        if (lookSet && priority <= lookPriority) return;
        requestLook(yaw, keepPitch, priority, false);
        lookYawOnly = true;
    }

    public void requestLookPitch(float pitch, int priority) {
        if (lookSet && priority <= lookPriority) return;
        requestLook(lookYaw, pitch, priority, false);
        lookPitchOnly = true;
    }

    public final EnemyMemory enemyMemory = new EnemyMemory();

    public final NavAvoid navAvoid = new NavAvoid();

    public void markNavFailure(int x, int y, int z) {
        navAvoid.mark(x, y, z, tickCounter);
        if (!settings.isUseTerrainMemory()) return;
        try {
            org.bukkit.entity.Player p = bot.getBukkitPlayer();
            if (p != null) com.pvpbot.nav.TerrainMemory.reportFailure(p.getWorld(), x, y, z);
        } catch (Throwable ignored) {
        }
    }

    public void markNavFailure(org.bukkit.Location loc) {
        if (loc == null) return;
        markNavFailure(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public String navBranch = "-";

    public TacticsController.Mode lastTacticsMode = null;

    public boolean pathComplete = false;

    public Location investigateTarget = null;
    public int investigateTicks = 0;
    public int investigateLookTicks = 0;
    public int investigateScanCooldown = 0;
    public float investigateScanYaw = 0f;

    public int pathCollideTicks = 0;

    public int laneCacheTick = -1000;
    public int laneCacheX = Integer.MIN_VALUE;
    public int laneCacheZ = Integer.MIN_VALUE;
    public double laneCacheBotX = Double.NaN;
    public double laneCacheBotZ = Double.NaN;
    public boolean laneCacheClear = true;

    public boolean fleeing = false;
    public boolean eating = false;

    public int eatCommitTicks = 0;
    public int eatChainCooldown = 0;
    public int eatingTicks = 0;

    public int targetShieldMemory = 0;

    public String lastShieldBreak = "-";
    int eatTimer = 0;
    static final int EAT_DURATION = 80;
    boolean lastSprinting = false;
    public int selfHurtTicks = 0;
    public double prevTickX = 0;
    public double prevTickY = 0;
    public double prevTickZ = 0;
    public boolean prevTickInitialized = false;

    int fleeStrafeDir = 1;
    public int fleeStrafeTimer = 0;
    float fleeOpenYaw = 0f;
    public int fleeOpenYawTicks = 0;
    public double lastFleeGap = -1.0;
    public int fleePanicTicks = 0;
    public int fleeGapAssistCooldown = 0;
    int healPotionCooldown = 0;

    public int healCommitTicks = 0;

    public int buffCooldown = 0;

    public int pearlCooldown = 0;

    public int pearlsThisFlee = 0;

    public int windLaunchCooldown = 0;

    public int offhandSwapTicks = 0;
    public org.bukkit.Material pendingOffhand = null;

    public org.bukkit.Location formationSlot = null;
    public int formationTicks = 0;

    public int regroupTicks = 0;

    public org.bukkit.Location cartThreat = null;

    public int cartThreatTicks = 0;

    public int cartReactionTicks = 0;

    public boolean cartReacting = false;

    public int cartBlockCooldown = 0;

    public int totemRecoveryTicks = 0;
    public int drinkingPotionTimer = 0;
    int drinkingPotionSlot = -1;
    boolean drinkingIsRegen = false;
    static final int DRINK_DURATION = 60;
    public int fleePathCooldown = 0;

    public boolean bridging = false;
    public int bridgeCheckCooldown = 0;

    public int bridgePlaceCooldown = 0;
    int bridgeStuckTicks = 0;
    Location lastBridgePos = null;

    public static final double BRIDGE_TRIGGER_GAP = 2.2;

    public enum BridgeMode { STANDARD, SCAFFOLD_UP, JUMP_OVER, REVERSE, SPEED }
    public BridgeMode bridgeMode = BridgeMode.STANDARD;
    public BridgeMode proposedBridgeMode = BridgeMode.STANDARD;
    public int detectedGapLength = 0;
    int bridgePlacementWindup = 0;

    public double bridgeEdgeDist = 0.0;

    public double bridgeLandingDist = -1.0;

    public int bridgeStepX = 0;
    public int bridgeStepZ = 0;

    public boolean bridgeCrossed = false;

    public boolean bridgeWanted = false;

    public int jumpOverSettleTicks = 0;

    public int bridgeModeTicks = 0;

    public boolean bridgeReachedEdge = false;

    public enum CritPhase { IDLE, JUMPING, ASCENDING }
    public CritPhase critPhase = CritPhase.IDLE;

    public volatile List<Location> currentPath = new java.util.ArrayList<>();
    public int pathNodeIndex = 0;
    int stuckTicks = 0;
    public int immobilizedTicks = 0;
    Location lastPos = null;
    public long lastFullInventoryHash = 0;
    public int inventoryChangeCooldown = 0;
    public int pathRecalcCooldown = 0;

    public int holeEscapeCooldown = 0;
    public Location holeEscapeTarget = null;

    public double lastX = 0;
    public double lastY = 0;
    public double lastZ = 0;

    public double ownedDy = 0.0;

    float targetYaw = 0.0f;
    float targetPitch = 0.0f;

    float rotationSpeed() { return (float) settings.getRotationSpeed(); }
    float pitchSpeed()    { return (float) settings.getPitchSpeed(); }
    float aimEase()       { return (float) settings.getAimEase(); }
    int   reactionTicks() { return settings.getReactionTicks(); }

    float aimNoiseYaw = 0f;
    float aimNoisePitch = 0f;

    float aimOvershoot = 1.0f;
    int aimOvershootTicks = 0;

    final AimHistory aimHistory = new AimHistory();

    static final int BROADCAST_INTERVAL = 4;
    int ticksSinceBroadcast = 0;

    int cachedBestWeapon = -1;
    long lastInventoryHash = 0;

    int targetCacheTicks = 0;

    int lastTargetSwitchTick = -1000;
    static final int TARGET_COMMIT_TICKS = 40;
    static final double SWITCH_CLOSER_FACTOR_SQ = 0.36;

    ItemStack lastMainHand = null;
    ItemStack lastOffHand = null;
    int equipmentBroadcastCooldown = 0;

    boolean shouldJumpThisTick = false;

    volatile boolean pathfindingInProgress = false;

    public MovementController movementController;
    public CombatController combatController;
    public HealingController healingController;
    public InventoryController inventoryController;
    public PathfindingController pathfindingController;
    public PacketBroadcaster packetBroadcaster;
    public TargetingController targetingController;
    public TacticsController tacticsController;
    public HazardController hazardController;
    public RestockController restockController;
    public ClutchController clutchController;
    public BuildController buildController;
    public PatrolController patrolController;
    public DeliveryController deliveryController;
    public MiningController miningController;
    public AreaMiningController areaMiningController;
    public TechniqueController techniqueController;
    public CartController cartController;
    public TunnelController tunnelController;
    public FarmController farmController;
    public MaceController maceController;
    public GolemFightController golemFightController;
    public LavaStuntController lavaStuntController;

    public final ObjectPool objectPool = new ObjectPool();

    public BotAIContext(PvPBot bot, BotSettings settings) {
        this.bot = bot;
        this.settings = settings;
        net.minecraft.server.level.ServerPlayer handle = bot.getHandle();
        if (handle != null) {
            this.lastX = handle.getX();
            this.lastY = handle.getY();
            this.lastZ = handle.getZ();
            this.ownedDy = handle.getDeltaMovement().y;
        }
        Player bp = bot.getBukkitPlayer();
        if (bp != null) this.lastHealth = bp.getHealth();
    }

    public void clearTransientAI() {
        assistTarget = null;
        shouldJumpThisTick = false;
        fleeing = false;
        fleeGapAssistCooldown = 0;
        eating = false;
        drinkingPotionTimer = 0;
        avoidTicks = 0;
        wallBumpTicks = 0;
        chaseBlockedTicks = 0;
        holeEscapeTicks = 0;
        holeEscapeTarget = null;
        confusedTicks = 0;
        critPhase = CritPhase.IDLE;
        critPhaseTicks = 0;
        critFallTicks = 0;
        criticalRetryTicks = 0;
        comboCount = 0;
        knockbackReactionTicks = 0;
        shieldPredictTicks = 0;
        shieldHoldTicks = 0;
        shieldFlickerTicks = 0;
    }

    public boolean hasNoActiveOrders() {
        if (guardAnchor != null) return false;
        if (formationSlot != null) return false;
        if (com.pvpbot.PvPBotPlugin.getInstance().getBotManager().getLeaderFor(bot.getUUID()) != null) {
            return false;
        }
        if (buildController != null && buildController.isBusy()) return false;
        if (miningController != null && miningController.isActive()) return false;
        if (areaMiningController != null && areaMiningController.isActive()) return false;
        if (farmController != null && farmController.isActive()) return false;
        if (deliveryController != null && deliveryController.isActive()) return false;
        if (patrolController != null && patrolController.isActive()) return false;
        if (cartController != null && cartController.isActive()) return false;
        if (tunnelController != null && tunnelController.isActive()) return false;
        if (golemFightController != null && golemFightController.isActive()) return false;
        if (lavaStuntController != null && lavaStuntController.isActive()) return false;
        return true;
    }

    public void initializeControllers() {
        this.packetBroadcaster = new PacketBroadcaster(this);
        this.pathfindingController = new PathfindingController(this);
        this.movementController = new MovementController(this);
        this.combatController = new CombatController(this);
        this.healingController = new HealingController(this);
        this.inventoryController = new InventoryController(this);
        this.targetingController = new TargetingController(this);
        this.hazardController = new HazardController(this);
        this.tacticsController = new TacticsController(this);
        this.restockController = new RestockController(this);
        this.clutchController = new ClutchController(this);
        this.buildController = new BuildController(this);
        this.patrolController = new PatrolController(this);
        this.deliveryController = new DeliveryController(this);
        this.miningController = new MiningController(this);
        this.areaMiningController = new AreaMiningController(this);
        this.techniqueController = new TechniqueController(this);
        this.cartController = new CartController(this);
        this.tunnelController = new TunnelController(this);
        this.farmController = new FarmController(this);
        this.maceController = new MaceController(this);
        this.golemFightController = new GolemFightController(this);
        this.lavaStuntController = new LavaStuntController(this);
    }
}
