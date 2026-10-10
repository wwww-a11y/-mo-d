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
 * 铜傀儡"分类搬运"行为（TransportItemsBetweenContainers）的性能与吞吐调整：
 *
 * 1. 搬运速度：构造参数 speedModifier 乘以系数，傀儡往返箱子更快，分类吞吐更高。
 * 2. 单次搬运数量：把原版拾取上限 16 提升到 64，一趟能搬更多。
 * 3. 单次搬运种类：原版一趟只认一种物品，这里在拾取完成后继续扫描源容器，
 *    把其它种类的物品也补进傀儡携带，最多同时带 4 种。
 * 4. 减卡顿：
 *    - hasValidTravellingPath（重新校验到目标箱子的路径，内部会跑寻路）
 *      原版在走路途中每个 tick 都可能调用；这里把结果按目标坐标缓存若干 tick，
 *      路径失效最多晚 0.4 秒才被发觉，但寻路开销降到一个零头。
 *    - isAnotherMobInteractingWithTarget（在目标周围扫描其他实体）
 *      同样做短时缓存，减少每 tick 的实体扫描。
 *    缓存字段加在行为实例上，每个傀儡的大脑各自持有一份，互不干扰。
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

    @Inject(method = "pickUpItems", at = @At("RETURN"), require = 0)
    private void blockforge$carryMultipleTypes(ServerLevel level,
                                               PathfinderMob mob,
                                               TransportItemsBetweenContainers.TransportItemTarget target,
                                               CallbackInfo ci) {
        if (!(mob instanceof CopperGolem golem)) {
            return;
        }
        Container source = target.container();
        if (source == null) {
            return;
        }

        // 使用 InventoryCarrier 接口获取物品栏，而不是直接调用 CopperGolem 的方法
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
                // SimpleContainer 有 addItem 方法，直接调用
                ((SimpleContainer) source).addItem(taken);
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
