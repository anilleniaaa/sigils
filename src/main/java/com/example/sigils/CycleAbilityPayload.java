package com.example.sigils;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: switch to the next ability on the held sigil (swap-hands key). */
public record CycleAbilityPayload() implements CustomPayload {
    public static final CustomPayload.Id<CycleAbilityPayload> ID =
            new CustomPayload.Id<>(Identifier.of(SigilsMod.MOD_ID, "cycle_ability"));
    public static final PacketCodec<RegistryByteBuf, CycleAbilityPayload> CODEC =
            PacketCodec.unit(new CycleAbilityPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
