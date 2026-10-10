package cn.blockforge.generated.mod384a4d91;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 铜傀儡分类加速 Mod。
 *
 * 真正的改动都在 mixin 包里：
 * - CopperGolemMixin：把"分类冷却"压到 8 tick 以内，傀儡分完一单马上接下一单；
 * - TransportItemsBetweenContainersMixin：搬运速度 ×1.5、单次搬运上限 64 个 / 4 种，
 *   并缓存每 tick 重复做的路径校验和"箱子被占用"检查，
 *   大幅降低傀儡群带来的服务器卡顿。
 */
public final class GeneratedMod implements ModInitializer {
    public static final String MOD_ID = "mod_384a4d91";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[{}] 铜傀儡分类加速已启用：冷却上限 8 tick，搬运速度 ×1.5，"
                + "单次搬运上限 64 个 / 4 种，寻路校验缓存 8 tick", MOD_ID);
    }
}
