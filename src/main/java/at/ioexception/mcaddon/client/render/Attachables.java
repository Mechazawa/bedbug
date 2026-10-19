package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedBone;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

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
    /**
     * One drawing pass, the animator whose bones it is posed by, and the slot it stands for.
     *
     * `bone` is the wearer's bone this pass's `binding` named, and `anchor` is that bone's frame
     * once the wearer has been found to carry it.
     *
     * Bedrock makes the bound bone's pivot the origin of the attachable's model, lowered by 24
     * pixels, so a model bound to a humanoid's head is drawn where it was modelled on that head:
     * Mojang's spyglass, moved to 27 pixels up while scoping, sits at the eye. Java's model space
     * already starts 24 pixels above the feet, so the bone's own frame is that origin as it stands,
     * and the attachable's own pivots stay where its pack wrote them.
     */
    public record Drawn(AddonModels.Pass pass, EntityAnimator animator, EquipmentSlot slot,
        String bone, Matrix4f anchor) {

        Drawn(AddonModels.Pass pass, EntityAnimator animator, EquipmentSlot slot) {
            this(pass, animator, slot, null, null);
        }

        /** The same pass, hung off the bone of a Bedrock wearer it named, where the wearer has one. */
        public Drawn hungFrom(BakedGeometry wearer, EntityAnimator wearerAnimator) {
            if (bone == null || wearer == null) return this;

            return hungAt(BedrockModelRenderer.anchorOf(wearer, wearerAnimator, bone));
        }

        /** The same pass, hung off the part of a Java model answering to the bone it named. */
        Drawn hungFrom(JavaModelParts wearer) {
            if (bone == null) return this;

            return hungAt(wearer.anchorOf(bone));
        }

        private Drawn hungAt(Matrix4f found) {
            if (found == null) {
                if (REPORTED.add(bone)) AddonEntities.unsupported("geometry binding " + bone);
                return this;
            }
            return new Drawn(pass, animator, slot, bone, found);
        }
    }

    /** The attachable drawn for one item, and the animator running it. */
    private record Chosen(AddonClientAssets.Binding binding, EntityAnimator animator) {
    }

    /** What an attachable asked for that the wearer could not answer, named once each. */
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

        // A pack's own entity wears what it has equipped only where its definition says it may; an
        // entity Java draws with a model of its own carries no such definition and always wears it.
        ClientEntityDefinition described = wearer == null ? null : wearer.definition();
        if (described != null && !described.attachablesEnabled()) return drawn;

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (described != null && described.armourHidden() && slot.isArmor()) continue;

            ItemStack held = entity.getItemBySlot(slot);
            if (held.isEmpty()) continue;

            String item = AddonItems.identifierOf(held.getItem());
            Chosen chosen = choose(entity, wearer, item, slotName(slot), partialTick);
            if (chosen == null) continue;

            parentSetup(chosen.binding(), wearer);
            for (AddonModels.Pass pass : chosen.binding().models().resolve(chosen.animator())) {
                drawn.add(new Drawn(pass, chosen.animator(), slot,
                    boundBone(pass.geometry(), chosen.animator()), null));
            }
        }
        return drawn;
    }

    /**
     * The bone of the wearer this model asked to hang off, or null where it asked for none.
     *
     * A `binding` is an expression rather than a name, because Mojang's own attachables answer it
     * with `q.item_slot_to_bone_name(c.item_slot)` and the spyglass moves between the hand and the
     * head by the same call. Every published one sits on the model's single root.
     */
    private static String boundBone(BakedGeometry geometry, EntityAnimator animator) {
        for (BakedBone root : geometry.roots()) {
            if (root.binding() == null) continue;

            String bone = root.binding().evaluate(animator.environment()).text();
            if (bone != null && !bone.isEmpty()) return bone;
        }
        return null;
    }

    /**
     * The first attachable offered for the item whose own condition holds.
     *
     * A condition is asked of the attachable's own scope, where `query.owner_identifier` names the
     * entity carrying it, which is how a pack dresses a player in one model and everything else in
     * another. An attachable naming the item without a condition always applies.
     */
    private static Chosen choose(LivingEntity entity, EntityAnimator wearer, String item,
        String itemSlot, float partialTick) {
        for (AddonClientAssets.Binding binding : AddonClientAssets.attachablesFor(item)) {
            EntityAnimator animator = animatorOf(entity, wearer, binding, item, itemSlot, partialTick);
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
        AddonClientAssets.Binding binding, String item, String itemSlot, float partialTick) {
        EntityAnimator animator = Animators.of(entity, binding,
            item + "#" + binding.definition().identifier(), itemSlot, partialTick,
            entity.tickCount + partialTick);
        if (animator == null) return null;

        animator.environment().setLink("owning_entity", wearer == null ? null : wearer.published());
        return animator;
    }

    /**
     * The slot an attachable is worn in, named the way `item_slot_to_bone_name` takes an argument.
     *
     * This is what `context.item_slot` answers with, which every binding Mojang publishes hands
     * straight to that call. A slot outside the six Bedrock has names nothing.
     */
    private static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> "main_hand";
            case OFFHAND -> "off_hand";
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            default -> "";
        };
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
