package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Generic payload for syncing block groups in group-based modes (Wall, Tower, Tree).
 * Replaces WallBlockGroupsS2CPayload, TowerBlockGroupsS2CPayload, TreeBlockGroupsS2CPayload.
 */
public record GroupModeBlockGroupsS2CPayload(int entityId, BuildMode mode, List<Integer> groups) implements CustomPacketPayload {

    public GroupModeBlockGroupsS2CPayload {
        groups = PayloadValidator.validateList(groups, 0, "groups");
    }

    public static final Type<GroupModeBlockGroupsS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "group_mode_block_groups"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GroupModeBlockGroupsS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GroupModeBlockGroupsS2CPayload::entityId,
            BuildMode.PACKET_CODEC, GroupModeBlockGroupsS2CPayload::mode,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), GroupModeBlockGroupsS2CPayload::groups,
            GroupModeBlockGroupsS2CPayload::new
    );

    @Override
    public Type<GroupModeBlockGroupsS2CPayload> type() { return ID; }
}
