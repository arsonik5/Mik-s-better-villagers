package dev.mike.bettervillagers.screen;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantMenu;

import dev.mike.bettervillagers.BetterVillagers;

/**
 * Extends the vanilla merchant menu so trade-offer sync, slot handling, and
 * price computation are all inherited for free (the client's trade-offer
 * packet handling accepts any MerchantMenu subclass, not just the exact
 * vanilla type).
 */
public class VillagerTalkMenu extends MerchantMenu {
    public static ExtendedMenuType<VillagerTalkMenu, Integer> TYPE;

    private final int villagerEntityId;

    public VillagerTalkMenu(int syncId, Inventory playerInventory, AbstractVillager villager) {
        super(syncId, playerInventory, villager);
        this.villagerEntityId = villager.getId();
    }

    public VillagerTalkMenu(int syncId, Inventory playerInventory, int villagerEntityId) {
        super(syncId, playerInventory);
        this.villagerEntityId = villagerEntityId;
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    @Override
    public MenuType<?> getType() {
        // MerchantMenu's own constructors hardcode MenuType.MERCHANT; override
        // so Fabric's extended-menu-provider check sees our registered type.
        return TYPE;
    }

    public static void register() {
        TYPE = Registry.register(
                BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath(BetterVillagers.MOD_ID, "villager_talk"),
                new ExtendedMenuType<>(
                        (syncId, inventory, entityId) -> new VillagerTalkMenu(syncId, inventory, entityId),
                        ByteBufCodecs.VAR_INT
                )
        );
    }
}
