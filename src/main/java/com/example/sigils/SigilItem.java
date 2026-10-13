package com.example.sigils;

import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Rarity;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A sigil has several passives and several abilities. F (swap hands) or sneak + right-click cycles the
 * selected ability; right-click uses it. Every ability has its own cooldown, shown on the HUD.
 */
public class SigilItem extends Item {

    @FunctionalInterface
    public interface AbilityAction {
        /** Return false to cancel without spending the cooldown. */
        boolean run(ServerWorld world, ServerPlayerEntity player, ItemStack stack, int tier);
    }

    public record Ability(String name, String desc, int cooldownTicks, AbilityAction action) {}

    private final String title;
    private final Formatting color;
    private final List<Ability> abilities;
    private final List<String> passiveDescs;
    private final BiConsumer<ServerPlayerEntity, Integer> passive;
    private final Predicate<DamageSource> immunity;
    private boolean doubleJump = false;

    public SigilItem(Settings settings, String title, Formatting color, List<Ability> abilities,
                     List<String> passiveDescs, BiConsumer<ServerPlayerEntity, Integer> passive,
                     Predicate<DamageSource> immunity) {
        super(settings.maxCount(1).rarity(Rarity.EPIC));
        this.title = title;
        this.color = color;
        this.abilities = abilities;
        this.passiveDescs = passiveDescs;
        this.passive = passive;
        this.immunity = immunity;
    }

    public SigilItem withDoubleJump() { this.doubleJump = true; return this; }
    public boolean hasDoubleJump() { return doubleJump; }
    public void applyPassive(ServerPlayerEntity p, int tier) { passive.accept(p, tier); }
    public boolean immuneTo(DamageSource src) { return immunity != null && immunity.test(src); }

    // ---- read-only views used by the client HUD
    public List<Ability> abilities() { return abilities; }
    public Formatting color() { return color; }
    public String title() { return title; }

    public int selected(ItemStack stack) {
        return Math.floorMod(SigilData.readData(stack).getInt("sel", 0), abilities.size());
    }

    public long readyAt(ItemStack stack, int index) {
        return SigilData.readData(stack).getLong("cd" + index, 0L);
    }

    /** Higher tiers shave 10% per tier-step off every cooldown. */
    private int cooldownFor(Ability a, int tier) {
        return (int) (a.cooldownTicks() * (1.0 - 0.1 * (tier - 1)));
    }

    /** Switch to the next ability. */
    public void cycle(ServerPlayerEntity player, ItemStack stack) {
        NbtCompound data = SigilData.readData(stack);
        int sel = (selected(stack) + 1) % abilities.size();
        data.putInt("sel", sel);
        SigilData.writeData(stack, data);
        player.sendMessage(Text.literal("[" + (sel + 1) + "/" + abilities.size() + "] " + abilities.get(sel).name())
                .formatted(color), true);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient() || !(user instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS; // client: swing; the server decides what happens
        }

        // First use binds the sigil to its owner.
        if (!SigilData.hasOwner(stack)) {
            SigilData.bind(stack, player);
            player.sendMessage(Text.literal("The sigil binds to your soul.").formatted(color), true);
            return ActionResult.SUCCESS;
        }
        if (!SigilData.isOwner(stack, player)) {
            player.sendMessage(Text.literal("This sigil rejects you.").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }

        // Sneak + use: cycle (same as pressing F).
        if (player.isSneaking()) {
            cycle(player, stack);
            return ActionResult.SUCCESS;
        }

        if (SigilData.active("silence", player.getUuid())) {
            player.sendMessage(Text.literal("You are silenced!").formatted(Formatting.GRAY), true);
            return ActionResult.FAIL;
        }

        int sel = selected(stack);
        Ability ability = abilities.get(sel);
        long now = world.getTime();
        long readyAt = readyAt(stack, sel);
        if (now < readyAt) {
            long secs = (readyAt - now + 19) / 20;
            player.sendMessage(Text.literal(ability.name() + " ready in " + secs + "s").formatted(Formatting.GRAY), true);
            return ActionResult.FAIL;
        }

        int tier = SigilData.getTier(stack);
        if (ability.action().run((ServerWorld) world, player, stack, tier)) {
            NbtCompound fresh = SigilData.readData(stack); // the ability may have written data (e.g. anchor)
            fresh.putLong("cd" + sel, now + cooldownFor(ability, tier));
            SigilData.writeData(stack, fresh);
            return ActionResult.SUCCESS;
        }
        return ActionResult.FAIL;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext ctx, TooltipDisplayComponent display,
                              Consumer<Text> tooltip, TooltipType type) {
        int kills = SigilData.getKills(stack);
        int tier = SigilData.tierFor(kills);
        int sel = selected(stack);

        tooltip.accept(Text.literal("Tier " + tier + " / " + SigilData.MAX_TIER + "  (" + kills + " kills)").formatted(color));
        tooltip.accept(Text.literal("Passives:").formatted(Formatting.GOLD));
        for (String s : passiveDescs) tooltip.accept(Text.literal(" - " + s).formatted(Formatting.GRAY));
        tooltip.accept(Text.literal("Abilities (F or sneak+use to switch):").formatted(Formatting.GOLD));
        for (int i = 0; i < abilities.size(); i++) {
            Ability a = abilities.get(i);
            tooltip.accept(Text.literal((i == sel ? "> " : "  ") + a.name() + " (" + a.cooldownTicks() / 20 + "s)")
                    .formatted(i == sel ? color : Formatting.GRAY));
            tooltip.accept(Text.literal("     " + a.desc()).formatted(Formatting.DARK_GRAY));
        }
        tooltip.accept(Text.literal(SigilData.hasOwner(stack) ? "Bound to " + SigilData.ownerName(stack) : "Right-click to bind")
                .formatted(Formatting.DARK_GRAY));
    }
}
