package com.mineagent.engine.cognition;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.MineAgentEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Copies a bounded local situation from the live server world. */
final class SituationObserver {
    private static final double ACTOR_RADIUS = 24.0;
    private static final int MAX_ACTORS = 32;

    private SituationObserver() {}

    static SituationSnapshot capture(AgentPlayer companion, ServerPlayer player,
                                     ServerPlayer owner,
                                     SituationSnapshot.TaskObservation task,
                                     long gameTick) {
        SituationSnapshot.Position self = position(player);
        int armorPieces = 0;
        for (ItemStack armor : player.getArmorSlots()) {
            if (!armor.isEmpty()) armorPieces++;
        }
        SituationSnapshot.Vitals vitals = new SituationSnapshot.Vitals(
                player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.getAirSupply(),
                player.isInWater(), player.isInLava(), player.isOnFire(),
                player.onGround(), player.fallDistance,
                combatCapable(player.getMainHandItem()), armorPieces);

        SituationSnapshot.Environment environment = environment(player);
        SituationSnapshot.OwnerObservation ownerObservation = owner == null
                ? SituationSnapshot.OwnerObservation.absent()
                : new SituationSnapshot.OwnerObservation(true, position(owner),
                owner.getHealth(), owner.getMaxHealth(), owner.hurtTime > 0,
                owner.isSprinting(), activity(owner), distance(player, owner));

        List<SituationSnapshot.ActorObservation> actors = actors(
                companion, player, owner, self);
        return new SituationSnapshot(gameTick, self, vitals, environment,
                ownerObservation, task, actors);
    }

    private static List<SituationSnapshot.ActorObservation> actors(
            AgentPlayer companion, ServerPlayer player, ServerPlayer owner,
            SituationSnapshot.Position self) {
        AABB bounds = player.getBoundingBox().inflate(ACTOR_RADIUS);
        ArrayList<SituationSnapshot.ActorObservation> result = new ArrayList<>();
        for (Entity entity : player.level().getEntities(player, bounds)) {
            if (!entity.isAlive() || entity == player) continue;
            double distance = entity.distanceTo(player);
            SituationSnapshot.ActorKind kind = kind(companion, player, owner, entity);
            boolean targetingSelf = false;
            boolean targetingOwner = false;
            if (entity instanceof Mob mob) {
                targetingSelf = mob.getTarget() == player;
                targetingOwner = owner != null && mob.getTarget() == owner;
            }
            boolean approaching = approaching(entity, player);
            float health = entity instanceof LivingEntity living ? living.getHealth() : 0.0f;
            float maxHealth = entity instanceof LivingEntity living ? living.getMaxHealth() : 0.0f;
            result.add(new SituationSnapshot.ActorObservation(
                    entity.getUUID(), entityId(entity), kind, position(entity),
                    distance, health, maxHealth, player.hasLineOfSight(entity),
                    targetingSelf, targetingOwner, approaching, activity(entity)));
        }
        result.sort(Comparator
                .comparingInt((SituationSnapshot.ActorObservation actor) -> actorPriority(actor.kind()))
                .thenComparingDouble(SituationSnapshot.ActorObservation::distance));
        if (result.size() > MAX_ACTORS) {
            return List.copyOf(result.subList(0, MAX_ACTORS));
        }
        return List.copyOf(result);
    }

    private static SituationSnapshot.ActorKind kind(AgentPlayer companion,
                                                     ServerPlayer player,
                                                     ServerPlayer owner,
                                                     Entity entity) {
        if (owner != null && entity.getUUID().equals(owner.getUUID())) {
            return SituationSnapshot.ActorKind.OWNER;
        }
        if (entity instanceof Player otherPlayer) {
            if (MineAgentEngine.isCompanionOwnedBy(otherPlayer.getUUID(), companion.ownerUuid())) {
                return SituationSnapshot.ActorKind.ALLIED_COMPANION;
            }
            if (player.isAlliedTo(otherPlayer)) {
                return SituationSnapshot.ActorKind.SAME_TEAM_PLAYER;
            }
            return SituationSnapshot.ActorKind.OTHER_PLAYER;
        }
        if (entity instanceof Monster) return SituationSnapshot.ActorKind.HOSTILE_MOB;
        if (entity instanceof Projectile) return SituationSnapshot.ActorKind.PROJECTILE;
        if (entity instanceof ItemEntity) return SituationSnapshot.ActorKind.DROPPED_ITEM;
        if (entity instanceof LivingEntity) return SituationSnapshot.ActorKind.PASSIVE_MOB;
        return SituationSnapshot.ActorKind.OTHER;
    }

    private static SituationSnapshot.Environment environment(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        int hazards = 0;
        for (BlockPos pos : List.of(feet, feet.below(), feet.north(), feet.south(),
                feet.east(), feet.west())) {
            if (isHazard(player.level().getBlockState(pos))) hazards++;
        }
        int dropDepth = 0;
        for (int offset = 1; offset <= 12; offset++) {
            BlockPos below = feet.below(offset);
            if (!player.level().hasChunkAt(below)) break;
            if (!player.level().getBlockState(below)
                    .getCollisionShape(player.level(), below).isEmpty()) break;
            dropDepth++;
        }
        int projectiles = 0;
        int drops = 0;
        for (Entity entity : player.level().getEntities(player,
                player.getBoundingBox().inflate(10.0))) {
            if (entity instanceof Projectile && approaching(entity, player)) projectiles++;
            if (entity instanceof ItemEntity) drops++;
        }
        boolean breathable = !player.level().getFluidState(feet.above()).is(FluidTags.WATER);
        return new SituationSnapshot.Environment(hazards, dropDepth,
                projectiles, drops, breathable);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private static boolean combatCapable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var item = stack.getItem();
        return item instanceof SwordItem || item instanceof AxeItem
                || item instanceof BowItem || item instanceof CrossbowItem
                || item instanceof TridentItem || item instanceof TieredItem;
    }

    private static boolean approaching(Entity entity, Entity target) {
        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.lengthSqr() < 0.0025) return false;
        Vec3 toward = target.position().subtract(entity.position());
        return toward.lengthSqr() > 0.01 && velocity.dot(toward.normalize()) > 0.03;
    }

    private static SituationSnapshot.Position position(Entity entity) {
        return new SituationSnapshot.Position(
                entity.level().dimension().location().toString(),
                entity.getX(), entity.getY(), entity.getZ());
    }

    private static double distance(Entity first, Entity second) {
        return first.level() == second.level()
                ? first.distanceTo(second) : Double.POSITIVE_INFINITY;
    }

    private static String entityId(Entity entity) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? entity.getType().getDescriptionId() : id.toString();
    }

    private static String activity(Entity entity) {
        if (entity instanceof Player player) {
            if (player.isSleeping()) return "sleeping";
            if (player.hurtTime > 0) return "hurt";
            if (player.isUsingItem()) return "using_item";
            if (player.isSwimming()) return "swimming";
            if (player.isSprinting()) return "sprinting";
            if (player.isCrouching()) return "sneaking";
            return "active";
        }
        if (entity instanceof Mob mob) {
            if (mob.getTarget() != null) return "attacking";
            if (mob.isAggressive()) return "aggressive";
            if (mob.hurtTime > 0) return "hurt";
            return "idle";
        }
        if (entity instanceof Projectile) return "in_flight";
        if (entity instanceof ItemEntity) return "dropped";
        return "unknown";
    }

    private static int actorPriority(SituationSnapshot.ActorKind kind) {
        return switch (kind) {
            case HOSTILE_MOB, PROJECTILE -> 0;
            case OWNER, ALLIED_COMPANION, SAME_TEAM_PLAYER, OTHER_PLAYER -> 1;
            case DROPPED_ITEM -> 2;
            case PASSIVE_MOB, OTHER -> 3;
        };
    }
}
