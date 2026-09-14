package dev.mike.bettervillagers.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.trading.MerchantOffers;

import dev.mike.bettervillagers.BetterVillagers;

/**
 * Server -> client: current trade offers for a villager. Sent right after
 * the talk screen opens, and again whenever offers change (a trade executes,
 * or a future LLM-driven regeneration replaces the list).
 */
public record VillagerOffersS2C(int villagerEntityId, MerchantOffers offers) implements CustomPacketPayload {
    public static final Type<VillagerOffersS2C> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BetterVillagers.MOD_ID, "villager_offers"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerOffersS2C> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VillagerOffersS2C::villagerEntityId,
            MerchantOffers.STREAM_CODEC, VillagerOffersS2C::offers,
            VillagerOffersS2C::new
    );

    @Override
    public Type<VillagerOffersS2C> type() {
        return TYPE;
    }
}
