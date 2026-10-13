package com.example.sigils;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * Client side:
 *  - HUD with the held sigil's abilities and live cooldown bars
 *  - F (swap hands) cycles abilities while a sigil is in your main hand
 *  - sends your WASD steering so dashes go where you steer
 *  - double jump detection
 *  - screen shake / colored flashes sent by abilities
 */
public class SigilsClient implements ClientModInitializer {
    // double jump
    private boolean available = true;
    private boolean releasedInAir = false;

    // steering input (sentinel forces a first send)
    private int lastForward = 99, lastStrafe = 99;

    // screen fx
    private int shakeTicks = 0, shakeTotal = 1, shakeStrength = 0;
    private int flashTicks = 0, flashTotal = 1, flashAlpha = 0, flashRgb = 0;

    private final java.util.Random random = new java.util.Random();

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(FxPayload.ID, (payload, context) -> handleFx(payload));

        // F key: consume the swap-hands press BEFORE vanilla sees it, if a sigil is in the main hand.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            ClientPlayerEntity p = client.player;
            if (p == null) return;
            if (p.getMainHandStack().getItem() instanceof SigilItem) {
                while (client.options.swapHandsKey.wasPressed()) {
                    ClientPlayNetworking.send(new CycleAbilityPayload());
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity p = client.player;
            if (p == null) { lastForward = 99; return; }
            tickDoubleJump(client, p);
            tickInput(client, p);
            tickShake(p);
            if (flashTicks > 0) flashTicks--;
        });

        HudElementRegistry.addLast(Identifier.of(SigilsMod.MOD_ID, "sigil_hud"),
                (context, tickCounter) -> renderHud(context));
    }

    // ------------------------------------------------------------------ double jump
    private void tickDoubleJump(MinecraftClient client, ClientPlayerEntity p) {
        if (p.isOnGround() || p.isClimbing() || p.isTouchingWater() || p.hasVehicle()) {
            available = true;
            releasedInAir = false;
            return;
        }
        boolean jumpDown = client.options.jumpKey.isPressed();
        if (!jumpDown) { releasedInAir = true; return; }
        if (available && releasedInAir && hasDoubleJumpSigil(p)) {
            ClientPlayNetworking.send(new DoubleJumpPayload());
            available = false;
            releasedInAir = false;
        }
    }

    private static boolean hasDoubleJumpSigil(ClientPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.getItem() instanceof SigilItem sigil && sigil.hasDoubleJump() && SigilData.isOwner(s, p)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ steering input
    private void tickInput(MinecraftClient client, ClientPlayerEntity p) {
        if (!(p.getMainHandStack().getItem() instanceof SigilItem)) { lastForward = 99; return; }
        int f = (client.options.forwardKey.isPressed() ? 1 : 0) - (client.options.backKey.isPressed() ? 1 : 0);
        int s = (client.options.leftKey.isPressed() ? 1 : 0) - (client.options.rightKey.isPressed() ? 1 : 0);
        if (f != lastForward || s != lastStrafe) {
            ClientPlayNetworking.send(new InputPayload(f, s));
            lastForward = f;
            lastStrafe = s;
        }
    }

    // ------------------------------------------------------------------ screen fx
    private void handleFx(FxPayload payload) {
        int strength = Math.max(1, Math.min(payload.strength(), 12));
        if (payload.kind() == FxPayload.SHAKE) {
            shakeStrength = Math.max(shakeStrength, strength);
            shakeTotal = shakeTicks = 8 + strength * 2;
            return;
        }
        int rgb = switch (payload.kind()) {
            case FxPayload.FLASH_WHITE -> 0xFFFFFF;
            case FxPayload.FLASH_DARK -> 0x000000;
            case FxPayload.FLASH_SAND -> 0xD8B864;
            case FxPayload.FLASH_PURPLE -> 0x7A2DBD;
            case FxPayload.FLASH_RED -> 0xBB1111;
            case FxPayload.FLASH_BLUE -> 0x3377CC;
            default -> -1;
        };
        if (rgb < 0) return;
        flashRgb = rgb;
        flashAlpha = Math.min(170, 50 + strength * 12);
        flashTotal = flashTicks = 8 + strength;
    }

    private void tickShake(ClientPlayerEntity p) {
        if (shakeTicks <= 0) { shakeStrength = 0; return; }
        float mag = shakeStrength * 0.35f * shakeTicks / shakeTotal;
        p.setYaw(p.getYaw() + (random.nextFloat() - 0.5f) * 2f * mag);
        p.setPitch(MathHelper.clamp(p.getPitch() + (random.nextFloat() - 0.5f) * 2f * mag, -90f, 90f));
        shakeTicks--;
    }

    // ------------------------------------------------------------------ HUD
    private void renderHud(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();

        if (flashTicks > 0) {
            int a = (int) (flashAlpha * (flashTicks / (float) flashTotal));
            context.fill(0, 0, w, h, (a << 24) | flashRgb);
        }

        ItemStack hand = p.getMainHandStack();
        if (!(hand.getItem() instanceof SigilItem sigil) || !SigilData.hasOwner(hand)) return;

        long now = mc.world.getTime();
        int sel = sigil.selected(hand);
        int x = w - 150;
        int y = h - 62;

        context.drawTextWithShadow(mc.textRenderer, Text.literal("Sigil of " + sigil.title() + "  (F: switch)"),
                x, y - 12, 0xFFFFFFFF);

        for (int i = 0; i < sigil.abilities().size(); i++) {
            SigilItem.Ability a = sigil.abilities().get(i);
            long left = Math.max(0, sigil.readyAt(hand, i) - now);
            boolean ready = left == 0;
            String label = (i == sel ? "> " : "  ") + a.name() + (ready ? "  READY" : "  " + ((left + 19) / 20) + "s");
            int color = !ready ? 0xFF888888 : (i == sel ? 0xFFFFFF55 : 0xFFCCCCCC);
            int ty = y + i * 14;
            context.drawTextWithShadow(mc.textRenderer, Text.literal(label), x, ty, color);
            if (!ready) {
                float frac = 1.0f - (left / (float) Math.max(1, a.cooldownTicks()));
                context.fill(x, ty + 10, x + 130, ty + 11, 0xFF333333);
                context.fill(x, ty + 10, x + (int) (130 * frac), ty + 11, 0xFF55AAFF);
            }
        }
    }
}
