package cn.blockforge.generated.mod384a4d91.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 铜傀儡"分类搬运"行为（TransportItemsBetweenContainers）的性能与吞吐调整。
 */
@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {

    /* ============ 可调参数 ============ */

    @Unique
    private static final float blockforge$SPEED_MULTIPLIER = 1.5f;

    @Unique
    private static final int blockforge$PATH_CACHE_TICKS = 8;

    @Unique
    private static final int blockforge$OCCUPY_CACHE_TICKS = 4;

    @Unique
    private static final int blockforge$MAX_CARRY_COUNT = 64;

    @Unique
    private static final int blockforge$MAX_CARRY_TYPES = 4;

    /* ============ 缓存状态 ============ */

    @Unique
    private int blockforge$lastPathCheckTick = Integer.MIN_VALUE;

    @Unique
    private BlockPos blockforge$pathCachePos;

    @Unique
    private boolean blockforge$pathCacheResult;

    @Unique
    private long blockforge$lastOccupyCheckTime = Long.MIN_VALUE;

    @Unique
    private BlockPos blockforge$occupyCachePos;

    @Unique
    private boolean blockforge$occupyCacheResult;

    /* ============ 1. 搬运速度 ×1.5 ============ */

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static float blockforge$boostCarrySpeed(float speedModifier) {
        return speedModifier * blockforge$SPEED_MULTIPLIER;
    }

    /* ============ 2. 单次搬运数量 16 → 64 ============ */

    @ModifyConstant(
            method = "pickUpItems",
            constant = @Constant(intValue = 16),
            require = 0)
    private int blockforge$increaseCarryLimit(int original) {
        return blockforge$MAX_CARRY_COUNT;
    }

    /* ============ 3. 单次搬运多种物品 ============ */

    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/PathfinderMob;J)V", at = @At("HEAD"))
    private void blockforge$resetCaches(ServerLevel serverLevel, PathfinderMob mob,
                                        long l, CallbackInfo ci) {
        this.blockforge$lastPathCheckTick = Integer.MIN_VALUE;
        this.blockforge$pathCachePos = null;
        this.blockforge$lastOccupyCheckTime = Long.MIN_VALUE;
        this.blockforge$occupyCachePos = null;
    }

    /**
     * 修正：pickUpItems 的实际签名是 (PathfinderMob, Container)。
     * 不再接收 ServerLevel 和 TransportItemTarget。
     */
    @Inject(method = "pickUpItems", at = @At("RETURN"), require = 0)
    private void blockforge$carryMultipleTypes(PathfinderMob mob,
                                               Container source,
                                               CallbackInfo ci) {
        if (!(mob instanceof CopperGolem golem)) {
            return;
        }
        if (source == null) {
            return;
        }

        // 使用 InventoryCarrier 接口获取物品栏
        if (!(golem instanceof InventoryCarrier carrier)) {
            return;
        }
        SimpleContainer inventory = carrier.getInventory();
        if (inventory == null) {
            return;
        }

        int carried = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            carried += inventory.getItem(i).getCount();
        }
        if (carried >= blockforge$MAX_CARRY_COUNT) {
            return;
        }

        int typesAdded = 0;
        for (int slot = 0;
             slot < source.getContainerSize() && typesAdded < blockforge$MAX_CARRY_TYPES;
             slot++) {

            ItemStack stack = source.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (blockforge$inventoryContains(inventory, stack)) {
                continue;
            }

            int space = blockforge$MAX_CARRY_COUNT - carried;
            int take = Math.min(stack.getCount(), space);
            if (take <= 0) {
                break;
            }

            ItemStack taken = source.removeItem(slot, take);
            if (taken.isEmpty()) {
                continue;
            }

            for (int i = 0; i < inventory.getContainerSize() && !taken.isEmpty(); i++) {
                ItemStack existing = inventory.getItem(i);
                if (existing.isEmpty()) {
                    inventory.setItem(i, taken.copy());
                    taken.setCount(0);
                } else if (ItemStack.isSameItemSameComponents(existing, taken)) {
                    int canAdd = existing.getMaxStackSize() - existing.getCount();
                    int add = Math.min(canAdd, taken.getCount());
                    if (add > 0) {
                        existing.grow(add);
                        taken.shrink(add);
                    }
                }
            }

            if (!taken.isEmpty()) {
                // 放回源容器
                if (source instanceof SimpleContainer simpleSource) {
                    simpleSource.addItem(taken);
                } else {
                    blockforge$addItemToContainer(source, taken);
                }
                break;
            }

            carried += take;
            typesAdded++;
        }
    }

    /** 判断物品栏里是否已有与该堆同类的物品 */
    @Unique
    private static boolean blockforge$inventoryContains(Container inventory, ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (!existing.isEmpty()
                    && ItemStack.isSameItemSameComponents(existing, stack)) {
                return true;
            }
        }
        return false;
    }

    /** 将 ItemStack 放入 Container，模拟原版 addItem 的行为 */
    @Unique
    private static void blockforge$addItemToContainer(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) {
                container.setItem(i, stack.copy());
                stack.setCount(0);
                return;
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                int canAdd = existing.getMaxStackSize() - existing.getCount();
                int add = Math.min(canAdd, stack.getCount());
                if (add > 0) {
                    existing.grow(add);
                    stack.shrink(add);
                    if (stack.isEmpty()) {
                        return;
                    }
                }
            }
        }
    }

    /* ============ 4. 路径校验缓存 ============ */

    @Inject(method = "hasValidTravellingPath", at = @At("HEAD"), cancellable = true)
    private void blockforge$reusePathResult(Level level,
                                            TransportItemsBetweenContainers.TransportItemTarget target,
                                            PathfinderMob mob,
                                            CallbackInfoReturnable<Boolean> cir) {
        int now = mob.tickCount;
        if (this.blockforge$pathCachePos != null
                && this.blockforge$pathCachePos.equals(target.pos())
                && this.blockforge$lastPathCheckTick != Integer.MIN_VALUE
                && now >= this.blockforge$lastPathCheckTick
                && now - this.blockforge$lastPathCheckTick < blockforge$PATH_CACHE_TICKS) {
            cir.setReturnValue(this.blockforge$pathCacheResult);
        }
    }

    @Inject(method = "hasValidTravellingPath", at = @At("RETURN"))
    private void blockforge$storePathResult(Level level,
                                            TransportItemsBetweenContainers.TransportItemTarget target,
                                            PathfinderMob mob,
                                            CallbackInfoReturnable<Boolean> cir) {
        this.blockforge$lastPathCheckTick = mob.tickCount;
        this.blockforge$pathCachePos = target.pos();
        this.blockforge$pathCacheResult = cir.getReturnValue();
    }

    /* ============ 5. 占用检查缓存 ============ */

    @Inject(method = "isAnotherMobInteractingWithTarget", at = @At("HEAD"), cancellable = true)
    private void blockforge$reuseOccupyResult(TransportItemsBetweenContainers.TransportItemTarget target,
                                              Level level,
                                              CallbackInfoReturnable<Boolean> cir) {
        long now = level.getGameTime();
        if (this.blockforge$occupyCachePos != null
                && this.blockforge$occupyCachePos.equals(target.pos())
                && this.blockforge$lastOccupyCheckTime != Long.MIN_VALUE
                && now >= this.blockforge$lastOccupyCheckTime
                && now - this.blockforge$lastOccupyCheckTime < blockforge$OCCUPY_CACHE_TICKS) {
            cir.setReturnValue(this.blockforge$occupyCacheResult);
        }
    }

    @Inject(method = "isAnotherMobInteractingWithTarget", at = @At("RETURN"))
    private void blockforge$storeOccupyResult(TransportItemsBetweenContainers.TransportItemTarget target,
                                              Level level,
                                              CallbackInfoReturnable<Boolean> cir) {
        this.blockforge$lastOccupyCheckTime = level.getGameTime();
        this.blockforge$occupyCachePos = target.pos();
        this.blockforge$occupyCacheResult = cir.getReturnValue();
    }
}
