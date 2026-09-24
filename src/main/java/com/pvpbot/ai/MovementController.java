package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.ThreadLocalRandom;

// Rewritten in place (see /pvpbot-adjacent backups/MovementController.java.bak-*
// for the pre-rewrite version). Same public/package-private API surface as
// before - every external caller (BotAI, BuildController, CombatController,
// GolemFightController, HazardController, HealingController, CartController,
// LavaStuntController, MiningController, MaceController, TechniqueController,
// TunnelController, FarmController, PatrolController, TacticsController,
// TargetingController, ClutchController, RestockController, PvPBotPlugin)
// keeps working unchanged. The one behavioral change is the "travel to a
// fixed point" methods (follow-leader, formation-march, escort, guard-post
// return, investigate) now share one decision core (see approachPoint below)
// instead of five near-duplicate copies of the same blocked/too-far check -
// that duplication is what produced the "walks into a wall" family of bugs.
public class MovementController {
    private final BotAIContext context;

    private static final int DESCEND_PEARL_COOLDOWN = 100;

    private static final double BASE_MOVE_SPEED = 0.10D;

    private static final float USE_ITEM_SLOWDOWN = 0.2f;

    private static final float SNEAK_SLOWDOWN = 0.3f;

    private boolean loggedFallback = false;

    public int doTickFailures = 0;

    private float waterStrafeOverride = Float.NaN;

    private static final int HOLE_ESCAPE_SEARCH_RADIUS = 16;

    private static final int HOLE_ESCAPE_SEARCH_UP = 16;

    private static final int HOLE_DETECTION_RADIUS = 6;

    public MovementController(BotAIContext context) {
        this.context = context;
    }

    // =====================================================================
    // Per-tick physics drive: this is what a real client would be sending -
    // movement input, jumping, bhop, lethal-edge protection, water swimming,
    // knockback riding, input smoothing - before handle.doTick() runs vanilla
    // physics on top of it.
    // =====================================================================

    private boolean tryPearlDescend(Player botPlayer, ServerPlayer handle, Location targetLoc) {
        if (context.descendPearlCooldown > 0) return false;
        if (context.eating || context.drinkingPotionTimer > 0) return false;
        if (context.maceController.isAttempting()) return false;

        int slot = context.inventoryController.findItemSlot(botPlayer, Material.ENDER_PEARL);
        if (slot < 0 || slot > 8) return false;

        Location eye = botPlayer.getEyeLocation();
        double dx = targetLoc.getX() - eye.getX();
        double dy = (targetLoc.getY() + 1.0) - eye.getY();
        double dz = targetLoc.getZ() - eye.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float flatPitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.5, horiz)));
        float pitch = Math.max(-70f, flatPitch - (float) Math.min(25.0, horiz * 1.1));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, true);
        flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        try {
            botPlayer.launchProjectile(org.bukkit.entity.EnderPearl.class);
        } catch (Throwable t) {
            return false;
        }


        ItemStack pearl = botPlayer.getInventory().getItem(slot);
        if (pearl != null) {
            pearl.setAmount(pearl.getAmount() - 1);
            botPlayer.getInventory().setItem(slot, pearl.getAmount() > 0 ? pearl : null);
            context.packetBroadcaster.broadcastEquipment();
        }

        context.descendPearlCooldown = DESCEND_PEARL_COOLDOWN;
        return true;
    }

    private boolean lethalDropInDirection(ServerPlayer handle, double dirX, double dirZ, double reach) {
        Player botPlayer = context.bot.getBukkitPlayer();
        if (handle == null || botPlayer == null || !handle.onGround()) return false;

        Location loc = botPlayer.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null) return false;

        int baseY = feetBlockY(loc, handle);
        double aheadX = loc.getX() + dirX * reach;
        double aheadZ = loc.getZ() + dirZ * reach;
        int ax = (int) Math.floor(aheadX);
        int az = (int) Math.floor(aheadZ);

        if (isStandableFloor(w.getBlockAt(ax, baseY - 1, az))
                || isStandableFloor(w.getBlockAt(ax, baseY - 2, az))) {
            return false;
        }
        return dropIsLethal(w, aheadX, aheadZ, baseY);
    }

    private boolean requestBridgeJump(ServerPlayer handle, double dirX, double dirZ, double reach) {
        if (handle == null || !handle.onGround() || context.jumpCooldown > 0) return false;
        if (lethalDropInDirection(handle, dirX, dirZ, reach)) return false;

        context.jumpCooldown = 9 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        context.shouldJumpThisTick = true;
        return true;
    }

    public void applyPhysics(ServerPlayer handle) {
        flushLook(handle);
        trackTargetElevation(handle);
        trackJumpFailure(handle);

        if (context.descendPearlCooldown > 0) context.descendPearlCooldown--;

        if (!context.bridging && handle.isShiftKeyDown()) {
            handle.setShiftKeyDown(false);
        }

        Player botPlayerRef = context.bot.getBukkitPlayer();

        com.pvpbot.ClientPacketSink sink = context.bot.getPacketSink();
        boolean knockbackLanded = sink != null && sink.applyPending(handle);

        context.inWater = handle.isInWater() || handle.isInLava();

        if (context.jumpBanTicks > 0) context.jumpBanTicks--;

        if (context.wtapPending && handle.onGround()) {
            handle.setSprinting(false);
            context.wtapPending = false;
        }

        Vec3 vel = handle.getDeltaMovement();
        double horizVel = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        if (knockbackLanded) {
            context.knockbackRideTicks = 8;
            context.lastKnockbackArmTick = context.tickCounter;
        }

        boolean ridingKnockback = false;
        if (context.knockbackRideTicks > 0) {
            context.knockbackRideTicks--;

            if (horizVel > 0.12) {
                ridingKnockback = true;
                handle.setSprinting(false);
            } else {
                context.knockbackRideTicks = 0;
            }
        }

        float forward = context.forwardInput;
        float strafe = context.strafeInput;

        if (context.sTapActive) {
            if (context.sTapTimer > 0) {
                forward = -0.5f;
                strafe = 0f;
                context.sTapTimer--;
            } else {
                context.sTapActive = false;
            }
        }

        boolean suppressForKnockback = ridingKnockback && !context.fleeing;

        if (suppressForKnockback) {
            forward = 0f;
            strafe = 0f;
        }

        if (handle.isUsingItem() && !handle.isPassenger()) {
            forward *= USE_ITEM_SLOWDOWN;
            strafe *= USE_ITEM_SLOWDOWN;
        } else if (context.eating || context.drinkingPotionTimer > 0) {
            forward *= USE_ITEM_SLOWDOWN;
            strafe *= USE_ITEM_SLOWDOWN;
        }

        if (handle.isShiftKeyDown() && !handle.isPassenger()) {
            forward *= SNEAK_SLOWDOWN;
            strafe *= SNEAK_SLOWDOWN;
        }

        float rawForward = forward;
        float rawStrafe = strafe;
        float[] guarded = guardLethalEdge(handle, forward, strafe);
        forward = guarded[0];
        strafe = guarded[1];
        boolean urgentEdgeResponse = context.edgeBlockedTicks > 0
                && (Math.abs(rawForward - forward) > 0.05f
                || Math.abs(rawStrafe - strafe) > 0.05f);

        if (!suppressForKnockback) {
            scanForJumps(handle, botPlayerRef, forward, strafe);
            applyBhop(handle, forward);

            forward = applyObstacleAvoidance(handle, botPlayerRef, forward, strafe);
            if (!Float.isNaN(context.avoidStrafeOverride)) {
                strafe = context.avoidStrafeOverride;
                context.avoidStrafeOverride = Float.NaN;
            }
        } else {
            context.shouldJumpThisTick = false;
        }

        boolean movingForward = forward > 0.1f;
        boolean canSprint = movingForward
                && context.settings.isSprint()
                && !context.suppressSprint
                && !context.eating
                && context.drinkingPotionTimer <= 0
                && !handle.isShiftKeyDown()
                && !handle.isUsingItem()
                && handle.getFoodData().getFoodLevel() > 6;
        handle.setSprinting(canSprint);

        float verticalSwim = handleWaterMovement(handle, botPlayerRef, forward, strafe);
        if (!Float.isNaN(waterStrafeOverride)) {
            strafe = waterStrafeOverride;
            waterStrafeOverride = Float.NaN;
        }

        if (context.inputLatchTicks > 0) context.inputLatchTicks--;

        context.pendingForwardInput = forward;
        context.pendingStrafeInput = strafe;

        boolean uncommittedChange =
                Math.abs(forward - context.smoothedForwardInput) > 0.05f
                        || Math.abs(strafe - context.smoothedStrafeInput) > 0.05f;

        if (urgentEdgeResponse) {
            context.smoothedForwardInput = forward;
            context.smoothedStrafeInput = strafe;
            context.inputReactionTicks = 0;
            context.inputLatchTicks = 1;
        } else if (context.inputReactionTicks > 0) {
            if (--context.inputReactionTicks == 0) {
                context.smoothedForwardInput = context.pendingForwardInput;
                context.smoothedStrafeInput = context.pendingStrafeInput;
                context.inputLatchTicks = 2;
            }
        } else if (uncommittedChange && context.inputLatchTicks <= 0) {
            context.inputReactionTicks =
                    1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        }

        handle.zza = context.smoothedForwardInput;
        handle.xxa = context.smoothedStrafeInput;

        handle.yya = verticalSwim;
        handle.setJumping(context.shouldJumpThisTick);

        driveVanillaTick(handle);

        Vec3 post = handle.getDeltaMovement();
        context.velX = post.x;
        context.velZ = post.z;
        context.ownedDy = post.y;

        context.shouldJumpThisTick = false;
    }

    private float handleWaterMovement(ServerPlayer handle, Player botPlayerRef,
                                      float forward, float strafe) {
        if (!handle.isInWater()) {
            if (handle.isSwimming()) handle.setSwimming(false);
            context.waterExitTicks = 0;
            return 0f;
        }

        boolean headUnder = handle.isUnderWater();
        boolean wantsUp = false;

        if (botPlayerRef != null && (Math.abs(forward) > 0.1f || Math.abs(strafe) > 0.1f)) {
            double radYaw = Math.toRadians(handle.getYRot());
            double dirX = -Math.sin(radYaw) * forward + Math.cos(radYaw) * strafe;
            double dirZ = Math.cos(radYaw) * forward + Math.sin(radYaw) * strafe;
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 0.05) {
                dirX /= len;
                dirZ /= len;
                Location loc = botPlayerRef.getLocation();
                org.bukkit.World w = loc.getWorld();
                int ax = (int) Math.floor(loc.getX() + dirX * 0.8);
                int az = (int) Math.floor(loc.getZ() + dirZ * 0.8);
                int feetY = (int) Math.floor(loc.getY());
                Block ahead = w.getBlockAt(ax, feetY, az);
                Block aheadUp = w.getBlockAt(ax, feetY + 1, az);

                if (ahead.getType().isSolid() || aheadUp.getType().isSolid()) wantsUp = true;
            }
        }

        if (headUnder) wantsUp = true;

        Player target = context.target;
        if (!wantsUp && target != null && target.getWorld() == handle.getBukkitEntity().getWorld()) {
            double vertGap = target.getLocation().getY() - handle.getY();
            if (vertGap > 0.6) wantsUp = true;

            if (vertGap < -1.2 && !headUnder) {
                context.waterExitTicks = 0;
                handle.setSwimming(false);
                return -1.0f;
            }
        }

        if (wantsUp) {
            if (context.jumpCooldown <= 0) {
                context.shouldJumpThisTick = true;
                context.jumpCooldown = 5 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
            }
            context.waterExitTicks++;

            if (context.waterExitTicks > 60) {
                waterStrafeOverride = (context.waterExitTicks % 40 < 20) ? 0.8f : -0.8f;
                if (context.waterExitTicks > 140) context.waterExitTicks = 0;
            }
            handle.setSwimming(false);
            return 1.0f;
        }

        context.waterExitTicks = 0;

        handle.setSwimming(headUnder && forward > 0.1f);
        return 0f;
    }

    private void syncVanillaSpeed(ServerPlayer handle) {
        handle.setSpeed((float) handle.getAttributeValue(Attributes.MOVEMENT_SPEED));
    }

    private void driveVanillaTick(ServerPlayer handle) {
        try {
            syncVanillaSpeed(handle);

            com.pvpbot.PvPBot.forceClientLoaded(handle);
            handle.doTick();
            doTickFailures = 0;
        } catch (Throwable t) {
            doTickFailures++;
            if (doTickFailures == 1 || doTickFailures % 100 == 0) {
                org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                        "[PvPBot] handle.doTick() threw for " + context.bot.getName()
                                + " (" + doTickFailures + " consecutive) — physics is NOT running", t);
            }
        }
    }

    private void scanForJumps(ServerPlayer handle, Player botPlayerRef, float forward, float strafe) {
        if (botPlayerRef == null) return;
        if (!handle.onGround()) return;

        if (context.jumpCooldown > 0) return;
        if (Math.abs(forward) < 0.1f && Math.abs(strafe) < 0.1f) return;
        if (isBotStuckInCobweb(botPlayerRef)) return;

        double radYaw = Math.toRadians(handle.getYRot());
        double dirX = -Math.sin(radYaw) * forward + Math.cos(radYaw) * strafe;
        double dirZ = Math.cos(radYaw) * forward + Math.sin(radYaw) * strafe;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.05) return;
        dirX /= len;
        dirZ /= len;

        Location botLoc = botPlayerRef.getLocation();
        int baseY = feetBlockY(botLoc, handle);

        double probeX = botLoc.getX() + dirX * 0.6;
        double probeZ = botLoc.getZ() + dirZ * 0.6;

        double stepTop = collisionTopAt(handle, probeX, baseY, probeZ);
        boolean headClear = collisionTopAt(handle, probeX, baseY + 1, probeZ) <= 0.0
                && collisionTopAt(handle, probeX, baseY + 2, probeZ) <= 0.0;

        if (stepTop > handle.maxUpStep() && stepTop <= 1.0 && headClear) {
            requestJump();
            return;
        }

        if (!context.bridging && !context.bridgeWanted && stepTop <= 0.0) {
            org.bukkit.World w = botLoc.getWorld();
            double aheadX = botLoc.getX() + dirX * 1.2;
            double aheadZ = botLoc.getZ() + dirZ * 1.2;
            Block g1 = w.getBlockAt((int) Math.floor(aheadX), baseY - 1, (int) Math.floor(aheadZ));
            Block g2 = w.getBlockAt((int) Math.floor(aheadX), baseY - 2, (int) Math.floor(aheadZ));

            boolean gapHere = !g1.getType().isSolid() && !g2.getType().isSolid()
                    && g1.getType() != Material.WATER && g1.getType() != Material.LAVA;

            boolean landing = false;
            if (gapHere) {
                for (double d = 2.0; d <= 4.0 && !landing; d += 0.5) {
                    int lx = (int) Math.floor(botLoc.getX() + dirX * d);
                    int lz = (int) Math.floor(botLoc.getZ() + dirZ * d);
                    if (w.getBlockAt(lx, baseY - 1, lz).getType().isSolid()
                            || w.getBlockAt(lx, baseY - 2, lz).getType().isSolid()) {
                        landing = true;
                    }
                }
            }

            if (gapHere && landing && !dropIsLethal(w, aheadX, aheadZ, baseY)) requestJump();
        }
    }

    private float[] guardLethalEdge(ServerPlayer handle, float forward, float strafe) {
        float[] out = { forward, strafe };

        if (!lethalEdgeAhead(handle, forward, strafe, 0.75)) {
            context.edgeBlockedTicks = 0;
            return out;
        }

        if (context.settings.isBridging()) {
            context.bridgeWanted = true;
            context.bridgeCheckCooldown = 0;
        } else {
            context.bridgeWanted = false;
        }

        if (++context.edgeBlockedTicks > 20) {
            if (context.edgeBlockedTicks == 21) {
                Player bp = context.bot.getBukkitPlayer();
                if (bp != null) {
                    double radYaw = Math.toRadians(handle.getYRot());
                    int ax = (int) Math.floor(handle.getX() - Math.sin(radYaw));
                    int az = (int) Math.floor(handle.getZ() + Math.cos(radYaw));
                    context.markNavFailure(ax, (int) Math.floor(handle.getY()), az);
                }
                context.currentPath.clear();
                context.pathNodeIndex = 0;
                context.pathComplete = false;
                context.pathNodeStuckTicks = 0;
                context.lastPathNodeIndex = -1;
                context.lastPathNodeDistance = -1.0;
                context.pathRecalcCooldown = 0;
            }
            if (context.edgeBlockedTicks > 60) context.edgeBlockedTicks = 0;

            out[0] = -0.5f;
            out[1] = 0.0f;
            return out;
        }

        out[0] = 0.0f;
        out[1] = 0.0f;
        return out;
    }

    private boolean lethalEdgeAhead(ServerPlayer handle, float forward, float strafe,
                                    double reach) {
        if (context.bridging || handle == null || !handle.onGround()) return false;
        if (Math.abs(forward) < 0.1f && Math.abs(strafe) < 0.1f) return false;

        Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer == null) return false;
        Location loc = botPlayer.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null) return false;

        double radYaw = Math.toRadians(handle.getYRot());
        double dirX = -Math.sin(radYaw) * forward + Math.cos(radYaw) * strafe;
        double dirZ = Math.cos(radYaw) * forward + Math.sin(radYaw) * strafe;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.05) return false;
        dirX /= len;
        dirZ /= len;

        int baseY = feetBlockY(loc, handle);
        double aheadX = loc.getX() + dirX * reach;
        double aheadZ = loc.getZ() + dirZ * reach;
        int ax = (int) Math.floor(aheadX);
        int az = (int) Math.floor(aheadZ);

        if (isStandableFloor(w.getBlockAt(ax, baseY - 1, az))
                || isStandableFloor(w.getBlockAt(ax, baseY - 2, az))) {
            return false;
        }

        return dropIsLethal(w, aheadX, aheadZ, baseY);
    }

    private boolean dropIsLethal(org.bukkit.World w, double x, double z, int fromY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        int minY = w.getMinHeight();
        for (int y = fromY - 1; y >= minY; y--) {
            Material m = w.getBlockAt(bx, y, bz).getType();

            if (m == Material.WATER) return false;

            if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE
                    || m == Material.MAGMA_BLOCK || m == Material.CACTUS
                    || m == Material.POINTED_DRIPSTONE || m == Material.CAMPFIRE
                    || m == Material.SOUL_CAMPFIRE || m == Material.SWEET_BERRY_BUSH
                    || m == Material.WITHER_ROSE) {
                return true;
            }

            if (m.isSolid()) {
                return (fromY - y) > 5;
            }
        }

        return true;
    }

    private void applyBhop(ServerPlayer handle, float forward) {
        if (!context.settings.isBhop()) return;
        if (context.bridging) return;
        if (context.shouldJumpThisTick) return;
        if (forward < 0.95f) return;
        if (!handle.isSprinting() || !handle.onGround()) return;
        if (context.jumpCooldown > 0 || context.jumpBanTicks > 0) return;
        if (context.inWater || handle.isShiftKeyDown()) return;

        Vec3 v = handle.getDeltaMovement();
        if (Math.sqrt(v.x * v.x + v.z * v.z) < 0.14) return;

        requestJump();
    }

    private double collisionTopAt(ServerPlayer handle, double worldX, double blockY, double worldZ) {
        int bx = (int) Math.floor(worldX);
        int by = (int) Math.floor(blockY);
        int bz = (int) Math.floor(worldZ);

        var level = handle.level();
        BlockPos pos = new BlockPos(bx, by, bz);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return 0.0;

        double top = shape.max(Direction.Axis.Y, worldX - bx, worldZ - bz);
        return (top < 0.0 || Double.isInfinite(top)) ? 0.0 : top;
    }

    public boolean requestJump() {
        Player botPlayerRef = context.bot.getBukkitPlayer();
        if (botPlayerRef != null && isBotStuckInCobweb(botPlayerRef)) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;
        if (!handle.onGround()) return false;
        if (context.jumpCooldown > 0) return false;

        if (lethalEdgeAhead(handle, context.forwardInput, context.strafeInput, 1.6)) {
            if (context.settings.isBridging()) {
                context.bridgeWanted = true;
                context.bridgeCheckCooldown = 0;
            } else {
                context.bridgeWanted = false;
            }
            return false;
        }

        context.jumpCooldown = 9 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        context.shouldJumpThisTick = true;
        return true;
    }

    public void applyIncomingKnockback(Player botPlayer, Player attacker) {
        if (attacker == null) return;

        if (context.settings.isSTapping() && !context.fleeing && Math.random() < 0.5) {
            context.sTapActive = true;
            context.sTapTimer = 3;
        }

        context.aimNoiseYaw += (float) ((Math.random() - 0.5) * 9.0);
        context.aimNoisePitch += (float) ((Math.random() - 0.5) * 5.0);
    }

    public void pushLikeVanilla(double strength, double fromX, double fromZ) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return;
        handle.knockback(strength, fromX, fromZ);
    }

    // =====================================================================
    // Look/aim: separate from movement input, but every travel/combat method
    // below routes through requestLook*/flushLook so multiple systems can
    // compete for where the bot looks each tick by priority instead of
    // stomping each other.
    // =====================================================================

    public void lookAt(Location targetLoc, double noise) {
        if (targetLoc == null) return;
        ServerPlayer handle = context.bot.getHandle();

        Location aimAt = context.aimHistory.push(targetLoc, context.reactionTicks());

        double dx = aimAt.getX() - handle.getX();
        double dy = aimAt.getY() - (handle.getY() + handle.getEyeHeight());
        double dz = aimAt.getZ() - handle.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float desiredPitch = (float) Math.toDegrees(-Math.atan2(dy, flat));

        if (noise > 0) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            context.aimNoiseYaw += (float) ((rng.nextDouble() - 0.5) * noise * 0.35);
            context.aimNoisePitch += (float) ((rng.nextDouble() - 0.5) * noise * 0.20);
            context.aimNoiseYaw *= 0.86f;
            context.aimNoisePitch *= 0.86f;
            desiredYaw += context.aimNoiseYaw;
            desiredPitch += context.aimNoisePitch;
        }

        context.targetYaw = desiredYaw;
        context.targetPitch = desiredPitch;

        float yawErr = wrapDegrees(desiredYaw - handle.getYRot());
        if (Math.abs(yawErr) > 40f && context.aimOvershootTicks <= 0) {
            context.aimOvershootTicks = 3;
            context.aimOvershoot = 1.06f + (float) (Math.random() * 0.10);
        }
        float gain = context.aimEase();
        if (context.aimOvershootTicks > 0) {
            context.aimOvershootTicks--;
            gain *= context.aimOvershoot;
        }

        context.requestLook(desiredYaw, desiredPitch, BotAIContext.LOOK_COMBAT, false);
        context.lookYawGain = gain;
    }

    public void flushLook(ServerPlayer handle) {
        if (handle == null || !context.lookSet) {
            if (context.lookSet) context.lookSet = false;
            return;
        }

        float gain = context.lookYawGain > 0 ? context.lookYawGain : context.aimEase();

        if (!context.lookPitchOnly) {
            float yaw = context.lookSnap
                    ? context.lookYaw
                    : easeAngle(handle.getYRot(), context.lookYaw, gain, context.rotationSpeed());
            handle.setYRot(yaw);

            if (context.lookSnap) {
                handle.setYHeadRot(yaw);
            } else {
                float headYaw = handle.getYHeadRot();
                float headGain = Math.max(0.1f, gain * 0.6f);
                float newHeadYaw = easeAngle(headYaw, yaw, headGain,
                        context.rotationSpeed() * 0.8f);

                float lag = wrapDegrees(newHeadYaw - yaw);
                if (Math.abs(lag) > MAX_HEAD_LAG_DEGREES) {
                    newHeadYaw = yaw + Math.signum(lag) * MAX_HEAD_LAG_DEGREES;
                }

                float jitter = (float) (java.util.concurrent.ThreadLocalRandom.current().nextDouble() - 0.5) * 4.0f;
                if (Math.abs(wrapDegrees(newHeadYaw - headYaw)) < 0.5f) {
                    newHeadYaw += jitter * 0.1f;
                }

                handle.setYHeadRot(newHeadYaw);
            }
        }

        if (!context.lookYawOnly) {
            float pitch = context.lookSnap
                    ? clampPitch(context.lookPitch)
                    : clampPitch(easeAngle(handle.getXRot(), clampPitch(context.lookPitch),
                    gain * 0.8f, context.pitchSpeed()));
            handle.setXRot(pitch);
        }

        context.lookSet = false;
        context.lookPriority = -1;
        context.lookYawGain = -1f;
        context.lookSnap = false;
        context.lookYawOnly = false;
        context.lookPitchOnly = false;
    }

    public void easePitchTo(float desiredPitch) {
        context.requestLookPitch(clampPitch(desiredPitch), BotAIContext.LOOK_TRAVEL);
    }

    public void easeYawTo(float desiredYaw) {
        context.requestLookYaw(desiredYaw, BotAIContext.LOOK_TRAVEL);
    }

    public void easeYawTo(float desiredYaw, int priority) {
        context.requestLookYaw(desiredYaw, priority);
    }

    private static final float ROTATION_SNAP_EPSILON = 0.1f;

    private static final float MAX_HEAD_LAG_DEGREES = 40.0f;

    private float easeAngle(float current, float desired, float gain, float maxStep) {
        float delta = wrapDegrees(desired - current);
        if (Math.abs(delta) < ROTATION_SNAP_EPSILON) return desired;

        float step = delta * gain;
        if (step > maxStep) step = maxStep;
        if (step < -maxStep) step = -maxStep;
        return current + step;
    }

    private float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }

    private void turnTowards(ServerPlayer handle, float desiredYaw) {
        context.requestLookYaw(desiredYaw, BotAIContext.LOOK_TRAVEL);
    }

    private void turnPitchTowards(ServerPlayer handle, float desiredPitch) {
        context.requestLookPitch(clampPitch(desiredPitch), BotAIContext.LOOK_TRAVEL);
    }

    void lookAtPlacement(ServerPlayer handle, Block support, Block placeAt) {
        if (handle == null || support == null || placeAt == null) return;

        double faceX = support.getX() + 0.5 + (placeAt.getX() - support.getX()) * 0.5;
        double faceY = support.getY() + 0.5 + (placeAt.getY() - support.getY()) * 0.5;
        double faceZ = support.getZ() + 0.5 + (placeAt.getZ() - support.getZ()) * 0.5;

        double dx = faceX - handle.getX();
        double dy = faceY - (handle.getY() + handle.getEyeHeight());
        double dz = faceZ - handle.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);

        float pitch = clampPitch((float) Math.toDegrees(-Math.atan2(dy, Math.max(0.05, flat))));

        if (flat < 0.2) {
            context.requestLook(handle.getYRot(), pitch, BotAIContext.LOOK_CRITICAL, true);
            applyPlacementLook(handle);
            return;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, true);
        applyPlacementLook(handle);
    }

    private void applyPlacementLook(ServerPlayer handle) {
        flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);
    }

    private float wrapDegrees(float angle) {
        angle %= 360.0f;
        return (angle >= 180.0f) ? angle - 360.0f : (angle < -180.0f ? angle + 360.0f : angle);
    }

    private float effectiveYaw(ServerPlayer handle) {
        if (!context.lookSet || context.lookPitchOnly) return handle.getYRot();
        if (context.lookSnap) return context.lookYaw;
        float gain = context.lookYawGain > 0 ? context.lookYawGain : context.aimEase();
        return easeAngle(handle.getYRot(), context.lookYaw, gain, context.rotationSpeed());
    }

    private static java.lang.reflect.Method ATTACK_COOLDOWN_METHOD;
    private static boolean ATTACK_COOLDOWN_LOOKED_UP = false;

    private static java.lang.reflect.Method attackCooldownMethod() {
        if (!ATTACK_COOLDOWN_LOOKED_UP) {
            ATTACK_COOLDOWN_LOOKED_UP = true;
            try {
                ATTACK_COOLDOWN_METHOD = Player.class.getMethod("getAttackCooldown");
            } catch (Throwable ignored) {
                ATTACK_COOLDOWN_METHOD = null;
            }
        }
        return ATTACK_COOLDOWN_METHOD;
    }

    private boolean targetJustSwung() {
        Player t = context.target;
        if (t == null) {
            context.lastTargetAttackCharge = 1.0f;
            return false;
        }
        java.lang.reflect.Method m = attackCooldownMethod();
        if (m == null) return false;

        float charge;
        try {
            charge = ((Number) m.invoke(t)).floatValue();
        } catch (Throwable ex) {
            return false;
        }
        float previous = context.lastTargetAttackCharge;
        context.lastTargetAttackCharge = charge;
        return previous > 0.85f && charge < 0.30f;
    }

    // =====================================================================
    // Combat movement & footwork: chase closing, fight-range jiggle/strafe,
    // guard-post interpose, and mace-smash dodging layered on top.
    // =====================================================================

    private boolean applyGuardInterpose(Player botPlayer, double distance) {
        if (context.guardMode != BotAIContext.GuardMode.LEADER) return false;

        Location anchor = context.guardAnchor;
        Player threat = context.target;
        ServerPlayer h = context.bot.getHandle();
        if (anchor == null || threat == null || h == null) return false;
        if (anchor.getWorld() != botPlayer.getWorld()) return false;
        if (threat.getWorld() != botPlayer.getWorld()) return false;

        if (distance <= context.settings.getReach() + 0.5) return false;

        Location tl = threat.getLocation();
        double tx = tl.getX() - anchor.getX();
        double tz = tl.getZ() - anchor.getZ();
        double toThreat = Math.sqrt(tx * tx + tz * tz);

        if (toThreat < 1.0) return false;

        double step = Math.min(2.5, toThreat * 0.5);
        double px = anchor.getX() + (tx / toThreat) * step;
        double pz = anchor.getZ() + (tz / toThreat) * step;

        double dx = px - h.getX();
        double dz = pz - h.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);

        if (d < 0.9) {
            context.forwardInput = 0.0f;
            context.strafeInput = 0.0f;
            return true;
        }

        worldDirToInputs(h, dx, dz);
        return true;
    }

    private void resampleFightInputs() {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        context.fightForwardInput = 0.5f + r.nextFloat() * 0.25f;
        context.fightBackInput = -(0.1f + r.nextFloat() * 0.15f);
    }

    private void resampleStrafeScale() {
        context.fightStrafeScale =
                0.85f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.30f;
    }

    private void maybePauseFootwork(boolean targetActed) {
        if (context.fightPauseCooldown > 0) return;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        int chance = targetActed ? 14 : 8;
        if (r.nextInt(100) >= chance) return;
        context.fightPauseTicks = 2 + r.nextInt(4);
        context.fightPauseCooldown = 40 + r.nextInt(40);
    }

    private void tryFlipStrafeDirection(ServerPlayer handle) {
        int candidate = -context.strafeDirection;
        float candidateStrafe = (float) context.settings.getStrafeSpeed()
                * context.fightStrafeScale * candidate;

        if (lethalEdgeAhead(handle, context.forwardInput, candidateStrafe, 0.9)) {
            context.strafeFlipTicks = 5;
            return;
        }

        context.strafeDirection = candidate;
        context.strafeFlipTicks = 7 + java.util.concurrent.ThreadLocalRandom
                .current().nextInt(13);
        resampleStrafeScale();
    }

    public void handleCombatMovement(double distance, TacticsController.Mode mode) {
        context.navBranch = "COMBAT";
        context.lastTacticsMode = mode;
        Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer == null) return;

        if (isBotInLava(botPlayer)) {
            context.forwardInput = 1.0f;
            context.strafeInput = 0f;
            requestJump();
            return;
        }

        if (!context.settings.isBridging() && context.bridging) {
            stopBridging(context.bot.getHandle());
        }

        if (context.settings.isBridging()
                && mode != TacticsController.Mode.FIGHT && !context.bridging) {
            if (context.bridgeCheckCooldown <= 0 && shouldStartBridging(botPlayer)) {
                context.bridging = true;
                context.bridgeMode = context.proposedBridgeMode;
                context.bridgeStuckTicks = 0;
                context.bridgeCrossed = false;
                context.jumpOverSettleTicks = 0;
                context.bridgeModeTicks = 0;
                context.bridgeReachedEdge = false;
                context.bridgeStepX = 0;
                context.bridgeStepZ = 0;
                context.bridgeCheckCooldown = 0;

                context.bridgePlacementWindup =
                        (context.bridgeMode == BotAIContext.BridgeMode.STANDARD) ? 3 : 0;
                context.lastBridgePos = null;
            }
        }

        if (context.bridging) {
            handleBridging(botPlayer);
            return;
        }

        if (mode == TacticsController.Mode.PATH) {
            if (context.currentPath.isEmpty() || context.pathNodeIndex >= context.currentPath.size()) {
                context.pathComplete = false;
                context.forwardInput = 0.0f;
                context.strafeInput = 0.0f;
                return;
            }

            followPath();
            return;
        }

        boolean techniqueMovement = mode == TacticsController.Mode.FIGHT
                && context.techniqueController.applyTechniqueMovement(
                        botPlayer, context.target, distance);
        if (techniqueMovement) {
            if (context.avoidTicks > 0) {
                context.strafeInput = context.avoidDir;
                if (context.forwardInput > 0.75f) context.forwardInput = 0.75f;
            }
            if (context.smashThreatTicks > 0) {
                applySmashEvade(botPlayer);
            } else {
                context.smashEvadeHold = 0;
            }
            return;
        }

        if (mode == TacticsController.Mode.CHASE) {
            context.forwardInput = 1.0f;
            context.strafeInput = 0.0f;
            probeChaseLane(botPlayer);

            applyGuardInterpose(botPlayer, distance);
        } else {
            context.chaseBlockedTicks = 0;
            double reach = context.combatController.effectiveReach();
            double minRange = context.combatController.minimumAttackRange();
            boolean closingForHit = distance > reach - 0.5;
            boolean tooClose = minRange > 0.0 && distance < minRange + 0.5;
            if (tooClose) closingForHit = false;
            boolean holdingAttackLane = context.critPhase != BotAIContext.CritPhase.IDLE
                    || context.attackCooldown <= 1;

            boolean targetActed = targetJustSwung();

            if (context.fightPauseCooldown > 0) context.fightPauseCooldown--;

            if (context.fightPauseTicks > 0) {
                context.fightPauseTicks--;
                context.forwardInput = 0.0f;
                context.strafeInput = 0.0f;
            } else {
                if (tooClose) {
                    context.forwardInput = -0.6f;
                } else if (closingForHit) {
                    context.forwardInput = 1.0f;
                } else if (distance < 1.5) {
                    context.forwardInput = 0.2f;
                } else {
                    context.jigglePhaseTicks--;
                    boolean flip = context.jigglePhaseTicks <= 0
                            || (targetActed && java.util.concurrent.ThreadLocalRandom
                                    .current().nextInt(100) < 35);
                    if (flip) {
                        context.jiggleIn = !context.jiggleIn;
                        context.jigglePhaseTicks = 5 + java.util.concurrent.ThreadLocalRandom
                                .current().nextInt(9);
                        resampleFightInputs();
                        maybePauseFootwork(targetActed);
                    }
                    context.forwardInput = context.jiggleIn
                            ? context.fightForwardInput : context.fightBackInput;
                }

                if (context.settings.isStrafing()) {
                    ServerPlayer handle = context.bot.getHandle();

                    if (closingForHit || holdingAttackLane || distance < reach + 0.3) {
                        context.strafeInput = 0.0f;
                    } else {
                        boolean strafeFlip = --context.strafeFlipTicks <= 0
                                || (targetActed && java.util.concurrent.ThreadLocalRandom
                                .current().nextInt(100) < 25);
                        if (strafeFlip) {
                            tryFlipStrafeDirection(handle);
                        }
                        if (handle.horizontalCollision && context.tickCounter % 10 == 0) {
                            tryFlipStrafeDirection(handle);
                        }
                        context.strafeInput = (float) context.settings.getStrafeSpeed()
                                * context.fightStrafeScale * context.strafeDirection;
                    }
                } else {
                    context.strafeInput = 0.0f;
                }
            }
        }


        if (context.blockToBreak != null && context.forwardInput > 0f) {
            Location webLoc = context.blockToBreak;
            if (webLoc.getWorld() == botPlayer.getWorld()) {
                double webDx = webLoc.getBlockX() + 0.5 - botPlayer.getLocation().getX();
                double webDz = webLoc.getBlockZ() + 0.5 - botPlayer.getLocation().getZ();
                double webDistSq = webDx * webDx + webDz * webDz;
                double stopDistSq = 1.3 * 1.3;
                if (webDistSq < stopDistSq) {
                    context.forwardInput = 0f;
                }
            }
        }

        if (context.avoidTicks > 0) {
            context.strafeInput = context.avoidDir;
            if (context.forwardInput > 0.75f) context.forwardInput = 0.75f;
        }

        if (context.smashThreatTicks > 0) {
            applySmashEvade(botPlayer);
        } else {
            context.smashEvadeHold = 0;
        }
    }

    private void applySmashEvade(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || botPlayer == null) return;

        Player threat = findSmashThreatSource(botPlayer, handle);
        if (threat == null) {
            context.smashEvadeHold = 0;
            return;
        }

        Location tl = threat.getLocation();
        org.bukkit.util.Vector tv = threat.getVelocity();

        double fallTicks = ticksToImpact(tl.getY() - handle.getY(), tv.getY());
        double impactX = tl.getX() + tv.getX() * fallTicks;
        double impactZ = tl.getZ() + tv.getZ() * fallTicks;

        double ex = handle.getX() - impactX;
        double ez = handle.getZ() - impactZ;
        double len = Math.sqrt(ex * ex + ez * ez);

        if (len > 4.5) {
            context.smashEvadeHold = 0;
            return;
        }

        double dx;
        double dz;

        if (context.smashEvadeHold > 0) {
            context.smashEvadeHold--;
            dx = context.smashEvadeX;
            dz = context.smashEvadeZ;
        } else {
            if (len < 0.35) {
                double a = (((handle.getId() * 2654435761L) >>> 16) & 0xFF) / 255.0 * Math.PI * 2.0;
                ex = Math.cos(a);
                ez = Math.sin(a);
            } else {
                ex /= len;
                ez /= len;
            }

            if (context.smashEvadeSign == 0) context.smashEvadeSign = 1;
            double rot = Math.toRadians(40.0 * context.smashEvadeSign);
            double cr = Math.cos(rot);
            double sr = Math.sin(rot);
            dx = ex * cr - ez * sr;
            dz = ex * sr + ez * cr;

            Location loc = botPlayer.getLocation();
            if (!sideOpen(handle, loc, dx, dz)) {
                double mx = ex * cr + ez * sr;
                double mz = -ex * sr + ez * cr;
                if (sideOpen(handle, loc, mx, mz)) {
                    context.smashEvadeSign = -context.smashEvadeSign;
                    dx = mx;
                    dz = mz;
                } else if (sideOpen(handle, loc, ex, ez)) {
                    dx = ex;
                    dz = ez;
                } else {
                    context.forwardInput = 0.0f;
                    context.strafeInput = 0.0f;
                    context.smashEvadeHold = 0;
                    return;
                }
            }

            context.smashEvadeX = dx;
            context.smashEvadeZ = dz;
            context.smashEvadeHold = 6;

            context.smashEvadeSign = -context.smashEvadeSign;
        }

        worldDirToInputs(handle, dx, dz);
    }

    private Player findSmashThreatSource(Player botPlayer, ServerPlayer handle) {
        com.pvpbot.perf.PlayerSnapshot.WorldView view =
                com.pvpbot.perf.PlayerSnapshot.forWorld(botPlayer.getWorld());

        double bx = handle.getX();
        double by = handle.getY();
        double bz = handle.getZ();

        Player best = null;
        double bestScore = Double.MAX_VALUE;

        com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();

        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null || p == botPlayer) continue;

            double px = view.x[i], py = view.y[i], pz = view.z[i];
            double dxa = px - bx, dza = pz - bz;
            double horizSq = dxa * dxa + dza * dza;

            if (horizSq > 6.5 * 6.5) continue;
            double above = py - by;
            if (above < 0.75 || above > 16.0) continue;
            if (mgr != null && mgr.isFriendly(context.bot.getUUID(), p.getUniqueId())) continue;

            double score = horizSq;
            if (p.getInventory().getItemInMainHand().getType() == Material.MACE) score -= 100.0;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    private double ticksToImpact(double heightAbove, double vy) {
        if (heightAbove <= 0.0) return 0.0;
        double y = heightAbove;
        double v = vy;
        for (int t = 1; t <= 40; t++) {
            v = (v - 0.08) * 0.98;
            y += v;
            if (y <= 0.0) return t;
        }
        return 40.0;
    }

    // =====================================================================
    // World-space direction -> forward/strafe input. The single shared
    // utility nearly everything else in this class (and CombatController,
    // CartController, HealingController, MaceController, TechniqueController,
    // TunnelController) uses to turn "I want to move this way in the world"
    // into the yaw-relative inputs handle.zza/xxa actually consume.
    // =====================================================================

    public void worldDirToInputs(ServerPlayer handle, double wx, double wz) {
        worldDirToInputs(handle, wx, wz, 1.0f);
    }

    public void worldDirToInputs(ServerPlayer handle, double wx, double wz, float magnitude) {
        double len = Math.sqrt(wx * wx + wz * wz);
        if (len < 1.0e-4) {
            context.forwardInput = 0.0f;
            context.strafeInput = 0.0f;
            return;
        }
        wx /= len;
        wz /= len;

        double yaw = Math.toRadians(effectiveYaw(handle));
        double c = Math.cos(yaw);
        double s = Math.sin(yaw);

        context.strafeInput = (float) ((wx * c + wz * s) * magnitude);
        context.forwardInput = (float) ((wz * c - wx * s) * magnitude);
    }

    private void probeChaseLane(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        Player target = context.target;
        if (handle == null || target == null) {
            context.chaseBlockedTicks = 0;
            return;
        }

        Location loc = botPlayer.getLocation();
        Location tLoc = target.getLocation();
        if (tLoc.getWorld() != loc.getWorld()) return;

        double dx = tLoc.getX() - loc.getX();
        double dz = tLoc.getZ() - loc.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) {
            context.chaseBlockedTicks = 0;
            return;
        }
        dx /= len;
        dz /= len;

        double probe = Math.min(len, 4.0);
        int feetY = feetBlockY(loc, handle);
        boolean clear = laneWalkable(loc.getWorld(), loc.getX(), feetY, loc.getZ(),
                loc.getX() + dx * probe, loc.getZ() + dz * probe) != Integer.MIN_VALUE;

        if (clear) {
            context.chaseBlockedTicks = Math.max(0, context.chaseBlockedTicks - 1);
            return;
        }

        context.chaseBlockedTicks++;

        if (context.chaseBlockedTicks > 1 && context.avoidTicks <= 0) {
            context.avoidDir = chooseAvoidDir(handle, loc);
            context.avoidTicks = 10;
        }
    }

    // =====================================================================
    // Hole/pit escape + wind-charge last resort. Two independent detectors
    // feed this: a fast wall-count check and a cached radius scan
    // (isInHoleOrPit), escalating to isDeepHoleOrPit for "emergency" mode
    // (shorter stall tolerance, allowed to interrupt melee).
    // =====================================================================

    public boolean handleHoleEscape(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        if (botPlayer == null || handle == null) return false;
        if (context.tunnelController != null && context.tunnelController.isActive()) return false;

        Location loc = botPlayer.getLocation();
        if (!isInHoleOrPit(loc, handle)) {
            clearHoleEscapeState();
            return false;
        }

        boolean emergencyEscape = isDeepHoleOrPit(loc, handle);
        if (!emergencyEscape && isEngagedInMelee(botPlayer)) {
            clearHoleEscapeState();
            return false;
        }

        context.holeEscapeTicks = Math.min(context.holeEscapeTicks + 1, 120);
        context.bridging = false;
        handle.setShiftKeyDown(false);
        context.aimLockedOnTarget = false;

        Location target = context.holeEscapeTarget;
        if (target == null || target.getWorld() == null || target.getWorld() != loc.getWorld()
                || target.distanceSquared(loc) < 0.25 || !isEscapeStandable(target)) {
            target = findHoleEscapeTarget(botPlayer);
            context.holeEscapeTarget = target;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
        }

        if (target != null) {
            if ((context.currentPath.isEmpty() || context.pathNodeIndex >= context.currentPath.size())
                    && context.pathRecalcCooldown <= 0) {
                context.pathfindingController.calculatePathAsync(loc, target);
            }

            if (!context.currentPath.isEmpty() && context.pathNodeIndex < context.currentPath.size()) {
                followPath();
            } else {
                float yaw = (float) Math.toDegrees(Math.atan2(
                        -(target.getX() - loc.getX()), target.getZ() - loc.getZ()));
                turnTowards(handle, yaw);

                double dy = target.getY() - loc.getY();
                turnPitchTowards(handle, dy > 1.0 ? -18.0f : 18.0f);

                context.forwardInput = 1.0f;
                context.strafeInput = 0.0f;
                if (dy > 0.75 && context.jumpCooldown <= 0) {
                    requestJump();
                }

                if (context.holeEscapeTicks > 6 && context.holeEscapeCooldown <= 0) {
                    if (tryWindChargeEscape(botPlayer, target)) return true;
                }
            }

            if (loc.distanceSquared(target) < 1.1) {
                context.holeEscapeCooldown = 20;
                context.holeEscapeTicks = 0;
                context.holeEscapeTarget = null;
                context.currentPath.clear();
                context.pathNodeIndex = 0;
            }

            if (holeEscapeStalled(loc, emergencyEscape)) {
                if (pillarOutOfHole(botPlayer, handle, loc)) return true;
                return giveUpHoleEscape();
            }
            return true;
        }

        if (context.holeEscapeTicks > 6 && context.holeEscapeCooldown <= 0) {
            if (tryWindChargeEscape(botPlayer, loc)) return true;
        }

        if (pillarOutOfHole(botPlayer, handle, loc)) return true;

        if (holeEscapeStalled(loc, emergencyEscape)) {
            return giveUpHoleEscape();
        }

        context.forwardInput = 0.0f;
        context.strafeInput = 0.0f;
        if (context.jumpCooldown <= 0) requestJump();
        return true;
    }

    private void clearHoleEscapeState() {
        context.holeEscapeTicks = 0;
        context.holeEscapeTarget = null;
        context.holeEscapeBestY = Double.NEGATIVE_INFINITY;
    }

    private boolean holeEscapeStalled(Location loc, boolean emergencyEscape) {
        int stallTicks = emergencyEscape ? 12 : 24;
        if (context.holeEscapeTicks < stallTicks) {
            if (loc.getY() > context.holeEscapeBestY) context.holeEscapeBestY = loc.getY();
            return false;
        }
        if (loc.getY() > context.holeEscapeBestY + 0.45) {
            context.holeEscapeBestY = loc.getY();
            context.holeEscapeTicks = emergencyEscape ? 5 : 10;
            return false;
        }
        return true;
    }

    private boolean pillarOutOfHole(Player botPlayer, ServerPlayer handle, Location loc) {
        int slot = findBlockSlot(botPlayer);
        if (slot == -1) return false;

        org.bukkit.World w = loc.getWorld();
        if (w == null) return false;

        handle.setShiftKeyDown(false);
        context.forwardInput = 0.0f;
        context.strafeInput = 0.0f;

        int feetY = feetBlockY(loc, handle);
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();

        lookAtPlacement(handle, w.getBlockAt(bx, feetY - 2, bz),
                w.getBlockAt(bx, feetY - 1, bz));

        if (context.bridgePlaceCooldown > 0) {
            context.bridgePlaceCooldown--;
            return true;
        }

        Block below = w.getBlockAt(bx, feetY - 1, bz);

        if (!below.getType().isAir()) {
            if (handle.onGround()) {
                context.pillarBaseY = loc.getY();
                requestJump();
            }
            return true;
        }

        if (loc.getY() - context.pillarBaseY < 0.95) return true;
        if (handle.getDeltaMovement().y > 0.08) return true;

        Block support = w.getBlockAt(bx, feetY - 2, bz);
        if (placeFromSlot(botPlayer, slot, below, support)) {
            context.bridgePlaceCooldown = 6;
            context.holeEscapeBestY = loc.getY();
            context.holeEscapeTicks = 10;
        }
        return true;
    }

    private boolean giveUpHoleEscape() {
        context.pathFailures = Math.max(context.pathFailures, 3);
        return false;
    }

    private Location findHoleEscapeTarget(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return null;

        Location loc = botPlayer.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null) return null;

        int baseY = feetBlockY(loc, handle);
        int startX = loc.getBlockX();
        int startZ = loc.getBlockZ();

        Location best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int r = 1; r <= HOLE_ESCAPE_SEARCH_RADIUS; r++) {
            for (int dy = 0; dy <= HOLE_ESCAPE_SEARCH_UP; dy++) {
                int y = baseY + dy;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;

                        int x = startX + dx;
                        int z = startZ + dz;
                        if (!isEscapeStandable(w, x, y, z)) continue;

                        Block feet = w.getBlockAt(x, y, z);
                        Block below = w.getBlockAt(x, y - 1, z);
                        double horiz = Math.sqrt(dx * dx + dz * dz);
                        int openSides = escapeOpenSides(w, x, y, z);
                        if (dy == 0 && openSides == 0) continue;

                        double score = -(horiz * 1.45) + dy * 1.8 + openSides * 3.5;

                        if (isStairLike(feet.getType()) || isStairLike(below.getType())) score += 18.0;
                        if (isClimbable(feet.getType()) || isClimbable(below.getType())) score += 16.0;
                        if (isStairLike(feet.getType())) score += 5.0;
                        if (isClimbable(feet.getType())) score += 5.0;
                        if (r <= 3) score += 2.0;
                        if (r <= 2 && (isStairLike(below.getType()) || isClimbable(below.getType()))) score += 6.0;

                        if (score > bestScore) {
                            bestScore = score;
                            best = new Location(w, x + 0.5, y + 0.1, z + 0.5);
                        }
                    }
                }
            }
        }

        return best;
    }

    private int escapeOpenSides(org.bukkit.World world, int x, int y, int z) {
        int open = 0;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            int nx = x + direction[0];
            int nz = z + direction[1];
            if (!isPassable(world.getBlockAt(nx, y, nz))
                    || !isPassable(world.getBlockAt(nx, y + 1, nz))) {
                continue;
            }
            if (isStandableFloor(world.getBlockAt(nx, y - 1, nz))
                    || isStandableFloor(world.getBlockAt(nx, y - 2, nz))) {
                open++;
            }
        }
        return open;
    }

    private boolean tryWindChargeEscape(Player botPlayer, Location aimTarget) {
        if (context.holeEscapeCooldown > 0) return false;
        if (context.eating || context.drinkingPotionTimer > 0) return false;

        int slot = context.inventoryController.findWindChargeSlot(botPlayer);
        if (slot < 0 || slot > 8) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        Location botLoc = botPlayer.getEyeLocation();
        double dx = aimTarget.getX() - botLoc.getX();
        double dy = aimTarget.getY() - botLoc.getY();
        double dz = aimTarget.getZ() - botLoc.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        float pitch = Math.max(78.0f,
                Math.min(87.0f, (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.25, horiz)))));

        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, true);
        flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);

        if (context.holeEscapeTicks < 3) {
            context.forwardInput = 0.0f;
            context.strafeInput = 0.0f;
            return true;
        }

        context.forwardInput = 0.0f;
        context.strafeInput = 0.0f;
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        org.bukkit.inventory.ItemStack charge = botPlayer.getInventory().getItem(slot);
        if (charge != null && charge.getType() == Material.WIND_CHARGE) {
            botPlayer.launchProjectile(org.bukkit.entity.WindCharge.class);
            charge.setAmount(charge.getAmount() - 1);
            botPlayer.getInventory().setItem(slot, charge.getAmount() > 0 ? charge : null);
        }

        context.holeEscapeCooldown = 40;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        return true;
    }

    private boolean isInHoleOrPit(Location loc, ServerPlayer handle) {
        org.bukkit.World w = loc.getWorld();
        if (w == null) return false;

        int baseY = feetBlockY(loc, handle);
        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        Block feet = w.getBlockAt(x, baseY, z);
        Block head = w.getBlockAt(x, baseY + 1, z);
        Block ground = w.getBlockAt(x, baseY - 1, z);
        if (feet.getType().isSolid() || head.getType().isSolid() || !ground.getType().isSolid()) return false;

        int wallCount = 0;
        if (isFullBlock(w.getBlockAt(x + 1, baseY, z))) wallCount++;
        if (isFullBlock(w.getBlockAt(x - 1, baseY, z))) wallCount++;
        if (isFullBlock(w.getBlockAt(x, baseY, z + 1))) wallCount++;
        if (isFullBlock(w.getBlockAt(x, baseY, z - 1))) wallCount++;

        if (wallCount >= 3) return true;

        if (context.holeCheckCooldown > 0) return context.holeCheckCached;

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.HOLE_ESCAPE);
        int highestSurface = baseY;
        int edgeWalls = 0;
        int visibleRim = 0;
        for (int dx = -HOLE_DETECTION_RADIUS; dx <= HOLE_DETECTION_RADIUS; dx++) {
            for (int dz = -HOLE_DETECTION_RADIUS; dz <= HOLE_DETECTION_RADIUS; dz++) {
                int top = w.getHighestBlockYAt(x + dx, z + dz);
                if (top > highestSurface) highestSurface = top;

                int manhattan = Math.abs(dx) + Math.abs(dz);
                if (manhattan <= 2 && top >= baseY + 1) edgeWalls++;
                if (manhattan >= 4 && top >= baseY + 2) visibleRim++;
            }
        }
        com.pvpbot.perf.BotProfiler.end();

        boolean broadPit = highestSurface - baseY >= 3 && (edgeWalls >= 4 || wallCount >= 1);
        boolean bowlPit = highestSurface - baseY >= 2 && visibleRim >= 6 && wallCount >= 1;

        context.holeCheckCached = broadPit || bowlPit;
        context.holeCheckCooldown = context.holeCheckCached ? 4 : 10;
        return context.holeCheckCached;
    }

    private boolean isDeepHoleOrPit(Location loc, ServerPlayer handle) {
        org.bukkit.World world = loc.getWorld();
        if (world == null) return false;

        int y = feetBlockY(loc, handle);
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int raisedWalls = 0;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] direction : directions) {
            int wallX = x + direction[0];
            int wallZ = z + direction[1];
            if (isFullBlock(world.getBlockAt(wallX, y, wallZ))
                    && isFullBlock(world.getBlockAt(wallX, y + 1, wallZ))
                    && isFullBlock(world.getBlockAt(wallX, y + 2, wallZ))) {
                raisedWalls++;
            }
        }
        return raisedWalls >= 3;
    }

    private boolean isEscapeStandable(Location loc) {
        org.bukkit.World w = loc.getWorld();
        if (w == null) return false;
        return isEscapeStandable(w, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private boolean isEscapeStandable(org.bukkit.World w, int x, int y, int z) {
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block below = w.getBlockAt(x, y - 1, z);

        Material feetType = feet.getType();
        Material headType = head.getType();
        Material belowType = below.getType();

        if (feetType.isSolid() || headType.isSolid()) return false;
        if (feetType == Material.WATER || feetType == Material.LAVA || feetType == Material.COBWEB) return false;
        if (headType == Material.WATER || headType == Material.LAVA || headType == Material.COBWEB) return false;

        return belowType.isSolid() || isClimbable(belowType) || isStairLike(belowType)
                || isClimbable(feetType) || isStairLike(feetType);
    }

    private boolean isStairLike(Material m) {
        String n = m.name();
        return n.contains("STAIR") || n.contains("SLAB") || n.contains("STEP");
    }

    private boolean isClimbable(Material m) {
        String n = m.name();
        return n.contains("LADDER") || n.contains("VINE") || n.contains("SCAFFOLD");
    }

    // =====================================================================
    // Travel to a fixed point in space: follow-leader, formation-march,
    // escort, guard-post return, and investigate all reduce to the same
    // question - "is the direct line blocked or too far, and if so is a real
    // path ready yet" - and used to each answer it with their own slightly
    // different copy. Those five copies drifting apart is what caused the
    // "walks into a wall/fence/lantern" and "checkchest routes through a
    // wall" bugs. approachPoint() is the one answer now; each caller only
    // supplies its own blocked/too-far conditions and keeps its own
    // look-at/arrival/speed-taper logic.
    // =====================================================================

    /**
     * @param fixedPoint true for a stationary destination (guard-post return,
     *                    investigate) - hold and wait for the real path
     *                    whenever EITHER blocked or too-far, since
     *                    canWalkStraightTo only probes ~15.5 blocks and
     *                    there's no urgency that justifies blind-marching
     *                    into whatever's past that range. false for a moving
     *                    destination (follow-leader, formation, escort) -
     *                    only hold when the direct line is actually confirmed
     *                    blocked; blind-forward while a path computes is
     *                    fine there and was deliberately kept after an
     *                    earlier regression made following feel laggy.
     */
    private enum Approach { HOLD, FOLLOWED, BLIND }

    private Approach approachPoint(Location from, Location dest,
                                   boolean blocked, boolean tooFar, boolean fixedPoint) {
        if (!blocked && !tooFar) return Approach.BLIND;

        if ((context.currentPath.isEmpty() || context.pathNodeIndex >= context.currentPath.size())
                && context.pathRecalcCooldown <= 0) {
            context.pathfindingController.calculatePathAsync(from, dest);
        }

        if (!context.currentPath.isEmpty() && context.pathNodeIndex < context.currentPath.size()) {
            followPath();
            return Approach.FOLLOWED;
        }

        if (blocked || fixedPoint) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return Approach.HOLD;
        }

        return Approach.BLIND;
    }

    private static final double FOLLOW_STOP_DISTANCE = 3.0;

    private static final double FOLLOW_RESUME_DISTANCE = 5.0;

    private static final double FOLLOW_PATH_DISTANCE = 18.0;

    public void handleFollowLeader(Player leader) {
        context.navBranch = "FOLLOW";
        Player botPlayer = context.bot.getBukkitPlayer();
        ServerPlayer handle = context.bot.getHandle();
        if (botPlayer == null || handle == null) return;

        if (leader.getWorld() != botPlayer.getWorld()) {
            Location rejoin = leader.getLocation().clone().add(
                    (Math.random() - 0.5) * 4.0, 0, (Math.random() - 0.5) * 4.0);
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.followHolding = false;
            context.bot.teleportTo(rejoin);
            return;
        }

        double distance = leader.getLocation().distance(botPlayer.getLocation());

        if (isBotInLava(botPlayer)) {
            context.forwardInput = 1.0f;
            context.strafeInput = 0f;
            requestJump();
            return;
        }

        if (context.followHolding) {
            if (distance > FOLLOW_RESUME_DISTANCE) context.followHolding = false;
        } else if (distance <= FOLLOW_STOP_DISTANCE) {
            context.followHolding = true;
        }

        if (context.followHolding) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            context.currentPath.clear();
            context.pathNodeIndex = 0;

            turnTowards(handle, leader.getLocation().getYaw());
            turnPitchTowards(handle, 0f);
            return;
        }

        boolean blocked = !context.combatController.hasLineOfSight(botPlayer, leader)
                || !canWalkStraightTo(leader.getLocation());
        boolean tooFar = distance > FOLLOW_PATH_DISTANCE;

        Approach approach = approachPoint(botPlayer.getLocation(), leader.getLocation(),
                blocked, tooFar, false);
        if (approach == Approach.FOLLOWED) return;
        if (approach == Approach.HOLD) {
            lookAt(leader.getEyeLocation(), context.settings.getAimNoise());
            return;
        }

        lookAt(leader.getEyeLocation(), context.settings.getAimNoise());
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
    }

    public boolean handleFormationMarch() {
        context.navBranch = "FORMATION";
        Player botPlayer = context.bot.getBukkitPlayer();
        ServerPlayer handle = context.bot.getHandle();
        Location slot = context.formationSlot;
        if (slot == null || botPlayer == null || handle == null) return false;

        if (context.formationTicks-- <= 0 || slot.getWorld() != botPlayer.getWorld()) {
            clearFormationOrder();
            return false;
        }

        double distance = slot.distance(botPlayer.getLocation());
        if (distance < 0.9) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            turnTowards(handle, slot.getYaw());
            turnPitchTowards(handle, 0f);
            if (distance < 0.6) clearFormationOrder();
            return true;
        }

        if (isBotInLava(botPlayer)) {
            context.forwardInput = 1.0f;
            context.strafeInput = 0f;
            requestJump();
            return true;
        }

        boolean blocked = handle.horizontalCollision || !canWalkStraightTo(slot);
        boolean tooFar = distance > 16.0;

        Approach approach = approachPoint(botPlayer.getLocation(), slot, blocked, tooFar, false);
        if (approach == Approach.FOLLOWED) return true;
        if (approach == Approach.HOLD) {
            lookAt(slot.clone().add(0, 1.6, 0), 0.0);
            return true;
        }

        lookAt(slot.clone().add(0, 1.6, 0), 0.0);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
        return true;
    }

    public void handleEscortSlot(Location slot, Player leader) {
        context.navBranch = "ESCORT";
        Player botPlayer = context.bot.getBukkitPlayer();
        ServerPlayer handle = context.bot.getHandle();
        if (botPlayer == null || handle == null || slot == null) return;

        if (slot.getWorld() != botPlayer.getWorld()) {
            context.bot.teleportTo(slot);
            return;
        }

        double distance = slot.distance(botPlayer.getLocation());

        if (isBotInLava(botPlayer)) {
            context.forwardInput = 1.0f;
            context.strafeInput = 0f;
            requestJump();
            return;
        }

        if (distance < 0.8) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            turnTowards(handle, leader != null ? leader.getLocation().getYaw() : slot.getYaw());
            turnPitchTowards(handle, 0f);
            return;
        }

        boolean blocked = handle.horizontalCollision || !canWalkStraightTo(slot);
        boolean tooFar = distance > 16.0;

        Approach approach = approachPoint(botPlayer.getLocation(), slot, blocked, tooFar, false);
        if (approach == Approach.FOLLOWED) return;
        if (approach == Approach.HOLD) {
            lookAt(slot.clone().add(0, 1.6, 0), 0.0);
            return;
        }

        lookAt(slot.clone().add(0, 1.6, 0), 0.0);

        context.forwardInput = distance > 2.0 ? 1.0f : 0.45f;
        context.strafeInput = 0f;
    }

    public void clearFormationOrder() {
        context.formationSlot = null;
        context.formationTicks = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    private static final double INVESTIGATE_RANGE = 28.0;

    public boolean handleInvestigate(Player botPlayer) {
        context.navBranch = "INVESTIGATE";
        if (!context.settings.isInvestigate()) return false;
        if (botPlayer == null) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        org.bukkit.World world = botPlayer.getWorld();

        if (context.investigateTarget != null) {
            if (context.investigateTicks-- <= 0
                    || context.investigateTarget.getWorld() != world) {
                endInvestigation();
                return false;
            }

            double dx = context.investigateTarget.getX() - handle.getX();
            double dz = context.investigateTarget.getZ() - handle.getZ();
            double horizSq = dx * dx + dz * dz;

            if (horizSq < 4.0) {
                context.forwardInput = 0f;
                context.strafeInput = 0f;
                context.investigateLookTicks++;
                if ((context.investigateLookTicks % 25) == 0) {
                    context.investigateScanYaw += (context.investigateLookTicks % 50 == 0) ? -85f : 85f;
                }
                easeYawTo(context.investigateScanYaw);
                easePitchTo(0f);
                if (context.investigateLookTicks > 70) endInvestigation();
                return true;
            }

            boolean blocked = !canWalkStraightTo(context.investigateTarget);
            boolean tooFar = horizSq > 100.0;

            Approach approach = approachPoint(botPlayer.getLocation(), context.investigateTarget,
                    blocked, tooFar, true);
            if (approach == Approach.FOLLOWED) return true;
            if (approach == Approach.HOLD) {
                float blockedYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                easeYawTo(blockedYaw);
                easePitchTo(0f);
                return true;
            }

            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            easeYawTo(yaw);
            easePitchTo(0f);
            context.forwardInput = 1.0f;
            context.strafeInput = 0f;
            return true;
        }

        if (context.investigateScanCooldown-- > 0) return false;
        context.investigateScanCooldown = 20;

        EnemyMemory.Sighting lead = context.enemyMemory.bestLead(
                world.getName(), handle.getX(), handle.getY(), handle.getZ(),
                context.tickCounter, INVESTIGATE_RANGE * INVESTIGATE_RANGE);
        if (lead == null) return false;

        context.enemyMemory.markInvestigated(lead);
        context.investigateTarget = lead.toLocation(world);
        context.investigateTicks = 400;
        context.investigateLookTicks = 0;
        context.investigateScanYaw = handle.getYRot();
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        return true;
    }

    private void endInvestigation() {
        context.investigateTarget = null;
        context.investigateTicks = 0;
        context.investigateLookTicks = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    // =====================================================================
    // Idle behavior: wandering (no orders at all) and guard-post (anchored,
    // with optional leader-escort leash). Neither one is a "travel to a
    // point" case above except for guard-post's return-to-anchor branch,
    // which shares approachPoint the same way.
    // =====================================================================

    public void handleIdleHold() {
        context.navBranch = "IDLE";
        ServerPlayer handle = context.bot.getHandle();
        Player botPlayer = context.bot.getBukkitPlayer();

        if (handle != null && botPlayer != null && handle.horizontalCollision) {
            context.wallBumpTicks++;
            if (context.wallBumpTicks > 10) {
                if (context.avoidTicks <= 0) {
                    context.avoidDir = chooseAvoidDir(handle, botPlayer.getLocation());
                    context.avoidTicks = 10;
                }
                context.forwardInput = 0.4f;
                context.strafeInput = 0.6f * context.avoidDir;
                return;
            }
        } else {
            context.wallBumpTicks = 0;
        }

        context.forwardInput = 0f;
        context.strafeInput = 0f;
    }

    public void handleIdleWandering() {
        context.navBranch = "IDLE";
        ServerPlayer handle = context.bot.getHandle();

        if (context.idlePhaseTicks-- <= 0) {
            context.idleWalking = !context.idleWalking;
            context.idlePhaseTicks = context.idleWalking
                    ? 60 + (int) (Math.random() * 80)
                    : 20 + (int) (Math.random() * 40);
            if (Math.random() < 0.4) context.idleStrafeDirection *= -1;
        }

        context.forwardInput = context.idleWalking ? 1.0f : 0f;
        context.suppressSprint = false;
        context.idleWanderTimer++;

        if (context.idleWalking && handle.onGround() && context.jumpCooldown <= 0
                && ThreadLocalRandom.current().nextInt(140) == 0) {
            requestJump();
        }

        if (context.wallBumpTicks > 0) context.idleStuckTicks++;
        else context.idleStuckTicks = 0;

        if (context.idleStuckTicks > 5 && context.idleWalking) {
            context.idleTargetYaw = handle.getYRot() + 90f + (float) (Math.random() * 180.0);
            context.idleStuckTicks = 0;

            context.idlePhaseTicks = Math.max(context.idlePhaseTicks, 40);
        }

        if (context.idleWanderTimer % 40 == 0 && Math.random() < 0.6) {
            Location glance = (Math.random() < 0.5) ? nearbyGlanceTarget() : null;
            if (glance != null) {
                double gx = glance.getX() - handle.getX();
                double gz = glance.getZ() - handle.getZ();
                context.idleTargetYaw = (float) Math.toDegrees(Math.atan2(-gx, gz));
            } else {
                context.idleTargetYaw = handle.getYRot() + (float) ((Math.random() - 0.5) * 120.0);
            }
        }
        turnTowards(handle, context.idleTargetYaw);

        if (context.idleWanderTimer % 60 == 0) {
            turnPitchTowards(handle, (float) ((Math.random() - 0.5) * 25.0));
        }

        if (context.forwardInput > 0) {
            context.strafeInput = (float) context.settings.getStrafeSpeed() * 0.5f * context.idleStrafeDirection;
        } else {
            context.strafeInput = 0f;
            context.forwardInput = 0f;
        }
    }

    public void handleGuardPost(Player botPlayer) {
        context.navBranch = "GUARD";
        Location anchor = context.guardAnchor;
        ServerPlayer h = context.bot.getHandle();
        if (anchor == null || h == null) {
            handleIdleWandering();
            return;
        }

        if (anchor.getWorld() != botPlayer.getWorld()) {
            handleIdleWandering();
            return;
        }

        double dx = anchor.getX() - h.getX();
        double dy = anchor.getY() - h.getY();
        double dz = anchor.getZ() - h.getZ();
        double horizSq = dx * dx + dz * dz;

        h.setShiftKeyDown(false);
        context.strafeInput = 0.0f;

        boolean escorting = context.guardMode == BotAIContext.GuardMode.LEADER;

        if (escorting && context.target != null
                && context.target.getWorld() == anchor.getWorld()) {
            double tx = context.target.getLocation().getX() - anchor.getX();
            double tz = context.target.getLocation().getZ() - anchor.getZ();
            double tl = Math.sqrt(tx * tx + tz * tz);
            if (tl > 0.5) {
                double step = Math.min(2.5, tl * 0.5);
                anchor = anchor.clone();
                anchor.setX(anchor.getX() + (tx / tl) * step);
                anchor.setZ(anchor.getZ() + (tz / tl) * step);
                dx = anchor.getX() - h.getX();
                dz = anchor.getZ() - h.getZ();
                horizSq = dx * dx + dz * dz;
            }
        }

        double tolerance = escorting ? 3.5 : 1.5;
        if (horizSq > tolerance * tolerance || Math.abs(dy) > 3.0) {
            context.guardReturning = true;

            boolean blocked = !canWalkStraightTo(anchor) || context.stuckTicks > 10;
            boolean tooFar = horizSq > 100.0;

            Approach approach = approachPoint(botPlayer.getLocation(), anchor, blocked, tooFar, true);
            if (approach == Approach.FOLLOWED) return;

            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            turnTowards(h, yaw);
            turnPitchTowards(h, 0.0f);

            if (approach == Approach.HOLD) return;

            context.forwardInput = horizSq > 9.0 ? 1.0f : 0.45f;

            if (dy > 0.75 && context.jumpCooldown <= 0) requestJump();
            return;
        }

        context.guardReturning = false;
        context.forwardInput = 0.0f;
        context.currentPath.clear();
        context.pathNodeIndex = 0;

        context.guardScanTicks++;

        Location glance = (context.guardScanTicks % 20 == 0) ? nearbyGlanceTarget() : null;
        if (glance != null) {
            lookAt(glance, 1.5);
            return;
        }

        float baseYaw = context.guardFacing;
        if (escorting && (dx * dx + dz * dz) > 0.25) {
            baseYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }

        if (context.guardScanTicks % 70 == 0) {
            context.guardScanYaw = (float) ((Math.random() - 0.5) * 130.0);
        }
        turnTowards(h, wrapDegrees(baseYaw + context.guardScanYaw));
        turnPitchTowards(h, (float) ((Math.random() - 0.5) * 4.0));
    }

    private Location nearbyGlanceTarget() {
        Player self = context.bot.getBukkitPlayer();
        ServerPlayer handle = context.bot.getHandle();
        if (self == null || handle == null) return null;

        double bx = handle.getX(), by = handle.getY(), bz = handle.getZ();
        com.pvpbot.perf.PlayerSnapshot.WorldView view =
                com.pvpbot.perf.PlayerSnapshot.forWorld(self.getWorld());

        Player best = null;
        double bestDistSq = 16.0 * 16.0;
        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null || p == self) continue;
            double dx = view.x[i] - bx;
            double dy = view.y[i] - by;
            double dz = view.z[i] - bz;
            if (Math.abs(dy) > 8.0) continue;
            double d = dx * dx + dz * dz;
            if (d < bestDistSq) { bestDistSq = d; best = p; }
        }
        return best == null ? null : best.getLocation();
    }

    // =====================================================================
    // Path-following core: the shared A*-path executor every travel method
    // above (and BuildController/FarmController/HealingController/
    // PatrolController externally) falls back to once a real path exists.
    // =====================================================================

    public void followPath() {
        context.navBranch = "PATH";
        if (context.pathNodeIndex >= context.currentPath.size()) {
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.pathComplete = false;
            context.forwardInput = 0.0f;
            context.strafeInput = 0.0f;
            return;
        }

        Location botLoc = context.bot.getBukkitPlayer().getLocation();
        ServerPlayer handle = context.bot.getHandle();

        if (context.avoidTicks > 0) {
            context.forwardInput = 0.2f;
            context.strafeInput = 0.8f * context.avoidDir;

            if (handle.onGround() && context.jumpCooldown <= 0) {
                requestJump();
            }

            if (context.avoidTicks <= 1 && handle.horizontalCollision) {
                context.avoidDir = chooseAvoidDir(handle, botLoc);
                context.avoidTicks = 10;
            }
            return;
        }

        if (handle.horizontalCollision) {
            context.pathCollideTicks++;
        } else {
            context.pathCollideTicks = 0;
        }

        if (context.pathRecenterTicks > 0) {
            context.pathRecenterTicks--;
            if (steerOntoPathSegment(handle, botLoc)) return;
            context.pathRecenterTicks = 0;
        }

        boolean blindAvoid = false;
        if (context.pathCollideTicks >= 2) {
            context.pathCollideTicks = 0;

            // Bumping the face of a one-block rise the path climbs is just
            // the step - jump it rather than treating it as an obstacle.
            Location ahead = context.currentPath.get(context.pathNodeIndex);
            int aheadRise = (int) Math.floor(ahead.getY() + 0.5) - feetBlockY(botLoc, handle);
            double aheadDx = ahead.getX() + 0.5 - botLoc.getX();
            double aheadDz = ahead.getZ() + 0.5 - botLoc.getZ();
            boolean stepAhead = aheadRise == 1
                    && aheadDx * aheadDx + aheadDz * aheadDz < 2.0 * 2.0;

            if (stepAhead) {
                if (handle.onGround() && context.jumpCooldown <= 0) requestJump();
            } else {
                if (context.pathBumpNodeIndex != context.pathNodeIndex) {
                    context.pathBumpNodeIndex = context.pathNodeIndex;
                    context.pathNodeBumps = 0;
                }
                context.pathNodeBumps++;

                if (context.pathNodeBumps >= 4) {
                    abandonPath("repeatedly blocked on the way to a waypoint");
                    return;
                }

                // Usually a snag means the bot drifted off the planned line
                // (knockback, a strafe, a carrot that shaved a corner) and
                // its shoulder caught a trunk/wall edge. The planned segment
                // itself was hitbox-swept when the path was built, so getting
                // back onto it is the reliable fix - far better than the old
                // blind strafe-and-jump, which could circle a tree forever.
                if (context.pathNodeBumps <= 2) {
                    context.pathRecenterTicks = 14;
                    if (steerOntoPathSegment(handle, botLoc)) return;
                    context.pathRecenterTicks = 0;
                }
                // Already on the line (or recentring didn't help): last
                // resort before giving up on the path is a side-step.
                blindAvoid = true;
            }
        }

        if (blindAvoid) {
            context.avoidDir = chooseAvoidDir(handle, botLoc);
            context.avoidTicks = 8;

            context.forwardInput = 0.0f;
            context.strafeInput = 0.7f * context.avoidDir;

            if (handle.onGround() && context.jumpCooldown <= 0) {
                requestJump();
            }
            return;
        }

        int botBlockY = feetBlockY(botLoc, handle);
        org.bukkit.World world = botLoc.getWorld();

        int bestIdx = context.pathNodeIndex;
        double bestScore = Double.MAX_VALUE;
        int scanEnd = Math.min(context.pathNodeIndex + 3, context.currentPath.size() - 1);

        int laneProbes = 0;
        for (int i = context.pathNodeIndex; i <= scanEnd; i++) {
            Location n = context.currentPath.get(i);
            double dx = (n.getX() + 0.5) - botLoc.getX();
            double dz = (n.getZ() + 0.5) - botLoc.getZ();
            double dy = n.getY() - botBlockY;

            double score = dx * dx + dz * dz + dy * dy * 4.0;
            if (score >= bestScore) continue;

            if (i > context.pathNodeIndex) {
                if (laneProbes >= 4) continue;
                laneProbes++;
                int nodeY = (int) Math.floor(n.getY() + 0.5);
                int endY = laneWalkable(world, botLoc.getX(), botBlockY, botLoc.getZ(),
                        n.getX() + 0.5, n.getZ() + 0.5, true);
                if (endY == Integer.MIN_VALUE || Math.abs(endY - nodeY) > 1) continue;
            }

            bestScore = score;
            bestIdx = i;
        }
        if (bestIdx > context.pathNodeIndex) context.pathNodeIndex = bestIdx;

        Location node = context.currentPath.get(context.pathNodeIndex);
        double nx = node.getX() + 0.5;
        double nz = node.getZ() + 0.5;
        double dx = nx - botLoc.getX();
        double dz = nz - botLoc.getZ();
        double dist2D = Math.sqrt(dx * dx + dz * dz);

        int nodeBlockY = (int) Math.floor(node.getY() + 0.5);
        int heightDiff = nodeBlockY - botBlockY;

        if (handle.onGround() && dist2D <= 2.0 && Math.abs(heightDiff) <= 1
                && laneWalkable(world, botLoc.getX(), botBlockY, botLoc.getZ(), nx, nz)
                == Integer.MIN_VALUE) {
            abandonPath("live path segment blocked");
            return;
        }

        if (heightDiff == 1 && dist2D < 1.6) {
            double stepTop = collisionTopAt(handle, nx, botBlockY, nz);
            if (stepTop > handle.maxUpStep()) {
                requestJump();
            }
        } else if (heightDiff >= 2 && dist2D < 1.6) {
            abandonPath("waypoint " + heightDiff + " blocks overhead");
            return;
        }

        if (heightDiff == 0 && dist2D > 1.5 && dist2D < 4.0) {
            org.bukkit.World w = botLoc.getWorld();
            int midX = (int) Math.floor(botLoc.getX() + dx * 0.5);
            int midZ = (int) Math.floor(botLoc.getZ() + dz * 0.5);
            if (!w.getBlockAt(midX, botBlockY - 1, midZ).getType().isSolid()
                    && handle.onGround()) {
                requestJump();
            }
        }

        if (dist2D < 1.1) {
            if (Math.abs(heightDiff) <= 1) {
                context.pathNodeIndex++;
                if (context.pathNodeIndex >= context.currentPath.size()) {
                    if (!context.pathComplete) {
                        // End of a partial route: this frontier didn't reach
                        // the goal, so make the next search prefer others.
                        context.mazeMemory.onFrontierReached(node.getBlockX(),
                                node.getBlockY(), node.getBlockZ(), context.tickCounter);
                    }
                    context.currentPath.clear();
                    context.pathNodeIndex = 0;
                    context.pathComplete = false;
                    context.forwardInput = 0.0f;
                    context.strafeInput = 0.0f;
                }
                return;
            }

            abandonPath("standing on top of an unreachable waypoint");
            return;
        }

        double carrotX;
        double carrotZ;
        {
            double ax = botLoc.getX(), az = botLoc.getZ();
            double remaining = PATH_LOOKAHEAD;
            carrotX = nx;
            carrotZ = nz;
            for (int i = context.pathNodeIndex; i < context.currentPath.size(); i++) {
                Location p = context.currentPath.get(i);
                double px = p.getX() + 0.5, pz = p.getZ() + 0.5;
                double segX = px - ax, segZ = pz - az;
                double seg = Math.sqrt(segX * segX + segZ * segZ);
                if (seg < 1.0e-4) continue;
                if (seg >= remaining) {
                    double t = remaining / seg;
                    carrotX = ax + segX * t;
                    carrotZ = az + segZ * t;
                    break;
                }
                remaining -= seg;
                ax = px;
                az = pz;
                carrotX = px;
                carrotZ = pz;
            }
        }

        if ((Math.abs(carrotX - nx) > 0.01 || Math.abs(carrotZ - nz) > 0.01)
                && laneWalkable(world, botLoc.getX(), botBlockY, botLoc.getZ(),
                carrotX, carrotZ, true) == Integer.MIN_VALUE) {
            carrotX = nx;
            carrotZ = nz;
        }

        double cdx = carrotX - botLoc.getX();
        double cdz = carrotZ - botLoc.getZ();
        double clen = Math.sqrt(cdx * cdx + cdz * cdz);
        if (clen < 1.0e-3) {
            cdx = dx;
            cdz = dz;
            clen = Math.max(dist2D, 1.0e-3);
        }
        cdx /= clen;
        cdz /= clen;

        boolean lastNode = context.pathNodeIndex >= context.currentPath.size() - 1;
        float speed = (lastNode && dist2D < 1.8) ? 0.7f : 1.0f;

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-cdx, cdz));

        if (!context.aimLockedOnTarget) {
            turnTowards(handle, desiredYaw);

            double eyeY = botLoc.getY() + handle.getEyeHeight();
            double nodeDy = (node.getY() + 1.0) - eyeY;
            float pitch = (float) Math.toDegrees(
                    -Math.atan2(nodeDy, Math.max(0.5, dist2D)));
            turnPitchTowards(handle, pitch);
        }

        float headingError = Math.abs(wrapDegrees(desiredYaw - handle.getYRot()));
        if (!context.aimLockedOnTarget && headingError > 75.0f) {
            context.forwardInput = 0.3f;
            context.strafeInput = 0.0f;
        } else {
            worldDirToInputs(handle, cdx, cdz, speed);
        }
    }

    private static final double PATH_LOOKAHEAD = 4.5;

    // Steer back onto the planned segment (previous node -> current node) by
    // heading for the closest point on it, nudged a little forward so the
    // bot rejoins the line moving the right way. Returns false once the bot
    // is already on the line (so recentring can't help) or when the anchor
    // isn't straight-line reachable.
    private boolean steerOntoPathSegment(ServerPlayer handle, Location botLoc) {
        if (context.pathNodeIndex >= context.currentPath.size()) return false;
        Location node = context.currentPath.get(context.pathNodeIndex);
        double bx = node.getX() + 0.5, bz = node.getZ() + 0.5;

        double ax, az;
        if (context.pathNodeIndex > 0) {
            Location prev = context.currentPath.get(context.pathNodeIndex - 1);
            ax = prev.getX() + 0.5;
            az = prev.getZ() + 0.5;
        } else {
            ax = Math.floor(botLoc.getX()) + 0.5;
            az = Math.floor(botLoc.getZ()) + 0.5;
        }

        double sx = bx - ax, sz = bz - az;
        double segLen2 = sx * sx + sz * sz;
        double t = segLen2 < 1.0e-6 ? 1.0
                : ((botLoc.getX() - ax) * sx + (botLoc.getZ() - az) * sz) / segLen2;
        t = Math.max(0.0, Math.min(1.0, t));
        double segLen = Math.sqrt(segLen2);
        if (segLen > 1.0e-3) t = Math.min(1.0, t + 0.35 / segLen);
        double tx = ax + sx * t, tz = az + sz * t;

        double dx = tx - botLoc.getX();
        double dz = tz - botLoc.getZ();
        double off = Math.sqrt(dx * dx + dz * dz);

        // Distance from the line itself (ignoring the forward nudge).
        double t0 = segLen2 < 1.0e-6 ? 1.0
                : Math.max(0.0, Math.min(1.0,
                ((botLoc.getX() - ax) * sx + (botLoc.getZ() - az) * sz) / segLen2));
        double lx = ax + sx * t0 - botLoc.getX();
        double lz = az + sz * t0 - botLoc.getZ();
        if (lx * lx + lz * lz < 0.18 * 0.18) return false;
        if (off < 1.0e-3) return false;

        int feetY = feetBlockY(botLoc, handle);
        if (laneWalkable(botLoc.getWorld(), botLoc.getX(), feetY, botLoc.getZ(), tx, tz, true)
                == Integer.MIN_VALUE) {
            return false;
        }

        context.navBranch = "PATH-RECENTER";
        dx /= off;
        dz /= off;
        if (!context.aimLockedOnTarget) {
            turnTowards(handle, (float) Math.toDegrees(Math.atan2(-dx, dz)));
        }
        worldDirToInputs(handle, dx, dz, 0.85f);
        return true;
    }

    private int laneWalkable(org.bukkit.World w,
                             double x0, int y0, double z0,
                             double x1, double z1) {
        return laneWalkable(w, x0, y0, z0, x1, z1, false);
    }

    // hitbox=true sweeps the bot's full 0.6-wide box along the lane instead
    // of just its centre point. Used when deciding whether it's safe to cut
    // a corner (skip ahead a node / aim at the look-ahead carrot): the centre
    // line can squeak past a tree trunk that the shoulders still hit.
    private int laneWalkable(org.bukkit.World w,
                             double x0, int y0, double z0,
                             double x1, double z1, boolean hitbox) {
        if (w == null) return Integer.MIN_VALUE;

        double dx = x1 - x0;
        double dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.05) return y0;

        int steps = (int) Math.ceil(dist / 0.5);
        if (steps > 32) return Integer.MIN_VALUE;
        dx /= steps;
        dz /= steps;

        int y = y0;
        int prevBx = (int) Math.floor(x0);
        int prevBz = (int) Math.floor(z0);
        for (int i = 1; i <= steps; i++) {
            int bx = (int) Math.floor(x0 + dx * i);
            int bz = (int) Math.floor(z0 + dz * i);

            int landed = Integer.MIN_VALUE;
            for (int off = 1; off >= -MAX_LANE_DROP; off--) {
                int fy = y + off;
                if (isStandableFloor(w.getBlockAt(bx, fy - 1, bz))
                        && isPassable(w.getBlockAt(bx, fy, bz))
                        && isPassable(w.getBlockAt(bx, fy + 1, bz))) {
                    landed = fy;
                    break;
                }
            }
            if (landed == Integer.MIN_VALUE) return Integer.MIN_VALUE;

            if (hitbox && !laneHitboxClear(w, x0 + dx * i, Math.max(landed, y), z0 + dz * i)) {
                return Integer.MIN_VALUE;
            }

            if (bx != prevBx && bz != prevBz) {
                boolean sideA = isPassable(w.getBlockAt(prevBx, landed, bz))
                        && isPassable(w.getBlockAt(prevBx, landed + 1, bz));
                boolean sideB = isPassable(w.getBlockAt(bx, landed, prevBz))
                        && isPassable(w.getBlockAt(bx, landed + 1, prevBz));
                if (!sideA && !sideB) return Integer.MIN_VALUE;
            }

            y = landed;
            prevBx = bx;
            prevBz = bz;
        }
        return y;
    }

    private static final int MAX_LANE_DROP = 3;

    private static final double LANE_HALF_WIDTH = 0.32;

    private static boolean laneHitboxClear(org.bukkit.World w, double px, int y, double pz) {
        int x0 = (int) Math.floor(px - LANE_HALF_WIDTH);
        int x1 = (int) Math.floor(px + LANE_HALF_WIDTH);
        int z0 = (int) Math.floor(pz - LANE_HALF_WIDTH);
        int z1 = (int) Math.floor(pz + LANE_HALF_WIDTH);
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                if (isPassable(w.getBlockAt(bx, y, bz))
                        && isPassable(w.getBlockAt(bx, y + 1, bz))) continue;
                // A shoulder over a one-block rise is a step, not a snag.
                if (isStandableFloor(w.getBlockAt(bx, y, bz))
                        && isPassable(w.getBlockAt(bx, y + 1, bz))
                        && isPassable(w.getBlockAt(bx, y + 2, bz))) continue;
                return false;
            }
        }
        return true;
    }

    private static boolean isPassable(Block b) {
        if (b == null) return false;
        Material m = b.getType();
        if (m.isAir()) return true;
        if (m == Material.LAVA || m == Material.COBWEB) return false;

        if (isThinBlocking(m)) return false;

        if (!m.isSolid()) return true;
        String n = m.name();
        if (n.startsWith("IRON_")) return false;
        if (n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE") || n.endsWith("_TRAPDOOR")) {
            org.bukkit.block.data.BlockData data = b.getBlockData();
            return data instanceof org.bukkit.block.data.Openable openable && openable.isOpen();
        }
        return false;
    }

    private static boolean isThinBlocking(Material m) {
        return com.pvpbot.nav.NavGrid.thinBlocking(m);
    }

    private int chooseAvoidDir(ServerPlayer handle, Location loc) {
        double rad = Math.toRadians(handle.getYRot());

        double sx = Math.cos(rad);
        double sz = Math.sin(rad);

        boolean plusOpen = sideOpen(handle, loc, sx, sz);
        boolean minusOpen = sideOpen(handle, loc, -sx, -sz);

        if (plusOpen && !minusOpen) return 1;
        if (minusOpen && !plusOpen) return -1;

        if (context.avoidDir != 0) return -context.avoidDir;
        return ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
    }

    private boolean sideOpen(ServerPlayer handle, Location loc, double dx, double dz) {
        org.bukkit.World w = loc.getWorld();
        if (w == null) return false;
        int baseY = feetBlockY(loc, handle);

        for (double d = 1.0; d <= 2.0; d += 1.0) {
            int bx = (int) Math.floor(loc.getX() + dx * d);
            int bz = (int) Math.floor(loc.getZ() + dz * d);
            if (!isPassable(w.getBlockAt(bx, baseY, bz))) return false;
            if (!isPassable(w.getBlockAt(bx, baseY + 1, bz))) return false;

            if (!isStandableFloor(w.getBlockAt(bx, baseY - 1, bz))
                    && !isStandableFloor(w.getBlockAt(bx, baseY - 2, bz))) return false;
        }
        return true;
    }

    private void abandonPath(String reason) {
        if (context.pathNodeIndex < context.currentPath.size()) {
            context.markNavFailure(context.currentPath.get(context.pathNodeIndex));
        }
        context.pathFailures = Math.min(context.pathFailures + 1, 4);
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        context.pathComplete = false;
        context.pathNodeStuckTicks = 0;
        context.lastPathNodeIndex = -1;
        context.pathCollideTicks = 0;
        context.forwardInput = 0.0f;
        context.strafeInput = 0.0f;

        context.pathRecalcCooldown = Math.min(context.pathRecalcCooldown, 4);
    }

    private float applyObstacleAvoidance(ServerPlayer handle, Player botPlayerRef,
                                         float forward, float strafe) {
        if (botPlayerRef == null) return forward;

        if (isEngagedInMelee(botPlayerRef)) {
            context.wallBumpTicks = 0;
            context.avoidTicks = 0;
            return forward;
        }

        if (context.bridging || context.inWater || !handle.onGround()) return forward;

        if (context.shouldJumpThisTick) return forward;

        if (forward < 0.1f) return forward;

        if (context.avoidTicks > 0) {
            context.avoidStrafeOverride = 0.85f * context.avoidDir;
            return Math.min(forward, 0.45f);
        }

        Location loc = botPlayerRef.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null) return forward;

        double radYaw = Math.toRadians(handle.getYRot());
        double dirX = -Math.sin(radYaw) * forward + Math.cos(radYaw) * strafe;
        double dirZ = Math.cos(radYaw) * forward + Math.sin(radYaw) * strafe;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.05) return forward;
        dirX /= len;
        dirZ /= len;

        int feetY = feetBlockY(loc, handle);

        double probe = 3.5;
        boolean onPath = !context.currentPath.isEmpty()
                && context.pathNodeIndex < context.currentPath.size();
        if (onPath) {
            Location n = context.currentPath.get(context.pathNodeIndex);
            double ndx = (n.getX() + 0.5) - loc.getX();
            double ndz = (n.getZ() + 0.5) - loc.getZ();
            probe = Math.min(probe, Math.max(1.0, Math.sqrt(ndx * ndx + ndz * ndz) + 0.4));
        }

        boolean clear = laneWalkable(w, loc.getX(), feetY, loc.getZ(),
                loc.getX() + dirX * probe, loc.getZ() + dirZ * probe) != Integer.MIN_VALUE;

        if (clear) {
            context.wallBumpTicks = 0;
            return forward;
        }

        context.wallBumpTicks++;

        if (tryOpenDoorAhead(handle, botPlayerRef, loc, dirX, dirZ)) {
            context.wallBumpTicks = 0;
            return forward;
        }

        if (context.wallBumpTicks < (onPath ? 3 : 1)) return forward;

        context.avoidDir = chooseAvoidDir(handle, loc);
        context.avoidTicks = 12;
        context.avoidStrafeOverride = 0.85f * context.avoidDir;
        return Math.min(forward, 0.45f);
    }

    private boolean tryOpenDoorAhead(ServerPlayer handle, Player botPlayer,
                                     Location loc, double dirX, double dirZ) {
        if (botPlayer == null || context.doorCooldown > 0) return false;
        if (!context.settings.isOpenDoors()) return false;
        org.bukkit.World w = loc.getWorld();
        if (w == null) return false;

        int feetY = feetBlockY(loc, handle);

        for (double d = 0.6; d <= 1.2; d += 0.6) {
            int bx = (int) Math.floor(loc.getX() + dirX * d);
            int bz = (int) Math.floor(loc.getZ() + dirZ * d);
            for (int dy = 0; dy <= 1; dy++) {
                Block b = w.getBlockAt(bx, feetY + dy, bz);
                if (openDoor(b, botPlayer)) return true;
            }
        }
        return false;
    }

    private boolean openDoor(Block b, Player botPlayer) {
        if (b == null) return false;
        Material m = b.getType();
        if (m.name().startsWith("IRON_")) return false;

        org.bukkit.block.data.BlockData data = b.getBlockData();
        if (!(data instanceof org.bukkit.block.data.Openable op)) return false;
        if (op.isOpen()) return false;

        try {
            org.bukkit.event.player.PlayerInteractEvent event =
                    new org.bukkit.event.player.PlayerInteractEvent(
                            botPlayer, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                            botPlayer.getInventory().getItemInMainHand(), b,
                            org.bukkit.block.BlockFace.UP);
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
            if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return false;
        } catch (Throwable ignored) {
            return false;
        }

        op.setOpen(true);
        b.setBlockData(op, true);

        try {
            b.getWorld().playSound(b.getLocation(),
                    m.name().contains("GATE")
                            ? org.bukkit.Sound.BLOCK_FENCE_GATE_OPEN
                            : org.bukkit.Sound.BLOCK_WOODEN_DOOR_OPEN,
                    1.0f, 1.0f);
        } catch (Throwable ignored) {
        }

        context.doorCooldown = 20;
        return true;
    }

    // =====================================================================
    // State sync & general stuck detection - distinct from the hole/bridge/
    // jump-specific stall trackers, this is the "not making any progress at
    // all" catch-all that kicks off a fresh repath or hole-escape.
    // =====================================================================

    public void syncState(ServerPlayer handle) {
        boolean currentSprinting = handle.isSprinting();
        if (currentSprinting != context.lastSprinting) {
            context.packetBroadcaster.broadcastEntityData();
            context.lastSprinting = currentSprinting;
        }
    }

    public void ensureAttributes() {
        var attr = context.bot.getHandle().getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null && attr.getBaseValue() != BASE_MOVE_SPEED) {
            attr.setBaseValue(BASE_MOVE_SPEED);
        }

        var armour = context.bot.getHandle().getAttribute(Attributes.ARMOR);
        if (armour != null && armour.getBaseValue() != 0.0) {
            armour.setBaseValue(0.0);
        }
        var toughness = context.bot.getHandle().getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null && toughness.getBaseValue() != 0.0) {
            toughness.setBaseValue(0.0);
        }
    }

    public void checkIfStuck(Location loc) {
        if (loc.getWorld() != null) {
            context.mazeMemory.observe(loc.getWorld(), loc.getBlockX(),
                    (int) Math.floor(loc.getY() + 0.01), loc.getBlockZ(), context.tickCounter);
        }

        if (context.lastPos == null) {
            context.lastPos = context.objectPool.cloneLocation(loc);
            return;
        }

        ServerPlayer handle = context.bot.getHandle();
        double distSq = Math.pow(loc.getX() - context.lastPos.getX(), 2)
                + Math.pow(loc.getZ() - context.lastPos.getZ(), 2);

        boolean tryingToMove = Math.abs(context.smoothedForwardInput) > 0.1f
                || Math.abs(context.smoothedStrafeInput) > 0.1f;
        boolean noProgress = distSq < 0.0025
                && (handle.onGround() || handle.horizontalCollision);

        if (tryingToMove && (noProgress || (handle.horizontalCollision && distSq < 0.01))) {
            context.stuckTicks++;
            if (context.stuckTicks > 4) {
                context.stuckTicks = 0;
                Player botPlayerRef = context.bot.getBukkitPlayer();

                boolean trappedInHole = botPlayerRef != null && isInHoleOrPit(loc, handle);
                if (!isEngagedInMelee(botPlayerRef) || trappedInHole) {
                    boolean stuckInWeb = botPlayerRef != null && isBotStuckInCobweb(botPlayerRef);

                    double aheadX = loc.getX() + handle.getDeltaMovement().x * 4.0;
                    double aheadZ = loc.getZ() + handle.getDeltaMovement().z * 4.0;
                    if (context.pathNodeIndex < context.currentPath.size()) {
                        Location node = context.currentPath.get(context.pathNodeIndex);
                        aheadX = node.getX() + 0.5;
                        aheadZ = node.getZ() + 0.5;
                    }
                    context.markNavFailure((int) Math.floor(aheadX), loc.getBlockY(),
                            (int) Math.floor(aheadZ));

                    context.currentPath.clear();
                    context.pathNodeIndex = 0;
                    context.pathComplete = false;
                    context.pathNodeStuckTicks = 0;
                    context.lastPathNodeIndex = -1;
                    context.lastPathNodeDistance = -1.0;

                    context.avoidDir = chooseAvoidDir(handle, loc);
                    context.avoidTicks = 12;

                    if (!stuckInWeb && trappedInHole) {
                        context.holeEscapeTicks = Math.max(context.holeEscapeTicks, 1);
                        context.holeEscapeCooldown = 0;
                        context.holeEscapeTarget = null;
                        context.currentPath.clear();
                        context.pathNodeIndex = 0;
                        requestJump();
                    }

                    if (!trappedInHole && context.target != null
                            && context.pathRecalcCooldown <= 0) {
                        context.pathfindingController.calculatePathAsync(loc, context.target.getLocation());
                        context.pathNodeIndex = 0;
                    }
                }
            }
        } else {
            context.stuckTicks = 0;
        }

        context.objectPool.releaseLocation(context.lastPos);
        context.lastPos = context.objectPool.cloneLocation(loc);
    }

    private boolean isBotStuckInCobweb(Player botPlayer) {
        return isInMaterialFeetOrHead(botPlayer.getLocation(), Material.COBWEB);
    }

    int feetBlockY(Location loc, ServerPlayer handle) {
        double y = loc.getY();
        int by = (int) Math.floor(y);
        double frac = y - by;
        if (handle.onGround() && frac > 0.95D) {
            by++;
        }
        return by;
    }

    private boolean isFullBlock(Block b) {
        if (b == null || !b.getType().isSolid()) return false;
        if (!b.getType().isOccluding()) return false;
        String n = b.getType().name();
        if (n.contains("SLAB") || n.contains("STAIRS") || n.contains("FENCE")
                || n.contains("WALL") || n.contains("CARPET") || n.contains("BANNER")
                || n.contains("DOOR") || n.contains("TRAPDOOR") || n.contains("CHEST")
                || n.contains("BED") || n.contains("SNOW") || n.contains("LADDER")
                || n.contains("VINE") || n.contains("SCAFFOLD")) {
            return false;
        }
        return true;
    }

    private boolean isBotInLava(Player botPlayer) {
        return isInMaterialFeetOrHead(botPlayer.getLocation(), Material.LAVA);
    }

    private Block blockAtOffset(Location loc, int dx, int dy, int dz) {
        return loc.getWorld().getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz);
    }

    private Block blockAtOffset(Location loc, double dx, double dy, double dz) {
        return loc.getWorld().getBlockAt(
                (int) Math.floor(loc.getX() + dx),
                (int) Math.floor(loc.getY() + dy),
                (int) Math.floor(loc.getZ() + dz)
        );
    }

    private boolean isInMaterialFeetOrHead(Location loc, Material material) {
        ServerPlayer handle = context.bot.getHandle();
        int feetY = feetBlockY(loc, handle);
        Block feet = loc.getWorld().getBlockAt(loc.getBlockX(), feetY, loc.getBlockZ());
        Block head = loc.getWorld().getBlockAt(loc.getBlockX(), feetY + 1, loc.getBlockZ());
        return feet.getType() == material || head.getType() == material;
    }

    // =====================================================================
    // Bridging: decide (shouldStartBridging) whether to cross a gap by
    // placing blocks/jumping/scaffolding, then execute it tick by tick
    // (handleBridging) across five modes - STANDARD, SCAFFOLD_UP, JUMP_OVER,
    // SPEED, REVERSE.
    // =====================================================================

    public boolean handleBridgingGate(Player botPlayer) {
        if (botPlayer == null) return false;
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        if (!context.settings.isBridging()) {
            if (context.bridging) stopBridging(context.bot.getHandle());
            return false;
        }

        if (context.bridging) {
            handleBridging(botPlayer);
            return true;
        }

        if (context.bridgeCheckCooldown > 0) return false;
        if (!shouldStartBridging(botPlayer)) return false;

        context.bridgePlaceCooldown = 0;
        beginBridging();
        handleBridging(botPlayer);
        return true;
    }

    private void beginBridging() {
        context.bridging = true;
        context.bridgeMode = context.proposedBridgeMode;
        context.bridgeStuckTicks = 0;
        context.bridgeCrossed = false;
        context.jumpOverSettleTicks = 0;
        context.bridgeModeTicks = 0;
        context.bridgeReachedEdge = false;
        context.bridgeStepX = 0;
        context.bridgeStepZ = 0;
        context.bridgeCheckCooldown = 0;
        context.bridgePlacementWindup =
                (context.bridgeMode == BotAIContext.BridgeMode.STANDARD) ? 3 : 0;
        context.lastBridgePos = null;
    }

    private void trackJumpFailure(ServerPlayer handle) {
        if (handle == null) return;

        int bx = (int) Math.floor(handle.getX());
        int bz = (int) Math.floor(handle.getZ());
        int by = (int) Math.floor(handle.getY());

        if (!handle.onGround()) {
            context.jumpAirborne = true;
            return;
        }

        if (!context.jumpAirborne) return;
        context.jumpAirborne = false;

        boolean samePlace = bx == context.jumpFromX
                && bz == context.jumpFromZ
                && by <= context.jumpFromY;

        if (samePlace) {
            if (++context.failedJumps >= 3) {
                context.failedJumps = 0;
                context.jumpBanTicks = 60;

                double yaw = Math.toRadians(handle.getYRot());
                int ax = (int) Math.floor(handle.getX() - Math.sin(yaw));
                int az = (int) Math.floor(handle.getZ() + Math.cos(yaw));
                context.markNavFailure(ax, by + 1, az);

                context.currentPath.clear();
                context.pathNodeIndex = 0;
                context.pathRecalcCooldown = 0;
            }
        } else {
            context.failedJumps = 0;
        }

        context.jumpFromX = bx;
        context.jumpFromY = by;
        context.jumpFromZ = bz;
    }

    private void trackTargetElevation(ServerPlayer handle) {
        Player t = context.target;
        if (t == null || handle == null) {
            context.targetElevatedTicks = 0;
            return;
        }
        if (t.getLocation().getY() - handle.getY() > 1.6) {
            if (context.targetElevatedTicks < 1000) context.targetElevatedTicks++;
        } else {
            context.targetElevatedTicks = 0;
        }
    }

    private static final int MAX_WALK_DROP = 3;

    private int floorBetween(org.bukkit.World w, int x, int z, int yTop, int yBottom) {
        for (int y = yTop; y >= yBottom; y--) {
            if (isStandableFloor(w.getBlockAt(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    private boolean shouldStartBridging(Player botPlayer) {
        double dxr, dzr, vertDelta;

        if (!context.settings.isBridging()) {
            context.bridgeWanted = false;
            return false;
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || !handle.onGround()) return false;
        Location loc = botPlayer.getLocation();

        if (!context.currentPath.isEmpty()
                && context.pathNodeIndex < context.currentPath.size()) {
            Location node = context.currentPath.get(context.pathNodeIndex);
            dxr = (node.getX() + 0.5) - loc.getX();
            dzr = (node.getZ() + 0.5) - loc.getZ();
            vertDelta = node.getY() - loc.getY();
        } else if (isValidTarget(context.target)) {
            Location tLoc = context.target.getLocation();
            dxr = tLoc.getX() - loc.getX();
            dzr = tLoc.getZ() - loc.getZ();
            vertDelta = tLoc.getY() - loc.getY();
        } else if (context.forwardInput > 0.1f) {
            double yaw = Math.toRadians(handle.getYRot());
            dxr = -Math.sin(yaw);
            dzr = Math.cos(yaw);
            vertDelta = 0.0;
        } else {
            context.bridgeWanted = false;
            return false;
        }

        context.bridgeWanted = false;

        if (!context.bridging
                && context.pathComplete
                && !context.currentPath.isEmpty()
                && context.pathNodeIndex < context.currentPath.size()) {
            return false;
        }

        double lenSq = dxr * dxr + dzr * dzr;
        if (lenSq < 0.0001) return false;

        double horizDist = Math.sqrt(lenSq);
        double invLen = 1.0 / horizDist;
        double dirX = dxr * invLen;
        double dirZ = dzr * invLen;

        int feetY = feetBlockY(loc, handle);
        org.bukkit.World w = loc.getWorld();

        if (!isStandableFloor(w.getBlockAt(loc.getBlockX(), feetY - 1, loc.getBlockZ()))) return false;

        boolean elevatedTargetIsSettled =
                context.target != null
                        && context.target.isOnGround()
                        && context.targetElevatedTicks > 30;

        if (context.smashThreatTicks <= 0 && elevatedTargetIsSettled
                && vertDelta > 1.6 && horizDist < 6.0) {
            int ax = (int) Math.floor(loc.getX() + dirX);
            int az = (int) Math.floor(loc.getZ() + dirZ);
            boolean groundAhead = isStandableFloor(w.getBlockAt(ax, feetY - 1, az));
            if (groundAhead && findBlockSlot(botPlayer) != -1) {
                context.bridgeEdgeDist = -1.0;
                context.bridgeLandingDist = -1.0;
                context.detectedGapLength = 0;
                context.proposedBridgeMode = BotAIContext.BridgeMode.SCAFFOLD_UP;
                context.bridgeWanted = true;
                return true;
            }
        }

        boolean nearEdgePossible = false;
        int probeFloorY = feetY - 1;
        for (double d = 1.0; d <= 3.0; d += 1.0) {
            int px = (int) Math.floor(loc.getX() + dirX * d);
            int pz = (int) Math.floor(loc.getZ() + dirZ * d);
            int found = floorBetween(w, px, pz, probeFloorY, probeFloorY - MAX_WALK_DROP);
            if (found == Integer.MIN_VALUE) {
                nearEdgePossible = true;
                break;
            }
            probeFloorY = found;
        }
        if (!nearEdgePossible) {
            context.bridgeEdgeDist = -1.0;
            context.bridgeLandingDist = -1.0;
            context.detectedGapLength = 0;
            return false;
        }

        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.BRIDGE_SCAN);
        try {
            final double STEP = 0.5;
            final double MAX_PROBE = 14.0;

            double edgeDist = -1.0;
            double landingDist = -1.0;
            int landingFloorY = Integer.MIN_VALUE;
            boolean lavaInPath = false;

            int floorY = feetY - 1;
            int edgeFloorY = floorY;

            for (double d = STEP; d <= MAX_PROBE && d <= horizDist + 2.0; d += STEP) {
                int px = (int) Math.floor(loc.getX() + dirX * d);
                int pz = (int) Math.floor(loc.getZ() + dirZ * d);

                Material groundM = w.getBlockAt(px, floorY, pz).getType();
                Material bodyM = w.getBlockAt(px, floorY + 1, pz).getType();

                if (groundM == Material.LAVA || bodyM == Material.LAVA) {
                    lavaInPath = true;
                    if (edgeDist < 0) edgeDist = d;
                    break;
                }

                if (edgeDist < 0) {
                    if (bodyM.isSolid()) return false;

                    int found = floorBetween(w, px, pz, floorY, floorY - MAX_WALK_DROP);
                    if (found == Integer.MIN_VALUE) {
                        edgeDist = d;
                        edgeFloorY = floorY;
                    } else {
                        floorY = found;
                    }
                } else {
                    int found = floorBetween(w, px, pz, edgeFloorY, edgeFloorY - MAX_WALK_DROP);
                    if (found != Integer.MIN_VALUE
                            && !w.getBlockAt(px, found + 1, pz).getType().isSolid()) {
                        landingDist = d;
                        landingFloorY = found;
                        break;
                    }
                }
            }

            context.bridgeEdgeDist = edgeDist;
            context.bridgeLandingDist = landingDist;
            context.detectedGapLength = (edgeDist < 0) ? 0
                    : (int) Math.ceil((landingDist < 0 ? MAX_PROBE : landingDist) - edgeDist);

            if (edgeDist < 0) return false;

            if (edgeDist > 3.0) return false;

            double gapLen = (landingDist < 0) ? Double.MAX_VALUE : (landingDist - edgeDist);

            int landingDrop = landingFloorY == Integer.MIN_VALUE
                    ? Integer.MAX_VALUE : edgeFloorY - landingFloorY;
            if (!lavaInPath && landingDist > 0.0 && gapLen <= 3.0
                    && landingDrop >= 1 && landingDrop <= MAX_WALK_DROP) {
                context.bridgeWanted = false;
                return false;
            }

            if (!lavaInPath && landingDist > 0.0 && gapLen <= 3.0 && landingDrop <= 0) {
                context.proposedBridgeMode = BotAIContext.BridgeMode.JUMP_OVER;
                return true;
            }

            if (landingDist < 0.0) {
                context.bridgeWanted = false;
                return false;
            }

            boolean lethalUnder = dropIsLethal(w,
                    loc.getX() + dirX * (edgeDist + 1.0),
                    loc.getZ() + dirZ * (edgeDist + 1.0),
                    feetY);

            boolean targetIsAbove = vertDelta > 1.3;
            boolean targetIsFarBelow = vertDelta < -3.0;

            if (!lavaInPath && !lethalUnder && !targetIsAbove
                    && landingDist > 0 && gapLen <= 3.0) {
                context.proposedBridgeMode = BotAIContext.BridgeMode.JUMP_OVER;
                return true;
            }

            if (lethalUnder && landingDist < 0 && targetIsFarBelow) {
                context.bridgeWanted = false;
                if (context.target != null) {
                    tryPearlDescend(botPlayer, handle, context.target.getLocation());
                }
                return false;
            }

            if (findBlockSlot(botPlayer) == -1) return false;

            context.proposedBridgeMode = context.settings.isSpeedBridging() && Math.abs(vertDelta) < 1.2
                    ? BotAIContext.BridgeMode.SPEED
                    : BotAIContext.BridgeMode.STANDARD;
            return true;
        } finally {
            com.pvpbot.perf.BotProfiler.end();
        }
    }

    private double[] bridgeHeading(Location loc) {
        double dxr, dzr, dy;

        if (!context.currentPath.isEmpty()
                && context.pathNodeIndex < context.currentPath.size()) {
            Location node = context.currentPath.get(context.pathNodeIndex);
            dxr = (node.getX() + 0.5) - loc.getX();
            dzr = (node.getZ() + 0.5) - loc.getZ();
            dy = node.getY() - loc.getY();
        } else if (isValidTarget(context.target)) {
            Location tLoc = context.target.getLocation();
            dxr = tLoc.getX() - loc.getX();
            dzr = tLoc.getZ() - loc.getZ();
            dy = tLoc.getY() - loc.getY();
        } else if (context.bridgeStepX != 0 || context.bridgeStepZ != 0) {
            dxr = context.bridgeStepX;
            dzr = context.bridgeStepZ;
            dy = 0.0;
        } else {
            return null;
        }

        if (dxr * dxr + dzr * dzr < 0.0001) return null;
        return new double[] { dxr, dzr, dy };
    }

    private void handleBridging(Player botPlayer) {
        context.navBranch = "BRIDGE";
        ServerPlayer h = context.bot.getHandle();
        Location loc = botPlayer.getLocation();

        context.inventoryController.releaseShield(botPlayer);

        double[] head = bridgeHeading(loc);
        if (head == null) {
            stopBridging(h);
            return;
        }
        double dxr = head[0];
        double dzr = head[1];
        double lenSq = dxr * dxr + dzr * dzr;
        double horizDist = Math.sqrt(lenSq);
        double invLen = 1.0 / horizDist;
        double dirX = dxr * invLen;
        double dirZ = dzr * invLen;
        float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

        org.bukkit.World w = loc.getWorld();
        int feetY = feetBlockY(loc, h);
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();

        if (context.bridgeStepX == 0 && context.bridgeStepZ == 0) {
            if (Math.abs(dirX) >= Math.abs(dirZ)) {
                context.bridgeStepX = dirX >= 0 ? 1 : -1;
                context.bridgeStepZ = 0;
            } else {
                context.bridgeStepX = 0;
                context.bridgeStepZ = dirZ >= 0 ? 1 : -1;
            }
        }
        int sx = context.bridgeStepX;
        int sz = context.bridgeStepZ;

        if (context.lastBridgePos != null
                && loc.getWorld() == context.lastBridgePos.getWorld()
                && loc.distanceSquared(context.lastBridgePos) < 0.02) {
            context.bridgeStuckTicks++;
        } else {
            context.bridgeStuckTicks = 0;
            context.lastBridgePos = loc.clone();
        }

        if (++context.bridgeModeTicks > 200) {
            stopBridging(h);
            context.bridgeCheckCooldown = 60;
            return;
        }

        if (context.bridgeStuckTicks > 80) {
            stopBridging(h);
            context.bridgeCheckCooldown = 40;
            if (context.pathRecalcCooldown <= 0 && isValidTarget(context.target)) {
                context.pathfindingController.calculatePathAsync(loc, context.target.getLocation());
            }
            return;
        }

        switch (context.bridgeMode) {
            case STANDARD -> {
                if (findBlockSlot(botPlayer) == -1) {
                    stopBridging(h);
                    context.bridgeCheckCooldown = 60;
                    return;
                }

                boolean standingSolid = isStandableFloor(w.getBlockAt(bx, feetY - 1, bz));
                boolean nextSolid = isStandableFloor(w.getBlockAt(bx + sx, feetY - 1, bz + sz));

                if (!nextSolid) context.bridgeReachedEdge = true;

                if (context.bridgeReachedEdge && standingSolid && nextSolid
                        && isStandableFloor(w.getBlockAt(bx + sx * 2, feetY - 1, bz + sz * 2))) {
                    context.bridgeCrossed = true;
                    stopBridging(h);
                    return;
                }

                context.strafeInput = 0.0f;

                Block aimSupport = w.getBlockAt(bx, feetY - 1, bz);
                Block aimPlace = w.getBlockAt(bx + sx, feetY - 1, bz + sz);
                lookAtPlacement(h, aimSupport, aimPlace);

                if (nextSolid) {
                    boolean nearEdge = !isStandableFloor(
                            w.getBlockAt(bx + sx * 2, feetY - 1, bz + sz * 2));
                    h.setShiftKeyDown(nearEdge);

                    context.forwardInput = 1.0f;

                    if (!context.bridgeReachedEdge) context.bridgePlacementWindup = 3;
                    return;
                }

                h.setShiftKeyDown(true);

                if (!standingSolid) {
                    context.forwardInput = -0.6f;
                    context.strafeInput = 0.0f;
                    if (++context.bridgeOverhangTicks > 25) {
                        context.bridgeOverhangTicks = 0;
                        stopBridging(h);
                        context.bridgeCheckCooldown = 30;
                    }
                    return;
                }
                context.bridgeOverhangTicks = 0;

                if (context.bridgePlacementWindup > 0) {
                    context.bridgePlacementWindup--;
                    context.forwardInput = 0.0f;
                    return;
                }

                if (context.bridgePlaceCooldown > 0) {
                    context.bridgePlaceCooldown--;
                    context.forwardInput = 0.0f;
                    return;
                }

                int slot = findBlockSlot(botPlayer);
                Block support = w.getBlockAt(bx, feetY - 1, bz);
                Block placeAt = w.getBlockAt(bx + sx, feetY - 1, bz + sz);

                if (placeAt.getType().isAir() && placeFromSlot(botPlayer, slot, placeAt, support)) {
                    context.bridgePlaceCooldown = 4;

                    context.forwardInput = 1.0f;
                } else {
                    stopBridging(h);
                    context.bridgeCheckCooldown = 40;
                }
            }

            case SCAFFOLD_UP -> {
                if (findBlockSlot(botPlayer) == -1) {
                    stopBridging(h);
                    context.bridgeCheckCooldown = 60;
                    return;
                }

                h.setShiftKeyDown(false);
                context.forwardInput = 0.0f;
                context.strafeInput = 0.0f;
                lookAtPlacement(h, w.getBlockAt(bx, feetY - 2, bz),
                        w.getBlockAt(bx, feetY - 1, bz));

                if (head[2] <= 1.0 || horizDist > 9.0) {
                    stopBridging(h);
                    return;
                }

                if (context.bridgePlaceCooldown > 0) { context.bridgePlaceCooldown--; return; }

                Block below = w.getBlockAt(bx, feetY - 1, bz);
                if (!below.getType().isAir()) {
                    if (h.onGround()) {
                        context.pillarBaseY = loc.getY();
                        requestJump();
                    }
                    return;
                }

                boolean highEnough = loc.getY() - context.pillarBaseY >= 0.95;
                boolean atApex = h.getDeltaMovement().y <= 0.08;
                if (!highEnough || !atApex) return;

                int slot = findBlockSlot(botPlayer);
                Block support = w.getBlockAt(bx, feetY - 2, bz);
                if (placeFromSlot(botPlayer, slot, below, support)) {
                    context.bridgePlaceCooldown = 6;
                } else {
                    stopBridging(h);
                    context.bridgeCheckCooldown = 40;
                }
            }

            case JUMP_OVER -> {
                h.setShiftKeyDown(false);
                context.forwardInput = 1.0f;
                context.strafeInput = 0.0f;
                turnTowards(h, yaw);

                boolean floorHere = isStandableFloor(w.getBlockAt(bx, feetY - 1, bz));
                boolean floorNext = isStandableFloor(w.getBlockAt(bx + sx, feetY - 1, bz + sz));

                if (h.onGround() && floorHere && !floorNext) {
                    context.bridgeReachedEdge = true;
                    if (!requestBridgeJump(h, sx, sz, 2.6)) {
                        context.forwardInput = 0.0f;
                        stopBridging(h);
                        context.bridgeCheckCooldown = 60;
                        if (context.target != null) {
                            tryPearlDescend(botPlayer, h, context.target.getLocation());
                        }
                        return;
                    }
                }

                if (context.bridgeReachedEdge && h.onGround() && floorHere && floorNext) {
                    if (++context.jumpOverSettleTicks > 3) {
                        context.bridgeCrossed = true;
                        stopBridging(h);
                        return;
                    }
                } else {
                    context.jumpOverSettleTicks = 0;
                }
            }

            case SPEED -> {
                if (findBlockSlot(botPlayer) == -1) {
                    stopBridging(h);
                    context.bridgeCheckCooldown = 60;
                    return;
                }

                boolean standingSolid = isStandableFloor(w.getBlockAt(bx, feetY - 1, bz));
                boolean nextSolid = isStandableFloor(w.getBlockAt(bx + sx, feetY - 1, bz + sz));

                if (!nextSolid) context.bridgeReachedEdge = true;

                if (context.bridgeReachedEdge && standingSolid && nextSolid
                        && isStandableFloor(w.getBlockAt(bx + sx * 2, feetY - 1, bz + sz * 2))) {
                    context.bridgeCrossed = true;
                    stopBridging(h);
                    return;
                }

                context.strafeInput = 0.0f;

                lookAtPlacement(h, w.getBlockAt(bx, feetY - 1, bz),
                        w.getBlockAt(bx + sx, feetY - 1, bz + sz));

                switch (context.speedBridgePhase) {
                    case 0 -> {
                        if (nextSolid) {
                            h.setShiftKeyDown(false);
                            context.forwardInput = 1.0f;
                            return;
                        }
                        h.setShiftKeyDown(true);
                        context.forwardInput = standingSolid ? 0.0f : -0.5f;
                        if (standingSolid) context.speedBridgePhase = 1;
                    }
                    case 1 -> {
                        h.setShiftKeyDown(true);
                        context.forwardInput = 0.0f;

                        if (context.bridgePlaceCooldown > 0) { context.bridgePlaceCooldown--; return; }

                        int slot = findBlockSlot(botPlayer);
                        Block support = w.getBlockAt(bx, feetY - 1, bz);
                        Block placeAt = w.getBlockAt(bx + sx, feetY - 1, bz + sz);

                        if (placeAt.getType().isAir() && placeFromSlot(botPlayer, slot, placeAt, support)) {
                            context.bridgePlaceCooldown = 2;
                            context.speedBridgePhase = 2;
                        } else {
                            stopBridging(h);
                            context.bridgeCheckCooldown = 40;
                        }
                    }
                    case 2 -> {
                        h.setShiftKeyDown(false);
                        context.forwardInput = 1.0f;
                        context.speedBridgePhase = 0;
                    }
                }
            }

            case REVERSE -> {
                if (findBlockSlot(botPlayer) == -1) {
                    stopBridging(h);
                    context.bridgeCheckCooldown = 60;
                    return;
                }

                h.setShiftKeyDown(true);
                context.forwardInput = -0.4f;
                context.strafeInput = 0.0f;

                turnTowards(h, wrapDegrees(yaw + 180f));
                turnPitchTowards(h, 75.0f);

                if (context.bridgePlaceCooldown > 0) { context.bridgePlaceCooldown--; return; }

                Block support = w.getBlockAt(bx, feetY - 1, bz);
                Block placeAt = w.getBlockAt(bx + sx, feetY - 1, bz + sz);
                if (!support.getType().isAir() && placeAt.getType().isAir()) {
                    int slot = findBlockSlot(botPlayer);
                    if (!placeFromSlot(botPlayer, slot, placeAt, support)) {
                        stopBridging(h);
                        context.bridgeCheckCooldown = 40;
                        return;
                    }
                }
                context.bridgePlaceCooldown = 5;
            }
        }
    }

    // =====================================================================
    // Flee-time gap assist (a quick one-block hop, distinct from the full
    // bridge state machine above) and the remaining shared block/geometry
    // primitives everything above relies on.
    // =====================================================================

    public boolean tryFleeGapAssist(Player botPlayer, float movementYaw) {
        if (botPlayer == null || !context.fleeing || context.fleeGapAssistCooldown > 0) {
            return false;
        }
        if (context.eating || context.drinkingPotionTimer > 0 || context.blockPlaceCooldown > 0) {
            return false;
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || !handle.onGround() || context.jumpCooldown > 0) return false;

        Location loc = botPlayer.getLocation();
        org.bukkit.World world = loc.getWorld();
        if (world == null || Float.isNaN(movementYaw)) return false;

        double radians = Math.toRadians(movementYaw);
        double dirX = -Math.sin(radians);
        double dirZ = Math.cos(radians);
        if (Math.min(Math.abs(dirX), Math.abs(dirZ)) > 0.65) return false;

        int stepX = Math.abs(dirX) >= Math.abs(dirZ) ? (dirX >= 0.0 ? 1 : -1) : 0;
        int stepZ = stepX == 0 ? (dirZ >= 0.0 ? 1 : -1) : 0;
        int feetY = feetBlockY(loc, handle);
        int blockX = loc.getBlockX();
        int blockZ = loc.getBlockZ();

        Block support = world.getBlockAt(blockX, feetY - 1, blockZ);
        if (!isStandableFloor(support)) return false;

        int landingDistance = -1;
        for (int distance = 1; distance <= 5; distance++) {
            int x = blockX + stepX * distance;
            int z = blockZ + stepZ * distance;
            if (!isPassable(world.getBlockAt(x, feetY, z))
                    || !isPassable(world.getBlockAt(x, feetY + 1, z))) {
                return false;
            }

            Block sameLevelFloor = world.getBlockAt(x, feetY - 1, z);
            Block lowerFloor = world.getBlockAt(x, feetY - 2, z);
            if (isStandableFloor(sameLevelFloor) || isStandableFloor(lowerFloor)) {
                landingDistance = distance;
                break;
            }

            Material floorType = sameLevelFloor.getType();
            if (floorType == Material.LAVA || floorType == Material.WATER
                    || floorType == Material.COBWEB) {
                return false;
            }
        }

        if (landingDistance < 4 || landingDistance > 5) return false;

        int slot = findBlockSlot(botPlayer);
        if (slot < 0) return false;

        Block platform = world.getBlockAt(blockX + stepX, feetY - 1, blockZ + stepZ);
        if (!platform.getType().isAir()) return false;

        int previousSlot = botPlayer.getInventory().getHeldItemSlot();
        if (!placeFromSlot(botPlayer, slot, platform, support)) return false;

        if (previousSlot != slot) {
            botPlayer.getInventory().setHeldItemSlot(previousSlot);
            context.packetBroadcaster.broadcastEquipment();
        }

        context.fleeGapAssistCooldown = 24 + ThreadLocalRandom.current().nextInt(8);
        context.blockPlaceCooldown = Math.max(context.blockPlaceCooldown, 8);
        context.forwardInput = 1.0f;
        context.strafeInput = 0.0f;
        turnTowards(handle, movementYaw);
        context.jumpCooldown = 9 + ThreadLocalRandom.current().nextInt(3);
        context.shouldJumpThisTick = true;
        return true;
    }

    private static boolean isStandableFloor(Block b) {
        if (b == null) return false;
        Material m = b.getType();

        if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE
                || m == Material.MAGMA_BLOCK || m == Material.CACTUS
                || m == Material.POWDER_SNOW || m == Material.POINTED_DRIPSTONE
                || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.SWEET_BERRY_BUSH || m == Material.WITHER_ROSE) {
            return false;
        }

        if (isThinBlocking(m)) return true;
        if (!m.isSolid()) return false;

        String n = m.name();
        return !n.endsWith("_FENCE") && !n.endsWith("_WALL");
    }

    public boolean canWalkStraightTo(Location dest) {
        Player botPlayer = context.bot.getBukkitPlayer();
        ServerPlayer handle = context.bot.getHandle();
        if (botPlayer == null || handle == null || dest == null) return true;

        Location loc = botPlayer.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null || dest.getWorld() != w) return false;

        if (context.tickCounter - context.laneCacheTick < 4
                && context.laneCacheX == dest.getBlockX()
                && context.laneCacheZ == dest.getBlockZ()
                && !Double.isNaN(context.laneCacheBotX)) {
            double mx = loc.getX() - context.laneCacheBotX;
            double mz = loc.getZ() - context.laneCacheBotZ;
            if (mx * mx + mz * mz < 2.25) return context.laneCacheClear;
        }

        double dx = dest.getX() - loc.getX();
        double dz = dest.getZ() - loc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        boolean clear;
        if (dist < 0.5) {
            clear = true;
        } else {
            double probe = Math.min(dist, 15.5);
            double ex = loc.getX() + (dx / dist) * probe;
            double ez = loc.getZ() + (dz / dist) * probe;
            int endY = laneWalkable(w, loc.getX(), feetBlockY(loc, handle), loc.getZ(), ex, ez);
            if (endY == Integer.MIN_VALUE) {
                clear = false;
            } else if (probe >= dist) {
                clear = Math.abs(endY - dest.getBlockY()) <= 2;
            } else {
                clear = true;
            }
        }

        context.laneCacheTick = context.tickCounter;
        context.laneCacheX = dest.getBlockX();
        context.laneCacheZ = dest.getBlockZ();
        context.laneCacheBotX = loc.getX();
        context.laneCacheBotZ = loc.getZ();
        context.laneCacheClear = clear;
        return clear;
    }

    private void stopBridging(ServerPlayer h) {
        context.bridging = false;
        context.pathComplete = false;
        context.bridgePlaceCooldown = 0;
        context.bridgeStuckTicks = 0;
        context.bridgeStepX = 0;
        context.bridgeStepZ = 0;
        context.bridgePlacementWindup = 0;
        context.jumpOverSettleTicks = 0;
        context.bridgeModeTicks = 0;
        context.bridgeReachedEdge = false;
        context.lastBridgePos = null;

        context.bridgeWanted = false;
        if (h != null) h.setShiftKeyDown(false);
    }

    boolean placeFromSlot(Player botPlayer, int slot, Block target, Block support) {
        if (slot < 0 || target == null) return false;
        if (!target.getType().isAir()) return false;

        if (support == null || !support.getType().isSolid()) return false;

        ItemStack blockItem = botPlayer.getInventory().getItem(slot);
        if (blockItem == null || blockItem.getAmount() <= 0) return false;

        Material mat = blockItem.getType();
        if (!InventoryController.isPlaceableBlock(blockItem)
                || GRAVITY_BLOCKS.contains(mat)) return false;

        org.bukkit.block.BlockState replaced = target.getState();
        org.bukkit.block.BlockFace face = support.getFace(target);
        if (face == null) face = org.bukkit.block.BlockFace.SELF;

        target.setType(mat, true);

        try {
            org.bukkit.block.data.BlockData bd = target.getBlockData();
            if (bd instanceof org.bukkit.block.data.type.Slab slab
                    && slab.getType() == org.bukkit.block.data.type.Slab.Type.BOTTOM) {
                slab.setType(org.bukkit.block.data.type.Slab.Type.TOP);
                target.setBlockData(slab, false);
            }
        } catch (Throwable ignored) {
        }

        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        target, replaced, support, blockItem.clone(),
                        botPlayer, true, org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled() || !event.canBuild()) {
            replaced.update(true, false);
            return false;
        }

        ItemStack offhandBackup = botPlayer.getInventory().getItemInOffHand().clone();
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();
        context.bot.getHandle().swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(context.bot.getHandle(), 0);

        blockItem.setAmount(blockItem.getAmount() - 1);
        if (blockItem.getAmount() <= 0) botPlayer.getInventory().setItem(slot, null);

        botPlayer.getInventory().setItemInOffHand(offhandBackup);
        context.packetBroadcaster.broadcastEquipment();

        try {
            target.getWorld().playSound(target.getLocation().add(0.5, 0.5, 0.5),
                    mat.createBlockData().getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static final java.util.EnumSet<Material> GRAVITY_BLOCKS =
            java.util.EnumSet.of(
                    Material.SAND, Material.RED_SAND, Material.GRAVEL,
                    Material.WHITE_CONCRETE_POWDER, Material.ORANGE_CONCRETE_POWDER,
                    Material.MAGENTA_CONCRETE_POWDER, Material.LIGHT_BLUE_CONCRETE_POWDER,
                    Material.YELLOW_CONCRETE_POWDER, Material.LIME_CONCRETE_POWDER,
                    Material.PINK_CONCRETE_POWDER, Material.GRAY_CONCRETE_POWDER,
                    Material.LIGHT_GRAY_CONCRETE_POWDER, Material.CYAN_CONCRETE_POWDER,
                    Material.PURPLE_CONCRETE_POWDER, Material.BLUE_CONCRETE_POWDER,
                    Material.BROWN_CONCRETE_POWDER, Material.GREEN_CONCRETE_POWDER,
                    Material.RED_CONCRETE_POWDER, Material.BLACK_CONCRETE_POWDER,
                    Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
                    Material.DRAGON_EGG, Material.POINTED_DRIPSTONE);

    private int findBlockSlot(Player p) {
        return context.inventoryController.findBlockSlot(p);
    }

    private boolean isEngagedInMelee(Player botPlayer) {
        if (botPlayer == null) return false;
        Player target = context.target;
        if (target == null || !isValidTarget(target)) return false;
        if (target.getWorld() != botPlayer.getWorld()) return false;
        double dist = botPlayer.getLocation().distance(target.getLocation());
        if (dist > context.settings.getReach() + 1.5) return false;
        return context.combatController.hasLineOfSight(botPlayer, target);
    }

    private boolean isValidTarget(Player p) {
        return TargetFilter.isEngageable(p, context.bot.getBukkitPlayer());
    }
}
