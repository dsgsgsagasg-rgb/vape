package gg.vape.module.combat;

import gg.vape.Vape;
import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventPreTick;
import gg.vape.input.AttackKeyController;
import gg.vape.mapping.MappedClasses;
import gg.vape.module.Category;
import gg.vape.module.UtilityMod;
import gg.vape.module.control.SharedModuleControlClaims;
import gg.vape.module.utility.clutch.ClutchPlacementPathUtils;
import gg.vape.module.utility.clutch.PlacementTarget;
import gg.vape.notification.NotificationType;
import gg.vape.rotation.AdaptiveRotationController;
import gg.vape.rotation.RotationControlClaim;
import gg.vape.rotation.RotationManager;
import gg.vape.utils.BlockUtil;
import gg.vape.utils.MathUtil;
import gg.vape.utils.datas.BlockData;
import gg.vape.value.BooleanValue;
import gg.vape.value.NumberValue;
import gg.vape.wrapper.impl.Block;
import gg.vape.wrapper.impl.Entity;
import gg.vape.wrapper.impl.EntityOtherPlayerMP;
import gg.vape.wrapper.impl.EntityPlayerSP;
import gg.vape.wrapper.impl.EnumFacing;
import gg.vape.wrapper.impl.InventoryPlayer;
import gg.vape.wrapper.impl.ItemStack;
import gg.vape.wrapper.impl.KeyBinding;
import gg.vape.wrapper.impl.Minecraft;
import gg.vape.wrapper.impl.RayTraceResult;
import gg.vape.wrapper.impl.Vec3;
import gg.vape.wrapper.impl.Vec3i;
import gg.vape.wrapper.impl.World;
import gg.vape.config.ClientSettings;

public class HeadWeb
extends UtilityMod {
    private static final int STATE_PREPARE_SPRINT = 0;
    private static final int STATE_ATTACK = 1;
    private static final int STATE_TRACK_PEAK = 2;
    private static final int STATE_PLACE = 3;

    private final BooleanValue silentAim = BooleanValue.create(this, "Silent aim", true,
            "Aims without moving the camera when placing the web");
    private final NumberValue aimSpeed = NumberValue.create(this, "Aim speed", "#.#", "", 1.0, 5.0, 10.0, 0.1,
            "Speed of the aim when placing the web");
    private final NumberValue sprintDelay = NumberValue.create(this, "Sprint delay", "#", "ticks", 0.0, 3.0, 10.0, 1.0,
            "How long to force sprint before attacking");
    private final BooleanValue antiSuicide = BooleanValue.create(this, "Anti suicide", false,
            "Doesn't place a web when it would be placed in your own block");

    private final RotationControlClaim rotationClaim = SharedModuleControlClaims.rotation;

    private int state;
    private int ticks;
    private int originalSlot = -1;
    private int cobwebSlot = -1;
    private boolean releasePending;
    private EntityOtherPlayerMP target;
    private boolean risen;
    private double attackStartY;
    private double peakMaxY;
    private int placeX;
    private int placeY;
    private int placeZ;
    private boolean placeStarted;
    private boolean webClicked;
    private AdaptiveRotationController webRotationController;
    private BlockData webSupportBlock;
    private EnumFacing webFacing;
    private Vec3 webAimPoint;

    public HeadWeb() {
        super("HeadWeb", Category.COMBAT,
                "Sprint hits a target against a wall and places a web at their peak height");
        this.addValue(this.silentAim, this.aimSpeed, this.sprintDelay, this.antiSuicide);
        this.rotationClaim.setPriority(this, 6);
    }

    @Override
    public void onEnable() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNull() || Minecraft.currentScreen().isNotNull()) {
            this.setEnabled(false, true);
            return;
        }
        this.originalSlot = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().v();
        this.cobwebSlot = this.findHotbarSlot(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6(), "cobweb");
        if (this.cobwebSlot < 0) {
            NotificationType notificationType = NotificationType.WARNING;
            Vape.INSTANCE.getNotificationManager().show("HeadWeb", "Cobweb not in hotbar", notificationType, 3000L);
            this.setEnabled(false, true);
            return;
        }
        this.target = this.findTarget(player);
        if (this.target == null) {
            Vape.INSTANCE.getNotificationManager().show("HeadWeb", "No target against a wall", NotificationType.WARNING,
                    2000L);
            this.setEnabled(false, true);
            return;
        }
        this.ticks = 0;
        this.releasePending = false;
        this.placeStarted = false;
        this.webClicked = false;
        this.webRotationController = null;
        this.webSupportBlock = null;
        this.webFacing = null;
        this.webAimPoint = null;
        this.state = STATE_PREPARE_SPRINT;
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        EntityPlayerSP player = event.getThePlayer();
        if (this.releasePending) {
            AttackKeyController.releaseAttackKey();
            this.releasePending = false;
        }
        if (player.isNull() || Minecraft.currentScreen().isNotNull()) {
            this.finish();
            return;
        }
        switch (this.state) {
            case STATE_PREPARE_SPRINT: {
                this.tickPrepareSprint(player);
                break;
            }
            case STATE_ATTACK: {
                this.tickAttack(player);
                break;
            }
            case STATE_TRACK_PEAK: {
                this.tickTrackPeak();
                break;
            }
            case STATE_PLACE: {
                this.tickPlace(player);
                break;
            }
        }
    }

    private void tickPrepareSprint(EntityPlayerSP player) {
        KeyBinding.setKeyBindState(Minecraft.gameSettings().Y(), true);
        KeyBinding.setKeyBindState(Minecraft.gameSettings().r(), true);
        if (player.B$src$Z$f90iek() || ++this.ticks >= this.sprintDelay.getValue().intValue()) {
            this.ticks = 0;
            this.state = STATE_ATTACK;
        }
    }

    private void tickAttack(EntityPlayerSP player) {
        this.attackStartY = this.target.N();
        this.peakMaxY = this.target.N();
        this.risen = false;
        this.releasePending = AttackKeyController.requestSyntheticAttack(this);
        this.ticks = 0;
        this.state = STATE_TRACK_PEAK;
    }

    private void tickTrackPeak() {
        if (this.target == null || this.target.isNull()) {
            this.finish();
            return;
        }
        double currentY = this.target.N();
        if (!this.risen && currentY > this.attackStartY + 0.08) {
            this.risen = true;
        }
        if (this.risen && currentY > this.peakMaxY) {
            this.peakMaxY = currentY;
        }
        if (this.risen && currentY < this.peakMaxY - 0.08) {
            this.placeX = MathUtil.floor(this.target.z());
            this.placeY = MathUtil.floor(this.peakMaxY);
            this.placeZ = MathUtil.floor(this.target.h());
            this.ticks = 0;
            this.state = STATE_PLACE;
            return;
        }
        if (++this.ticks > 60) {
            this.finish();
        }
    }

    private void tickPlace(EntityPlayerSP player) {
        if (!this.placeStarted) {
            this.placeStarted = this.setupPlacement(player);
            if (!this.placeStarted) {
                this.finish();
                return;
            }
        }
        if (!this.aimPlacement()) {
            this.finish();
            return;
        }
        if (this.webRotationController == null || !this.webRotationController.isComplete()) {
            if (++this.ticks > 60) {
                this.finish();
            }
            return;
        }
        if (!this.isWebRayTraceValid()) {
            if (++this.ticks > 20) {
                this.finish();
            }
            return;
        }
        if (!this.webClicked) {
            this.ticks = 0;
            this.webClicked = true;
            player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.cobwebSlot);
            this.rightClick();
            return;
        }
        if (++this.ticks >= 2) {
            this.finish();
        }
    }

    private boolean setupPlacement(EntityPlayerSP player) {
        if (this.target == null || this.target.isNull() || this.cobwebSlot < 0) {
            return false;
        }
        World world = player.getWorld();
        if (world.isNull()) {
            return false;
        }
        if (this.antiSuicide.getEffectiveValue().booleanValue()) {
            int playerX = MathUtil.floor(player.z());
            int playerY = MathUtil.floor(player.N());
            int playerZ = MathUtil.floor(player.h());
            if (this.placeX == playerX && this.placeY == playerY && this.placeZ == playerZ) {
                return false;
            }
        }
        Block targetBlock = world.getBlockByPos(this.placeX, this.placeY, this.placeZ);
        if (targetBlock.isNull() || !BlockUtil.u(targetBlock)) {
            return false;
        }
        String targetBlockName = targetBlock.U();
        if (targetBlockName != null && targetBlockName.toLowerCase().contains("web")) {
            return false;
        }
        Vec3 eyePosition = Vec3.create(player.z(), player.N() + (double)player.X(), player.h());
        BlockData targetData = new BlockData(this.placeX, this.placeY, this.placeZ);
        int[][] offsets = new int[][]{{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
        EnumFacing[] facings = new EnumFacing[]{EnumFacing.F$src$Lgg_vape_wrapper_impl_EnumFacing_$glfxl5(), EnumFacing.B(), EnumFacing.g$src$Lgg_vape_wrapper_impl_EnumFacing_$1ii8mzu(), EnumFacing.X(), EnumFacing.M(), EnumFacing.w()};
        for (int index = 0; index < offsets.length; ++index) {
            BlockData supportData = targetData.y(offsets[index][0], offsets[index][1], offsets[index][2]);
            Block supportBlock = world.getBlockByPos(supportData.D(), supportData.B(), supportData.G());
            if (!supportBlock.isNotNull() || !BlockUtil.b(supportBlock) || BlockUtil.u(supportBlock)) continue;
            if (!ClutchPlacementPathUtils.isBlockFaceVisible(eyePosition, world, supportData, facings[index])) continue;
            PlacementTarget placementTarget = new PlacementTarget(supportData, facings[index]);
            Vec3 aimPoint = ClutchPlacementPathUtils.findBestPlacementHitPoint(
                    player, world, eyePosition, placementTarget, player.J(), player.V());
            if (aimPoint == null || aimPoint.isNull()) {
                aimPoint = this.faceCenter(supportData, facings[index]);
            }
            if (aimPoint == null || aimPoint.isNull()) continue;
            this.webSupportBlock = supportData;
            this.webFacing = facings[index];
            this.webAimPoint = aimPoint;
            return true;
        }
        return false;
    }

    private boolean aimPlacement() {
        if (!this.rotationClaim.isOwnedBy(this) && !this.rotationClaim.acquire(this, this.silentAim.getEffectiveValue())) {
            return false;
        }
        if (this.webAimPoint == null || this.webAimPoint.isNull()) {
            return false;
        }
        if (this.webRotationController == null) {
            AdaptiveRotationController controller = new AdaptiveRotationController(this.webAimPoint);
            controller.setNormalizeTargetYaw(false);
            controller.setRetainAfterCompletion(true);
            controller.setClampStepToRemaining(true);
            controller.setTolerance(0.1f);
            controller.setAngleBasedAcceleration(true);
            controller.setScaleAxesProportionally(true);
            controller.setLinearAcceleration(true);
            controller.setCubicAcceleration(true);
            controller.setRelativeMode(false);
            controller.setSpeed(((Double)this.aimSpeed.getValue()).floatValue());
            this.webRotationController = controller;
        }
        if (this.webRotationController instanceof AdaptiveRotationController) {
            ((AdaptiveRotationController)this.webRotationController).setTarget(this.webAimPoint);
        }
        this.webRotationController.setSpeed(((Double)this.aimSpeed.getValue()).floatValue());
        if (!this.webRotationController.equals(RotationManager.INSTANCE.getActiveController())) {
            RotationManager.INSTANCE.setController(this.webRotationController);
        }
        return true;
    }

    private boolean isWebRayTraceValid() {
        if (this.webSupportBlock == null) {
            return false;
        }
        RayTraceResult rayTrace = RotationManager.INSTANCE.getNormalReachRayTrace();
        if (rayTrace == null || rayTrace.isNull() || !rayTrace.isBlockHit()) {
            return false;
        }
        EnumFacing sideHit = rayTrace.getSideHit();
        if (sideHit == null || sideHit.isNull() || sideHit.Y() != this.webFacing.Y()) {
            return false;
        }
        return rayTrace.g() == this.webSupportBlock.D()
                && rayTrace.T() == this.webSupportBlock.B()
                && rayTrace.a$src$I$8nuo9d() == this.webSupportBlock.G();
    }

    private Vec3 faceCenter(BlockData blockData, EnumFacing facing) {
        Vec3i direction = facing.getDirectionVector();
        return Vec3.create((double)blockData.D() + 0.5 + (double)direction.getX() * 0.5,
                (double)blockData.B() + 0.5 + (double)direction.getY() * 0.5,
                (double)blockData.G() + 0.5 + (double)direction.getZ() * 0.5);
    }

    private int findHotbarSlot(InventoryPlayer inventory, String itemNameFragment) {
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inventory.c(slot);
            if (stack == null || stack.isNull() || stack.getItem() == null || stack.getItem().isNull()) continue;
            String itemName = stack.getItem().A();
            if (itemName != null && itemName.toLowerCase().contains(itemNameFragment)) {
                return slot;
            }
        }
        return -1;
    }

    private void rightClick() {
        KeyBinding keyBinding = Minecraft.gameSettings().b$src$Lgg_vape_wrapper_impl_KeyBinding_$1yi3362();
        KeyBinding.setKeyBindState(keyBinding, true);
        KeyBinding.onTick(keyBinding);
        KeyBinding.setKeyBindState(keyBinding, false);
    }

    private EntityOtherPlayerMP findTarget(EntityPlayerSP player) {
        RayTraceResult rayTrace = RotationManager.INSTANCE.getExtendedReachRayTrace();
        if (rayTrace == null || !rayTrace.isEntityHit()) {
            return null;
        }
        Entity entity = rayTrace.getEntity();
        if (entity == null || !entity.isNotNull() || !entity.isInstance(MappedClasses.lG)) {
            return null;
        }
        if (entity.S() == player.S() || !Vape.INSTANCE.getClientSettings().isValidTarget(entity, false)) {
            return null;
        }
        if (player.getDistanceToEntity(entity) > 4.5) {
            return null;
        }
        EntityOtherPlayerMP otherPlayer = new EntityOtherPlayerMP(entity.getObject());
        if (!this.hasWallBehind(player, otherPlayer)) {
            return null;
        }
        return otherPlayer;
    }

    private boolean hasWallBehind(EntityPlayerSP player, EntityOtherPlayerMP otherPlayer) {
        World world = player.getWorld();
        if (world.isNull()) {
            return false;
        }
        int targetX = MathUtil.floor(otherPlayer.z());
        int targetY = MathUtil.floor(otherPlayer.N());
        int targetZ = MathUtil.floor(otherPlayer.h());
        int[][] offsets = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            Block block = world.getBlockByPos(targetX + offset[0], targetY, targetZ + offset[2]);
            if (block.isNotNull() && BlockUtil.b(block)) {
                return true;
            }
        }
        return false;
    }

    private void finish() {
        if (this.releasePending) {
            AttackKeyController.releaseAttackKey();
            this.releasePending = false;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNotNull() && Minecraft.currentScreen().isNull()) {
            KeyBinding forwardKey = Minecraft.gameSettings().Y();
            if (!ClientSettings.isPhysicalKeyDown(forwardKey)) {
                KeyBinding.setKeyBindState(forwardKey, false);
            }
            KeyBinding sprintKey = Minecraft.gameSettings().r();
            if (!ClientSettings.isPhysicalKeyDown(sprintKey)) {
                KeyBinding.setKeyBindState(sprintKey, false);
            }
            if (this.originalSlot >= 0) {
                player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
            }
        }
        if (this.webRotationController != null) {
            RotationManager.INSTANCE.releaseController(this.webRotationController);
        }
        this.rotationClaim.release(this);
        this.webRotationController = null;
        this.target = null;
        this.webSupportBlock = null;
        this.webFacing = null;
        this.webAimPoint = null;
        this.originalSlot = -1;
        this.cobwebSlot = -1;
        this.setEnabled(false, true);
    }

    @Override
    public void onDisable() {
        if (this.releasePending) {
            AttackKeyController.releaseAttackKey();
            this.releasePending = false;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNotNull()) {
            KeyBinding forwardKey = Minecraft.gameSettings().Y();
            if (!ClientSettings.isPhysicalKeyDown(forwardKey)) {
                KeyBinding.setKeyBindState(forwardKey, false);
            }
            KeyBinding sprintKey = Minecraft.gameSettings().r();
            if (!ClientSettings.isPhysicalKeyDown(sprintKey)) {
                KeyBinding.setKeyBindState(sprintKey, false);
            }
            if (this.originalSlot >= 0) {
                player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
            }
        }
        if (this.webRotationController != null) {
            RotationManager.INSTANCE.releaseController(this.webRotationController);
        }
        this.rotationClaim.release(this);
        this.webRotationController = null;
        this.target = null;
        this.webSupportBlock = null;
        this.webFacing = null;
        this.webAimPoint = null;
        this.originalSlot = -1;
        this.cobwebSlot = -1;
    }
}