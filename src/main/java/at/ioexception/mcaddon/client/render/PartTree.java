package at.ioexception.mcaddon.client.render;

import net.minecraft.client.model.geom.ModelPart;

import java.util.Map;

/**
 * A Java model part's place in its tree, which `ModelPartMixin` gives every part.
 *
 * Java keeps a part's children by name and tells nobody else. Before 1.21.2 a model need not keep
 * its root either, so there a part also knows the part it hangs off, which is the way from any one
 * part a model holds back up to the names of all of them.
 */
public interface PartTree {
    Map<String, ModelPart> mcaddon$children();

    //? if <1.21.2 {
    /*ModelPart mcaddon$parent();
    *///?}
}
