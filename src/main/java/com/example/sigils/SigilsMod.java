package com.example.sigils;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

public class SigilsMod implements ModInitializer {
    public static final String MOD_ID = "sigils";

    @Override
    public void onInitialize() {
        ModItems.init();
        registerNetworking();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SigilData.now = server.getTicks();
            Scheduler.tick(server);

            for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                if (pl.isOnGround()) SigilData.resetAirJump(pl.getUuid());
            }

            // Passives: refresh every 10 ticks for owners carrying their sigil.
            if (server.getTicks() % 10 != 0) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.isSpectator() || !player.isAlive()) continue;
                SigilData.forEachOwnedSigil(player, (stack, sigil) -> sigil.applyPassive(player, SigilData.getTier(stack)));
            }
        });

        // Damage rules: passive immunities, Specter Form projectile phasing, Bloodlust lifesteal.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity sp) {
                if (SigilData.isImmune(sp, source)) return false;
                if (SigilData.active("phased", sp.getUuid()) && source.isIn(DamageTypeTags.IS_PROJECTILE)) return false;
            }
            if (source.getAttacker() instanceof ServerPlayerEntity atk && atk != entity
                    && SigilData.active("bloodlust", atk.getUuid())) {
                atk.heal(Math.min(amount, 10.0f) * 0.25f);
            }
            return true;
        });

        // Dying costs the victim sigil progress (runs before the death is final).
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity victim) {
                SigilData.forEachOwnedSigil(victim, (stack, sigil) ->
                        SigilData.setKills(stack, SigilData.getKills(stack) - SigilData.DEATH_PENALTY));
            }
            return true;
        });

        // PvP kills level the killer's sigil up (melee, projectiles, etc.).
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity victim
                    && source.getAttacker() instanceof ServerPlayerEntity killer
                    && killer != victim) {
                SigilData.forEachOwnedSigil(killer, (stack, sigil) -> {
                    int before = SigilData.getTier(stack);
                    SigilData.setKills(stack, SigilData.getKills(stack) + 1);
                    int after = SigilData.getTier(stack);
                    if (after > before) {
                        killer.sendMessage(Text.literal("Your sigil ascends to tier " + after + "!").formatted(Formatting.GOLD), true);
                    }
                });
            }
        });
    }

    private void registerNetworking() {
        PayloadTypeRegistry.playC2S().register(DoubleJumpPayload.ID, DoubleJumpPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(InputPayload.ID, InputPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CycleAbilityPayload.ID, CycleAbilityPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FxPayload.ID, FxPayload.CODEC);

        // Double jump: the client reports a mid-air jump press; the server decides if it is allowed.
        ServerPlayNetworking.registerGlobalReceiver(DoubleJumpPayload.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            if (p.isOnGround() || p.isSpectator() || !p.isAlive()) return;
            if (SigilData.active("silence", p.getUuid())) return;
            if (!SigilData.holdsDoubleJump(p)) return;
            if (!SigilData.useAirJump(p.getUuid())) return; // already used since last landing
            Vec3d v = p.getVelocity();
            p.setVelocity(v.x, 0.75, v.z);
            p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
        });

        // Steering input, so dashes follow WASD.
        ServerPlayNetworking.registerGlobalReceiver(InputPayload.ID, (payload, context) ->
                SigilData.setInput(context.player().getUuid(), payload.forward(), payload.strafe()));

        // F key: switch ability on the held sigil.
        ServerPlayNetworking.registerGlobalReceiver(CycleAbilityPayload.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            ItemStack st = p.getMainHandStack();
            if (st.getItem() instanceof SigilItem sigil && SigilData.isOwner(st, p)) {
                sigil.cycle(p, st);
            }
        });
    }
}
