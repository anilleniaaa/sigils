package com.example.sigils;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: which way the player is steering (WASD), so dashes follow your movement. */
public record InputPayload(int forward, int strafe) implements CustomPayload {
    public static final CustomPayload.Id<InputPayload> ID =
            new CustomPayload.Id<>(Identifier.of(SigilsMod.MOD_ID, "input"));
    public static final PacketCodec<RegistryByteBuf, InputPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, InputPayload::forward,
            PacketCodecs.INTEGER, InputPayload::strafe,
            InputPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
