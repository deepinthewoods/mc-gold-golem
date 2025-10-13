package ninja.trek.mc.goldgolem.registry;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import ninja.trek.mc.goldgolem.GoldGolem;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public final class GoldGolemEntities {
    private GoldGolemEntities() {}

    public static EntityType<GoldGolemEntity> GOLD_GOLEM;

    public static void init() {
        var key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, GoldGolem.id("gold_golem"));
        EntityType<GoldGolemEntity> built = (EntityType<GoldGolemEntity>) FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, GoldGolemEntity::new)
                .dimensions(EntityDimensions.fixed(1.4f, 2.7f))
                .trackRangeChunks(8)
                .build(key);
        GOLD_GOLEM = Registry.register(Registries.ENTITY_TYPE, GoldGolem.id("gold_golem"), built);
        FabricDefaultAttributeRegistry.register(GOLD_GOLEM, GoldGolemEntity.createAttributes());
    }
}
