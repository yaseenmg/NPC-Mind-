package com.yaseen.npcmind.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import com.yaseen.npcmind.api.Internal;

/**
 * Vanilla {@link ThrownEnderpearl} behavior with player parity for non-player owners.
 *
 * <p>Vanilla 1.20.1 teleports only {@code ServerPlayer} owners, so a pearl thrown by a
 * companion mob silently disappeared on impact and {@code throw_ender_pearl} had no effect.
 * This subclass replicates the vanilla teleport path for any living owner: endermite chance,
 * teleport, fall damage and the TELEPORT game event, then discards the pearl like vanilla.</p>
 */
@Internal
public class CompanionEnderpearl extends ThrownEnderpearl {
    public CompanionEnderpearl(EntityType<? extends CompanionEnderpearl> type, Level level) {
        super(type, level);
    }

    public CompanionEnderpearl(Level level, LivingEntity owner) {
        super(level, owner);
    }

    public CompanionEnderpearl(EntityType<? extends CompanionEnderpearl> type, Level level, LivingEntity owner) {
        super(type, level);
        this.setOwner(owner);
    }

    @Override
    protected void onHit(HitResult result) {
        HitResult.Type type = result.getType();
        if (type == HitResult.Type.ENTITY) {
            ((EntityHitResult) result).getEntity().hurt(this.damageSources().thrown(this, this.getOwner()), 0.0F);
        }
        if (this.level().isClientSide || this.isRemoved()) return;

        // Player parity: a 5% endermite chance while mob spawning is enabled.
        if (this.random.nextFloat() < 0.05F && this.level().getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            Endermite endermite = EntityType.ENDERMITE.create(this.level());
            if (endermite != null) {
                endermite.moveTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot());
                this.level().addFreshEntity(endermite);
            }
        }

        if (this.getOwner() instanceof ServerPlayer serverPlayer) {
            // Reachable only when a host mod reuses this entity for a player; mirror vanilla.
            if (serverPlayer.connection.isAcceptingMessages()) {
                serverPlayer.teleportTo(this.getX(), this.getY(), this.getZ());
                serverPlayer.hurt(this.damageSources().fall(), 5.0F);
                serverPlayer.resetFallDistance();
            }
        } else if (this.getOwner() instanceof LivingEntity living && living.isAlive() && !living.isRemoved()) {
            living.teleportTo(this.getX(), this.getY(), this.getZ());
            // A teleport is a position discontinuity: leaving sprint armed made the next
            // aiStep charge a whole jump-distance exhaustion spike (moved * 0.1) against
            // food that was never walked off. Mirror the follow-teleport reset here.
            living.setSprinting(false);
            if (living instanceof CompanionEntity companion) companion.resetSprintExhaustionAnchor();
            living.hurt(this.damageSources().fall(), 5.0F);
            living.resetFallDistance();
        }

        this.level().gameEvent(GameEvent.TELEPORT, this.blockPosition(),
                GameEvent.Context.of(this.getOwner() == null ? this : this.getOwner()));
        this.discard();
    }
}
