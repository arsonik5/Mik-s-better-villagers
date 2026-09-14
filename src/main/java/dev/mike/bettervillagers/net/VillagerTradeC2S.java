package dev.mike.bettervillagers.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import dev.mike.bettervillagers.BetterVillagers;

/** Client -> server: "execute trade offer N with this villager." */
public record VillagerTradeC2S(int villagerEntityId, int offerIndex) implements CustomPacketPayload {
    public static final Type<VillagerTradeC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BetterVillagers.MOD_ID, "villager_trade"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerTradeC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VillagerTradeC2S::villagerEntityId,
            ByteBufCodecs.VAR_INT, VillagerTradeC2S::offerIndex,
            VillagerTradeC2S::new
    );

    @Override
    public Type<VillagerTradeC2S> type() {
        return TYPE;
    }
}
