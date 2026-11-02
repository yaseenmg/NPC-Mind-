package com.yaseen.npcmind.client;

import com.yaseen.npcmind.entity.CompanionEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import com.yaseen.npcmind.api.Internal;

/** Player-style renderer with UUID-consistent default skin model and hand poses. */
@Internal
public final class CompanionRenderer extends HumanoidMobRenderer<CompanionEntity, PlayerModel<CompanionEntity>> {
    private final PlayerModel<CompanionEntity> defaultModel;
    private final PlayerModel<CompanionEntity> slimModel;

    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.defaultModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.addLayer(new ArrowLayer<>(context, this));
        // HumanoidMobRenderer does NOT add an armor layer (only players get one), so worn
        // companion armor was invisible. Add the standard player armor geometry explicitly.
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public void render(CompanionEntity entity, float entityYaw, float partialTick,
                       com.mojang.blaze3d.vertex.PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource buffer, int packedLight) {
        // DefaultPlayerSkin chooses Steve/Alex by UUID. Use the matching geometry as well;
        // using Alex's texture on the classic model produces visibly misaligned arms.
        boolean slim = "slim".equals(DefaultPlayerSkin.getSkinModelName(entity.getUUID()));
        this.model = slim ? this.slimModel : this.defaultModel;
        this.model.crouching = entity.isCrouching();
        this.model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        this.model.rightArmPose = HumanoidModel.ArmPose.EMPTY;

        ItemStack main = entity.getMainHandItem();
        if (!main.isEmpty()) {
            HumanoidModel.ArmPose pose = HumanoidModel.ArmPose.ITEM;
            if (main.is(Items.SPYGLASS) && entity.isUsingItem()) {
                pose = HumanoidModel.ArmPose.SPYGLASS;
            } else if (main.getItem() instanceof CrossbowItem) {
                pose = entity.isUsingItem()
                        ? HumanoidModel.ArmPose.CROSSBOW_CHARGE
                        : HumanoidModel.ArmPose.CROSSBOW_HOLD;
            } else if (main.getItem() instanceof BowItem && entity.isUsingItem()
                    && entity.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                pose = HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
            setMainHandPose(entity, pose);
        }

        ItemStack offhand = entity.getOffhandItem();
        if (isActiveOffhandShield(entity, offhand)) {
            setOffhandPose(entity, HumanoidModel.ArmPose.BLOCK);
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private static boolean isActiveOffhandShield(CompanionEntity entity, ItemStack offhand) {
        if (!(offhand.getItem() instanceof ShieldItem)) return false;
        if (entity.getUsedItemHand() != InteractionHand.OFF_HAND) return false;
        return entity.isUsingItem();
    }

    private void setMainHandPose(CompanionEntity entity, HumanoidModel.ArmPose pose) {
        if (entity.getMainArm() == HumanoidArm.RIGHT) this.model.rightArmPose = pose;
        else this.model.leftArmPose = pose;
    }

    private void setOffhandPose(CompanionEntity entity, HumanoidModel.ArmPose pose) {
        if (entity.getMainArm() == HumanoidArm.RIGHT) this.model.leftArmPose = pose;
        else this.model.rightArmPose = pose;
    }

    @Override
    public ResourceLocation getTextureLocation(CompanionEntity entity) {
        return DefaultPlayerSkin.getDefaultSkin(entity.getUUID());
    }
}
