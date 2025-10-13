package ninja.trek.mc.goldgolem;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.registry.ModScreenHandlers;
import ninja.trek.mc.goldgolem.registry.GoldGolemEntities;
import ninja.trek.mc.goldgolem.net.NetworkInit;
import ninja.trek.mc.goldgolem.summon.PumpkinSummoning;

public class GoldGolem implements ModInitializer {
    public static final String MOD_ID = "gold-golem";

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        GoldGolemEntities.init();
        ModScreenHandlers.init();
        NetworkInit.register();
        PumpkinSummoning.register();
    }
}
