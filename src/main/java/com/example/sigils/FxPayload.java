package com.example.sigils;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> client: screen shake / colored screen flash. */
public record FxPayload(int kind, int strength) implements CustomPayload {
    public static final int SHAKE = 1, FLASH_WHITE = 2, FLASH_DARK = 3, FLASH_SAND = 4,
            FLASH_PURPLE = 5, FLASH_RED = 6, FLASH_BLUE = 7;

    public static final CustomPayload.Id<FxPayload> ID =
            new CustomPayload.Id<>(Identifier.of(SigilsMod.MOD_ID, "fx"));
    public static final PacketCodec<RegistryByteBuf, FxPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, FxPayload::kind,
            PacketCodecs.INTEGER, FxPayload::strength,
            FxPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
