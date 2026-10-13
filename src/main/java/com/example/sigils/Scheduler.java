package com.example.sigils;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/** Tiny "run this in N ticks" helper for multi-stage abilities (bashes, meteor strike). */
public final class Scheduler {
    private record Task(int at, Runnable run) {}

    private static final List<Task> TASKS = new ArrayList<>();

    private Scheduler() {}

    public static void later(MinecraftServer server, int delayTicks, Runnable r) {
        TASKS.add(new Task(server.getTicks() + delayTicks, r));
    }

    public static void tick(MinecraftServer server) {
        if (TASKS.isEmpty()) return;
        int now = server.getTicks();
        List<Task> due = new ArrayList<>();
        for (Task t : TASKS) if (t.at() <= now) due.add(t);
        TASKS.removeAll(due);
        for (Task t : due) t.run().run();
    }
}
