package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What an entity is wearing or holding that an add-on described.
 *
 * A pack says which items an attachable belongs to, so an equipped stack finds its model by its own
 * identifier. Each one animates on a clock of its own beside the wearer's, which is why a pass
 * carries the animator that poses it.
 */
public final class Attachables {
    /** One drawing pass and the animator whose bones it is posed by. */
    public record Drawn(AddonModels.Pass pass, EntityAnimator animator) {
    }

    /** The attachable drawn for one item, and the animator running it. */
    private record Chosen(AddonClientAssets.Binding binding, EntityAnimator animator) {
    }

    /** Attachables whose `parent_setup` found no Bedrock scope, so the report names each once. */
    private static final Set<String> REPORTED = new HashSet<>();

    private Attachables() {
    }

    /**
     * The passes for everything the entity has equipped that a pack describes.
     *
     * `wearer` is the animator drawing the entity itself, and null for one Java draws with a model
     * of its own. It is what an attachable reaches through `c.owning_entity` and what an
     * attachable's `parent_setup` writes into, so both happen here rather than at each caller.
     */
    public static List<Drawn> on(LivingEntity entity, EntityAnimator wearer, float partialTick) {
        List<Drawn> drawn = new ArrayList<>();
        if (entity == null) return drawn;

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack held = entity.getItemBySlot(slot);
            if (held.isEmpty()) continue;

            String item = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            Chosen chosen = choose(entity, wearer, item, partialTick);
            if (chosen == null) continue;

            parentSetup(chosen.binding(), wearer);
            for (AddonModels.Pass pass : chosen.binding().models().resolve(chosen.animator())) {
                drawn.add(new Drawn(pass, chosen.animator()));
            }
        }
        return drawn;
    }

    /**
     * The first attachable offered for the item whose own condition holds.
     *
     * A condition is asked of the attachable's own scope, where `query.owner_identifier` names the
     * entity carrying it, which is how a pack dresses a player in one model and everything else in
     * another. An attachable naming the item without a condition always applies.
     */
    private static Chosen choose(LivingEntity entity, EntityAnimator wearer, String item,
        float partialTick) {
        for (AddonClientAssets.Binding binding : AddonClientAssets.attachablesFor(item)) {
            EntityAnimator animator = animatorOf(entity, wearer, binding, item, partialTick);
            if (animator == null) continue;

            MolangExpression condition = binding.definition().items().get(item);
            if (condition == null || condition.evaluate(animator.environment()).truthy()) {
                return new Chosen(binding, animator);
            }
        }
        return null;
    }

    /**
     * The animator running one attachable on one entity, linked to whoever is carrying it.
     *
     * Keyed by the attachable's own identifier beside the item, because two packs may both dress
     * the same item and each needs a clock and a set of variables of its own.
     */
    private static EntityAnimator animatorOf(LivingEntity entity, EntityAnimator wearer,
        AddonClientAssets.Binding binding, String item, float partialTick) {
        EntityAnimator animator = Animators.of(entity, binding,
            item + "#" + binding.definition().identifier(), true, partialTick,
            entity.tickCount + partialTick);
        if (animator == null) return null;

        animator.environment().setLink("owning_entity", wearer == null ? null : wearer.published());
        return animator;
    }

    /**
     * What an attachable writes into whoever is carrying it.
     *
     * The statements switch off the layer the attachable stands in for, so they belong to the
     * wearer's own variables and run between its `pre_animation` and the render controllers reading
     * them. An entity drawn from a Java model carries no such variables and no such layer, so the
     * attachable is reported rather than left writing into nothing.
     */
    private static void parentSetup(AddonClientAssets.Binding binding, EntityAnimator wearer) {
        List<MolangExpression> statements = binding.definition().parentSetup();
        if (statements.isEmpty()) return;

        if (wearer == null) {
            if (REPORTED.add(binding.definition().identifier())) {
                AddonEntities.unsupported("client entity parent_setup");
            }
            return;
        }
        for (MolangExpression statement : statements) statement.evaluate(wearer.environment());
    }
}
