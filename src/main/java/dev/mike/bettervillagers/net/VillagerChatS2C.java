package dev.mike.bettervillagers.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import dev.mike.bettervillagers.BetterVillagers;

/** Server -> client: the villager's reply (or a status message if the LLM isn't available). */
public record VillagerChatS2C(int villagerEntityId, String reply) implements CustomPacketPayload {
    public static final Type<VillagerChatS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BetterVillagers.MOD_ID, "villager_chat_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerChatS2C> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VillagerChatS2C::villagerEntityId,
            ByteBufCodecs.stringUtf8(1024), VillagerChatS2C::reply,
            VillagerChatS2C::new
    );

    @Override
    public Type<VillagerChatS2C> type() {
        return TYPE;
    }
}
