package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.BedrockPotions;
import at.ioexception.mcaddon.client.render.BedrockParticles;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;

/**
 * `spawn_aoe_cloud`: the cloud a projectile leaves where it lands.
 *
 * Java's lingering cloud is `AreaEffectCloud`, which shrinks by its radius per tick over its life
 * and by `radius_on_use` each time it applies its effects, as Bedrock's does. The duration is in
 * seconds and the reapplication delay in ticks, so the cloud starts at once and with the delay the
 * pack states, where Java's own lingering potion waits and reapplies every twenty ticks.
 */
final class CloudOnHit {
    private static final String DEFAULT_PARTICLE = "mobspellambient";

    private final JsonObject body;

    private CloudOnHit(JsonObject body) {
        this.body = body;
    }

    /** Null for a projectile whose `on_hit` leaves no cloud. */
    static CloudOnHit tryFrom(JsonObject onHit) {
        return onHit.has("spawn_aoe_cloud") && onHit.get("spawn_aoe_cloud").isJsonObject()
            ? new CloudOnHit(onHit.getAsJsonObject("spawn_aoe_cloud")) : null;
    }

    /** `projectilePotion` is the potion the projectile itself carries, which stands in for a `potion` of -1. */
    void spawn(ServerLevel level, Vec3 at, LivingEntity owner, int projectilePotion) {
        AreaEffectCloud cloud = new AreaEffectCloud(level, at.x, at.y, at.z);
        float radius = (float) Json.number(body, "radius", 0);
        int ticks = (int) (Json.number(body, "duration", 0) * Goals.TICKS_PER_SECOND);
        cloud.setRadius(radius);
        cloud.setDuration(ticks);
        cloud.setWaitTime(0);
        cloud.setRadiusOnUse((float) Json.number(body, "radius_on_use", 0));
        if (ticks > 0) cloud.setRadiusPerTick(-radius / ticks);
        ((at.ioexception.mcaddon.mixin.AreaEffectCloudAccessor) cloud)
            .mcaddon$reapplicationDelay((int) Json.number(body, "reapplication_delay", 0));

        int aux = (int) Json.number(body, "potion", -1);
        BedrockPotions.numbered(aux >= 0 ? aux : projectilePotion).ifPresent(potion ->
            cloud.setPotionContents(new PotionContents(BuiltInRegistries.POTION.wrapAsHolder(potion))));

        String named = Json.text(body, "particle", DEFAULT_PARTICLE);
        ParticleOptions particle = DEFAULT_PARTICLE.equals(named) ? null : BedrockParticles.vanilla(named);
        if (particle != null) cloud.setCustomParticle(particle);

        if (owner != null) {
            cloud.setOwner(owner);
            if (!Json.bool(body, "affect_owner", true)) {
                ((at.ioexception.mcaddon.mixin.AreaEffectCloudAccessor) cloud).mcaddon$victims()
                    .put(owner, Integer.MAX_VALUE);
            }
        }
        level.addFreshEntity(cloud);
    }
}
