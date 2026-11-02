package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.entity.EntityReference;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
     * `bindings` names, for each bone of this pass carrying a `binding`, the wearer's bone it asked
     * for, and `anchors` holds that bone's frame for each one the wearer was found to carry. A bone
     * with an anchor is drawn from it instead of from its own parent, and its children go with it.
     *
     * Bedrock makes the bound bone's pivot the origin of the attachable's model, lowered by 24
     * pixels, so a model bound to a humanoid's head is drawn where it was modelled on that head:
     * Mojang's spyglass, moved to 27 pixels up while scoping, sits at the eye. Java's model space
     * already starts 24 pixels above the feet, so the bone's own frame is that origin as it stands,
     * and the attachable's own pivots stay where its pack wrote them.
     *
     * A bound bone below the root keeps its pivot's offset from its parent's pivot, with no such
     * lowering. Packs write it that way: one pack lifts an item bound at its root by 23 pixels to
     * reach the hand, and holds another, bound on the child of an empty root, at the hand unlifted.
     */
    public record Drawn(AddonModels.Pass pass, EntityAnimator animator, EquipmentSlot slot,
        Map<String, String> bindings, Map<String, Matrix4f> anchors) {

        Drawn(AddonModels.Pass pass, EntityAnimator animator, EquipmentSlot slot) {
            this(pass, animator, slot, Map.of(), Map.of());
        }

        /** The same pass, hung off the bones of a Bedrock wearer it named, where the wearer has them. */
        public Drawn hungFrom(BakedGeometry wearer, EntityAnimator wearerAnimator) {
            if (wearer == null) return this;

            return hungAt(bone -> BedrockModelRenderer.anchorOf(wearer, wearerAnimator, bone));
        }

        /** The same pass, hung off the parts of a Java model answering to the bones it named. */
        Drawn hungFrom(JavaModelParts wearer) {
            return hungAt(wearer::anchorOf);
        }

        private Drawn hungAt(Function<String, Matrix4f> find) {
            if (bindings.isEmpty()) return this;

            Map<String, Matrix4f> found = new HashMap<>();
            bindings.forEach((own, wanted) -> {
                Matrix4f anchor = find.apply(wanted);
                if (anchor != null) {
                    found.put(own, anchor);
                } else if (REPORTED.add(wanted)) {
                    AddonEntities.unsupported("geometry binding " + wanted);
                }
            });
            return new Drawn(pass, animator, slot, bindings, found);
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
            Chosen chosen = choose(entity, item, slotName(slot), partialTick);
            if (chosen == null) continue;

            parentSetup(chosen.binding(), wearer);
            for (AddonModels.Pass pass : chosen.binding().models().resolve(chosen.animator())) {
                drawn.add(new Drawn(pass, chosen.animator(), slot,
                    bindings(pass.geometry(), chosen.animator()), Map.of()));
            }
        }
        return drawn;
    }

    /**
     * For each bone of this model that asked to hang off a bone of the wearer, the bone it named.
     *
     * A `binding` is an expression rather than a name, because Mojang's own attachables answer it
     * with `q.item_slot_to_bone_name(c.item_slot)` and the spyglass moves between the hand and the
     * head by the same call. Mojang's sit on the model's root; a pack may put one on any bone below
     * it, such as the one child of an empty root.
     */
    private static Map<String, String> bindings(BakedGeometry geometry, EntityAnimator animator) {
        Map<String, String> bindings = new HashMap<>();
        geometry.bones().values().stream().filter(bone -> bone.binding() != null).forEach(bone -> {
            String wanted = bone.binding().evaluate(animator.environment()).text();
            if (wanted != null && !wanted.isEmpty()) bindings.put(bone.name(), wanted);
        });
        return bindings;
    }

    /**
     * The attachable drawn for the item: the first whose own condition holds, else the first naming
     * the item with no condition.
     *
     * A condition is asked of the attachable's own scope, where `query.owner_identifier` names the
     * entity carrying it, which is how a pack dresses a player in one model and everything else in
     * another. Mojang ships such pairs as `diamond_helmet.json` with no condition beside
     * `diamond_helmet.player.json` asking for the player, so the one whose condition holds is the
     * more particular answer whichever file was read first. Only the chosen one's clock moves.
     */
    private static Chosen choose(LivingEntity entity, String item, String itemSlot, float partialTick) {
        List<AddonClientAssets.Binding> offered = AddonClientAssets.attachablesFor(item);
        AddonClientAssets.Binding picked = offered.stream()
            .filter(binding -> holds(entity, binding, item, itemSlot, partialTick))
            .findFirst()
            .or(() -> offered.stream().filter(binding -> binding.definition().items().get(item) == null).findFirst())
            .orElse(null);
        if (picked == null) return null;

        EntityAnimator animator = Animators.of(entity, picked, keyOf(item, picked), itemSlot, partialTick,
            entity.tickCount + partialTick);
        animator.environment().setContext("owning_entity", MolangValue.of(new EntityReference(entity)));
        return new Chosen(picked, animator);
    }

    private static boolean holds(LivingEntity entity, AddonClientAssets.Binding binding, String item,
        String itemSlot, float partialTick) {
        MolangExpression condition = binding.definition().items().get(item);
        if (condition == null) return false;

        EntityAnimator standing = Animators.standing(entity, binding, keyOf(item, binding), itemSlot, partialTick,
            entity.tickCount + partialTick);
        standing.environment().setContext("owning_entity", MolangValue.of(new EntityReference(entity)));
        return condition.evaluate(standing.environment()).truthy();
    }

    /**
     * What an attachable's animator is kept under for one entity.
     *
     * The attachable's own identifier goes beside the item, because two packs may both dress the
     * same item and each needs a clock and a set of variables of its own.
     */
    private static String keyOf(String item, AddonClientAssets.Binding binding) {
        return item + "#" + binding.definition().identifier();
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
