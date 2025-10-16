package ninja.trek.mc.goldgolem.registry;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
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
        // Hitbox: 13x13x13 pixels (0.8125 blocks cubed)
        EntityType<GoldGolemEntity> built = net.minecraft.entity.EntityType.Builder.create(GoldGolemEntity::new, SpawnGroup.CREATURE)
                .dimensions(13f/16f, 13f/16f)
                .build(key);
        GOLD_GOLEM = Registry.register(Registries.ENTITY_TYPE, GoldGolem.id("gold_golem"), built);
        FabricDefaultAttributeRegistry.register(GOLD_GOLEM, GoldGolemEntity.createAttributes());
    }
}
