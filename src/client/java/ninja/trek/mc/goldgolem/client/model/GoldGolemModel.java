package ninja.trek.mc.goldgolem.client.model;

import de.tomalbrc.bil.core.model.Model;
import de.tomalbrc.bil.file.loader.BbModelLoader;
import net.minecraft.util.Identifier;

public class GoldGolemModel {
    private static Model model;

    public static Model getModel() {
        if (model == null) {
            try {
                Identifier modelId = Identifier.of("gold-golem", "models/entity/goldgolem.bbmodel");
                model = BbModelLoader.load(modelId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load gold golem model", e);
            }
        }
        return model;
    }
}
