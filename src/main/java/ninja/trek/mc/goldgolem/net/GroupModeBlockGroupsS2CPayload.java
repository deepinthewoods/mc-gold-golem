package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.BuildMode;

import java.util.List;

/**
 * Generic payload for syncing block groups in group-based modes (Wall, Tower, Tree).
 * Replaces WallBlockGroupsS2CPayload, TowerBlockGroupsS2CPayload, TreeBlockGroupsS2CPayload.
 */
public record GroupModeBlockGroupsS2CPayload(int entityId, BuildMode mode, List<Integer> groups) implements CustomPayload {

    public GroupModeBlockGroupsS2CPayload {
        groups = PayloadValidator.validateList(groups, 0, "groups");
    }

    public static final Id<GroupModeBlockGroupsS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "group_mode_block_groups"));

    public static final PacketCodec<RegistryByteBuf, GroupModeBlockGroupsS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, GroupModeBlockGroupsS2CPayload::entityId,
            BuildMode.PACKET_CODEC, GroupModeBlockGroupsS2CPayload::mode,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), GroupModeBlockGroupsS2CPayload::groups,
            GroupModeBlockGroupsS2CPayload::new
    );

    @Override
    public Id<GroupModeBlockGroupsS2CPayload> getId() { return ID; }
}
