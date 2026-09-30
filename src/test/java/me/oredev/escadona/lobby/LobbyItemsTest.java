package me.oredev.escadona.lobby;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LobbyItemsTest {
    @BeforeAll
    static void bootstrap() {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void selectorUsesPaperAndRobustCustomMarker() {
        ItemStack selector = LobbyItems.selector();
        ItemStack ordinaryPaperWithSameName = new ItemStack(Items.PAPER);
        ordinaryPaperWithSameName.set(DataComponents.CUSTOM_NAME, Component.literal("Selecionar Arena"));

        assertTrue(selector.is(Items.PAPER));
        assertEquals("Selecionar Arena", selector.get(DataComponents.CUSTOM_NAME).getString());
        assertTrue(LobbyItems.isSelector(selector));
        assertFalse(LobbyItems.isSelector(ordinaryPaperWithSameName));
        assertTrue(LobbyItems.shouldPreventDrop(selector, true));
        assertFalse(LobbyItems.shouldPreventDrop(selector, false));
        assertFalse(LobbyItems.shouldPreventDrop(ordinaryPaperWithSameName, true));
    }

    @Test
    void arenaEntryUsesFilledMapAndStoresArenaIdOutsideDisplayName() {
        ArenaMenuEntry entry = new ArenaMenuEntry("classic", "Escadona Clássica", 3, 8,
                ArenaDisplayState.WAITING);

        ItemStack stack = LobbyItems.arena(entry);

        assertTrue(stack.is(Items.FILLED_MAP));
        assertEquals("Escadona Clássica", stack.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals("classic", LobbyItems.arenaId(stack).orElseThrow());
        var lore = stack.get(DataComponents.LORE).lines().stream().map(Component::getString).toList();
        assertTrue(lore.contains("Jogadores: 3/8"));
        assertTrue(lore.contains("Estado: Aguardando"));
        assertTrue(lore.contains("Clique para entrar!"));
    }
}
