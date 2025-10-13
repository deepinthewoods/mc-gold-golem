package ninja.trek.mc.goldgolem.client.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.client.state.ClientState;

import java.util.List;
import java.util.Map;

public final class DebugLineParticles {
    private DebugLineParticles() {}

    public static void init() {
        ClientTickEvents.END_WORLD_TICK.register(DebugLineParticles::onWorldTick);
    }

    private static void onWorldTick(ClientWorld world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        boolean holding = mc.player.getMainHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET)
                || mc.player.getOffHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET);
        if (!holding) return;
        Map<Integer, List<BlockPos>> all = ClientState.getAllLines();
        if (all.isEmpty()) return;
        // Use a bright, visible particle for debug lines
        int budget = 150; // particle budget per tick
        for (var entry : all.entrySet()) {
            List<BlockPos> pts = entry.getValue();
            for (int i = 0; i + 1 < pts.size(); i += 2) {
                Vec3d a = Vec3d.ofCenter(pts.get(i));
                Vec3d b = Vec3d.ofCenter(pts.get(i + 1));
                double len = a.distanceTo(b);
                int samples = Math.max(2, Math.min(60, (int) Math.ceil(len * 8))); // ~8 samples per block
                for (int k = 0; k <= samples && budget > 0; k++) {
                    double t = (double) k / (double) samples;
                    double x = a.x + (b.x - a.x) * t;
                    double y = a.y + (b.y - a.y) * t + 0.05;
                    double z = a.z + (b.z - a.z) * t;
                    MinecraftClient.getInstance().particleManager.addParticle(ParticleTypes.END_ROD, x, y, z, 0.0, 0.0, 0.0);
                    budget--;
                }
                if (budget <= 0) return;
            }
        }
    }
}
