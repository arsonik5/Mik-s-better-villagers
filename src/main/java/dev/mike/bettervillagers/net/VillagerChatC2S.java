package dev.mike.bettervillagers.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import dev.mike.bettervillagers.BetterVillagers;

/** Client -> server: the player said something to this villager. */
public record VillagerChatC2S(int villagerEntityId, String message) implements CustomPacketPayload {
    public static final Type<VillagerChatC2S> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BetterVillagers.MOD_ID, "villager_chat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerChatC2S> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VillagerChatC2S::villagerEntityId,
            ByteBufCodecs.stringUtf8(2000), VillagerChatC2S::message,
            VillagerChatC2S::new
    );

    @Override
    public Type<VillagerChatC2S> type() {
        return TYPE;
    }
}
