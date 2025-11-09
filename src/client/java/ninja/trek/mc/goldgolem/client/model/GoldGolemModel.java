package ninja.trek.mc.goldgolem.client.model;

import net.minecraft.util.Identifier;

public class GoldGolemModel {
    private static BBModelParser.BBModel model;

    public static BBModelParser.BBModel getModel() {
        if (model == null) {
            try {
                Identifier modelId = Identifier.of("gold-golem", "models/entity/goldgolem.bbmodel");
                model = BBModelParser.parse(modelId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load gold golem model", e);
            }
        }
        return model;
    }
}
