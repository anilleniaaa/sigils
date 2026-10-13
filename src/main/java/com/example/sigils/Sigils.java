package com.example.sigils;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;

/** The ten sigils, one per armor-trim template. Three abilities each. */
public final class Sigils {
    private Sigils() {}

    private static final String[] DIRS = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };

    // ================================================================== helpers

    private static StatusEffectInstance passive(RegistryEntry<StatusEffect> e, int amp) {
        return new StatusEffectInstance(e, 40, amp, true, false, false); // ambient, no particles, no icon
    }

    private static StatusEffectInstance fx(RegistryEntry<StatusEffect> e, int ticks, int amp) {
        return new StatusEffectInstance(e, ticks, amp);
    }

    private static void msg(ServerPlayerEntity p, String s) {
        p.sendMessage(Text.literal(s).formatted(Formatting.GRAY), true);
    }

    private static void say(ServerPlayerEntity p, String s, Formatting f) {
        p.sendMessage(Text.literal(s).formatted(f), true);
    }

    private static void sound(ServerWorld w, Entity at, SoundEvent s, float vol, float pitch) {
        w.playSound(null, at.getBlockPos(), s, SoundCategory.PLAYERS, vol, pitch);
    }

    private static void later(ServerWorld w, int ticks, Runnable r) {
        Scheduler.later(w.getServer(), ticks, r);
    }

    private static SigilItem.Ability ab(String name, String desc, int cooldownSeconds, SigilItem.AbilityAction a) {
        return new SigilItem.Ability(name, desc, cooldownSeconds * 20, a);
    }

    /** Screen shake / flash for one entity (only does something if it is a player with the mod). */
    private static void fxTo(Entity e, int kind, int strength) {
        if (e instanceof ServerPlayerEntity sp) ServerPlayNetworking.send(sp, new FxPayload(kind, strength));
    }

    private static void fxAround(ServerWorld w, Entity c, double r, int kind, int strength) {
        for (ServerPlayerEntity sp : w.getPlayers()) {
            if (sp.squaredDistanceTo(c) <= r * r) ServerPlayNetworking.send(sp, new FxPayload(kind, strength));
        }
    }

    /** Sets velocity and makes sure players actually receive it. */
    private static void launch(Entity e, Vec3d v) {
        e.setVelocity(v);
        if (e instanceof ServerPlayerEntity sp) {
            sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
        }
    }

    private static List<LivingEntity> nearby(ServerWorld w, Entity c, double r) {
        return w.getEntitiesByClass(LivingEntity.class, c.getBoundingBox().expand(r),
                e -> e != c && e.isAlive() && !e.isSpectator() && e.squaredDistanceTo(c) <= r * r);
    }

    private static Vec3d flatLook(ServerPlayerEntity p) {
        Vec3d l = p.getRotationVec(1.0f);
        Vec3d f = new Vec3d(l.x, 0, l.z);
        return f.lengthSquared() < 1.0E-4 ? new Vec3d(0, 0, 1) : f.normalize();
    }

    /** The direction the player is steering with WASD (from the client), or where they look if standing still. */
    private static Vec3d moveDir(ServerPlayerEntity p) {
        int[] in = SigilData.input(p.getUuid());
        if (in[0] == 0 && in[1] == 0) return flatLook(p);
        double yaw = Math.toRadians(p.getYaw());
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d left = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d d = fwd.multiply(in[0]).add(left.multiply(in[1]));
        return d.lengthSquared() < 1.0E-4 ? flatLook(p) : d.normalize();
    }

    private static Vec3d rot(Vec3d v, double degrees) {
        double a = Math.toRadians(degrees);
        return new Vec3d(v.x * Math.cos(a) - v.z * Math.sin(a), 0, v.x * Math.sin(a) + v.z * Math.cos(a));
    }

    private static Vec3d awayFrom(Entity from, Entity e) {
        Vec3d v = new Vec3d(e.getX() - from.getX(), 0, e.getZ() - from.getZ());
        return v.lengthSquared() < 1.0E-4 ? Vec3d.ZERO : v.normalize();
    }

    /** The living entity you are aiming at, or null. Walls block the line of sight. */
    private static LivingEntity aim(ServerWorld world, ServerPlayerEntity p, double range) {
        Vec3d start = p.getEyePos();
        Vec3d dir = p.getRotationVec(1.0f);
        Vec3d end = start.add(dir.multiply(range));
        Box box = p.getBoundingBox().stretch(dir.multiply(range)).expand(1.0);
        EntityHitResult hit = ProjectileUtil.raycast(p, start, end, box,
                e -> e instanceof LivingEntity && e != p && !e.isSpectator() && e.isAlive(), range * range);
        if (hit == null) return null;
        HitResult block = world.raycast(new RaycastContext(start, hit.getPos(),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        if (block.getType() != HitResult.Type.MISS) return null;
        return (LivingEntity) hit.getEntity();
    }

    /** Damages everything within `radius` of the line from the player forward `len` blocks. */
    private static void sweep(ServerWorld w, ServerPlayerEntity p, Vec3d dir, double len, double radius, float dmg) {
        Vec3d a = new Vec3d(p.getX(), p.getY() + 0.9, p.getZ());
        Vec3d b = a.add(dir.multiply(len));
        Vec3d ab = b.subtract(a);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, new Box(a, b).expand(radius + 1),
                x -> x != p && x.isAlive() && !x.isSpectator())) {
            Vec3d c = new Vec3d(e.getX(), e.getY() + e.getHeight() / 2, e.getZ());
            double t = MathHelper.clamp(c.subtract(a).dotProduct(ab) / ab.lengthSquared(), 0.0, 1.0);
            if (a.add(ab.multiply(t)).squaredDistanceTo(c) <= radius * radius) {
                e.damage(w, w.getDamageSources().playerAttack(p), dmg);
            }
        }
    }

    private static void beam(ServerWorld w, Vec3d a, Vec3d b, ParticleEffect particle) {
        int n = (int) Math.max(2, a.distanceTo(b) * 2);
        for (int i = 0; i <= n; i++) {
            Vec3d v = a.lerp(b, (double) i / n);
            w.spawnParticles(particle, v.x, v.y, v.z, 1, 0, 0, 0, 0);
        }
    }

    private static void ring(ServerWorld w, double cx, double y, double cz, double r, ParticleEffect pe) {
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI * 2 / 28;
            w.spawnParticles(pe, cx + Math.cos(a) * r, y, cz + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    /** Polls every 2 ticks until the entity touches the ground (or gives up), then runs onImpact. */
    private static void whenGrounded(ServerWorld w, LivingEntity e, int tries, Runnable onImpact) {
        later(w, 2, () -> {
            if (!e.isAlive()) return;
            if (e.isOnGround() || tries <= 0) onImpact.run();
            else whenGrounded(w, e, tries - 1, onImpact);
        });
    }

    private static void strike(ServerWorld w, ServerPlayerEntity p, double x, double y, double z) {
        LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, w);
        bolt.setPosition(x, y, z);
        bolt.setChanneler(p);
        w.spawnEntity(bolt);
    }

    private static void fangs(ServerWorld w, ServerPlayerEntity p, double x, double y, double z, int warmup) {
        w.spawnEntity(new EvokerFangsEntity(w, x, y, z, p.getYaw(), warmup, p));
    }

    private static final BlockStateParticleEffect SAND =
            new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.SAND.getDefaultState());

    // ================================================================== BOLT
    public static SigilItem bolt(Item.Settings s) {
        return new SigilItem(s, "Bolt", Formatting.YELLOW, List.of(
                ab("Thunder Strike", "Mark a foe. Lightning lands 0.6s later and chains to nearby enemies. Dodge it!", 35, (w, p, st, t) -> {
                    LivingEntity e = aim(w, p, 22 + 4 * t);
                    if (e == null) { msg(p, "No target in sight."); return false; }
                    e.addStatusEffect(fx(StatusEffects.GLOWING, 20, 0));
                    fxTo(e, FxPayload.SHAKE, 3);
                    w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 2.5, e.getZ(), 30, 0.3, 0.5, 0.3, 0.1);
                    sound(w, e, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f);
                    later(w, 12, () -> {
                        if (!e.isAlive()) return;
                        strike(w, p, e.getX(), e.getY(), e.getZ());
                        int chains = 0;
                        for (LivingEntity o : nearby(w, e, 6)) {
                            if (o == p || chains >= t) continue;
                            o.damage(w, w.getDamageSources().lightningBolt(), 4.0f);
                            beam(w, new Vec3d(e.getX(), e.getY() + 1, e.getZ()), new Vec3d(o.getX(), o.getY() + 1, o.getZ()), ParticleTypes.ELECTRIC_SPARK);
                            chains++;
                        }
                        fxAround(w, e, 16, FxPayload.FLASH_WHITE, 6);
                        fxAround(w, e, 16, FxPayload.SHAKE, 7);
                    });
                    return true;
                }),
                ab("Storm Aura", "Five thunderous pulses around you, one per second. Shocks and reveals.", 30, (w, p, st, t) -> {
                    for (int i = 0; i < 5; i++) {
                        later(w, i * 20, () -> {
                            if (!p.isAlive()) return;
                            double r = 5 + t;
                            for (LivingEntity e : nearby(w, p, r)) {
                                e.damage(w, w.getDamageSources().lightningBolt(), 2.0f + 0.5f * t);
                                e.addStatusEffect(fx(StatusEffects.GLOWING, 30, 0));
                                fxTo(e, FxPayload.SHAKE, 3);
                            }
                            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 80, r / 2, 0.8, r / 2, 0.2);
                            ring(w, p.getX(), p.getY() + 0.2, p.getZ(), r, ParticleTypes.ELECTRIC_SPARK);
                            sound(w, p, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 0.8f, 1.6f);
                        });
                    }
                    return true;
                }),
                ab("Overcharge", "Detonate a shock around you, then surge: Speed II and Haste II for 8s.", 45, (w, p, st, t) -> {
                    for (LivingEntity e : nearby(w, p, 4)) {
                        launch(e, awayFrom(p, e).multiply(1.0).add(0, 0.3, 0));
                        fxTo(e, FxPayload.SHAKE, 5);
                    }
                    p.addStatusEffect(fx(StatusEffects.SPEED, 160, 1));
                    p.addStatusEffect(fx(StatusEffects.HASTE, 160, 1));
                    fxTo(p, FxPayload.FLASH_WHITE, 3);
                    w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 60, 0.5, 0.9, 0.5, 0.4);
                    sound(w, p, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 2.0f);
                    return true;
                })),
                List.of("Haste I", "Immune to lightning"),
                (p, t) -> p.addStatusEffect(passive(StatusEffects.HASTE, 0)),
                src -> src.isOf(DamageTypes.LIGHTNING_BOLT));
    }

    // ================================================================== FLOW
    public static SigilItem flow(Item.Settings s) {
        return new SigilItem(s, "Flow", Formatting.AQUA, List.of(
                ab("Wind Step", "Dash in the direction you're STEERING (WASD), hurting everything in your path.", 16, (w, p, st, t) -> {
                    Vec3d d = moveDir(p);
                    sweep(w, p, d, 7.0, 1.6, 4.0f + 2 * t);
                    launch(p, d.multiply(1.4 + 0.1 * t).add(0, 0.2, 0));
                    for (int i = 0; i < 5; i++) {
                        later(w, i * 2, () -> w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.6, p.getZ(), 8, 0.3, 0.3, 0.3, 0.02));
                    }
                    sound(w, p, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 1.2f, 1.6f);
                    return true;
                }),
                ab("Breezy Bash", "Fling your target skyward, then slam them down in a shockwave.", 35, (w, p, st, t) -> {
                    LivingEntity e = aim(w, p, 10 + 2 * t);
                    if (e == null) { msg(p, "No target in sight."); return false; }
                    launch(e, new Vec3d(0, 1.6, 0));
                    fxTo(e, FxPayload.SHAKE, 4);
                    w.spawnParticles(ParticleTypes.GUST, e.getX(), e.getY(), e.getZ(), 10, 0.3, 0.1, 0.3, 0.05);
                    later(w, 14, () -> {
                        if (!e.isAlive()) return;
                        launch(e, new Vec3d(0, -3.0, 0));
                        whenGrounded(w, e, 15, () -> {
                            e.damage(w, w.getDamageSources().playerAttack(p), 3.0f + t);
                            for (LivingEntity o : nearby(w, e, 3.5)) {
                                if (o == p) continue;
                                launch(o, awayFrom(e, o).multiply(0.9).add(0, 0.4, 0));
                                o.damage(w, w.getDamageSources().playerAttack(p), 2.0f);
                            }
                            w.spawnParticles(ParticleTypes.GUST_EMITTER_LARGE, e.getX(), e.getY(), e.getZ(), 1, 0, 0, 0, 0);
                            fxAround(w, e, 12, FxPayload.SHAKE, 8);
                        });
                    });
                    return true;
                }),
                ab("Cyclone", "Suck everyone in for a second, then blast them all skyward.", 40, (w, p, st, t) -> {
                    double r = 6 + t;
                    for (int i = 0; i < 3; i++) {
                        later(w, i * 6, () -> {
                            for (LivingEntity e : nearby(w, p, r)) {
                                launch(e, awayFrom(p, e).multiply(-0.6).add(0, 0.1, 0));
                                fxTo(e, FxPayload.SHAKE, 2);
                            }
                            w.spawnParticles(ParticleTypes.GUST, p.getX(), p.getY() + 1, p.getZ(), 20, r / 3, 0.8, r / 3, 0.1);
                        });
                    }
                    later(w, 20, () -> {
                        for (LivingEntity e : nearby(w, p, r)) {
                            launch(e, awayFrom(p, e).multiply(1.6).add(0, 1.0, 0));
                            fxTo(e, FxPayload.SHAKE, 6);
                        }
                        w.spawnParticles(ParticleTypes.GUST_EMITTER_LARGE, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0);
                        sound(w, p, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 1.5f, 0.7f);
                    });
                    return true;
                })),
                List.of("Jump Boost I", "Immune to fall damage", "Double jump"),
                (p, t) -> p.addStatusEffect(passive(StatusEffects.JUMP_BOOST, 0)),
                src -> src.isIn(DamageTypeTags.IS_FALL)).withDoubleJump();
    }

    // ================================================================== VEX
    public static SigilItem vex(Item.Settings s) {
        return new SigilItem(s, "Vex", Formatting.GRAY, List.of(
                ab("Phase", "Slip through walls in the direction you're STEERING, to the first open space.", 16, (w, p, st, t) -> {
                    Vec3d f = moveDir(p);
                    for (double d = 6 + 2 * t; d >= 2; d -= 0.5) {
                        Vec3d delta = f.multiply(d);
                        if (w.isSpaceEmpty(p, p.getBoundingBox().offset(delta))) {
                            w.spawnParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 40, 0.3, 0.6, 0.3, 0.3);
                            p.networkHandler.requestTeleport(p.getX() + delta.x, p.getY(), p.getZ() + delta.z, p.getYaw(), p.getPitch());
                            w.spawnParticles(ParticleTypes.PORTAL, p.getX() + delta.x, p.getY() + 1, p.getZ() + delta.z, 40, 0.3, 0.6, 0.3, 0.3);
                            p.addStatusEffect(fx(StatusEffects.INVISIBILITY, 30, 0));
                            sound(w, p, SoundEvents.ENTITY_VEX_CHARGE, 1.0f, 1.2f);
                            return true;
                        }
                    }
                    msg(p, "No room to phase.");
                    return false;
                }),
                ab("Haunt", "Curse your target for 3 seconds: they glow, go dark, and bleed life to you.", 30, (w, p, st, t) -> {
                    LivingEntity e = aim(w, p, 20 + 4 * t);
                    if (e == null) { msg(p, "No target in sight."); return false; }
                    e.addStatusEffect(fx(StatusEffects.GLOWING, 160, 0));
                    e.addStatusEffect(fx(StatusEffects.DARKNESS, 100, 0));
                    fxTo(e, FxPayload.FLASH_PURPLE, 7);
                    for (int i = 1; i <= 3; i++) {
                        later(w, i * 20, () -> {
                            if (!e.isAlive() || !p.isAlive()) return;
                            e.damage(w, w.getDamageSources().magic(), 2.0f + 0.5f * t);
                            p.heal(1.0f + 0.5f * t);
                            beam(w, new Vec3d(e.getX(), e.getY() + 1, e.getZ()), new Vec3d(p.getX(), p.getY() + 1, p.getZ()), ParticleTypes.SCULK_SOUL);
                            fxTo(e, FxPayload.SHAKE, 2);
                        });
                    }
                    return true;
                }),
                ab("Specter Form", "Vanish for 8s with a burst of speed. Projectiles pass right through you for 4s.", 50, (w, p, st, t) -> {
                    p.addStatusEffect(fx(StatusEffects.INVISIBILITY, 160, 0));
                    p.addStatusEffect(fx(StatusEffects.SPEED, 80, 1));
                    SigilData.timer("phased", p.getUuid(), 80);
                    w.spawnParticles(ParticleTypes.SMOKE, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.8, 0.4, 0.02);
                    return true;
                })),
                List.of("Night Vision", "Immune to suffocation in walls"),
                (p, t) -> p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 320, 0, true, false, false)),
                src -> src.isOf(DamageTypes.IN_WALL));
    }

    // ================================================================== WAYFINDER
    public static SigilItem wayfinder(Item.Settings s) {
        return new SigilItem(s, "Wayfinder", Formatting.GREEN, List.of(
                ab("Set Anchor", "Plant a recall point where you stand.", 0, (w, p, st, t) -> {
                    NbtCompound d = SigilData.readData(st);
                    d.putBoolean("anchor", true);
                    d.putDouble("ax", p.getX());
                    d.putDouble("ay", p.getY());
                    d.putDouble("az", p.getZ());
                    d.putString("adim", w.getRegistryKey().getValue().toString());
                    SigilData.writeData(st, d);
                    for (int i = 0; i < 12; i++) {
                        final int k = i;
                        later(w, i * 2, () -> w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + k * 0.25, p.getZ(), 6, 0.2, 0.0, 0.2, 0.01));
                    }
                    sound(w, p, SoundEvents.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
                    say(p, "Anchor set.", Formatting.GREEN);
                    return true;
                }),
                ab("Recall", "Channel for 1.5s, then snap back to your anchor. Moving cancels it.", 50, (w, p, st, t) -> {
                    NbtCompound d = SigilData.readData(st);
                    if (!d.getBoolean("anchor", false)) { msg(p, "Set an anchor first."); return false; }
                    if (!d.getString("adim", "").equals(w.getRegistryKey().getValue().toString())) {
                        msg(p, "Your anchor is in another dimension.");
                        return false;
                    }
                    double ax = d.getDouble("ax", p.getX()), ay = d.getDouble("ay", p.getY()), az = d.getDouble("az", p.getZ());
                    double sx = p.getX(), sy = p.getY(), sz = p.getZ();
                    say(p, "Recalling... stand still.", Formatting.GREEN);
                    for (int i = 0; i < 15; i++) {
                        later(w, i * 2, () -> ring(w, p.getX(), p.getY() + 0.1, p.getZ(), 1.2, ParticleTypes.PORTAL));
                    }
                    later(w, 30, () -> {
                        if (!p.isAlive()) return;
                        if (p.squaredDistanceTo(sx, sy, sz) > 4.0) { say(p, "Recall interrupted.", Formatting.RED); return; }
                        w.spawnParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 40, 0.3, 0.6, 0.3, 0.3);
                        p.networkHandler.requestTeleport(ax, ay, az, p.getYaw(), p.getPitch());
                        w.spawnParticles(ParticleTypes.PORTAL, ax, ay + 1, az, 40, 0.3, 0.6, 0.3, 0.3);
                        sound(w, p, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    });
                    return true;
                }),
                ab("Bloodhound", "Hunt the nearest player within 64 blocks: reveal them, learn their bearing, gain speed.", 45, (w, p, st, t) -> {
                    ServerPlayerEntity best = null;
                    double bd = 64.0 * 64.0;
                    for (ServerPlayerEntity o : w.getPlayers()) {
                        if (o == p || o.isSpectator()) continue;
                        double dd = o.squaredDistanceTo(p);
                        if (dd < bd) { bd = dd; best = o; }
                    }
                    if (best == null) { msg(p, "No prey within 64 blocks."); return false; }
                    best.addStatusEffect(fx(StatusEffects.GLOWING, 80 + 20 * t, 0));
                    p.addStatusEffect(fx(StatusEffects.SPEED, 100, 1));
                    double dx = best.getX() - p.getX(), dz = best.getZ() - p.getZ();
                    int idx = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dx, -dz)) / 45.0), 8);
                    say(p, "Prey: " + best.getName().getString() + " - " + (int) Math.sqrt(bd) + "m " + DIRS[idx], Formatting.GREEN);
                    return true;
                })),
                List.of("Speed I", "Immune to fall damage"),
                (p, t) -> p.addStatusEffect(passive(StatusEffects.SPEED, 0)),
                src -> src.isIn(DamageTypeTags.IS_FALL));
    }

    // ================================================================== WARD
    public static SigilItem ward(Item.Settings s) {
        return new SigilItem(s, "Ward", Formatting.DARK_AQUA, List.of(
                ab("Sonic Boom", "Charge for 0.7s (you're rooted), then fire an armor-piercing boom where you look.", 30, (w, p, st, t) -> {
                    p.addStatusEffect(fx(StatusEffects.SLOWNESS, 14, 3));
                    sound(w, p, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 1.0f);
                    w.spawnParticles(ParticleTypes.SCULK_CHARGE_POP, p.getX(), p.getY() + 1.5, p.getZ(), 20, 0.3, 0.3, 0.3, 0.02);
                    later(w, 14, () -> {
                        if (!p.isAlive()) return;
                        double range = 16 + 2 * t;
                        LivingEntity e = aim(w, p, range);
                        Vec3d start = p.getEyePos();
                        Vec3d dir = p.getRotationVec(1.0f);
                        double dist = e == null ? range : Math.sqrt(e.squaredDistanceTo(p));
                        for (double d = 1.5; d < dist; d += 1.0) {
                            Vec3d at = start.add(dir.multiply(d));
                            w.spawnParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0, 0, 0, 0);
                        }
                        sound(w, p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.0f);
                        if (e != null) {
                            e.damage(w, w.getDamageSources().sonicBoom(p), 7.0f + 2 * t);
                            launch(e, dir.multiply(0.9).add(0, 0.3, 0));
                            fxTo(e, FxPayload.SHAKE, 9);
                            fxTo(e, FxPayload.FLASH_BLUE, 5);
                        }
                    });
                    return true;
                }),
                ab("Resonant Ward", "Cleanse every debuff, then gain absorption hearts and Resistance II for 5s.", 40, (w, p, st, t) -> {
                    p.removeStatusEffect(StatusEffects.SLOWNESS);
                    p.removeStatusEffect(StatusEffects.WEAKNESS);
                    p.removeStatusEffect(StatusEffects.BLINDNESS);
                    p.removeStatusEffect(StatusEffects.DARKNESS);
                    p.removeStatusEffect(StatusEffects.NAUSEA);
                    p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
                    p.removeStatusEffect(StatusEffects.LEVITATION);
                    p.removeStatusEffect(StatusEffects.GLOWING);
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 200, t, false, true, true));
                    p.addStatusEffect(fx(StatusEffects.RESISTANCE, 100, 1));
                    w.spawnParticles(ParticleTypes.SCULK_CHARGE_POP, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.8, 0.5, 0.02);
                    fxTo(p, FxPayload.FLASH_BLUE, 3);
                    return true;
                }),
                ab("Seismic Pulse", "Three expanding ground shockwaves. Only hits those standing on the ground. JUMP!", 35, (w, p, st, t) -> {
                    for (int i = 0; i < 3; i++) {
                        final double r = 3.0 + i * 2.5 + t;
                        final double inner = i == 0 ? 0.0 : r - 2.5;
                        later(w, i * 6, () -> {
                            if (!p.isAlive()) return;
                            for (LivingEntity e : nearby(w, p, r)) {
                                if (e.squaredDistanceTo(p) < inner * inner || !e.isOnGround()) continue;
                                e.damage(w, w.getDamageSources().sonicBoom(p), 2.0f + 0.5f * t);
                                e.addStatusEffect(fx(StatusEffects.SLOWNESS, 60, 1));
                                fxTo(e, FxPayload.SHAKE, 5);
                            }
                            ring(w, p.getX(), p.getY() + 0.1, p.getZ(), r, ParticleTypes.POOF);
                            sound(w, p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.7f, 0.5f);
                        });
                    }
                    return true;
                })),
                List.of("Resistance I while sneaking", "Immune to sonic booms"),
                (p, t) -> { if (p.isSneaking()) p.addStatusEffect(passive(StatusEffects.RESISTANCE, 0)); },
                src -> src.isOf(DamageTypes.SONIC_BOOM));
    }

    // ================================================================== TIDE
    public static SigilItem tide(Item.Settings s) {
        return new SigilItem(s, "Tide", Formatting.BLUE, List.of(
                ab("Undertow", "Drag everyone nearby toward you twice and slow them.", 30, (w, p, st, t) -> {
                    double r = 7 + t;
                    for (int i = 0; i < 2; i++) {
                        later(w, i * 8, () -> {
                            for (LivingEntity e : nearby(w, p, r)) {
                                launch(e, awayFrom(p, e).multiply(-(0.9 + 0.2 * t)).add(0, 0.2, 0));
                                e.addStatusEffect(fx(StatusEffects.SLOWNESS, 40, 1));
                                fxTo(e, FxPayload.FLASH_BLUE, 3);
                            }
                            w.spawnParticles(ParticleTypes.BUBBLE, p.getX(), p.getY() + 1, p.getZ(), 80, r / 2, 0.8, r / 2, 0.1);
                            ring(w, p.getX(), p.getY() + 0.3, p.getZ(), r, ParticleTypes.SPLASH);
                        });
                    }
                    sound(w, p, SoundEvents.ENTITY_GENERIC_SPLASH, 1.5f, 0.6f);
                    return true;
                }),
                ab("Riptide Rush", "Surge in the direction you're STEERING, tossing foes up in your wake.", 20, (w, p, st, t) -> {
                    Vec3d d = moveDir(p);
                    sweep(w, p, d, 7.0, 1.6, 3.0f + t);
                    for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(7.0),
                            x -> x != p && x.isAlive() && !x.isSpectator())) {
                        Vec3d rel = new Vec3d(e.getX() - p.getX(), 0, e.getZ() - p.getZ());
                        double along = rel.dotProduct(d);
                        if (along > 0 && along < 7 && rel.subtract(d.multiply(along)).length() < 1.7) {
                            launch(e, new Vec3d(0, 0.6, 0));
                        }
                    }
                    launch(p, d.multiply(1.5 + 0.1 * t).add(0, 0.1, 0));
                    p.addStatusEffect(fx(StatusEffects.DOLPHINS_GRACE, 100, 0));
                    w.spawnParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 0.5, p.getZ(), 50, 0.5, 0.3, 0.5, 0.2);
                    return true;
                }),
                ab("Tidal Wave", "Send a wave surging forward that carries everything in its path away.", 40, (w, p, st, t) -> {
                    Vec3d d = flatLook(p);
                    double ox = p.getX(), oy = p.getY(), oz = p.getZ();
                    p.extinguish();
                    for (int i = 1; i <= 10; i++) {
                        final int k = i;
                        later(w, k, () -> {
                            double cx = ox + d.x * k * 1.3, cz = oz + d.z * k * 1.3;
                            Box b = new Box(cx - 2.2, oy - 1, cz - 2.2, cx + 2.2, oy + 3, cz + 2.2);
                            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, b, x -> x != p && x.isAlive() && !x.isSpectator())) {
                                launch(e, d.multiply(1.3).add(0, 0.35, 0));
                                e.addStatusEffect(fx(StatusEffects.WEAKNESS, 60, 0));
                                fxTo(e, FxPayload.FLASH_BLUE, 4);
                            }
                            w.spawnParticles(ParticleTypes.SPLASH, cx, oy + 0.6, cz, 40, 1.8, 0.5, 1.8, 0.2);
                            w.spawnParticles(ParticleTypes.BUBBLE, cx, oy + 1.0, cz, 12, 1.5, 0.6, 1.5, 0.05);
                        });
                    }
                    sound(w, p, SoundEvents.ENTITY_GENERIC_SPLASH, 1.5f, 1.0f);
                    return true;
                })),
                List.of("Water Breathing", "Immune to drowning"),
                (p, t) -> p.addStatusEffect(passive(StatusEffects.WATER_BREATHING, 0)),
                src -> src.isIn(DamageTypeTags.IS_DROWNING));
    }

    // ================================================================== SILENCE
    public static SigilItem silence(Item.Settings s) {
        return new SigilItem(s, "Silence", Formatting.DARK_GRAY, List.of(
                ab("Hush", "Nearby players cannot use ANY sigil for ~5-6 seconds.", 40, (w, p, st, t) -> {
                    double r = 8 + 2 * t;
                    int hits = 0;
                    for (LivingEntity e : nearby(w, p, r)) {
                        if (e instanceof ServerPlayerEntity other) {
                            SigilData.timer("silence", other.getUuid(), 100 + 20 * t);
                            other.sendMessage(Text.literal("You have been silenced!").formatted(Formatting.DARK_GRAY), true);
                            fxTo(other, FxPayload.FLASH_DARK, 6);
                            fxTo(other, FxPayload.SHAKE, 4);
                            hits++;
                        }
                    }
                    w.spawnParticles(ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + 1, p.getZ(), 60, r / 3, 0.6, r / 3, 0.02);
                    sound(w, p, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.0f, 0.5f);
                    if (hits == 0) { msg(p, "Nobody to silence."); return false; }
                    return true;
                }),
                ab("Dread", "Fill nearby players with dread: darkness, nausea and weakness.", 35, (w, p, st, t) -> {
                    for (LivingEntity e : nearby(w, p, 8 + 2 * t)) {
                        e.addStatusEffect(fx(StatusEffects.DARKNESS, 140, 0));
                        e.addStatusEffect(fx(StatusEffects.NAUSEA, 100, 0));
                        e.addStatusEffect(fx(StatusEffects.WEAKNESS, 80, 0));
                        fxTo(e, FxPayload.FLASH_DARK, 8);
                    }
                    w.spawnParticles(ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + 1, p.getZ(), 40, 3, 0.6, 3, 0.02);
                    return true;
                }),
                ab("Soul Drain", "Tether to your target for 2s, draining their life into yours.", 25, (w, p, st, t) -> {
                    LivingEntity e = aim(w, p, 12 + 2 * t);
                    if (e == null) { msg(p, "No target in sight."); return false; }
                    for (int i = 0; i < 4; i++) {
                        later(w, i * 10, () -> {
                            if (!e.isAlive() || !p.isAlive() || e.squaredDistanceTo(p) > 20.0 * 20.0) return;
                            float amount = 2.0f + 0.5f * t;
                            e.damage(w, w.getDamageSources().magic(), amount);
                            p.heal(amount);
                            beam(w, new Vec3d(e.getX(), e.getY() + 1, e.getZ()), new Vec3d(p.getX(), p.getY() + 1, p.getZ()), ParticleTypes.SCULK_SOUL);
                            fxTo(e, FxPayload.SHAKE, 2);
                        });
                    }
                    return true;
                })),
                List.of("Speed I while sneaking", "Immune to sonic booms"),
                (p, t) -> { if (p.isSneaking()) p.addStatusEffect(passive(StatusEffects.SPEED, 0)); },
                src -> src.isOf(DamageTypes.SONIC_BOOM));
    }

    // ================================================================== SPIRE
    public static SigilItem spire(Item.Settings s) {
        return new SigilItem(s, "Spire", Formatting.LIGHT_PURPLE, List.of(
                ab("Skyward", "Rocket upward while everyone nearby levitates.", 35, (w, p, st, t) -> {
                    double r = 5 + t;
                    for (LivingEntity e : nearby(w, p, r)) {
                        e.addStatusEffect(fx(StatusEffects.LEVITATION, 30 + 10 * t, 0));
                        fxTo(e, FxPayload.FLASH_PURPLE, 4);
                    }
                    launch(p, new Vec3d(p.getVelocity().x, 1.0 + 0.1 * t, p.getVelocity().z));
                    w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 0.5, p.getZ(), 60, r / 3, 0.2, r / 3, 0.1);
                    sound(w, p, SoundEvents.ENTITY_SHULKER_SHOOT, 1.2f, 0.8f);
                    return true;
                }),
                ab("Meteor Strike", "Launch skyward, then crash down. The shockwave lands where YOU land.", 45, (w, p, st, t) -> {
                    launch(p, new Vec3d(p.getVelocity().x, 1.3, p.getVelocity().z));
                    later(w, 14, () -> { if (p.isAlive()) launch(p, new Vec3d(0, -3.2, 0)); });
                    later(w, 16, () -> whenGrounded(w, p, 20, () -> {
                        for (LivingEntity e : nearby(w, p, 4 + t)) {
                            e.damage(w, w.getDamageSources().playerAttack(p), 4.0f + 2 * t);
                            launch(e, awayFrom(p, e).multiply(1.0).add(0, 0.6, 0));
                            fxTo(e, FxPayload.SHAKE, 8);
                        }
                        w.spawnParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.3, p.getZ(), 6, 1.5, 0.2, 1.5, 0.0);
                        ring(w, p.getX(), p.getY() + 0.2, p.getZ(), 4 + t, ParticleTypes.POOF);
                        fxAround(w, p, 14, FxPayload.FLASH_WHITE, 5);
                        fxAround(w, p, 14, FxPayload.SHAKE, 9);
                        sound(w, p, SoundEvents.BLOCK_ANVIL_LAND, 1.5f, 0.5f);
                    }));
                    return true;
                }),
                ab("Lift & Drop", "Hoist your target helplessly into the air, then drop them.", 30, (w, p, st, t) -> {
                    LivingEntity e = aim(w, p, 20);
                    if (e == null) { msg(p, "No target in sight."); return false; }
                    launch(e, new Vec3d(0, 1.0, 0));
                    e.addStatusEffect(fx(StatusEffects.LEVITATION, 40, 0));
                    fxTo(e, FxPayload.FLASH_PURPLE, 5);
                    w.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY(), e.getZ(), 30, 0.3, 0.2, 0.3, 0.1);
                    later(w, 45, () -> {
                        if (!e.isAlive()) return;
                        e.removeStatusEffect(StatusEffects.LEVITATION);
                        launch(e, new Vec3d(0, -2.5, 0));
                        fxTo(e, FxPayload.SHAKE, 5);
                    });
                    return true;
                })),
                List.of("Slow Falling", "Immune to fall damage", "Double jump"),
                (p, t) -> p.addStatusEffect(passive(StatusEffects.SLOW_FALLING, 0)),
                src -> src.isIn(DamageTypeTags.IS_FALL)).withDoubleJump();
    }

    // ================================================================== RAISER
    public static SigilItem raiser(Item.Settings s) {
        return new SigilItem(s, "Raiser", Formatting.DARK_RED, List.of(
                ab("Fang Line", "Evoker fangs erupt ahead of you. Higher tiers fan out into more lines.", 22, (w, p, st, t) -> {
                    Vec3d f = flatLook(p);
                    double[][] sets = { { 0 }, { -12, 12 }, { 0, -22, 22 } };
                    for (double a : sets[Math.min(t, 3) - 1]) {
                        Vec3d dir = rot(f, a);
                        for (int i = 0; i < 6 + 2 * t; i++) {
                            double d = 1.5 + i * 1.3;
                            fangs(w, p, p.getX() + dir.x * d, p.getY(), p.getZ() + dir.z * d, i * 2);
                        }
                    }
                    sound(w, p, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 1.0f, 1.0f);
                    return true;
                }),
                ab("Fang Trap", "Cage your target in rings of fangs and slow them.", 35, (w, p, st, t) -> {
                    LivingEntity e = aim(w, p, 18 + 2 * t);
                    if (e == null) { msg(p, "No target in sight."); return false; }
                    double x = e.getX(), y = e.getY(), z = e.getZ();
                    for (int i = 0; i < 10; i++) {
                        double a = i * Math.PI / 5;
                        fangs(w, p, x + Math.cos(a) * 2.2, y, z + Math.sin(a) * 2.2, 3 + i / 5);
                    }
                    for (int i = 0; i < 6; i++) {
                        double a = i * Math.PI / 3;
                        fangs(w, p, x + Math.cos(a) * 1.1, y, z + Math.sin(a) * 1.1, 10);
                    }
                    fangs(w, p, x, y, z, 6);
                    e.addStatusEffect(fx(StatusEffects.SLOWNESS, 40, 2));
                    fxTo(e, FxPayload.FLASH_RED, 5);
                    sound(w, p, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 1.0f, 0.7f);
                    return true;
                }),
                ab("Bloodlust", "8s of Strength, speed and LIFESTEAL: you heal a quarter of the damage you deal.", 50, (w, p, st, t) -> {
                    p.addStatusEffect(fx(StatusEffects.STRENGTH, 160, t >= 3 ? 1 : 0));
                    p.addStatusEffect(fx(StatusEffects.SPEED, 160, 0));
                    SigilData.timer("bloodlust", p.getUuid(), 160);
                    fxTo(p, FxPayload.FLASH_RED, 4);
                    w.spawnParticles(ParticleTypes.ANGRY_VILLAGER, p.getX(), p.getY() + 1.5, p.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
                    return true;
                })),
                List.of("Strength I below 30% health"),
                (p, t) -> { if (p.getHealth() <= p.getMaxHealth() * 0.3f) p.addStatusEffect(passive(StatusEffects.STRENGTH, 0)); },
                null);
    }

    // ================================================================== DUNE
    public static SigilItem dune(Item.Settings s) {
        return new SigilItem(s, "Dune", Formatting.GOLD, List.of(
                ab("Sandstorm", "A three-pulse storm that blinds and bogs down everything near you.", 35, (w, p, st, t) -> {
                    double r = 6 + t;
                    for (int i = 0; i < 3; i++) {
                        later(w, i * 15, () -> {
                            if (!p.isAlive()) return;
                            for (LivingEntity e : nearby(w, p, r)) {
                                e.addStatusEffect(fx(StatusEffects.BLINDNESS, 40 + 10 * t, 0));
                                e.addStatusEffect(fx(StatusEffects.SLOWNESS, 40, 0));
                                fxTo(e, FxPayload.FLASH_SAND, 6);
                            }
                            w.spawnParticles(SAND, p.getX(), p.getY() + 1, p.getZ(), 200, r / 2, 1.0, r / 2, 0.2);
                            sound(w, p, SoundEvents.BLOCK_SAND_BREAK, 1.5f, 0.6f);
                        });
                    }
                    return true;
                }),
                ab("Quicksand", "Drag your target down: heavy slowness, fatigue, weakness, and they sink.", 28, (w, p, st, t) -> {
                    LivingEntity e = aim(w, p, 16 + 2 * t);
                    if (e == null) { msg(p, "No target in sight."); return false; }
                    e.addStatusEffect(fx(StatusEffects.SLOWNESS, 100, 3));
                    e.addStatusEffect(fx(StatusEffects.MINING_FATIGUE, 100, 1));
                    e.addStatusEffect(fx(StatusEffects.WEAKNESS, 100, 0));
                    fxTo(e, FxPayload.FLASH_SAND, 5);
                    for (int i = 0; i < 5; i++) {
                        later(w, i * 4, () -> {
                            if (!e.isAlive()) return;
                            launch(e, new Vec3d(e.getVelocity().x * 0.2, -0.5, e.getVelocity().z * 0.2));
                            w.spawnParticles(SAND, e.getX(), e.getY() + 0.2, e.getZ(), 25, 0.5, 0.2, 0.5, 0.1);
                        });
                    }
                    return true;
                }),
                ab("Scorch", "Three rings of desert flame sweep outward, igniting all in their path.", 40, (w, p, st, t) -> {
                    for (int i = 0; i < 3; i++) {
                        final double r = 3.0 + i * 2.0 + t * 0.5;
                        final double inner = i == 0 ? 0.0 : r - 2.0;
                        later(w, i * 6, () -> {
                            if (!p.isAlive()) return;
                            for (LivingEntity e : nearby(w, p, r)) {
                                if (e.squaredDistanceTo(p) < inner * inner) continue;
                                e.setOnFireFor(3 + t);
                                fxTo(e, FxPayload.SHAKE, 2);
                            }
                            ring(w, p.getX(), p.getY() + 0.3, p.getZ(), r, ParticleTypes.FLAME);
                            sound(w, p, SoundEvents.ENTITY_BLAZE_SHOOT, 1.0f, 0.7f);
                        });
                    }
                    return true;
                })),
                List.of("Fire Resistance", "Speed I while sprinting", "Immune to cacti"),
                (p, t) -> {
                    p.addStatusEffect(passive(StatusEffects.FIRE_RESISTANCE, 0));
                    if (p.isSprinting()) p.addStatusEffect(passive(StatusEffects.SPEED, 0));
                },
                src -> src.isOf(DamageTypes.CACTUS));
    }
}
