package gg.vape.module.combat;

import gg.vape.event.Event;
import gg.vape.event.EventHandler;
import gg.vape.event.EventPriority;
import gg.vape.event.impl.EventKeyPress;
import gg.vape.event.impl.EventMouseButton;
import gg.vape.event.impl.EventPreTick;
import gg.vape.event.impl.SyntheticAttackRequestEvent;
import gg.vape.input.AttackKeyController;
import gg.vape.mapping.MappedClasses;
import gg.vape.module.Category;
import gg.vape.module.Mod;
import gg.vape.module.control.SharedModuleControlClaims;
import gg.vape.module.utility.clutch.ClutchPlacementPathUtils;
import gg.vape.module.utility.clutch.PlacementTarget;
import gg.vape.rotation.FixedRotationController;
import gg.vape.rotation.PointRotationController;
import gg.vape.rotation.RotationControlClaim;
import gg.vape.rotation.RotationManager;
import gg.vape.unmap.ItemLimitData;
import gg.vape.utils.BlockUtil;
import gg.vape.utils.ItemStackScoreUtil;
import gg.vape.utils.MathUtil;
import gg.vape.utils.RotationUtil;
import gg.vape.utils.datas.BlockData;
import gg.vape.value.BooleanValue;
import gg.vape.value.LimitValue;
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

public class ShieldBreaker extends Mod {
    private static final long MODULE_ID = 7954407336301342843L;

    private final NumberValue swapDelay;
    private final NumberValue swapBackDelay;
    private final BooleanValue doubleClick;
    private final BooleanValue stunWeb;
    private final NumberValue webAimSpeed;
    private final BooleanValue limitToItems;
    private final LimitValue allowedItems;
    private final RotationControlClaim rotationClaim = SharedModuleControlClaims.rotation;

    private boolean active;
    private boolean waitingToAttack;
    private boolean releasePending;
    private boolean placingWeb;
    private boolean webPlacementStarted;
    private boolean webClicked;
    private int originalSlot = -1;
    private int axeSlot = -1;
    private int swapTicks;
    private int restoreTicks;
    private int webTicks;
    private int cobwebSlot = -1;
    private EntityOtherPlayerMP webTarget;
    private BlockData webSupportBlock;
    private EnumFacing webFacing;
    private Vec3 webAimPoint;
    private FixedRotationController webRotationController;

    public ShieldBreaker() {
        super("ShieldBreaker", (int)MODULE_ID, Category.COMBAT,
                "Swaps to an axe when attacking a player with a raised shield");
        this.swapDelay = NumberValue.create(this, "Swap delay", "#", "ticks", 0.0, 0.0, 10.0, 1.0,
                "Delay between swapping to an axe and attacking");
        this.swapBackDelay = NumberValue.create(this, "Swap back delay", "#", "ticks", 1.0, 2.0, 10.0, 1.0,
                "Delay between attacking and swapping back to the original slot");
        this.doubleClick = BooleanValue.create(this, "Double click", false,
                "Attacks again immediately after breaking the shield to knock the target back");
        this.stunWeb = BooleanValue.create(this, "Stun web", false,
                "Places a cobweb under the target after breaking their shield");
        this.webAimSpeed = NumberValue.create(this, "Web aim speed", "#.#", "", 0.5, 4.0, 10.0, 0.1,
                "Speed of the aim when placing the cobweb");
        this.limitToItems = BooleanValue.create(this, "Limit to items", false,
                "ShieldBreaker functions only while holding selected items");
        this.allowedItems = LimitValue.create(this, "shieldbreaker-alloweditems", "Allowed Items",
                LimitValue.ALLOW_LIST_COLOR, new ItemLimitData("swords"));
        this.swapDelay.setMaximumFractionDigits(0);
        this.swapBackDelay.setMaximumFractionDigits(0);
        this.limitToItems.addDependentValues(this.allowedItems);
        this.stunWeb.addDependentValues(this.webAimSpeed);
        this.addValue(this.swapDelay, this.swapBackDelay, this.doubleClick, this.stunWeb, this.webAimSpeed, this.limitToItems, this.allowedItems);
        this.rotationClaim.setPriority(this, 7);
    }

    @EventHandler(priority = EventPriority.HIGH, skipCanceled = true)
    public void onKeyPress(EventKeyPress event) {
        if (event.isKeybinding(Minecraft.gameSettings().F()) && event.isDown()) {
            this.handleAttack(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, skipCanceled = true)
    public void onMouseButton(EventMouseButton event) {
        if (event.isKeybinding(Minecraft.gameSettings().F()) && event.isDown()) {
            this.handleAttack(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, skipCanceled = true)
    public void onSyntheticAttack(SyntheticAttackRequestEvent event) {
        Mod source = event.getSource();
        if (source != this && !(source instanceof HitSwap) && !(source instanceof AutoMace)) {
            this.handleAttack(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTick(EventPreTick event) {
        EntityPlayerSP player = event.getThePlayer();
        if (this.releasePending) {
            AttackKeyController.releaseAttackKey();
            this.releasePending = false;
        }
        if (player.isNull()) {
            this.reset(null, false);
            return;
        }
        if (!this.active) {
            return;
        }
        if (!this.placingWeb && !this.isStateValid(player)) {
            this.restoreSlot(player);
            this.reset(player, false);
            return;
        }
        if (this.waitingToAttack) {
            if (this.swapTicks++ >= this.swapDelay.getValue().intValue()) {
                this.attack();
                this.waitingToAttack = false;
                this.restoreTicks = 0;
                this.beginWebPlacement();
            }
            return;
        }
        if (this.placingWeb) {
            this.tickWebPlacement(player);
            return;
        }
        if (this.restoreTicks++ >= this.swapBackDelay.getValue().intValue()) {
            this.restoreSlot(player);
            this.reset(player, false);
        }
    }

    private void handleAttack(Event event) {
        if (this.active || Minecraft.currentScreen().isNotNull()) {
            return;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNull() || !this.canUseHeldItem(player)) {
            return;
        }
        EntityOtherPlayerMP shieldTarget = this.findShieldTarget();
        if (shieldTarget == null) {
            return;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        int selectedSlot = inventory.v();
        if (this.isAxe(inventory.c(selectedSlot))) {
            if (this.doubleClick.getEffectiveValue().booleanValue()) {
                this.releasePending = AttackKeyController.requestSyntheticAttack(this);
            }
            return;
        }
        int foundAxeSlot = this.findAxeSlot(inventory);
        if (foundAxeSlot < 0) {
            return;
        }
        event.setCancelled(true);
        this.originalSlot = selectedSlot;
        this.axeSlot = foundAxeSlot;
        this.webTarget = shieldTarget;
        inventory.g(foundAxeSlot);
        this.active = true;
        this.waitingToAttack = true;
        this.swapTicks = 0;
        this.restoreTicks = 0;
        if (this.swapDelay.getValue().intValue() == 0) {
            this.attack();
            this.waitingToAttack = false;
            this.beginWebPlacement();
        }
    }

    private void attack() {
        AttackKeyController.releaseAttackKey();
        this.releasePending = AttackKeyController.requestSyntheticAttack(this);
        if (this.doubleClick.getEffectiveValue().booleanValue() && this.releasePending) {
            AttackKeyController.releaseAttackKey();
            this.releasePending = AttackKeyController.requestSyntheticAttack(this);
        }
    }

    private boolean isStateValid(EntityPlayerSP player) {
        return Minecraft.currentScreen().isNull()
                && player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().v() == this.axeSlot
                && (!this.waitingToAttack || this.isAttackingRaisedShield());
    }

    private boolean canUseHeldItem(EntityPlayerSP player) {
        return !this.limitToItems.getEffectiveValue().booleanValue()
                || this.allowedItems.isValid(player.getHeldItemHand(), false);
    }

    private boolean isAttackingRaisedShield() {
        return this.findShieldTarget() != null;
    }

    private EntityOtherPlayerMP findShieldTarget() {
        RayTraceResult rayTrace = RotationManager.INSTANCE.getExtendedReachRayTrace();
        if (rayTrace == null || !rayTrace.isEntityHit()) {
            return null;
        }
        Entity target = rayTrace.getEntity();
        if (target == null || !target.isNotNull() || !target.isInstance(MappedClasses.lG)) {
            return null;
        }
        EntityOtherPlayerMP otherPlayer = new EntityOtherPlayerMP(target.getObject());
        if (!RotationUtil.n(otherPlayer)) {
            return null;
        }
        return otherPlayer;
    }

    public boolean hasAxeInHotbar() {
        EntityPlayerSP player = Minecraft.thePlayer();
        return player.isNotNull() && this.findAxeSlot(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6()) >= 0;
    }

    private int findAxeSlot(InventoryPlayer inventory) {
        for (int slot = 0; slot < 9; ++slot) {
            if (this.isAxe(inventory.c(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isAxe(ItemStack stack) {
        return stack != null && stack.isNotNull() && stack.getItem().isNotNull()
                && ItemStackScoreUtil.T(stack.getItem());
    }

    private void beginWebPlacement() {
        this.placingWeb = this.stunWeb.getEffectiveValue().booleanValue();
        this.webPlacementStarted = false;
        this.webClicked = false;
        this.webTicks = 0;
        this.webRotationController = null;
        this.cobwebSlot = -1;
        this.webSupportBlock = null;
        this.webFacing = null;
        this.webAimPoint = null;
    }

    private void tickWebPlacement(EntityPlayerSP player) {
        if (Minecraft.currentScreen().isNotNull() || player.isNull()) {
            this.restoreSlot(player);
            this.reset(player, false);
            return;
        }
        if (!this.webPlacementStarted) {
            this.webPlacementStarted = this.setupWebPlacement(player);
            if (!this.webPlacementStarted) {
                this.restoreSlot(player);
                this.reset(player, false);
                return;
            }
        }
        if (!this.aimWeb()) {
            this.restoreSlot(player);
            this.reset(player, false);
            return;
        }
        if (this.webRotationController == null || !this.webRotationController.isComplete()) {
            if (++this.webTicks > 70) {
                this.restoreSlot(player);
                this.reset(player, false);
            }
            return;
        }
        if (!this.isWebRayTraceValid()) {
            if (++this.webTicks > 30) {
                this.restoreSlot(player);
                this.reset(player, false);
            }
            return;
        }
        if (!this.webClicked) {
            this.webTicks = 0;
            this.webClicked = true;
            player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.cobwebSlot);
            this.rightClick();
            return;
        }
        if (++this.webTicks >= 2) {
            this.restoreSlot(player);
            this.reset(player, false);
        }
    }

    private boolean setupWebPlacement(EntityPlayerSP player) {
        if (this.webTarget == null || this.webTarget.isNull()) {
            return false;
        }
        World world = player.getWorld();
        if (world.isNull()) {
            return false;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        if (inventory == null || inventory.isNull()) {
            return false;
        }
        this.cobwebSlot = this.findHotbarSlot(inventory, "cobweb");
        if (this.cobwebSlot < 0) {
            return false;
        }
        int targetX = MathUtil.floor(this.webTarget.z());
        int targetY = MathUtil.floor(this.webTarget.N());
        int targetZ = MathUtil.floor(this.webTarget.h());
        Block targetBlock = world.getBlockByPos(targetX, targetY, targetZ);
        if (targetBlock.isNull() || !BlockUtil.u(targetBlock)) {
            return false;
        }
        Vec3 eyePosition = Vec3.create(player.z(), player.N() + (double)player.X(), player.h());
        BlockData targetData = new BlockData(targetX, targetY, targetZ);
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

    private boolean aimWeb() {
        if (!this.rotationClaim.isOwnedBy(this) && !this.rotationClaim.acquire(this, true)) {
            return false;
        }
        if (this.webAimPoint == null || this.webAimPoint.isNull()) {
            return false;
        }
        if (this.webRotationController == null) {
            PointRotationController controller = new PointRotationController(this.webAimPoint);
            controller.setNormalizeYaw(false);
            controller.setRetainAfterCompletion(true);
            controller.setClampStepToRemaining(true);
            controller.setTolerance(0.1f);
            controller.setAngleBasedAcceleration(true);
            controller.setScaleAxesProportionally(true);
            controller.setLinearAcceleration(true);
            controller.setCubicAcceleration(true);
            controller.setSpeed(((Double)this.webAimSpeed.getValue()).floatValue());
            this.webRotationController = controller;
        }
        if (this.webRotationController instanceof PointRotationController) {
            ((PointRotationController)this.webRotationController).setTarget(this.webAimPoint);
        }
        this.webRotationController.setSpeed(((Double)this.webAimSpeed.getValue()).floatValue());
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

    private void restoreSlot(EntityPlayerSP player) {
        if (player != null && player.isNotNull() && this.originalSlot >= 0) {
            player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
        }
    }

    private void reset(EntityPlayerSP player, boolean restore) {
        if (restore) {
            this.restoreSlot(player);
        }
        if (this.releasePending) {
            AttackKeyController.releaseAttackKey();
        }
        if (this.webRotationController != null) {
            RotationManager.INSTANCE.releaseController(this.webRotationController);
            this.webRotationController = null;
        }
        this.rotationClaim.release(this);
        this.active = false;
        this.waitingToAttack = false;
        this.releasePending = false;
        this.placingWeb = false;
        this.webPlacementStarted = false;
        this.webClicked = false;
        this.originalSlot = -1;
        this.axeSlot = -1;
        this.swapTicks = 0;
        this.restoreTicks = 0;
        this.webTicks = 0;
        this.webTarget = null;
        this.cobwebSlot = -1;
        this.webSupportBlock = null;
        this.webFacing = null;
        this.webAimPoint = null;
    }

    @Override
    public void onDisable() {
        this.reset(Minecraft.thePlayer(), true);
    }
}
