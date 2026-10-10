# 铜傀儡分类加速 (mod_384a4d91)

Fabric 26.3 / Java 25 / Fabric Loom 1.17.20

## 功能

1. **分类冷却压缩** — 铜傀儡的 TRANSPORT_ITEMS_COOLDOWN_TICKS 被压到 8 tick（0.4 秒）以内。
2. **搬运速度 ×1.5** — TransportItemsBetweenContainers 构造参数 speedModifier 乘 1.5。
3. **单次搬运数量 16 → 64** — 通过 ModifyConstant 替换原版硬编码上限。
4. **单次搬运种类 → 4 种** — 拾取结束后追加扫描源容器，把其他种类的物品也带上。
5. **寻路 / 占用检查缓存** — hasValidTravellingPath 缓存 8 tick，isAnotherMobInteractingWithTarget 缓存 4 tick。

## 构建

```bash
./gradlew clean build
```

产物位于 `build/libs/`。

## 注意事项

- 代码使用 **Mojang 官方映射**，因此 build.gradle 里必须写 `mappings loom.officialMojangMappings()`。
- `mod_384a4d91.mixins.json` 的 `compatibilityLevel` 必须是 `JAVA_25`，与 `options.release = 25` 保持一致。
- `pickUpItems` / `TransportItemTarget#container` / `CopperGolem#getInventory` 等方法名依赖具体版本，
  跨版本升级时请用 IDE 反编译 `TransportItemsBetweenContainers` 校对签名。
