package at.ioexception.mcaddon.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The entity events Bedrock's own behaviour files raise on the game's mobs, which a pack's script
 * hears as `dataDrivenEntityTrigger`. Each is raised at the moment Java does the same thing.
 */
public final class VanillaEvents {
    private static final Set<Entity> BRED = Collections.newSetFromMap(new WeakHashMap<>());

    private VanillaEvents() {
    }

    /** Marks a mob that breeding made, so its arrival reads as a birth rather than a placement. */
    public static void bred(Entity young) {
        synchronized (BRED) {
            BRED.add(young);
        }
    }

    public static boolean wasBred(Entity entity) {
        synchronized (BRED) {
            return BRED.contains(entity);
        }
    }

    /** What a mob's arrival in the world raises, from why Java made it. */
    public static void spawned(Entity entity, EntitySpawnReason reason) {
        if (!(entity.level() instanceof ServerLevel)) return;

        if (entity instanceof Villager && reason == EntitySpawnReason.STRUCTURE
            || entity instanceof Cat && reason == EntitySpawnReason.NATURAL) {
            EntityEvents.raiseDataDriven(entity, "minecraft:spawn_from_village");
        } else if (entity instanceof TraderLlama && reason == EntitySpawnReason.EVENT) {
            EntityEvents.raiseDataDriven(entity, "minecraft:from_wandering_trader");
        }
    }

    public static void transformed(Entity into) {
        if (into.level() instanceof ServerLevel) EntityEvents.raiseDataDriven(into, "minecraft:entity_transformed");
    }

    public static void celebrating(Entity entity) {
        if (entity.level() instanceof ServerLevel) EntityEvents.raiseDataDriven(entity, "minecraft:start_celebrating");
    }

    public static void sheared(Entity entity) {
        if (entity.level() instanceof ServerLevel) EntityEvents.raiseDataDriven(entity, "minecraft:on_sheared");
    }
}
