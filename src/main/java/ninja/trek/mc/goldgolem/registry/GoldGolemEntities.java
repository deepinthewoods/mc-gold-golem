package ninja.trek.mc.goldgolem.registry;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import ninja.trek.mc.goldgolem.GoldGolem;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public final class GoldGolemEntities {
    private GoldGolemEntities() {}

    public static EntityType<GoldGolemEntity> GOLD_GOLEM;

    public static void init() {
        var key = ResourceKey.create(Registries.ENTITY_TYPE, GoldGolem.id("gold_golem"));
        // Hitbox: 13x13x13 pixels (0.8125 blocks cubed)
        EntityType<GoldGolemEntity> built = net.minecraft.world.entity.EntityType.Builder.of(GoldGolemEntity::new, MobCategory.CREATURE)
                .sized(13f/16f, 13f/16f)
                .build(key);
        GOLD_GOLEM = Registry.register(BuiltInRegistries.ENTITY_TYPE, GoldGolem.id("gold_golem"), built);
        FabricDefaultAttributeRegistry.register(GOLD_GOLEM, GoldGolemEntity.createAttributes());
    }
}
