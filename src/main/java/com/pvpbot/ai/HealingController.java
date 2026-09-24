package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

public class HealingController {
    private static final double GOLDEN_APPLE_HEAL_THRESHOLD = 12.0;

    private static final int CORNERED_LOCKOUT_TICKS = 120;
    private static final int CORNERED_LOCKOUT_GRACE_TICKS = 20;

    private static final int EAT_COMMIT_TICKS = 40;

    private static final int EAT_CHAIN_COOLDOWN = 60;

    private static final int EAT_MAX_TICKS = 50;

    private final BotAIContext context;

    public HealingController(BotAIContext context) {
        this.context = context;
    }

    private boolean needsEmergencyHeal(Player botPlayer) {
        return botPlayer.getHealth() <= GOLDEN_APPLE_HEAL_THRESHOLD;
    }

    private boolean hasEmergencyGoldenApple(Player botPlayer) {
        if (!needsEmergencyHeal(botPlayer)) return false;
        if (goldenAppleStillWorking(botPlayer)) return false;
        if (context.eatChainCooldown > 0) return false;
        return context.inventoryController.findGoldenAppleSlot(botPlayer) >= 0;
    }

    private boolean goldenAppleStillWorking(Player p) {
        try {
            if (p.hasPotionEffect(PotionEffectType.REGENERATION)) return true;
            return p.getAbsorptionAmount() > 0.0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean wantsPreChaseTopUp(Player botPlayer, double gap) {
        if (!context.settings.isPreChaseHeal()) return false;
        if (gap < context.settings.getPreChaseHealDistance()) return false;
        if (botPlayer.getHealth() > context.settings.getPreChaseHealHealth()) return false;

        if (context.eatChainCooldown > 0) return false;
        if (goldenAppleStillWorking(botPlayer)) return false;

        if (botPlayer.hasPotionEffect(PotionEffectType.REGENERATION)) return false;

        if (context.inventoryController.findGoldenAppleSlot(botPlayer) >= 0) return true;

        return botPlayer.getFoodLevel() < 20
                && context.inventoryController.findFoodSlot(botPlayer) >= 0;
    }

    private void syncItemUseState(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || !handle.isUsingItem()) {
            if (context.eating) {
                context.eating = false;
                context.eatCommitTicks = 0;
                context.eatingTicks = 0;
                Player done = context.bot.getBukkitPlayer();
                if (done != null) {
                    int weapon = context.inventoryController.findBestWeaponSlot(done);
                    if (weapon >= 0 && weapon <= 8
                            && done.getInventory().getHeldItemSlot() != weapon) {
                        done.getInventory().setHeldItemSlot(weapon);
                        context.packetBroadcaster.broadcastEquipment();
                    }
                }
            }
            if (context.drinkingPotionTimer > 0) {
                context.drinkingPotionTimer = 0;
                context.drinkingPotionSlot = -1;
            }
            return;
        }

        ItemStack held = botPlayer.getInventory().getItemInMainHand();
        Material type = (held == null) ? Material.AIR : held.getType();

        boolean isPotion = type == Material.POTION;
        context.eating = !isPotion && type.isEdible();
        context.drinkingPotionTimer = isPotion ? handle.getUseItemRemainingTicks() : 0;
    }

    public void handleHealing(Player botPlayer) {
        syncItemUseState(botPlayer);

        if (context.healCommitTicks > 0) context.healCommitTicks--;
        if (context.totemRecoveryTicks > 0) context.totemRecoveryTicks--;
        if (context.eatCommitTicks > 0) context.eatCommitTicks--;
        if (context.eatChainCooldown > 0) context.eatChainCooldown--;

        if (context.eating) {
            if (++context.eatingTicks > EAT_MAX_TICKS) {
                Player stuck = context.bot.getBukkitPlayer();
                if (stuck != null) stopEating(stuck);
                context.eatingTicks = 0;
                context.eatChainCooldown = EAT_CHAIN_COOLDOWN;
            }
        } else {
            context.eatingTicks = 0;
        }

        double hp = botPlayer.getHealth();

        if (context.totemRecoveryTicks > 0 && !context.fleeing) {
            context.fleeing = true;
            context.healCommitTicks = Math.max(context.healCommitTicks, context.totemRecoveryTicks);
            context.lastFleeGap = -1.0;
            context.fleePanicTicks = 0;
            context.critPhase = BotAIContext.CritPhase.IDLE;
            context.attackCooldown = Math.max(context.attackCooldown, 20);
            context.comboCount = 0;
        }

        double fleeThreshold = context.settings.getFleeHealthThreshold();
        boolean criticallyLow = hp <= fleeThreshold * 0.5;
        boolean lockoutGraceOver =
                context.fleeBlockedTicks <= CORNERED_LOCKOUT_TICKS - CORNERED_LOCKOUT_GRACE_TICKS;

        if (!context.fleeing
                && (context.fleeBlockedTicks <= 0 || (criticallyLow && lockoutGraceOver))
                && hp <= fleeThreshold) {
            context.fleeBlockedTicks = 0;
            context.fleeing = true;

            context.healCommitTicks = 60;

            context.lastFleeGap = -1.0;
            context.fleePanicTicks = 0;
            context.critPhase = BotAIContext.CritPhase.IDLE;

            context.attackCooldown = Math.max(context.attackCooldown, 20);
            context.comboCount = 0;
            if (context.eatCommitTicks <= 0) {
                context.eating = false;
                context.drinkingPotionTimer = 0;
                context.bot.getHandle().stopUsingItem();
            }

            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.fleeOpenYawTicks = 0;
            context.fleePathCooldown = 0;

            if (isValidTarget(context.target)) {
                Location b = botPlayer.getLocation();
                Location t = context.target.getLocation();
                double dx = b.getX() - t.getX();
                double dz = b.getZ() - t.getZ();
                double len = Math.sqrt(dx*dx + dz*dz);
                if (len < 0.0001) { dx = 0; dz = 1; len = 1; }
                dx /= len; dz /= len;
                Location escape = b.clone().add(dx * 8.0, 0, dz * 8.0);
                context.pathfindingController.calculatePathAsync(b, escape);
            }
            if (context.shieldStunnedTicks <= 0) {
                context.forwardInput = 1.0f;
                context.strafeInput = 0.35f * context.fleeStrafeDir;
            }
        }

        if (!context.fleeing && (context.eating || context.drinkingPotionTimer > 0) && isValidTarget(context.target)) {
            context.fleeing = true;
            context.healCommitTicks = Math.max(context.healCommitTicks, 40);
            context.lastFleeGap = -1.0;
            context.fleePanicTicks = 0;
            context.critPhase = BotAIContext.CritPhase.IDLE;
            context.attackCooldown = Math.max(context.attackCooldown, 20);
            context.comboCount = 0;

            Location b = botPlayer.getLocation();
            Location t = context.target.getLocation();
            double dx = b.getX() - t.getX();
            double dz = b.getZ() - t.getZ();
            double len = Math.sqrt(dx*dx + dz*dz);
            if (len < 0.0001) { dx = 0; dz = 1; len = 1; }
            dx /= len; dz /= len;
            Location escape = b.clone().add(dx * 8.0, 0, dz * 8.0);
            context.pathfindingController.calculatePathAsync(b, escape);
            if (context.shieldStunnedTicks <= 0) {
                context.forwardInput = 1.0f;
                context.strafeInput = 0.35f * context.fleeStrafeDir;
            }
        }

        boolean consumingItem = context.bot.getHandle().isUsingItem()
                && botPlayer.getInventory().getItemInMainHand().getType() != Material.SHIELD
                && botPlayer.getInventory().getItemInOffHand().getType() != Material.SHIELD;
        boolean stillConsuming = context.eating || context.drinkingPotionTimer > 0
                || consumingItem;

        boolean underfed = botPlayer.getFoodLevel() < 18
                && context.inventoryController.findFoodSlot(botPlayer) >= 0;
        if (context.fleeing
                && hp >= context.settings.getReturnHealthThreshold()
                && context.healCommitTicks <= 0
                && context.totemRecoveryTicks <= 0
                && !underfed
                && !stillConsuming) {
            context.fleeing = false;
            context.pearlsThisFlee = 0;
            stopEating(botPlayer);
            cancelDrinking(botPlayer);

            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.fleePathCooldown = 0;
            context.fleeGapAssistCooldown = 0;
        }
    }

    public boolean maybeEatInCombat(Player botPlayer, double gap) {
        boolean emergencyHeal = hasEmergencyGoldenApple(botPlayer);
        boolean topUp = wantsPreChaseTopUp(botPlayer, gap);

        if (context.eating) {
            double stopGap = emergencyHeal
                    ? context.settings.getReach() + 0.5
                    : context.settings.getReach() + 1.5;
            if (gap < stopGap && context.eatCommitTicks <= 0) {
                stopEating(botPlayer);
                return false;
            }
            startEating(botPlayer);
            return true;
        }
        if (context.drinkingPotionTimer > 0) return false;

        if (!emergencyHeal && !topUp) {
            if (!context.settings.isAutoEat()) return false;
            if (botPlayer.getFoodLevel() > 6) return false;
            if (context.inventoryController.findFoodSlot(botPlayer) < 0) return false;
        }

        double eatGap = emergencyHeal
                ? context.settings.getReach() + 1.2
                : context.settings.getReach() + 4.0;
        if (!topUp && gap < eatGap) return false;

        startEating(botPlayer, topUp || needsEmergencyHeal(botPlayer));
        return context.eating;
    }

    public void startEating(Player botPlayer) {
        startEating(botPlayer, needsEmergencyHeal(botPlayer));
    }

    public void startEating(Player botPlayer, boolean preferGoldenApple) {
        if (context.eating) {
            if (context.tickCounter % 4 == 0) {
                context.packetBroadcaster.broadcastEntityData();
            }
            return;
        }

        int food = preferGoldenApple
                ? context.inventoryController.findGoldenAppleSlot(botPlayer)
                : -1;
        if (food < 0 || food > 8) food = context.inventoryController.findFoodSlot(botPlayer);
        if (food < 0 || food > 8) return;

        botPlayer.getInventory().setHeldItemSlot(food);
        context.bot.getHandle().startUsingItem(InteractionHand.MAIN_HAND);
        context.eating = true;
        context.eatChainCooldown = EAT_CHAIN_COOLDOWN;
        context.eatCommitTicks = EAT_COMMIT_TICKS;
        context.packetBroadcaster.broadcastEquipment();
        context.packetBroadcaster.broadcastEntityData();
    }

    public void stopEating(Player botPlayer) {
        context.eating = false;
        context.eatCommitTicks = 0;
        context.bot.getHandle().stopUsingItem();
        botPlayer.getInventory().setHeldItemSlot(context.inventoryController.findBestWeaponSlot(botPlayer));
        context.packetBroadcaster.broadcastEquipment();
        context.packetBroadcaster.broadcastEntityData();
    }

    public void handleFleeing() {
        ServerPlayer h = context.bot.getHandle();
        Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer == null || h == null) return;

        if (isBotInLava(botPlayer)) {
            context.forwardInput = 1.0f;
            context.strafeInput = 0f;
            context.movementController.requestJump();
            return;
        }

        if (context.healPotionCooldown > 0) context.healPotionCooldown--;
        if (context.fleeGapAssistCooldown > 0) context.fleeGapAssistCooldown--;
        if (context.windLaunchCooldown > 0) context.windLaunchCooldown--;

        tickDrinking(botPlayer);

        Player threat = isValidTarget(context.target) ? context.target
                : (context.lastDamager != null && isValidTarget(context.lastDamager)
                        ? context.lastDamager : null);

        if (threat == null) {
            context.forwardInput = 0.0f;
            context.strafeInput = 0.0f;
            if (context.drinkingPotionTimer <= 0 && !tryStartDrinking(botPlayer)
                    && shouldEat(botPlayer)) {
                startEating(botPlayer);
            }
            return;
        }

        Location b = context.bot.getLocation();
        Location tLoc = threat.getLocation();
        double dx = tLoc.getX() - b.getX();
        double dz = tLoc.getZ() - b.getZ();
        double gap = Math.sqrt(dx * dx + dz * dz);

        boolean closing = context.lastFleeGap >= 0 && gap < context.lastFleeGap - 0.02;
        context.lastFleeGap = gap;
        context.fleePanicTicks = closing
                ? Math.min(context.fleePanicTicks + 1, 60)
                : Math.max(context.fleePanicTicks - 1, 0);

        float awayYaw = 0f;
        if (gap > 0.0001) {
            float towardYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            awayYaw = (towardYaw + 180f) % 360f;
        }

        boolean cornered = gap <= context.settings.getReach() + 0.5 && context.fleePanicTicks > 8;
        if (cornered && context.eating && context.eatCommitTicks <= 0) stopEating(botPlayer);

        if (cornered) {
            if (++context.corneredTicks > 40) {
                context.corneredTicks = 0;
                context.fleeing = false;
                context.fleeBlockedTicks = CORNERED_LOCKOUT_TICKS;
                context.healCommitTicks = 0;
                if (context.eatCommitTicks <= 0) stopEating(botPlayer);
                cancelDrinking(botPlayer);
                context.currentPath.clear();
                context.pathNodeIndex = 0;
                context.fleePanicTicks = 0;
                return;
            }
        } else {
            context.corneredTicks = 0;
        }

        if ((cornered || context.fleePanicTicks > 4)
                && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.25
                && context.combatController.tryLungeAway(botPlayer, awayYaw)) {
            return;
        }

        if (tryEscapePearl(botPlayer, awayYaw, gap, cornered)) return;
        if (tryWindLaunch(botPlayer, cornered)) return;

        boolean haveRoute = !context.currentPath.isEmpty()
                && context.pathNodeIndex < context.currentPath.size();

        if (!haveRoute && context.fleePathCooldown <= 0 && context.pathRecalcCooldown <= 0) {
            float openYaw = pickOpenFleeYaw(b, awayYaw);
            if (Float.isNaN(openYaw)) {
                Location escape = fleePointAt(b, awayYaw, 14.0);
                context.pathfindingController.calculatePathAsync(b, escape);
                context.fleePathCooldown = 18 + java.util.concurrent.ThreadLocalRandom.current().nextInt(6);
            } else {
                context.fleeOpenYaw = openYaw;
                context.fleeOpenYawTicks = 20;
            }
        }

        if (context.fleeOpenYawTicks > 0) context.fleeOpenYawTicks--;

        float movementYaw;
        if (haveRoute) {
            context.movementController.followPath();
            movementYaw = h.getYRot();
        } else {
            if (context.fleeStrafeTimer <= 0) {
                context.fleeStrafeDir *= -1;
                context.fleeStrafeTimer = 6 + java.util.concurrent.ThreadLocalRandom.current().nextInt(8);
            }
            float zigzag = 35f * context.fleeStrafeDir;

            float baseYaw = context.fleeOpenYawTicks > 0 ? context.fleeOpenYaw : awayYaw;
            float headingYaw = (baseYaw + zigzag) % 360f;
            movementYaw = headingYaw;

            context.movementController.easeYawTo(headingYaw, BotAIContext.LOOK_UTILITY);

            double awayRad = Math.toRadians(headingYaw);
            double wx = -Math.sin(awayRad);
            double wz = Math.cos(awayRad);
            context.movementController.worldDirToInputs(h, wx, wz);
        }

        boolean gapAssisted = context.movementController.tryFleeGapAssist(
                botPlayer, movementYaw);

        if (!gapAssisted && h.onGround() && context.jumpCooldown <= 0) {
            Location botLoc = botPlayer.getLocation();
            double checkX = botLoc.getX() + Math.sin(Math.toRadians(-h.getYRot())) * 1.0;
            double checkZ = botLoc.getZ() + Math.cos(Math.toRadians(-h.getYRot())) * 1.0;
            org.bukkit.World w = botLoc.getWorld();
            int baseY = (int) Math.floor(botLoc.getY());

            Block blockAhead = w.getBlockAt((int) Math.floor(checkX), baseY, (int) Math.floor(checkZ));
            Block blockAboveHead = w.getBlockAt((int) Math.floor(checkX), baseY + 2, (int) Math.floor(checkZ));

            if (blockAhead.getType().isSolid() && !blockAboveHead.getType().isSolid()) {
                context.movementController.requestJump();
            } else if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.15) {
                context.movementController.requestJump();
            }
        }

        if (context.eating) {
            startEating(botPlayer);
        } else if (context.drinkingPotionTimer <= 0) {
            boolean startedPotion = tryStartDrinking(botPlayer);

            if (!startedPotion && shouldEat(botPlayer)) {
                double eatGap = hasEmergencyGoldenApple(botPlayer)
                        ? context.settings.getReach() + 1.2
                        : context.settings.getReach() + 2.0;

                boolean airborneSafe = !h.onGround() && (b.getY() - tLoc.getY()) > 2.5;

                if (gap > eatGap || airborneSafe) {
                    startEating(botPlayer);
                }
            }
        }
    }

    private boolean tryEscapePearl(Player botPlayer, float awayYaw, double gap, boolean cornered) {
        if (context.maceController.isAttempting()) return false;

        if (!context.settings.isPearling()) return false;
        if (context.pearlCooldown > 0) return false;
        if (context.eating || context.drinkingPotionTimer > 0) return false;

        if (!cornered && context.fleePanicTicks < 6) return false;
        if (gap > 12.0) return false;
        if (!cornered && gap < 4.5) return false;

        if (context.pearlsThisFlee >= 2) return false;

        if (!throwEscapePearl(botPlayer, awayYaw)) return false;

        context.pearlsThisFlee++;

        context.pearlCooldown = 100 + context.pearlsThisFlee * 60;
        return true;
    }

    private static final int WIND_LAUNCH_COOLDOWN = 160;

    private boolean tryWindLaunch(Player botPlayer, boolean cornered) {
        if (context.windLaunchCooldown > 0) return false;
        if (context.eating || context.drinkingPotionTimer > 0) return false;
        if (!cornered) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || !handle.onGround()) return false;

        int slot = context.inventoryController.findWindChargeSlot(botPlayer);
        if (slot < 0 || slot > 8) return false;

        int previousSlot = botPlayer.getInventory().getHeldItemSlot();
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        context.requestLook(handle.getYRot(), 90.0f, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        try {
            botPlayer.launchProjectile(org.bukkit.entity.WindCharge.class);
        } catch (Throwable t) {
            botPlayer.getInventory().setHeldItemSlot(previousSlot);
            context.packetBroadcaster.broadcastEquipment();
            return false;
        }

        ItemStack stack = botPlayer.getInventory().getItem(slot);
        if (stack != null) {
            stack.setAmount(stack.getAmount() - 1);
            botPlayer.getInventory().setItem(slot, stack.getAmount() > 0 ? stack : null);
        }
        botPlayer.getInventory().setHeldItemSlot(
                stack != null && stack.getAmount() > 0 ? previousSlot
                        : context.inventoryController.findBestWeaponSlot(botPlayer));
        context.packetBroadcaster.broadcastEquipment();

        context.windLaunchCooldown = WIND_LAUNCH_COOLDOWN;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        return true;
    }

    public boolean tryOffensivePearl(Player botPlayer, Player target) {
        if (!context.settings.isPearling()) return false;
        if (context.maceController.isAttempting()) return false;

        if (context.pearlCooldown > 0) return false;
        if (context.eating || context.drinkingPotionTimer > 0) return false;
        if (target == null || target.getWorld() != botPlayer.getWorld()) return false;

        double distance = target.getLocation().distance(botPlayer.getLocation());

        if (distance < 18.0 || distance > 70.0) return false;
        if (!context.combatController.hasLineOfSight(botPlayer, target)) return false;

        int slot = context.inventoryController.findItemSlot(botPlayer, Material.ENDER_PEARL);
        if (slot < 0 || slot > 8) return false;

        ServerPlayer handle = context.bot.getHandle();
        Location from = botPlayer.getEyeLocation();
        Location to = target.getLocation();

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        float pitch = (float) -(horizontal * 0.32 + (to.getY() - from.getY()) * -1.2);
        pitch = (float) Math.max(-55.0, Math.min(5.0, pitch));

        int previousSlot = botPlayer.getInventory().getHeldItemSlot();

        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();
        context.packetBroadcaster.broadcastRotation(handle);

        handle.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        org.bukkit.entity.EnderPearl pearl =
                botPlayer.launchProjectile(org.bukkit.entity.EnderPearl.class);
        pearl.setVelocity(botPlayer.getLocation().getDirection().multiply(1.5));

        ItemStack stack = botPlayer.getInventory().getItem(slot);
        if (stack != null) {
            stack.setAmount(stack.getAmount() - 1);
            botPlayer.getInventory().setItem(slot, stack.getAmount() > 0 ? stack : null);
        }
        botPlayer.getInventory().setHeldItemSlot(
                stack != null && stack.getAmount() > 0 ? previousSlot
                        : context.inventoryController.findBestWeaponSlot(botPlayer));
        context.packetBroadcaster.broadcastEquipment();

        context.pearlCooldown = 80;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        return true;
    }

    public boolean throwEscapePearl(Player botPlayer, float awayYaw) {
        if (context.pearlCooldown > 0) return false;
        if (context.eating || context.drinkingPotionTimer > 0) return false;

        int slot = context.inventoryController.findItemSlot(botPlayer, Material.ENDER_PEARL);
        if (slot < 0 || slot > 8) return false;

        ServerPlayer handle = context.bot.getHandle();
        int previousSlot = botPlayer.getInventory().getHeldItemSlot();

        context.requestLook(awayYaw, -32.0f, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();
        context.packetBroadcaster.broadcastRotation(handle);

        handle.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        org.bukkit.entity.EnderPearl pearl =
                botPlayer.launchProjectile(org.bukkit.entity.EnderPearl.class);
        pearl.setVelocity(botPlayer.getLocation().getDirection().multiply(1.5));

        ItemStack stack = botPlayer.getInventory().getItem(slot);
        if (stack != null) {
            stack.setAmount(stack.getAmount() - 1);
            botPlayer.getInventory().setItem(slot, stack.getAmount() > 0 ? stack : null);
        }

        botPlayer.getInventory().setHeldItemSlot(
                stack != null && stack.getAmount() > 0 ? previousSlot
                        : context.inventoryController.findBestWeaponSlot(botPlayer));
        context.packetBroadcaster.broadcastEquipment();

        context.pearlCooldown = 100;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        return true;
    }

    public boolean shouldEat(Player botPlayer) {
        if (needsEmergencyHeal(botPlayer)
                && context.inventoryController.findGoldenAppleSlot(botPlayer) >= 0) {
            return true;
        }
        if (!context.settings.isAutoEat()) return false;

        var food = botPlayer.getFoodLevel();
        float saturation = botPlayer.getSaturation();
        boolean hurt = botPlayer.getHealth() < botPlayer.getMaxHealth() - 0.5;

        if (food >= 20 && saturation >= 5.0f) return false;

        if (food < 18) return true;

        if (saturation < 3.0f) return true;

        return hurt;
    }

    public boolean tickDrinking(Player botPlayer) {
        if (context.drinkingPotionTimer <= 0) return false;

        if (context.tickCounter % 4 == 0) {
            context.packetBroadcaster.broadcastEntityData();
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle != null && !handle.isUsingItem()) {
            context.drinkingPotionTimer = 0;
            context.drinkingPotionSlot = -1;
            botPlayer.getInventory().setHeldItemSlot(
                    context.inventoryController.findBestWeaponSlot(botPlayer));
            context.packetBroadcaster.broadcastEquipment();
            context.packetBroadcaster.broadcastEntityData();
            context.healPotionCooldown = 40;
            return true;
        }
        return false;
    }

    public boolean tryStartDrinking(Player botPlayer) {
        if (context.healPotionCooldown > 0) return false;

        int regenSlot = context.inventoryController
                .findDrinkableSlotByEffect(botPlayer, PotionEffectType.REGENERATION);
        int slot = regenSlot;
        if (slot == -1) slot = context.inventoryController
                .findDrinkableSlotByEffect(botPlayer, PotionEffectType.INSTANT_HEALTH);
        if (slot < 0 || slot > 8) return false;

        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        context.drinkingPotionSlot = slot;
        context.drinkingIsRegen = (regenSlot != -1);

        context.bot.getHandle().startUsingItem(InteractionHand.MAIN_HAND);

        context.drinkingPotionTimer =
                Math.max(1, context.bot.getHandle().getUseItemRemainingTicks());
        context.packetBroadcaster.broadcastEntityData();
        return true;
    }

    public void cancelDrinking(Player botPlayer) {
        if (context.drinkingPotionTimer <= 0) return;
        context.drinkingPotionTimer = 0;
        context.drinkingPotionSlot = -1;
        context.bot.getHandle().stopUsingItem();
        botPlayer.getInventory().setHeldItemSlot(context.inventoryController.findBestWeaponSlot(botPlayer));
        context.packetBroadcaster.broadcastEquipment();
        context.packetBroadcaster.broadcastEntityData();
    }

    private Location fleePointAt(Location from, float yaw, double dist) {
        double rad = Math.toRadians(yaw);
        return from.clone().add(-Math.sin(rad) * dist, 0, Math.cos(rad) * dist);
    }

    private float pickOpenFleeYaw(Location from, float awayYaw) {
        float[] offsets = {0f, -30f, 30f, -60f, 60f, -90f, 90f};
        for (float off : offsets) {
            float yaw = (awayYaw + off) % 360f;
            Location probe = fleePointAt(from, yaw, 10.0);
            if (context.movementController.canWalkStraightTo(probe)) return yaw;
        }
        return Float.NaN;
    }

    public void handleFleeingBlocks(Player p) {
        if (!context.fleeing || context.eating || context.drinkingPotionTimer > 0) return;
        if (context.blockPlaceCooldown > 0) { context.blockPlaceCooldown--; return; }
        if (context.fleeGapAssistCooldown > 0) return;
        if (context.fleePanicTicks < 10 || Math.random() > 0.12) return;
        if (!isValidTarget(context.target)) return;
        if (context.target.getWorld() != p.getWorld()) return;

        double targetGap = p.getLocation().distance(context.target.getLocation());
        if (targetGap < 1.8 || targetGap > context.settings.getReach() + 1.0
                || Math.abs(p.getLocation().getY() - context.target.getLocation().getY()) > 1.25) {
            return;
        }

        int slot = context.inventoryController.findBlockSlot(p);
        if (slot < 0 || slot > 8) return;

        Vector toTarget = context.target.getLocation().toVector()
                .subtract(p.getLocation().toVector()).setY(0);
        if (toTarget.lengthSquared() < 0.01) return;
        toTarget.normalize();

        Location placeLoc = p.getLocation().clone().add(toTarget.multiply(1.2));
        Block placeBlock = placeLoc.getBlock();
        if (placeBlock.getType() != Material.AIR) { context.blockPlaceCooldown = 10; return; }

        Block below = placeBlock.getRelative(org.bukkit.block.BlockFace.DOWN);
        if (!below.getType().isSolid()) { context.blockPlaceCooldown = 10; return; }

        ItemStack offhandBackup = p.getInventory().getItemInOffHand().clone();

        p.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();
        context.bot.getHandle().swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(context.bot.getHandle(), 0);

        ItemStack blockItem = p.getInventory().getItem(slot);
        if (blockItem != null) {
            placeBlock.setType(blockItem.getType());
            blockItem.setAmount(blockItem.getAmount() - 1);
            if (blockItem.getAmount() <= 0) p.getInventory().setItem(slot, null);
        }

        p.getInventory().setItemInOffHand(offhandBackup);
        context.packetBroadcaster.broadcastEquipment();
        context.blockPlaceCooldown = 70 + java.util.concurrent.ThreadLocalRandom.current().nextInt(21);
    }

    private boolean queueSplashBuffs(Player botPlayer) {
        boolean added = false;
        added |= queueSplashBuff(botPlayer, PotionEffectType.FIRE_RESISTANCE);
        added |= queueSplashBuff(botPlayer, PotionEffectType.STRENGTH);
        added |= queueSplashBuff(botPlayer, PotionEffectType.SPEED);
        return added;
    }

    private boolean queueSplashBuff(Player botPlayer, PotionEffectType effect) {
        int slot = context.inventoryController.findSplashSlotByEffect(botPlayer, effect);
        if (slot < 0 || slot > 8) return false;
        context.splashBuffQueue.addLast(slot);
        return true;
    }

    public boolean tickSplashBuffSequence(Player botPlayer) {
        if (context.splashPotionDelayTicks > 0) {
            context.splashPotionDelayTicks--;
            if (context.splashPotionDelayTicks > 0) return true;
        }

        if (context.splashBuffQueue.isEmpty()) {
            if (context.pendingPotionSlot >= 0) {
                finishSplashBuffSequence(botPlayer);
                return true;
            }
            return false;
        }

        int slot = context.splashBuffQueue.removeFirst();
        throwSplashBuff(botPlayer, slot);
        return true;
    }

    private void throwSplashBuff(Player botPlayer, int slot) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return;

        if (!context.potionOffhandPreserved) {
            context.potionOffhandBackup = botPlayer.getInventory().getItemInOffHand().clone();
            context.potionOffhandPreserved = true;
        }

        context.requestLook(handle.getYRot(), 90.0f, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        ItemStack potItem = botPlayer.getInventory().getItem(slot);
        if (potItem != null && potItem.getType() == Material.SPLASH_POTION) {
            org.bukkit.entity.SplashPotion thrown =
                    botPlayer.launchProjectile(org.bukkit.entity.SplashPotion.class);
            thrown.setItem(potItem);
            thrown.setVelocity(botPlayer.getLocation().getDirection().multiply(1.5));

            thrown.setGravity(true);

            potItem.setAmount(potItem.getAmount() - 1);
            botPlayer.getInventory().setItem(slot, potItem.getAmount() > 0 ? potItem : null);
        }

        context.pendingPotionSlot = slot;
        context.splashPotionDelayTicks = 8;
    }

    private void finishSplashBuffSequence(Player botPlayer) {
        context.splashBuffQueue.clear();
        context.pendingPotionSlot = -1;
        context.splashPotionDelayTicks = 0;

        botPlayer.getInventory().setHeldItemSlot(context.inventoryController.findBestWeaponSlot(botPlayer));
        if (context.potionOffhandPreserved) {
            botPlayer.getInventory().setItemInOffHand(context.potionOffhandBackup);
            context.potionOffhandPreserved = false;
        }
        context.packetBroadcaster.broadcastEquipment();
        context.packetBroadcaster.broadcastEntityData();
    }

    public void handleBuffs(Player botPlayer) {
        if (context.splashPotionTimer > 0 || context.eating || context.drinkingPotionTimer > 0) return;
        if (context.buffCooldown > 0) { context.buffCooldown--; return; }

        if (context.buffScanCooldown > 0) { context.buffScanCooldown--; return; }
        context.buffScanCooldown = 9 + java.util.concurrent.ThreadLocalRandom.current().nextInt(4);

        double hpPct = botPlayer.getHealth() / botPlayer.getMaxHealth();

        boolean wantsTurtle = hpPct <= 0.4
                && !botPlayer.hasPotionEffect(PotionEffectType.RESISTANCE);
        if (wantsTurtle && tryBuff(botPlayer, PotionEffectType.RESISTANCE, true)) return;

        if (context.fleeing) return;

        if (botPlayer.getFireTicks() > 0
                && !botPlayer.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)
                && tryBuff(botPlayer, PotionEffectType.FIRE_RESISTANCE, true)) return;

        if (context.settings.isBotsUseInvis()
                && !botPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY)
                && tryBuff(botPlayer, PotionEffectType.INVISIBILITY, false)) return;

        if (!botPlayer.hasPotionEffect(PotionEffectType.STRENGTH)
                && tryBuff(botPlayer, PotionEffectType.STRENGTH, false)) return;

        if (!botPlayer.hasPotionEffect(PotionEffectType.SPEED)
                && tryBuff(botPlayer, PotionEffectType.SPEED, false)) return;
    }

    private boolean tryBuff(Player botPlayer, PotionEffectType effect, boolean urgent) {
        int splash = context.inventoryController.findSplashSlotByEffect(botPlayer, effect);
        if (splash >= 0 && splash <= 8) {
            context.splashThrowSlot = splash;
            context.splashPotionTimer = 8;
            context.buffCooldown = 40;
            return true;
        }

        double gap = isValidTarget(context.target)
                ? context.target.getLocation().distance(botPlayer.getLocation())
                : Double.MAX_VALUE;
        if (!urgent && gap < 8.0) return false;

        int drink = context.inventoryController.findDrinkableSlotByEffect(botPlayer, effect);
        if (drink < 0 || drink > 8) return false;

        botPlayer.getInventory().setHeldItemSlot(drink);
        context.packetBroadcaster.broadcastEquipment();
        context.drinkingPotionSlot = drink;
        context.drinkingIsRegen = false;
        context.bot.getHandle().startUsingItem(InteractionHand.MAIN_HAND);
        context.drinkingPotionTimer =
                Math.max(1, context.bot.getHandle().getUseItemRemainingTicks());
        context.packetBroadcaster.broadcastEntityData();
        context.buffCooldown = 60;
        return true;
    }

    private boolean isBotInLava(Player botPlayer) {
        Location loc = botPlayer.getLocation();
        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1, 0).getBlock();

        Material feetType = feet.getType();
        Material headType = head.getType();

        return feetType == Material.LAVA || headType == Material.LAVA;
    }

    private boolean isBotInWater(Player botPlayer) {
        Location loc = botPlayer.getLocation();
        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1, 0).getBlock();

        Material feetType = feet.getType();
        Material headType = head.getType();

        return feetType == Material.WATER || headType == Material.WATER;
    }

    private boolean isValidTarget(Player p) {
        return TargetFilter.isEngageable(p, context.bot.getBukkitPlayer());
    }
}
