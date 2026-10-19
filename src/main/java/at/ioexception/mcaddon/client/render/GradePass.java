package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.grading.GradeUniforms;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.pipeline.RenderTarget;
//? if >=26.3 {
/*import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
*///?} elif >=26 {
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
//?}
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryStack;

/**
 * Grades the finished frame of the world, before the heads-up display is drawn over it.
 *
 * The frame is copied aside and drawn back over itself through `colour_grade.glsl`, one triangle
 * covering the screen, as a pipeline of the game's own device. A grade that leaves every pixel alone
 * draws nothing. Before 26 nothing is drawn.
 */
public final class GradePass {
    private static final String LABEL = "mcaddon colour grade";

    private GradePass() {
    }

    //? if >=26 {
    private static RenderPipeline pipeline;

    private static GpuBuffer uniforms;
    private static GpuTexture copy;
    private static GpuTextureView copyView;

    /**
     * Builds the pass's pipeline. Before 26.3 it joins the game's own list, which the shader manager
     * compiles as resources load; from 26.3 the game compiles a pipeline the first time it is used.
     */
    public static void register() {
        //? if >=26.3 {
        /*pipeline = Platform.screenPipeline(McAddon.id("pipeline/colour_grade"),
            McAddon.id("post/colour_grade_spirv"), "InSampler", "ColourGrade");
        *///?} else {
        pipeline = net.minecraft.client.renderer.RenderPipelines.register(Platform.screenPipeline(
            McAddon.id("pipeline/colour_grade"), McAddon.id("post/colour_grade"), "InSampler", "ColourGrade"));
        //?}
    }

    public static void draw(RenderTarget target, GradeUniforms grade) {
        if (grade.leavesFrameAlone()) return;

        GpuDevice device = RenderSystem.getDevice();
        GpuTexture colour = target.getColorTexture();
        int width = colour.getWidth(0);
        int height = colour.getHeight(0);
        if (copy == null || copy.getWidth(0) != width || copy.getHeight(0) != height) {
            if (copy != null) {
                copyView.close();
                copy.close();
            }
            copy = device.createTexture(() -> LABEL, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                colour.getFormat(), width, height, 1, 1);
            copyView = device.createTextureView(copy);
        }
        if (uniforms == null) {
            uniforms = device.createBuffer(() -> LABEL, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                GradeUniforms.BYTES);
        }

        CommandEncoder encoder = device.createCommandEncoder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoder.writeToBuffer(uniforms.slice(), grade.write(stack.malloc(GradeUniforms.BYTES)));
        }
        encoder.copyTextureToTexture(colour, copy, 0, 0, 0, 0, 0, width, height);
        try (RenderPass pass = Platform.screenPass(encoder, () -> LABEL, target.getColorTextureView())) {
            //? if >=26.3 {
            /*pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            *///?} else {
            pass.setPipeline(pipeline);
            //?}
            pass.setUniform("ColourGrade", uniforms);
            //? if >=26.3 {
            /*pass.setUniform("InSampler", copyView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            *///?} else {
            pass.bindTexture("InSampler", copyView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            //?}
            Platform.drawScreenTriangle(pass);
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
