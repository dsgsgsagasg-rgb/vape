package gg.vape.module.utility;

import gg.vape.Vape;
import gg.vape.event.EventHandler;
import gg.vape.event.EventPriority;
import gg.vape.event.impl.EventPlayerUseItem;
import gg.vape.event.impl.EventRightClickMouse;
import gg.vape.event.impl.EventPreTick;
import gg.vape.event.impl.EventWorldChange;
import gg.vape.module.Category;
import gg.vape.module.Mod;
import gg.vape.module.blatant.blockin.BlockPlacementUtility;
import gg.vape.module.control.SharedModuleControlClaims;
import gg.vape.notification.NotificationType;
import gg.vape.rotation.AdaptiveRotationController;
import gg.vape.rotation.RotationControlClaim;
import gg.vape.rotation.RotationManager;
import gg.vape.utils.BlockUtil;
import gg.vape.utils.MathUtil;
import gg.vape.utils.TimerUtil;
import gg.vape.utils.datas.BlockCoordinate;
import gg.vape.utils.datas.BlockData;
import gg.vape.value.BooleanValue;
import gg.vape.value.NumberValue;
import gg.vape.wrapper.impl.Block;
import gg.vape.wrapper.impl.BlockPos;
import gg.vape.wrapper.impl.BlockState;
import gg.vape.wrapper.impl.EntityPlayerSP;
import gg.vape.wrapper.impl.InventoryPlayer;
import gg.vape.wrapper.impl.ItemStack;
import gg.vape.wrapper.impl.KeyBinding;
import gg.vape.wrapper.impl.Minecraft;
import gg.vape.wrapper.impl.RayTraceResult;
import gg.vape.wrapper.impl.Vec3;
import gg.vape.wrapper.impl.World;
import java.util.HashSet;
import java.util.Set;

public class AutoDrain
extends Mod {
    private static final int STATE_SCAN = 0;
    private static final int STATE_AIM = 1;
    private static final int STATE_RESTORE = 2;
    private static final long MODULE_ID = -6688822042274727933L;

    private final NumberValue aimSpeed = NumberValue.create(this, "Aim speed", "#.#", "", 3.0, 10.0, 20.0, 0.5,
            "How quickly AutoDrain will change your look angles");
    private final BooleanValue silentAim = BooleanValue.create(this, "Silent aim", true,
            "Aims without moving the camera when picking up water");
    private final NumberValue reach = NumberValue.create(this, "Reach", "#.#", "m", 1.0, 4.5, 7.0, 0.1,
            "Maximum distance to search and pick up water");
    private final NumberValue delay = NumberValue.create(this, "Delay", "#", "ticks", 0.0, 3.0, 40.0, 1.0,
            "Delay between draining water sources");

    private final RotationControlClaim rotationClaim = SharedModuleControlClaims.rotation;
    private final TimerUtil delayTimer = new TimerUtil();
    private final TimerUtil notifyTimer = new TimerUtil();
    private final Set<BlockData> handledWater = new HashSet<>();
    private final Set<BlockData> playerPlacedWater = new HashSet<>();

    private int state;
    private int ticks;
    private int originalSlot = -1;
    private int bucketSlot = -1;
    private BlockData target;
    private AdaptiveRotationController rotationController;
    private RayTraceResult clickOverrideRayTrace;
    private boolean clickPending;
    private boolean rotationRestorePending;
    private float savedYaw;
    private float savedPitch;
    private float savedYawOffset;

    public AutoDrain() {
        super("AutoDrain", (int)MODULE_ID, Category.UTILITY,
                "Automatically picks up water placed by others using an empty bucket");
        this.addValue(this.aimSpeed, this.silentAim, this.reach, this.delay);
        this.rotationClaim.setPriority(this, 7);
    }

    @Override
    public void onEnable() {
        this.handledWater.clear();
        this.delayTimer.reset();
        this.notifyTimer.reset();
        this.ticks = 0;
        this.state = STATE_SCAN;
    }

    @Override
    public void onDisable() {
        this.cancel();
    }

    @EventHandler
    public void onWorldChange(EventWorldChange eventWorldChange) {
        this.handledWater.clear();
        this.playerPlacedWater.clear();
        this.cancel();
    }

    @EventHandler
    public void onPlayerUseItem(EventPlayerUseItem eventPlayerUseItem) {
        ItemStack itemStack = eventPlayerUseItem.getItemStack();
        if (itemStack == null || itemStack.isNull()) {
            return;
        }
        if (!BlockPlacementUtility.getWaterBucketItem().equals(Vape.INSTANCE.getItemStackResolver().resolve(itemStack))) {
            return;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNull()) {
            return;
        }
        BlockPos placedPos = BlockPlacementUtility.getPlacementBlockPos(BlockPlacementUtility.getWaterBucketItem());
        if (placedPos == null || placedPos.isNull()) {
            return;
        }
        this.playerPlacedWater.add(BlockData.E(placedPos));
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        EntityPlayerSP player = event.getThePlayer();
        if (player.isNull()) {
            return;
        }
        if (Minecraft.currentScreen().isNotNull()) {
            if (this.state != STATE_SCAN) {
                this.cancel();
            }
            return;
        }
        switch (this.state) {
            case STATE_SCAN: {
                this.tickScan(player);
                break;
            }
            case STATE_AIM: {
                this.tickAim(player);
                break;
            }
            case STATE_RESTORE: {
                this.tickRestore(player);
                break;
            }
        }
    }

    private void tickScan(EntityPlayerSP player) {
        World world = player.getWorld();
        if (world.isNull()) {
            return;
        }
        double delayMs = this.delay.getValue().doubleValue() * 50.0;
        if (delayMs > 0.0 && !this.delayTimer.hasTimeElapsed((long)delayMs)) {
            return;
        }
        this.pruneWaterSets(world);
        BlockData waterSource = this.findNearestWaterSource(player, world, this.reach.getValue().doubleValue());
        if (waterSource == null) {
            return;
        }
        if (!this.hasEmptyBucket(player)) {
            if (this.notifyTimer.hasTimeElapsed(5000L)) {
                this.notifyTimer.reset();
                Vape.INSTANCE.getNotificationManager().show("AutoDrain", "No empty bucket in your hotbar",
                        NotificationType.WARNING, 3500L);
            }
            return;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        this.originalSlot = inventory.v();
        this.bucketSlot = this.findEmptyBucketSlot(inventory);
        if (this.bucketSlot == this.originalSlot) {
            this.bucketSlot = -1;
        }
        this.target = waterSource;
        this.ticks = 0;
        this.state = STATE_AIM;
    }

    private void tickAim(EntityPlayerSP player) {
        World world = player.getWorld();
        if (world.isNull() || this.target == null || !this.isWaterSource(world, this.target)) {
            this.cancel();
            return;
        }
        if (player.i((double)this.target.D() + 0.5, (double)this.target.B() + 0.5, (double)this.target.G() + 0.5)
                > this.reach.getValue().doubleValue()) {
            this.cancel();
            return;
        }
        if (!this.updateAim()) {
            if (++this.ticks > 60) {
                this.cancel();
            }
            return;
        }
        if (this.rotationController == null || !this.rotationController.isComplete()) {
            if (++this.ticks > 60) {
                this.cancel();
            }
            return;
        }
        RayTraceResult fluidHit = RotationManager.INSTANCE.rayTraceUsingManagedRotation(true);
        if (fluidHit == null || fluidHit.isNull() || !this.isFullWaterHit(fluidHit)) {
            if (++this.ticks > 60) {
                this.cancel();
            }
            return;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        if (this.bucketSlot >= 0) {
            inventory.g(this.bucketSlot);
        }
        this.spoofRotation(player);
        this.clickOverrideRayTrace = fluidHit;
        this.clickPending = true;
        Minecraft.O(fluidHit);
        this.rightClick();
        this.handledWater.add(this.target);
        this.ticks = 0;
        this.state = STATE_RESTORE;
    }

    private void tickRestore(EntityPlayerSP player) {
        if (++this.ticks < 2) {
            return;
        }
        if (this.rotationRestorePending) {
            this.restoreRotation(player);
        }
        this.clickPending = false;
        this.clickOverrideRayTrace = null;
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        if (this.originalSlot >= 0) {
            inventory.g(this.originalSlot);
        }
        this.releaseRotation();
        this.target = null;
        this.originalSlot = -1;
        this.bucketSlot = -1;
        this.ticks = 0;
        this.delayTimer.reset();
        this.state = STATE_SCAN;
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onRightClickMouse(EventRightClickMouse eventRightClickMouse) {
        if (this.clickPending && this.clickOverrideRayTrace != null && this.clickOverrideRayTrace.isNotNull()) {
            Minecraft.O(this.clickOverrideRayTrace);
        }
        this.clickPending = false;
        this.clickOverrideRayTrace = null;
    }

    private boolean isFullWaterHit(RayTraceResult rayTraceResult) {
        if (this.target == null || rayTraceResult == null || rayTraceResult.isNull() || !rayTraceResult.isBlockHit()) {
            return false;
        }
        BlockPos hitPos = rayTraceResult.getBlockPos();
        return hitPos != null && hitPos.isNotNull()
                && hitPos.getX() == this.target.D() && hitPos.getY() == this.target.B() && hitPos.getZ() == this.target.G();
    }

    private void spoofRotation(EntityPlayerSP player) {
        this.savedYaw = player.J();
        this.savedPitch = player.V();
        this.savedYawOffset = player.s();
        this.rotationRestorePending = true;
        float managedYaw = RotationManager.INSTANCE.getManagedYaw();
        float managedPitch = RotationManager.INSTANCE.getManagedPitch();
        player.H(managedYaw);
        player.z(managedYaw);
        player.C(managedPitch);
    }

    private void restoreRotation(EntityPlayerSP player) {
        this.rotationRestorePending = false;
        if (player == null || player.isNull()) {
            return;
        }
        player.H(this.savedYaw);
        player.z(this.savedYawOffset);
        player.C(this.savedPitch);
    }

    private boolean updateAim() {
        if (!this.rotationClaim.isOwnedBy(this) && !this.rotationClaim.acquire(this, this.silentAim.getEffectiveValue())) {
            return false;
        }
        if (this.target == null) {
            return false;
        }
        Vec3 aimPoint = BlockPlacementUtility.getAimPoint(
                new BlockCoordinate(this.target.D(), this.target.B(), this.target.G()),
                BlockPlacementUtility.getEmptyBucketItem());
        if (this.rotationController == null) {
            AdaptiveRotationController controller = new AdaptiveRotationController(aimPoint);
            controller.setNormalizeTargetYaw(false);
            controller.setRetainAfterCompletion(true);
            controller.setClampStepToRemaining(true);
            controller.setTolerance(0.1f);
            controller.setRelativeMode(false);
            controller.setSpeed(this.aimSpeed.getValue().floatValue());
            this.rotationController = controller;
        }
        if (this.rotationController instanceof AdaptiveRotationController) {
            ((AdaptiveRotationController)this.rotationController).setTarget(aimPoint);
        }
        this.rotationController.setSpeed(this.aimSpeed.getValue().floatValue());
        if (!this.rotationController.equals(RotationManager.INSTANCE.getActiveController())) {
            RotationManager.INSTANCE.setController(this.rotationController);
        }
        return true;
    }

    private boolean isWaterSource(World world, BlockData blockData) {
        BlockState blockState = world.getBlockState(BlockPos.create(blockData.D(), blockData.B(), blockData.G()));
        if (blockState == null || blockState.isNull()) {
            return false;
        }
        Block block = blockState.getBlock();
        if (block == null || block.isNull() || !BlockUtil.C(block)) {
            return false;
        }
        String blockName = block.U();
        if (blockName == null || !blockName.toLowerCase().contains("water")) {
            return false;
        }
        String stateString = block.a().toString();
        return stateString != null && stateString.contains("level=0");
    }

    private BlockData findNearestWaterSource(EntityPlayerSP player, World world, double reach) {
        double playerX = player.z();
        double playerY = player.N();
        double playerZ = player.h();
        int baseX = MathUtil.floor(playerX);
        int baseY = MathUtil.floor(playerY);
        int baseZ = MathUtil.floor(playerZ);
        int radius = MathUtil.ceil(reach);
        int minY = Math.max(world.R(), baseY - 4);
        int maxY = baseY + 3;
        double reachSquared = reach * reach;
        BlockData best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = baseX - radius; x <= baseX + radius; ++x) {
            for (int z = baseZ - radius; z <= baseZ + radius; ++z) {
                double deltaX = (double)x + 0.5 - playerX;
                double deltaZ = (double)z + 0.5 - playerZ;
                if (deltaX * deltaX + deltaZ * deltaZ > reachSquared) {
                    continue;
                }
                for (int y = minY; y <= maxY; ++y) {
                    double deltaY = (double)y + 0.5 - playerY;
                    if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > reachSquared) {
                        continue;
                    }
                    BlockData blockData = new BlockData(x, y, z);
                    if (this.playerPlacedWater.contains(blockData) || this.handledWater.contains(blockData)) {
                        continue;
                    }
                    if (!this.isWaterSource(world, blockData)) {
                        continue;
                    }
                    double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
                    if (!(distance < bestDistance)) {
                        continue;
                    }
                    best = blockData;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private void pruneWaterSets(World world) {
        this.handledWater.removeIf(blockData -> !this.isWaterSource(world, blockData));
        this.playerPlacedWater.removeIf(blockData -> !this.isWaterSource(world, blockData));
        if (this.handledWater.size() > 1024) {
            this.handledWater.clear();
        }
        if (this.playerPlacedWater.size() > 1024) {
            this.playerPlacedWater.clear();
        }
    }

    private boolean hasEmptyBucket(EntityPlayerSP player) {
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        ItemStack heldStack = inventory.c(inventory.v());
        if (heldStack != null && heldStack.isNotNull()) {
            if (BlockPlacementUtility.getEmptyBucketItem().equals(Vape.INSTANCE.getItemStackResolver().resolve(heldStack))) {
                return true;
            }
        }
        return this.findEmptyBucketSlot(inventory) != -1;
    }

    private int findEmptyBucketSlot(InventoryPlayer inventory) {
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inventory.c(slot);
            if (stack == null || stack.isNull()) {
                continue;
            }
            if (BlockPlacementUtility.getEmptyBucketItem().equals(Vape.INSTANCE.getItemStackResolver().resolve(stack))) {
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

    private void releaseRotation() {
        if (this.rotationController != null) {
            RotationManager.INSTANCE.releaseController(this.rotationController);
        }
        this.rotationClaim.release(this);
        this.rotationController = null;
    }

    private void cancel() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNotNull()) {
            this.restoreRotation(player);
            if (Minecraft.currentScreen().isNull() && this.originalSlot >= 0) {
                player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
            }
        }
        this.clickPending = false;
        this.clickOverrideRayTrace = null;
        this.releaseRotation();
        this.target = null;
        this.originalSlot = -1;
        this.bucketSlot = -1;
        this.ticks = 0;
        this.state = STATE_SCAN;
    }
}