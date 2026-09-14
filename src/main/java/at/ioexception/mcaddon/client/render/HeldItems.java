package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The item in an entity's hand that no attachable draws for it.
 *
 * Bedrock hangs it off the bone `q.item_slot_to_bone_name` names for the slot, so a model saying
 * where its hands are is one carrying a `rightItem` or a `leftItem` bone. Forty of Mojang's own
 * entity geometries carry one and it holds no boxes: it exists to be hung off. A model with neither
 * bone has nowhere to put an item and draws none.
 */
public final class HeldItems {

    /** One item to draw: where on the model it sits, how big, and whether it carries its own light. */
    public record Held(ItemStack stack, boolean offHand, Matrix4f anchor, float scale, boolean lit) {
    }

    /** The bone each hand is drawn at, which is what `q.item_slot_to_bone_name` answers with. */
    private static final String MAIN_HAND_BONE = "rightitem";
    private static final String OFF_HAND_BONE = "leftitem";

    private HeldItems() {
    }

    /**
     * What the entity holds that is drawn as an item rather than as a model of the pack's own.
     *
     * `covered` are the slots an attachable already drew, because Bedrock draws the attachable
     * instead of the item wherever a pack describes one.
     */
    public static List<Held> on(LivingEntity entity, EntityAnimator animator, BakedGeometry geometry,
        Set<EquipmentSlot> covered) {
        List<Held> held = new ArrayList<>();
        if (entity == null || animator == null || geometry == null) return held;

        ClientEntityDefinition definition = animator.definition();
        MolangExpression hidden = definition.heldItemsHidden();
        if (hidden != null && hidden.evaluate(animator.environment()).truthy()) return held;

        add(held, entity, animator, geometry, covered, EquipmentSlot.MAINHAND, MAIN_HAND_BONE);
        add(held, entity, animator, geometry, covered, EquipmentSlot.OFFHAND, OFF_HAND_BONE);
        return held;
    }

    private static void add(List<Held> held, LivingEntity entity, EntityAnimator animator,
        BakedGeometry geometry, Set<EquipmentSlot> covered, EquipmentSlot slot, String bone) {
        if (covered.contains(slot)) return;

        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty()) return;

        Matrix4f anchor = BedrockModelRenderer.anchorOf(geometry, animator, bone);
        if (anchor == null) return;

        ClientEntityDefinition definition = animator.definition();
        MolangExpression asked = definition.heldItemScale();
        // The published default is one, and Mojang states the value has to come out above zero.
        float scale = asked == null ? 1f : asked.asFloat(animator.environment());
        held.add(new Held(stack, slot == EquipmentSlot.OFFHAND, anchor,
            scale > 0f ? scale : 1f, definition.heldItemLit()));
    }
}
