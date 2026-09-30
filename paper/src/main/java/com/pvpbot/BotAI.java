package com.pvpbot;

import com.pvpbot.ai.BotAIContext;
import com.pvpbot.ai.TacticsController;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

public class BotAI {
    private final BotAIContext context;

    public BotAI(PvPBot bot, BotSettings settings) {
        this.context = new BotAIContext(bot, settings);
        this.context.initializeControllers();
    }

    public void tick() {
        context.tickCounter++;
        context.aimLockedOnTarget = false;
        if (!context.bot.isAlive()) return;

        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (context.forceFullTicks > 0) context.forceFullTicks--;
        if (context.selfHurtTicks > 0) context.selfHurtTicks--;
        if (context.pathRecalcCooldown > 0) context.pathRecalcCooldown--;
        if (context.fleePathCooldown > 0) context.fleePathCooldown--;
        if (context.criticalRetryTicks > 0) context.criticalRetryTicks--;
        if (context.cobwebCooldown > 0) context.cobwebCooldown--;
        if (context.axeStunCooldown > 0) context.axeStunCooldown--;
        if (context.shieldStunnedTicks > 0) context.shieldStunnedTicks--;
        if (context.fleeBlockedTicks > 0) context.fleeBlockedTicks--;
        if (context.holeCheckCooldown > 0) context.holeCheckCooldown--;
        if (context.weaponSwitchCooldown > 0) context.weaponSwitchCooldown--;
        if (context.avoidTicks > 0) context.avoidTicks--;
        if (context.doorCooldown > 0) context.doorCooldown--;
        if (context.movementInputStutterCooldown > 0) context.movementInputStutterCooldown--;

        Player swingOwner = context.bot.getBukkitPlayer();
        if (swingOwner != null) {
            org.bukkit.Material heldNow = swingOwner.getInventory().getItemInMainHand().getType();
            if (heldNow != context.lastMainHandType) {
                context.lastMainHandType = heldNow;
                context.ticksSinceSwing = 0;
            }
        }
        context.ticksSinceSwing++;
        if (context.breachSwapCooldown > 0) context.breachSwapCooldown--;
        if (context.lungeSwapCooldown > 0) context.lungeSwapCooldown--;
        if (context.lungeRetryTicks > 0) context.lungeRetryTicks--;
        if (context.elytraApproachCooldown > 0) context.elytraApproachCooldown--;
        if (context.agroCartCooldown > 0) context.agroCartCooldown--;
        if (context.cartController != null) context.cartController.tickOwnCart();
        if (context.maceController != null) context.maceController.tick();
        if (context.targetShieldMemory > 0) context.targetShieldMemory--;
        if (context.agroCartWindow > 0) context.agroCartWindow--;

        net.minecraft.server.level.ServerPlayer impulseHandle = context.bot.getHandle();
        if (impulseHandle != null) impulseHandle.tryResetCurrentImpulseContext();

        Player handOwner = context.bot.getBukkitPlayer();
        if (handOwner != null && context.target != null
                && !context.eating && context.drinkingPotionTimer <= 0
                && context.maceWindupTicks <= 0 && context.maceStunSlamPhase == 0
                && context.splashPotionTimer <= 0) {
            org.bukkit.inventory.ItemStack inHand =
                    handOwner.getInventory().getItemInMainHand();
            if (!com.pvpbot.ai.CombatController.isCombatWeaponItem(inHand)
                    && !inHand.getType().isEdible()) {
                int weapon = context.inventoryController.findBestWeaponSlot(handOwner);
                if (weapon >= 0 && weapon <= 8
                        && handOwner.getInventory().getHeldItemSlot() != weapon) {
                    handOwner.getInventory().setHeldItemSlot(weapon);
                    context.packetBroadcaster.broadcastEquipment();
                }
            }
        }

        Player elytraOwner = context.bot.getBukkitPlayer();
        if (elytraOwner != null) context.inventoryController.tickElytraRestore(elytraOwner);
        if (context.maceWindupTicks > 0) context.maceWindupTicks--;
        if (context.maceStunSlamPhaseTicks > 0) context.maceStunSlamPhaseTicks--;
        if (context.maceWindupCooldown > 0) context.maceWindupCooldown--;
        if (context.maceStunSlamCooldown > 0) context.maceStunSlamCooldown--;
        if (context.maceHoldAfterAttack > 0) context.maceHoldAfterAttack--;
        if (context.critPhaseTicks > 0) context.critPhaseTicks--;
        if (context.critFallTicks > 0) context.critFallTicks--;
        if (context.strafeChangeTimer > 0) context.strafeChangeTimer--;
        if (context.aimDriftChangeTimer > 0) context.aimDriftChangeTimer--;
        if (context.targetElevatedTicks > 0) context.targetElevatedTicks--;
        if (context.bridgeOverhangTicks > 0) context.bridgeOverhangTicks--;
        if (context.opponentAttackingTicks > 0) context.opponentAttackingTicks--;
        if (context.fleeStrafeTimer > 0) context.fleeStrafeTimer--;
        if (context.fleeOpenYawTicks > 0) context.fleeOpenYawTicks--;

        if (context.smashThreatTicks > 0) context.smashThreatTicks--;

        if (!context.bridging && context.bridgeCheckCooldown > 0) context.bridgeCheckCooldown--;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || handle.isRemoved()) return;

        handle.noPhysics = false;

        context.movementController.ensureAttributes();

        Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer == null || botPlayer.isDead()) return;

        context.inventoryController.applyToolAttributes(botPlayer);

        if (context.settings.isFrozen()) {
            context.target = null;
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.bridging = false;
            context.clearTransientAI();
            handle.setSprinting(false);
            handle.setShiftKeyDown(false);
            if (handle.isUsingItem()) handle.stopUsingItem();
            finishTick(handle);
            return;
        }

        context.movementController.checkIfStuck(botPlayer.getLocation());

        net.minecraft.world.phys.Vec3 vel = handle.getDeltaMovement();
        double prevVelX = context.velX;
        double prevVelZ = context.velZ;
        context.velX = vel.x;
        context.velZ = vel.z;
        context.ownedDy = vel.y;

        double horizVel = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (context.prevTickInitialized && horizVel > 0.15 && context.knockbackReactionTicks <= 0) {
            double prevHorizVel = Math.sqrt(prevVelX * prevVelX + prevVelZ * prevVelZ);
            if (horizVel > prevHorizVel * 1.5 || horizVel > 0.25) {
                context.knockbackReactionTicks = 3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(4);
                if (vel.x != 0.0 || vel.z != 0.0) {
                    double len = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                    context.knockbackDirX = vel.x / len;
                    context.knockbackDirZ = vel.z / len;
                }
                context.shieldPredictTicks = 0;
                context.shieldHoldTicks = 0;
                context.shieldFlickerTicks = 0;
                context.inventoryController.releaseShield(botPlayer);
            }
        }

        float charge = handle.getAttackStrengthScale(0.0f);
        double chargeDelay = handle.getCurrentItemAttackStrengthDelay();
        context.attackCooldown = (int) Math.ceil(Math.max(0.0, (1.0f - charge) * chargeDelay));
        if (context.attackCooldown > 0) context.attackCooldown--;

        if (context.target == null && context.movementController.handleBridgingGate(botPlayer)) {
            finishTick(handle);
            return;
        }

        if (context.patrolController.handlePatrol(botPlayer)) {
            context.navBranch = "PATROL";
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        if ((context.tickCounter & 63) == 0) {
            context.enemyMemory.expire(context.tickCounter);

            context.navAvoid.expire(context.tickCounter);
        }

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.TARGETING);
        context.targetingController.updateDamageTracking(botPlayer);
        com.pvpbot.perf.BotProfiler.end();

        context.combatController.handleWaterScoop(botPlayer);

        if (context.blockToBreak != null && context.breakingCobwebTimer > 0) {
            if (--context.breakingCobwebTimer <= 0) {
                if (context.blockToBreak.getBlock().getType() == org.bukkit.Material.COBWEB) {
                    context.blockToBreak.getBlock().setType(org.bukkit.Material.AIR);
                }
                context.blockToBreak = null;
            }
        }

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.HEALING);
        context.healingController.handleHealing(botPlayer);
        com.pvpbot.perf.BotProfiler.end();

        if ((context.tickCounter & 3) == 0) {
            long fullHash = calculateFullInventoryHash(botPlayer);
            if (fullHash != context.lastFullInventoryHash) {
                context.lastFullInventoryHash = fullHash;
                context.inventoryChangeCooldown = 10;
                context.buffScanCooldown = 0;
                context.weaponSwitchCooldown = 0;
            }
        }

        if (context.guardMode == BotAIContext.GuardMode.LEADER && context.guardAnchor != null) {
            Player guarded = resolveLeader(botPlayer);
            if (guarded != null && guarded.isOnline() && !guarded.isDead()) {
                org.bukkit.Location a = context.guardAnchor;
                a.setWorld(guarded.getWorld());
                a.setX(guarded.getX());
                a.setY(guarded.getY());
                a.setZ(guarded.getZ());
            }
        }

        if (context.findTargetCooldown > 0) context.findTargetCooldown--;
        if (context.target != null && !isValidTarget(context.target)) {
            context.target = null;
            context.critPhase = BotAIContext.CritPhase.IDLE;
        }
        if (context.findTargetCooldown <= 0) {
            com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.TARGETING);
            context.targetingController.findTarget();
            com.pvpbot.perf.BotProfiler.end();
            int baseDelay = context.settings.getReactionTicks();
            int randomDelay = java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
            context.findTargetCooldown = (context.target == null)
                    ? baseDelay + randomDelay
                    : baseDelay + 3 + randomDelay;
        }

        if (context.jumpCooldown > 0) context.jumpCooldown--;

        if (context.pearlCooldown > 0) context.pearlCooldown--;
        if (context.regroupTicks > 0) context.regroupTicks--;

        if (context.formationSlot != null) {
            context.target = null;
            if (context.movementController.handleFormationMarch()) {
                finishTick(handle);
                return;
            }
        }

        if (context.inventoryController.isSwappingOffhand()) {
            context.inventoryController.manageOffhand(botPlayer);
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            handle.setSprinting(false);
            if (context.target != null) {
                context.movementController.lookAt(
                        context.target.getEyeLocation(), context.settings.getAimNoise());
            }
            finishTick(handle);
            return;
        }

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.HAZARD);
        boolean hazardOwns = context.hazardController.handleThreat(botPlayer);
        com.pvpbot.perf.BotProfiler.end();
        if (hazardOwns) {
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        boolean areaMiningOwns = context.areaMiningController.handleAreaMining(botPlayer);
        if (areaMiningOwns) {
            context.navBranch = "AREAMINE";
            finishTick(handle);
            return;
        }

        context.clutchController.tick(botPlayer, handle);

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.HOLE_ESCAPE);
        boolean holeOwns = context.movementController.handleHoleEscape(botPlayer);
        com.pvpbot.perf.BotProfiler.end();
        if (holeOwns) {
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        if (context.tunnelController.tick(botPlayer)) {
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }
        maybeStartTunnel(botPlayer);

        context.suppressSprint = false;

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.RESTOCK);
        boolean restockOwns = context.restockController.handleRestock(botPlayer);
        com.pvpbot.perf.BotProfiler.end();
        if (restockOwns) {
            context.navBranch = "RESTOCK";
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        boolean deliveryOwns = context.deliveryController.handleDelivery(botPlayer);
        if (deliveryOwns) {
            context.navBranch = "DELIVER";
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        boolean miningOwns = context.miningController.handleMining(botPlayer);
        if (miningOwns) {
            context.navBranch = "MINE";
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        boolean farmOwns = context.farmController.handleFarm(botPlayer);
        if (farmOwns) {
            context.navBranch = "FARM";
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        boolean golemFightOwns = context.golemFightController.handleGolemFight(botPlayer);
        if (golemFightOwns) {
            context.navBranch = "GOLEM";
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        boolean lavaStuntOwns = context.lavaStuntController.handleLavaStunt(botPlayer);
        if (lavaStuntOwns) {
            context.navBranch = "LAVA_STUNT";
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.BUILD);
        boolean buildOwns = context.buildController.handleBuild(botPlayer);
        com.pvpbot.perf.BotProfiler.end();
        if (buildOwns) {
            context.inventoryController.manageOffhand(botPlayer);
            finishTick(handle);
            return;
        }

        checkLeaderRetreat(botPlayer);

        if (context.healingController.tickSplashBuffSequence(botPlayer)) {
            finishTick(handle);
            return;
        }

        boolean defendWhileRegrouping = context.regroupTicks > 0
                && context.target != null
                && context.target.getWorld() == botPlayer.getWorld()
                && botPlayer.getLocation().distance(context.target.getLocation())
                <= context.settings.getReach() + 1.5;

        if (context.target != null && (context.regroupTicks <= 0 || defendWhileRegrouping)) {
            double distance = botPlayer.getLocation().distance(context.target.getLocation());
            handle.yRotO = handle.getYRot();
            handle.xRotO = handle.getXRot();

            if (context.fleeing) {
                executeSplashSequence(botPlayer);

                context.bridging = false;
                handle.setShiftKeyDown(false);

                if (context.combatController.handleCobwebDefense(botPlayer)) {
                    context.inventoryController.manageOffhand(botPlayer);
                    finishTick(handle);
                    return;
                }

                context.inventoryController.manageOffhand(botPlayer);

                context.healingController.handleBuffs(botPlayer);

                context.inventoryController.manageShieldBlock(botPlayer);
                context.healingController.handleFleeing();
                context.healingController.handleFleeingBlocks(botPlayer);
            } else if (!executeSplashSequence(botPlayer)) {
                {
                    boolean maceOwnsTick = context.maceStunSlamPhase > 0
                            || context.maceWindupTicks > 0;

                    context.inventoryController.manageOffhand(botPlayer);
                    context.healingController.handleBuffs(botPlayer);

                    if (context.healingController.maybeEatInCombat(botPlayer, distance)) {
                        context.movementController.lookAt(
                                context.target.getEyeLocation(), context.settings.getAimNoise());
                        context.forwardInput = -0.4f;
                        context.strafeInput = 0f;
                        finishTick(handle);
                        return;
                    }

                    if (!maceOwnsTick && context.cartController.tryAgroPearl(
                            botPlayer, context.target, distance)) {
                        finishTick(handle);
                        return;
                    }

                    if (!maceOwnsTick && context.cartController.handleCart(
                            botPlayer, context.target, distance)) {
                        context.movementController.lookAt(
                                context.target.getEyeLocation(), context.settings.getAimNoise());
                        finishTick(handle);
                        return;
                    }

                    if (context.combatController.handleCobwebDefense(botPlayer)) {
                        context.movementController.lookAt(
                                context.target.getEyeLocation(), context.settings.getAimNoise());
                        context.inventoryController.releaseShield(botPlayer);
                        if (distance <= context.settings.getReach() + 0.5) {
                            context.combatController.handleAttack(botPlayer, distance);
                        }
                        context.inventoryController.manageOffhand(botPlayer);
                        finishTick(handle);
                        return;
                    }

                    {
                        if (context.maceStunSlamPhase == 0
                                && context.combatController.driveElytraApproach(botPlayer)) {
                            finishTick(handle);
                            return;
                        }

                        if (!maceOwnsTick
                                && context.combatController.tryGapCloser(botPlayer, distance)) {
                            finishTick(handle);
                            return;
                        }

                        if (!maceOwnsTick
                                && !context.combatController.reservesGapForMace(botPlayer, distance)
                                && context.healingController.tryOffensivePearl(
                                        botPlayer, context.target)) {
                            finishTick(handle);
                            return;
                        }

                        TacticsController.Mode mode = context.tacticsController.decide(botPlayer, distance);

                        boolean pathOwnsAim = mode == TacticsController.Mode.PATH
                                && distance > 8.0
                                && !context.combatController.hasLineOfSight(botPlayer, context.target);
                        context.aimLockedOnTarget = !pathOwnsAim && !context.bridging;

                        if (context.aimLockedOnTarget) {
                            context.movementController.lookAt(
                                    context.target.getEyeLocation(), context.settings.getAimNoise());
                        }

                        context.inventoryController.manageShieldBlock(botPlayer);
                        context.combatController.handleCobwebOffense(botPlayer, distance);
                        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.MOVEMENT);
                        context.movementController.handleCombatMovement(distance, mode);
                        com.pvpbot.perf.BotProfiler.end();
                        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.COMBAT);
                        context.combatController.handleAttack(botPlayer, distance);
                        com.pvpbot.perf.BotProfiler.end();
                        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.INVENTORY);
                        context.inventoryController.handleInventorySwitching(botPlayer);
                        com.pvpbot.perf.BotProfiler.end();
                    }
                }
            }
        } else {
            context.bridging = false;
            handle.setShiftKeyDown(false);

            if (context.combatController.handleCobwebDefense(botPlayer)) {
                finishTick(handle);
                return;
            }

            if (context.fleeing) {
                context.healingController.handleFleeing();
            } else {
                context.healingController.handleBuffs(botPlayer);
                context.inventoryController.manageShieldBlock(botPlayer);

                if (context.eating || context.healingController.shouldEat(botPlayer)) {
                    context.healingController.startEating(botPlayer);
                }

                if (context.guardAnchor != null) {
                    context.followHolding = false;
                    context.movementController.handleGuardPost(botPlayer);
                } else {
                    Player leader = resolveLeader(botPlayer);
                    if (leader != null && handleFormationEscort(botPlayer, leader)) {
                    } else if (leader != null) {
                        context.movementController.handleFollowLeader(leader);
                    } else {
                        context.followHolding = false;

                        if (!context.movementController.handleInvestigate(botPlayer)) {
                            if (context.settings.isWanderWithoutFaction()) {
                                context.movementController.handleIdleWandering();
                            } else {
                                context.movementController.handleIdleHold();
                            }
                        }
                    }
                }
            }
        }

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.PHYSICS);
        context.movementController.applyPhysics(handle);
        context.movementController.syncState(handle);
        com.pvpbot.perf.BotProfiler.end();

        if (context.prevTickInitialized) {
            try {
                handle.doCheckFallDamage(
                        handle.getX() - context.prevTickX,
                        handle.getY() - context.prevTickY,
                        handle.getZ() - context.prevTickZ,
                        handle.onGround());
            } catch (Throwable ignored) {
            }
        }

        if (context.inventoryChangeCooldown > 0) {
            context.inventoryChangeCooldown--;
        }

        double tpDx = handle.getX() - context.prevTickX;
        double tpDy = handle.getY() - context.prevTickY;
        double tpDz = handle.getZ() - context.prevTickZ;
        double tpDistance = Math.sqrt(tpDx * tpDx + tpDy * tpDy + tpDz * tpDz);
        if (tpDistance > 2.0) {
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.fleePathCooldown = 0;
            context.pathRecalcCooldown = 0;
        }

        double horizSpeedSq = handle.getDeltaMovement().horizontalDistanceSqr();
        if (horizSpeedSq > 0.0004) {
            context.immobilizedTicks = 0;
        } else {
            context.immobilizedTicks++;
        }

        if (context.immobilizedTicks > 100 && context.target != null && !context.fleeing && !context.eating) {
            context.immobilizedTicks = 0;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.pathfindingController.calculatePathAsync(botPlayer.getLocation(), context.target.getLocation());
            context.forwardInput = 1.0f;
            if (context.settings.isStrafing()) context.strafeInput = (float) context.settings.getStrafeSpeed() * context.strafeDirection;
            context.movementController.requestJump();
        }

        context.packetBroadcaster.broadcastPosition();

        context.prevTickX = handle.getX();
        context.prevTickY = handle.getY();
        context.prevTickZ = handle.getZ();
    }

    public void tickLight() {
        if (context.maceStunSlamPhase > 0 || context.maceWindupTicks > 0) {
            Player lightBot = context.bot.getBukkitPlayer();
            if (lightBot != null) context.maceController.abortSequences(lightBot);
        }

        context.tickCounter++;
        if (!context.bot.isAlive()) return;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || handle.isRemoved()) return;

        if (!context.settings.isFrozen()) {
            context.clutchController.tick(context.bot.getBukkitPlayer(), handle);
        }

        if (context.forceFullTicks > 0) context.forceFullTicks--;
        if (context.selfHurtTicks > 0) context.selfHurtTicks--;
        if (context.pathRecalcCooldown > 0) context.pathRecalcCooldown--;
        if (context.fleePathCooldown > 0) context.fleePathCooldown--;
        if (context.cobwebCooldown > 0) context.cobwebCooldown--;
        if (context.axeStunCooldown > 0) context.axeStunCooldown--;
        if (context.shieldStunnedTicks > 0) context.shieldStunnedTicks--;
        if (context.fleeBlockedTicks > 0) context.fleeBlockedTicks--;
        if (context.holeCheckCooldown > 0) context.holeCheckCooldown--;
        if (context.weaponSwitchCooldown > 0) context.weaponSwitchCooldown--;
        if (context.avoidTicks > 0) context.avoidTicks--;
        if (context.doorCooldown > 0) context.doorCooldown--;
        if (context.smashThreatTicks > 0) context.smashThreatTicks--;
        if (context.jumpCooldown > 0) context.jumpCooldown--;
        if (context.pearlCooldown > 0) context.pearlCooldown--;
        if (context.regroupTicks > 0) context.regroupTicks--;
        if (context.findTargetCooldown > 0) context.findTargetCooldown--;
        if (context.buffScanCooldown > 0) context.buffScanCooldown--;
        if (context.inventoryChangeCooldown > 0) context.inventoryChangeCooldown--;
        if (!context.bridging && context.bridgeCheckCooldown > 0) context.bridgeCheckCooldown--;

        net.minecraft.world.phys.Vec3 vel = handle.getDeltaMovement();
        context.velX = vel.x;
        context.velZ = vel.z;
        context.ownedDy = vel.y;

        Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer != null && !botPlayer.isDead()) {
            context.movementController.checkIfStuck(botPlayer.getLocation());
        }

        if (!context.prevTickInitialized) {
            context.prevTickX = handle.getX();
            context.prevTickY = handle.getY();
            context.prevTickZ = handle.getZ();
            context.prevTickInitialized = true;
        }

        finishTick(handle);
    }

    private void finishTick(ServerPlayer handle) {
        context.movementController.applyPhysics(handle);
        context.movementController.syncState(handle);
        if (context.prevTickInitialized) {
            try {
                handle.doCheckFallDamage(
                        handle.getX() - context.prevTickX,
                        handle.getY() - context.prevTickY,
                        handle.getZ() - context.prevTickZ,
                        handle.onGround());
            } catch (Throwable ignored) {
            }
        } else {
            context.prevTickInitialized = true;
        }
        context.prevTickX = handle.getX();
        context.prevTickY = handle.getY();
        context.prevTickZ = handle.getZ();
    }

    private void checkLeaderRetreat(Player botPlayer) {
        Player leader = resolveLeader(botPlayer);

        if (context.regroupTicks > 0) {
            if (leader == null || leader.getWorld() != botPlayer.getWorld()) {
                context.regroupTicks = 0;
                return;
            }
            if (leader.getLocation().distance(botPlayer.getLocation()) <= REGROUP_ARRIVE_DISTANCE
                    && !isLeaderFleeing(leader)) {
                context.regroupTicks = 0;
            }
            return;
        }

        if (context.target == null) return;
        if (leader == null) return;

        boolean leaderFleeing = isLeaderFleeing(leader);

        boolean crossWorld = leader.getWorld() != botPlayer.getWorld();
        double botToLeader = crossWorld
                ? Double.MAX_VALUE
                : leader.getLocation().distance(botPlayer.getLocation());

        double targetToLeader = Double.MAX_VALUE;
        if (!crossWorld && context.target.getWorld() == leader.getWorld()) {
            targetToLeader = context.target.getLocation().distance(leader.getLocation());
        }

        boolean strayed = botToLeader > leaderLeash()
                && targetToLeader > leaderLeash();

        if (strayed && !leaderFleeing && context.target.getWorld() == botPlayer.getWorld()
                && botPlayer.getLocation().distance(context.target.getLocation())
                <= context.settings.getReach() + 1.0) {
            return;
        }

        if (leaderFleeing || strayed) {
            context.regroupTicks = 100;
            context.target = null;
            context.critPhase = BotAIContext.CritPhase.IDLE;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
        }
    }

    private boolean isLeaderFleeing(Player leader) {
        PvPBot leaderBot = PvPBotPlugin.getInstance().getBotManager()
                .getBots().get(leader.getUniqueId());
        return leaderBot != null && leaderBot.isAlive()
                && leaderBot.getAI().getContext().fleeing;
    }

    private boolean handleFormationEscort(Player botPlayer, Player leader) {
        BotManager manager = PvPBotPlugin.getInstance().getBotManager();
        String faction = manager.getPlayerFaction(context.bot.getUUID());
        BotManager.FormationOrder order = manager.getFactionFormation(faction);
        if (order == null) return false;
        if (leader.getWorld() != botPlayer.getWorld()) return false;

        List<PvPBot> members = manager.getFactionBotsOrdered(faction);
        members.removeIf(b -> b.getUUID().equals(leader.getUniqueId()));
        int index = -1;
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).getUUID().equals(context.bot.getUUID())) { index = i; break; }
        }
        if (index < 0) return false;

        Location anchor = leader.getLocation().clone();
        anchor.setYaw(anchor.getYaw() + 180.0f);

        List<Location> slots = FormationManager.compute(
                anchor, order.shape(), members.size(), order.spacing());
        if (index >= slots.size()) return false;

        context.movementController.handleEscortSlot(slots.get(index), leader);
        return true;
    }

    private static final int TUNNEL_WALL_BUMP_TICKS = 20;
    private static final int TUNNEL_PATH_FAILURES = 2;
    private static final int TUNNEL_BLOCKED_TICKS = 15;

    private void maybeStartTunnel(Player botPlayer) {
        if (context.tunnelController.isActive()) return;

        boolean grindingWall = context.wallBumpTicks >= TUNNEL_WALL_BUMP_TICKS;

        if (!grindingWall && context.target == null && context.currentPath.isEmpty()
                && context.pathFailures < TUNNEL_PATH_FAILURES) {
            return;
        }

        boolean plannerGaveUp = context.pathFailures >= TUNNEL_PATH_FAILURES;
        boolean pathStuck = !context.currentPath.isEmpty()
                && context.pathNodeIndex < context.currentPath.size()
                && context.chaseBlockedTicks >= TUNNEL_BLOCKED_TICKS;

        if (!plannerGaveUp && !grindingWall && !pathStuck) return;

        org.bukkit.Location dest = null;
        if (!context.currentPath.isEmpty()) {
            dest = context.currentPath.get(context.currentPath.size() - 1);
        } else if (context.target != null) {
            dest = context.target.getLocation();
        } else if (context.holeEscapeTarget != null) {
            dest = context.holeEscapeTarget;
        }
        if (dest == null) return;

        if (context.tunnelController.begin(botPlayer, dest)) {
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.pathFailures = 0;
            context.wallBumpTicks = 0;
            context.chaseBlockedTicks = 0;
        }
    }

    private double leaderLeash() {
        return context.settings.getLeaderLeash();
    }

    private static final double REGROUP_ARRIVE_DISTANCE = 8.0;

    private Player resolveLeader(Player botPlayer) {
        java.util.UUID leaderUUID =
                PvPBotPlugin.getInstance().getBotManager().getLeaderFor(context.bot.getUUID());
        if (leaderUUID == null || leaderUUID.equals(context.bot.getUUID())) return null;

        Player leader = Bukkit.getPlayer(leaderUUID);
        if (leader == null || !leader.isOnline() || leader.isDead()) return null;
        if (leader.getUniqueId().equals(botPlayer.getUniqueId())) return null;
        return leader;
    }

    private boolean isValidTarget(Player p) {
        return com.pvpbot.ai.TargetFilter.isEngageable(p, context.bot.getBukkitPlayer());
    }

    private boolean hasArmor(Player player) {
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() != Material.AIR) {
                return true;
            }
        }
        return false;
    }

    private boolean executeSplashSequence(Player botPlayer) {
        if (context.splashPotionTimer <= 0) return false;
        context.splashPotionTimer--;

        ServerPlayer handle = context.bot.getHandle();

        int slot = context.splashThrowSlot;
        if (slot < 0 || slot > 8) {
            abortSplashSequence(botPlayer);
            return false;
        }

        if (context.splashPotionTimer > 2) {
            context.movementController.easePitchTo(90.0f);
        }

        if (context.splashPotionTimer == 4) {
            if (!context.potionOffhandPreserved) {
                context.potionOffhandBackup = botPlayer.getInventory().getItemInOffHand().clone();
                context.potionOffhandPreserved = true;
            }

            handle.setXRot(90.0f);
            botPlayer.getInventory().setHeldItemSlot(slot);
            context.packetBroadcaster.broadcastEquipment();
        } else if (context.splashPotionTimer == 2) {
            handle.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);

            org.bukkit.inventory.ItemStack potItem = botPlayer.getInventory().getItem(slot);
            if (potItem != null && potItem.getType() == org.bukkit.Material.SPLASH_POTION) {
                org.bukkit.entity.SplashPotion thrown =
                        botPlayer.launchProjectile(org.bukkit.entity.SplashPotion.class);
                thrown.setItem(potItem);
                thrown.setVelocity(botPlayer.getLocation().getDirection().multiply(1.5));

                thrown.setGravity(true);

                potItem.setAmount(potItem.getAmount() - 1);
                botPlayer.getInventory().setItem(
                        slot, potItem.getAmount() > 0 ? potItem : null);
            }
        } else if (context.splashPotionTimer == 0) {
            // isInvisible() catches both a real potion effect and the
            // plain metadata flag (cinematic-circle bots use the latter,
            // which hasPotionEffect(INVISIBILITY) never saw - they kept
            // auto-equipping a weapon into their empty hand every tick).
            if (botPlayer.isInvisible() && !hasArmor(botPlayer)) {
                int emptySlot = -1;
                for (int i = 0; i < 9; i++) {
                    if (botPlayer.getInventory().getItem(i) == null || botPlayer.getInventory().getItem(i).getType() == Material.AIR) {
                        emptySlot = i;
                        break;
                    }
                }
                if (emptySlot < 0) {
                    botPlayer.getInventory().setItem(0, null);
                    emptySlot = 0;
                }
                botPlayer.getInventory().setHeldItemSlot(emptySlot);
            } else {
                int weapon = context.inventoryController.findBestWeaponSlot(botPlayer);
                if (weapon >= 0 && weapon <= 8) {
                    botPlayer.getInventory().setHeldItemSlot(weapon);
                }
            }
            if (context.potionOffhandPreserved) {
                botPlayer.getInventory().setItemInOffHand(context.potionOffhandBackup);
                context.potionOffhandPreserved = false;
            }
            context.packetBroadcaster.broadcastEquipment();
            context.splashThrowSlot = -1;
        }

        return true;
    }

    private void abortSplashSequence(Player botPlayer) {
        context.splashPotionTimer = 0;
        context.splashThrowSlot = -1;
        if (context.potionOffhandPreserved) {
            botPlayer.getInventory().setItemInOffHand(context.potionOffhandBackup);
            context.potionOffhandPreserved = false;
            context.packetBroadcaster.broadcastEquipment();
        }
    }

    private long calculateFullInventoryHash(Player p) {
        long h = 1469598103934665603L;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();

        for (int i = 0; i < 36; i++) {
            org.bukkit.inventory.ItemStack item = inv.getItem(i);
            if (item != null) {
                int v = item.getType().ordinal() + (item.getAmount() << 16);
                h ^= v;
                h *= 1099511628211L;
            }
        }

        for (org.bukkit.inventory.ItemStack item : inv.getArmorContents()) {
            if (item != null) {
                h ^= item.getType().ordinal();
                h *= 1099511628211L;
            }
        }

        org.bukkit.inventory.ItemStack offhand = inv.getItemInOffHand();
        if (offhand != null) {
            h ^= offhand.getType().ordinal();
            h *= 1099511628211L;
        }

        return h;
    }

    public BotAIContext getContext() {
        return context;
    }

    public Player getTarget() {
        return context.target;
    }

    public void setTarget(Player target) {
        if (target != context.target) {
            context.critPhase = BotAIContext.CritPhase.IDLE;
        }
        context.target = target;
    }

    public void notifyDamage(Player attacker) {
        context.targetingController.notifyDamage(attacker);

        context.techniqueController.onDamaged(attacker);
    }

    public void notifyLeaderAttacked(Player attacker) {
        context.targetingController.notifyLeaderAttacked(attacker);
    }

    public void notifyAllyAttacked(Player attacker) {
        context.targetingController.notifyAllyAttacked(attacker);
    }

    public void notifyTotemPop() {
        context.forceFullTicks = 120;
        context.totemRecoveryTicks = 100;
        context.healCommitTicks = Math.max(context.healCommitTicks, 100);
        context.fleeing = true;
        context.critPhase = BotAIContext.CritPhase.IDLE;
        context.comboCount = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        context.lastFleeGap = -1.0;
        context.fleePanicTicks = 0;

        context.inventoryPauseTimer = 0;

        if (context.splashPotionTimer > 0) {
            Player botPlayer = context.bot.getBukkitPlayer();
            if (botPlayer != null) abortSplashSequence(botPlayer);
        }
    }
}
