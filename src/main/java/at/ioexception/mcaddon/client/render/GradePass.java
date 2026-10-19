package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.grading.GradeUniforms;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.system.MemoryStack;

/**
 * Grades the finished frame of the world, before the heads-up display is drawn over it.
 *
 * The frame is copied aside and drawn back over itself through `colour_grade.fsh`, one triangle
 * covering the screen, as a pipeline of the game's own device. A grade that leaves every pixel alone
 * draws nothing. Before 26 nothing is drawn.
 */
public final class GradePass {
    private static final String LABEL = "mcaddon colour grade";

    private GradePass() {
    }

    //? if >=26 {
    private static com.mojang.blaze3d.pipeline.RenderPipeline pipeline;

    private static com.mojang.blaze3d.buffers.GpuBuffer uniforms;
    private static com.mojang.blaze3d.textures.GpuTexture copy;
    private static com.mojang.blaze3d.textures.GpuTextureView copyView;

    /** Adds the pass's pipeline to the game's own, which the shader manager compiles as resources load. */
    public static void register() {
        pipeline = net.minecraft.client.renderer.RenderPipelines.register(at.ioexception.mcaddon.platform.Platform
            .screenPipeline(McAddon.id("pipeline/colour_grade"), McAddon.id("post/colour_grade"), "InSampler",
                "ColourGrade"));
    }

    public static void draw(RenderTarget target, GradeUniforms grade) {
        if (grade.leavesFrameAlone()) return;

        com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        com.mojang.blaze3d.textures.GpuTexture colour = target.getColorTexture();
        int width = colour.getWidth(0);
        int height = colour.getHeight(0);
        if (copy == null || copy.getWidth(0) != width || copy.getHeight(0) != height) {
            if (copy != null) {
                copyView.close();
                copy.close();
            }
            copy = device.createTexture(() -> LABEL, com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST
                    | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
                colour.getFormat(), width, height, 1, 1);
            copyView = device.createTextureView(copy);
        }
        if (uniforms == null) {
            uniforms = device.createBuffer(() -> LABEL, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM
                | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, GradeUniforms.BYTES);
        }

        com.mojang.blaze3d.systems.CommandEncoder encoder = device.createCommandEncoder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(uniforms.slice(), grade.write(stack.malloc(GradeUniforms.BYTES)));
        }
        encoder.copyTextureToTexture(colour, copy, 0, 0, 0, 0, 0, width, height);
        try (com.mojang.blaze3d.systems.RenderPass pass = at.ioexception.mcaddon.platform.Platform.screenPass(
            encoder, () -> LABEL, target.getColorTextureView())) {
            pass.setPipeline(pipeline);
            pass.setUniform("ColourGrade", uniforms);
            pass.bindTexture("InSampler", copyView, com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                .getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST));
            at.ioexception.mcaddon.platform.Platform.drawScreenTriangle(pass);
        }
    }
    //?} else {
    /*// Before 26 the frame is left as drawn, and the conversion names every grade a pack ships.
    public static void register() {
    }

    public static void draw(RenderTarget target, GradeUniforms grade) {
    }
    *///?}
}
