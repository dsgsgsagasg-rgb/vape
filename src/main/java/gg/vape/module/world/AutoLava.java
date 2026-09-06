package gg.vape.module.world;

import gg.vape.Vape;
import gg.vape.event.EventHandler;
import gg.vape.event.EventPriority;
import gg.vape.event.impl.EventPreTick;
import gg.vape.event.impl.EventRightClickMouse;
import gg.vape.mapping.ItemMappingEntry;
import gg.vape.mapping.MappedClasses;
import gg.vape.module.Category;
import gg.vape.module.UtilityMod;
import gg.vape.module.blatant.blockin.BlockPlacementUtility;
import gg.vape.module.control.SharedModuleControlClaims;
import gg.vape.module.utility.clutch.ClutchPlacementPathUtils;
import gg.vape.module.utility.clutch.PlacementTarget;
import gg.vape.notification.NotificationType;
import gg.vape.rotation.AdaptiveRotationController;
import gg.vape.rotation.RotationControlClaim;
import gg.vape.rotation.RotationManager;
import gg.vape.utils.BlockUtil;
import gg.vape.utils.MathUtil;
import gg.vape.utils.TimerUtil;
import gg.vape.utils.datas.BlockData;
import gg.vape.value.BooleanValue;
import gg.vape.value.NumberValue;
import gg.vape.wrapper.impl.Block;
import gg.vape.wrapper.impl.BlockPos;
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
import java.util.List;

public class AutoLava
extends UtilityMod {
    private static final int STATE_PLACE = 0;
    private static final int STATE_PICKUP = 1;
    private static final float PLACEMENT_AIM_SPEED = 200.0f;
    private static final float PICKUP_AIM_SPEED = 120.0f;
    private static final double MAX_TARGET_DISTANCE = 4.5;

    private final BooleanValue silentAim = BooleanValue.create(this, "Silent aim", true,
            "Aims without moving the camera when placing the lava");
    private final BooleanValue takeBackLava = BooleanValue.create(this, "Take back lava", true,
            "Collects the placed lava back into the bucket after a delay");
    private final NumberValue takeBackDelay = NumberValue.create(this, "Take back delay", "#.#", "s", 0.0, 2.0, 15.0, 0.5,
            "How long to wait before picking the lava back up");

    private final RotationControlClaim rotationClaim = SharedModuleControlClaims.rotation;

    private int state;
    private int ticks;
    private int originalSlot = -1;
    private int lavaSlot = -1;
    private EntityOtherPlayerMP target;
    private int placeX;
    private int placeY;
    private int placeZ;
    private boolean placeStarted;
    private boolean lavaClicked;
    private AdaptiveRotationController lavaRotationController;
    private BlockData lavaSupportBlock;
    private EnumFacing lavaFacing;
    private Vec3 lavaAimPoint;
    private TimerUtil pickupTimer;
    private boolean pickupClicked;
    private AdaptiveRotationController pickupRotationController;
    private RayTraceResult pickupOverrideRayTrace;
    private boolean pickupClickPending;

    public AutoLava() {
        super("AutoLava", Category.WORLD,
                "Places a lava bucket at the nearest enemy's feet");
        this.addValue(this.silentAim, this.takeBackLava, this.takeBackDelay);
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
        this.lavaSlot = this.findHotbarLavaSlot(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6());
        if (this.lavaSlot < 0) {
            Vape.INSTANCE.getNotificationManager().show("AutoLava", "Lava bucket not in hotbar",
                    NotificationType.WARNING, 3000L);
            this.setEnabled(false, true);
            return;
        }
        this.target = this.findTarget(player);
        if (this.target == null) {
            Vape.INSTANCE.getNotificationManager().show("AutoLava", "No target", NotificationType.WARNING,
                    2000L);
            this.setEnabled(false, true);
            return;
        }
        if (!this.computePlacementCell(player)) {
            Vape.INSTANCE.getNotificationManager().show("AutoLava", "No spot to place the lava",
                    NotificationType.WARNING, 2000L);
            this.setEnabled(false, true);
            return;
        }
        this.ticks = 0;
        this.placeStarted = false;
        this.lavaClicked = false;
        this.lavaRotationController = null;
        this.lavaSupportBlock = null;
        this.lavaFacing = null;
        this.lavaAimPoint = null;
        this.pickupTimer = new TimerUtil();
        this.pickupTimer.reset();
        this.pickupClicked = false;
        this.pickupRotationController = null;
        this.pickupOverrideRayTrace = null;
        this.pickupClickPending = false;
        this.state = STATE_PLACE;
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        EntityPlayerSP player = event.getThePlayer();
        if (player.isNull() || Minecraft.currentScreen().isNotNull()) {
            this.finish();
            return;
        }
        switch (this.state) {
            case STATE_PLACE: {
                this.tickPlace(player);
                break;
            }
            case STATE_PICKUP: {
                this.tickPickup(player);
                break;
            }
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
        if (this.lavaRotationController == null || !this.lavaRotationController.isComplete()) {
            if (++this.ticks > 15) {
                this.finish();
            }
            return;
        }
        if (!this.isLavaRayTraceValid()) {
            if (++this.ticks > 10) {
                this.finish();
            }
            return;
        }
        if (!this.lavaClicked) {
            this.lavaClicked = true;
            this.ticks = 0;
            player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.lavaSlot);
            this.rightClick();
            return;
        }
        if (++this.ticks >= 2) {
            this.startPickup(player);
        }
    }

    private void startPickup(EntityPlayerSP player) {
        if (!this.takeBackLava.getEffectiveValue() || this.target == null || this.target.isNull()) {
            this.finish();
            return;
        }
        this.pickupTimer = new TimerUtil();
        this.pickupTimer.reset();
        this.pickupClicked = false;
        this.pickupRotationController = null;
        this.pickupOverrideRayTrace = null;
        this.pickupClickPending = false;
        this.ticks = 0;
        this.state = STATE_PICKUP;
    }

    private void tickPickup(EntityPlayerSP player) {
        if (player.i((double)this.placeX + 0.5, (double)this.placeY + 0.5, (double)this.placeZ + 0.5)
                > MAX_TARGET_DISTANCE) {
            this.finish();
            return;
        }
        long delayMs = (long)(this.takeBackDelay.getValue().doubleValue() * 1000.0);
        if (!this.pickupClicked && this.pickupTimer != null
                && delayMs > 0L && !this.pickupTimer.hasTimeElapsed(delayMs)) {
            return;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        int heldSlot = inventory.v();
        ItemStack heldStack = inventory.c(heldSlot);
        boolean hasBucket = heldStack != null && heldStack.isNotNull() && BlockPlacementUtility.getEmptyBucketItem()
                .equals(Vape.INSTANCE.getItemStackResolver().resolve(heldStack));
        if (!hasBucket) {
            int bucketSlot = this.findEmptyBucketSlot(inventory);
            if (bucketSlot < 0) {
                this.finish();
                return;
            }
            inventory.g(bucketSlot);
        }
        if (!this.aimPickup()) {
            if (++this.ticks > 60) {
                this.finish();
            }
            return;
        }
        if (this.pickupRotationController == null || !this.pickupRotationController.isComplete()) {
            if (++this.ticks > 60) {
                this.finish();
            }
            return;
        }
        RayTraceResult lavaHit = RotationManager.INSTANCE.rayTraceUsingManagedRotation(true);
        if (lavaHit == null || lavaHit.isNull() || !this.isPickupLavaHit(lavaHit)) {
            if (++this.ticks > 60) {
                this.finish();
            }
            return;
        }
        if (!this.pickupClicked) {
            this.pickupClicked = true;
            this.ticks = 0;
            this.pickupOverrideRayTrace = lavaHit;
            this.pickupClickPending = true;
            Minecraft.O(lavaHit);
            this.rightClick();
            return;
        }
        if (++this.ticks >= 2) {
            this.finish();
        }
    }

    private boolean computePlacementCell(EntityPlayerSP player) {
        World world = player.getWorld();
        if (world.isNull()) {
            return false;
        }
        if (this.target == null || this.target.isNull()) {
            return false;
        }
        int baseX = MathUtil.floor(this.target.z());
        int baseY = MathUtil.floor(this.target.N());
        int baseZ = MathUtil.floor(this.target.h());
        int[] candidates = new int[]{baseY, baseY + 1, baseY - 1};
        for (int candidateY : candidates) {
            Block block = world.getBlockByPos(baseX, candidateY, baseZ);
            if (block.isNull()) {
                continue;
            }
            if (this.isWebBlock(block)) {
                this.placeX = baseX;
                this.placeY = candidateY;
                this.placeZ = baseZ;
                return true;
            }
            if (BlockUtil.u(block)) {
                this.placeX = baseX;
                this.placeY = candidateY;
                this.placeZ = baseZ;
                return true;
            }
        }
        return false;
    }

    private boolean setupPlacement(EntityPlayerSP player) {
        if (this.target == null || this.target.isNull() || this.lavaSlot < 0) {
            return false;
        }
        World world = player.getWorld();
        if (world.isNull()) {
            return false;
        }
        Block targetBlock = world.getBlockByPos(this.placeX, this.placeY, this.placeZ);
        if (targetBlock.isNull() || !this.isPlaceableCell(targetBlock)) {
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
            this.lavaSupportBlock = supportData;
            this.lavaFacing = facings[index];
            this.lavaAimPoint = aimPoint;
            return true;
        }
        return false;
    }

    private boolean aimPlacement() {
        if (!this.rotationClaim.isOwnedBy(this) && !this.rotationClaim.acquire(this, this.silentAim.getEffectiveValue())) {
            return false;
        }
        if (this.lavaAimPoint == null || this.lavaAimPoint.isNull()) {
            return false;
        }
        if (this.lavaRotationController == null) {
            AdaptiveRotationController controller = new AdaptiveRotationController(this.lavaAimPoint);
            controller.setNormalizeTargetYaw(false);
            controller.setRetainAfterCompletion(true);
            controller.setClampStepToRemaining(true);
            controller.setTolerance(0.1f);
            controller.setRelativeMode(false);
            controller.setSpeed(PLACEMENT_AIM_SPEED);
            this.lavaRotationController = controller;
        }
        if (this.lavaRotationController instanceof AdaptiveRotationController) {
            this.lavaRotationController.setTarget(this.lavaAimPoint);
        }
        this.lavaRotationController.setSpeed(PLACEMENT_AIM_SPEED);
        if (!this.lavaRotationController.equals(RotationManager.INSTANCE.getActiveController())) {
            RotationManager.INSTANCE.setController(this.lavaRotationController);
        }
        return true;
    }

    private boolean aimPickup() {
        if (!this.rotationClaim.isOwnedBy(this) && !this.rotationClaim.acquire(this, this.silentAim.getEffectiveValue())) {
            return false;
        }
        Vec3 aimPoint = Vec3.create((double)this.placeX + 0.5, (double)this.placeY + 0.5, (double)this.placeZ + 0.5);
        if (this.pickupRotationController == null) {
            AdaptiveRotationController controller = new AdaptiveRotationController(aimPoint);
            controller.setNormalizeTargetYaw(false);
            controller.setRetainAfterCompletion(true);
            controller.setClampStepToRemaining(true);
            controller.setTolerance(0.1f);
            controller.setRelativeMode(false);
            controller.setSpeed(PICKUP_AIM_SPEED);
            this.pickupRotationController = controller;
        }
        if (this.pickupRotationController instanceof AdaptiveRotationController) {
            this.pickupRotationController.setTarget(aimPoint);
        }
        this.pickupRotationController.setSpeed(PICKUP_AIM_SPEED);
        if (!this.pickupRotationController.equals(RotationManager.INSTANCE.getActiveController())) {
            RotationManager.INSTANCE.setController(this.pickupRotationController);
        }
        return true;
    }

    private boolean isPickupLavaHit(RayTraceResult rayTraceResult) {
        if (rayTraceResult == null || rayTraceResult.isNull() || !rayTraceResult.isBlockHit()) {
            return false;
        }
        BlockPos hitPos = rayTraceResult.getBlockPos();
        if (hitPos == null || !hitPos.isNotNull()) {
            return false;
        }
        if (hitPos.getX() != this.placeX || hitPos.getY() != this.placeY || hitPos.getZ() != this.placeZ) {
            return false;
        }
        Block block = Minecraft.theWorld().getBlockByPos(this.placeX, this.placeY, this.placeZ);
        if (block == null || block.isNull() || !BlockUtil.C(block)) {
            return false;
        }
        String stateString = block.a().toString();
        return stateString != null && stateString.contains("level=0");
    }

    private int findEmptyBucketSlot(InventoryPlayer inventory) {
        ItemMappingEntry emptyBucket = BlockPlacementUtility.getEmptyBucketItem();
        if (emptyBucket == null) {
            return -1;
        }
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inventory.c(slot);
            if (stack == null || stack.isNull() || stack.getItem() == null || stack.getItem().isNull()) {
                continue;
            }
            ItemMappingEntry resolved = Vape.INSTANCE.getItemStackResolver().resolve(stack);
            if (resolved != null && emptyBucket.equals(resolved)) {
                return slot;
            }
        }
        return -1;
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onRightClickMouse(EventRightClickMouse eventRightClickMouse) {
        if (this.pickupClickPending && this.pickupOverrideRayTrace != null && this.pickupOverrideRayTrace.isNotNull()) {
            Minecraft.O(this.pickupOverrideRayTrace);
        }
        this.pickupClickPending = false;
        this.pickupOverrideRayTrace = null;
    }

    private boolean isLavaRayTraceValid() {
        if (this.lavaSupportBlock == null) {
            return false;
        }
        RayTraceResult rayTrace = RotationManager.INSTANCE.getNormalReachRayTrace();
        if (rayTrace == null || rayTrace.isNull() || !rayTrace.isBlockHit()) {
            return false;
        }
        EnumFacing sideHit = rayTrace.getSideHit();
        if (sideHit == null || sideHit.isNull() || sideHit.Y() != this.lavaFacing.Y()) {
            return false;
        }
        return rayTrace.g() == this.lavaSupportBlock.D()
                && rayTrace.T() == this.lavaSupportBlock.B()
                && rayTrace.a$src$I$8nuo9d() == this.lavaSupportBlock.G();
    }

    private Vec3 faceCenter(BlockData blockData, EnumFacing facing) {
        Vec3i direction = facing.getDirectionVector();
        return Vec3.create((double)blockData.D() + 0.5 + (double)direction.getX() * 0.5,
                (double)blockData.B() + 0.5 + (double)direction.getY() * 0.5,
                (double)blockData.G() + 0.5 + (double)direction.getZ() * 0.5);
    }

    private boolean isWebBlock(Block block) {
        String blockName = block.U();
        return blockName != null && blockName.toLowerCase().contains("web");
    }

    private boolean isPlaceableCell(Block block) {
        return this.isWebBlock(block) || BlockUtil.u(block);
    }

    private int findHotbarLavaSlot(InventoryPlayer inventory) {
        ItemMappingEntry lavaBucket = Vape.INSTANCE.getItemStackResolver().findByName("minecraft:lava_bucket");
        if (lavaBucket == null) {
            return -1;
        }
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inventory.c(slot);
            if (stack == null || stack.isNull() || stack.getItem() == null || stack.getItem().isNull()) continue;
            ItemMappingEntry resolved = Vape.INSTANCE.getItemStackResolver().resolve(stack);
            if (resolved != null && lavaBucket.equals(resolved)) {
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
        World world = Minecraft.theWorld();
        if (world == null || world.isNull()) {
            return null;
        }
        EntityOtherPlayerMP best = null;
        double bestDistance = MAX_TARGET_DISTANCE;
        List loadedEntities = world.z();
        if (loadedEntities == null) {
            return null;
        }
        for (Object entityObject : loadedEntities) {
            Entity entity = new Entity(entityObject);
            if (entity == null || !entity.isNotNull() || !entity.isInstance(MappedClasses.zm)
                    || !entity.isInstance(MappedClasses.lG)) {
                continue;
            }
            EntityOtherPlayerMP candidate = new EntityOtherPlayerMP(entityObject);
            if (candidate.S() == player.S()
                    || !Vape.INSTANCE.getClientSettings().isValidTarget(candidate, false)) {
                continue;
            }
            double distance = (double)player.getDistanceToEntity(candidate);
            if (distance > MAX_TARGET_DISTANCE || distance >= bestDistance) {
                continue;
            }
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }

    private void finish() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNotNull() && Minecraft.currentScreen().isNull()) {
            if (this.originalSlot >= 0) {
                player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
            }
        }
        if (this.lavaRotationController != null) {
            RotationManager.INSTANCE.releaseController(this.lavaRotationController);
        }
        if (this.pickupRotationController != null) {
            RotationManager.INSTANCE.releaseController(this.pickupRotationController);
        }
        this.rotationClaim.release(this);
        this.lavaRotationController = null;
        this.pickupRotationController = null;
        this.pickupOverrideRayTrace = null;
        this.pickupClickPending = false;
        this.target = null;
        this.lavaSupportBlock = null;
        this.lavaFacing = null;
        this.lavaAimPoint = null;
        this.originalSlot = -1;
        this.lavaSlot = -1;
        this.state = STATE_PLACE;
        this.setEnabled(false, true);
    }

    @Override
    public void onDisable() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNotNull()) {
            if (this.originalSlot >= 0) {
                player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
            }
        }
        if (this.lavaRotationController != null) {
            RotationManager.INSTANCE.releaseController(this.lavaRotationController);
        }
        if (this.pickupRotationController != null) {
            RotationManager.INSTANCE.releaseController(this.pickupRotationController);
        }
        this.rotationClaim.release(this);
        this.lavaRotationController = null;
        this.pickupRotationController = null;
        this.pickupOverrideRayTrace = null;
        this.pickupClickPending = false;
        this.target = null;
        this.lavaSupportBlock = null;
        this.lavaFacing = null;
        this.lavaAimPoint = null;
        this.originalSlot = -1;
        this.lavaSlot = -1;
    }
}