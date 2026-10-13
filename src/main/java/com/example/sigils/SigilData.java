package com.example.sigils;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Sigil state (owner, kills, selected ability, cooldowns) lives in the item's custom data. */
public final class SigilData {
    public static final int MAX_TIER = 3;
    public static final int KILLS_FOR_TIER_2 = 3;
    public static final int KILLS_FOR_TIER_3 = 8;
    public static final int DEATH_PENALTY = 3;

    /** Server tick counter, updated every tick. */
    public static int now = 0;

    private static final Map<String, Map<UUID, Integer>> TIMERS = new HashMap<>();
    private static final Map<UUID, int[]> INPUT = new HashMap<>();
    private static final Set<UUID> AIR_JUMP_USED = new HashSet<>();

    private SigilData() {}

    // ---- item data
    public static NbtCompound readData(ItemStack s) {
        return s.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
    }

    public static void writeData(ItemStack s, NbtCompound n) {
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(n));
    }

    public static boolean hasOwner(ItemStack s) { return !readData(s).getString("owner", "").isEmpty(); }

    public static boolean isOwner(ItemStack s, PlayerEntity p) {
        return readData(s).getString("owner", "").equals(p.getUuidAsString());
    }

    public static String ownerName(ItemStack s) { return readData(s).getString("ownerName", ""); }

    public static void bind(ItemStack s, PlayerEntity p) {
        NbtCompound n = readData(s);
        n.putString("owner", p.getUuidAsString());
        n.putString("ownerName", p.getName().getString());
        writeData(s, n);
    }

    public static int getKills(ItemStack s) { return readData(s).getInt("kills", 0); }

    public static void setKills(ItemStack s, int kills) {
        NbtCompound n = readData(s);
        n.putInt("kills", Math.max(0, kills));
        writeData(s, n);
    }

    public static int tierFor(int kills) {
        if (kills >= KILLS_FOR_TIER_3) return 3;
        if (kills >= KILLS_FOR_TIER_2) return 2;
        return 1;
    }

    public static int getTier(ItemStack s) { return tierFor(getKills(s)); }

    // ---- named timers (silence, phased, bloodlust, ...) measured in server ticks
    public static void timer(String name, UUID id, int ticks) {
        TIMERS.computeIfAbsent(name, k -> new HashMap<>()).merge(id, now + ticks, Math::max);
    }

    public static boolean active(String name, UUID id) {
        Map<UUID, Integer> m = TIMERS.get(name);
        if (m == null) return false;
        Integer until = m.get(id);
        if (until == null) return false;
        if (now >= until) { m.remove(id); return false; }
        return true;
    }

    // ---- client steering input (forward: +1/-1, strafe: +1 = left, -1 = right)
    public static void setInput(UUID id, int forward, int strafe) {
        INPUT.put(id, new int[] { Integer.signum(forward), Integer.signum(strafe) });
    }

    public static int[] input(UUID id) {
        return INPUT.getOrDefault(id, new int[] { 0, 0 });
    }

    // ---- double jump bookkeeping
    public static boolean useAirJump(UUID player) { return AIR_JUMP_USED.add(player); }

    public static void resetAirJump(UUID player) { if (!AIR_JUMP_USED.isEmpty()) AIR_JUMP_USED.remove(player); }

    public static boolean holdsDoubleJump(ServerPlayerEntity player) {
        boolean[] r = new boolean[1];
        forEachOwnedSigil(player, (stack, sigil) -> { if (sigil.hasDoubleJump()) r[0] = true; });
        return r[0];
    }

    // ---- passive immunities
    public static boolean isImmune(ServerPlayerEntity player, DamageSource source) {
        boolean[] result = new boolean[1];
        forEachOwnedSigil(player, (stack, sigil) -> { if (sigil.immuneTo(source)) result[0] = true; });
        return result[0];
    }

    /** Runs the action for every sigil in the player's inventory that is bound to that player. */
    public static void forEachOwnedSigil(ServerPlayerEntity player, BiConsumer<ItemStack, SigilItem> action) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() instanceof SigilItem sigil && isOwner(stack, player)) {
                action.accept(stack, sigil);
            }
        }
    }
}
