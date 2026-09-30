package me.oredev.escadona.lobby;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Optional;

public final class LobbyItems {
    private static final String TYPE_KEY = "escadona:item_type";
    private static final String ARENA_KEY = "escadona:arena_id";
    private static final String SELECTOR = "lobby_selector";
    private static final String ARENA = "arena_entry";
    private static final String PREVIOUS = "previous_page";
    private static final String NEXT = "next_page";

    private LobbyItems() { }

    public static ItemStack selector() {
        ItemStack stack = marked(new ItemStack(Items.PAPER), SELECTOR, null);
        stack.set(DataComponents.CUSTOM_NAME, text("Selecionar Arena", ChatFormatting.GOLD, true));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                text("Clique com o botão direito para escolher uma arena.", ChatFormatting.AQUA, false))));
        return stack;
    }

    public static ItemStack arena(ArenaMenuEntry entry) {
        ItemStack stack = marked(new ItemStack(Items.FILLED_MAP), ARENA, entry.arenaId());
        stack.set(DataComponents.CUSTOM_NAME, text(entry.displayName(), entry.state().color(), true));
        Component action = entry.state().joinable() && entry.players() < entry.capacity()
                ? text("Clique para entrar!", ChatFormatting.GREEN, false)
                : text("Arena indisponível para entrada.", ChatFormatting.DARK_GRAY, false);
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                text("Jogadores: " + entry.players() + "/" + entry.capacity(), ChatFormatting.WHITE, false),
                text("Estado: " + entry.state().label(), entry.state().color(), false),
                Component.empty(), action)));
        return stack;
    }

    public static ItemStack previousPage() { return navigation("Página anterior", PREVIOUS); }
    public static ItemStack nextPage() { return navigation("Próxima página", NEXT); }

    public static boolean isSelector(ItemStack stack) { return hasType(stack, SELECTOR); }
    public static boolean shouldPreventDrop(ItemStack stack, boolean lobbyContext) {
        return lobbyContext && isSelector(stack);
    }
    public static boolean isPreviousPage(ItemStack stack) { return hasType(stack, PREVIOUS); }
    public static boolean isNextPage(ItemStack stack) { return hasType(stack, NEXT); }

    public static Optional<String> arenaId(ItemStack stack) {
        if (!hasType(stack, ARENA)) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? Optional.empty() : data.copyTag().getString(ARENA_KEY).filter(value -> !value.isBlank());
    }

    private static ItemStack navigation(String name, String type) {
        ItemStack stack = marked(new ItemStack(Items.ARROW), type, null);
        stack.set(DataComponents.CUSTOM_NAME, text(name, ChatFormatting.YELLOW, true));
        return stack;
    }

    private static ItemStack marked(ItemStack stack, String type, String arenaId) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TYPE_KEY, type);
        if (arenaId != null) tag.putString(ARENA_KEY, arenaId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static boolean hasType(ItemStack stack, String expected) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && expected.equals(data.copyTag().getStringOr(TYPE_KEY, ""));
    }

    private static Component text(String value, ChatFormatting color, boolean bold) {
        return Component.literal(value).withStyle(style -> style.withColor(color).withBold(bold).withItalic(false));
    }
}
