package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.block.BorderColumns;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/** Bedrock's border block, placed by its Bedrock id, walling off the whole column it stands in. */
public final class BorderGameTests {
    /** Inside the 8x8x8 the empty structure covers, with room above it for a mob. */
    private static final BlockPos BORDER = new BlockPos(3, 1, 3);
    private static final int ABOVE = 5;

    /**
     * A pig five blocks over a border block is stopped at the edge of its column, from the west and
     * from the north, and walks on past it along the column's side.
     */
    @GameTest()
    public void aMobCannotEnterABordersColumnHighAboveIt(GameTestHelper helper) {
        BlockPos border = helper.absolutePos(BORDER);
        BedrockLines.run(helper, "setblock " + border.getX() + " " + border.getY() + " " + border.getZ()
            + " minecraft:border_block");
        helper.assertTrue(BorderColumns.is(helper.getLevel().getBlockState(border)),
            "minecraft:border_block placed " + helper.getLevel().getBlockState(border) + ".");

        Entity west = floating(helper, border.getX() - 1.5, border.getY() + ABOVE, border.getZ() + 0.5);
        west.move(MoverType.SELF, new Vec3(2, 0, 0));
        helper.assertTrue(Math.abs(west.getBoundingBox().maxX - border.getX()) < 1.0E-6,
            "a pig moving east from the west stopped with its side at x " + west.getBoundingBox().maxX
                + " where the column starts at x " + border.getX() + ".");

        Entity north = floating(helper, border.getX() + 0.5, border.getY() + ABOVE, border.getZ() - 1.5);
        north.move(MoverType.SELF, new Vec3(0, 0, 2));
        helper.assertTrue(Math.abs(north.getBoundingBox().maxZ - border.getZ()) < 1.0E-6,
            "a pig moving south from the north stopped with its side at z " + north.getBoundingBox().maxZ
                + " where the column starts at z " + border.getZ() + ".");

        west.move(MoverType.SELF, new Vec3(0, 0, 2));
        helper.assertTrue(Math.abs(west.getZ() - (border.getZ() + 2.5)) < 1.0E-6,
            "a pig walking along the column's side was held at z " + west.getZ() + ".");
        helper.succeed();
    }

    private static Entity floating(GameTestHelper helper, double x, double y, double z) {
        Entity pig = Platform.createMob(AddonEntities.typeOf("minecraft:pig"), helper.getLevel());
        helper.assertTrue(pig != null, "a pig could not be created.");
        Platform.placeAt(pig, x, y, z, 0f, 0f);
        pig.setNoGravity(true);
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }
}
