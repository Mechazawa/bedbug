package at.ioexception.mcaddon.platform;

import at.ioexception.mcaddon.McAddon;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21 {
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
//?}
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
//? if >=26 {
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
//?}

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every call whose spelling differs between the supported Minecraft versions.
 *
 * Keeping them here is what lets the rest of the mod read as ordinary code. Whole-class renames are
 * handled by the string replacements in the buildscript instead.
 */
public final class Platform {
    private Platform() {
    }

    /** The factory replaced the public constructor in 1.21. */
    public static Identifier id(String namespace, String path) {
        //? if >=1.21 {
        return Identifier.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new Identifier(namespace, path);
        *///?}
    }

    public static Identifier idOf(ResourceKey<?> key) {
        //? if >=26 {
        return key.identifier();
        //?} else {
        /*return key.location();
        *///?}
    }

    public static Entity entityByUuid(ServerLevel level, UUID uuid) {
        //? if >=26 {
        return level.getEntityInAnyDimension(uuid);
        //?} else {
        /*return level.getEntity(uuid);
        *///?}
    }

    public static Set<String> tagsOf(Entity entity) {
        //? if >=26 {
        return entity.entityTags();
        //?} else {
        /*return entity.getTags();
        *///?}
    }

    /** Ticks since dawn on day zero. The name changed when the clock moved to the overworld. */
    public static long dayTime(Level level) {
        //? if >=26 {
        return level.getOverworldClockTime();
        //?} else {
        /*return level.getDayTime();
        *///?}
    }

    public static void kill(Entity entity, ServerLevel level) {
        //? if >=1.21.2 {
        entity.kill(level);
        //?} else {
        /*entity.kill();
        *///?}
    }

    /** Answers whether the blow landed. */
    public static boolean hurt(Entity entity, ServerLevel level, DamageSource source, float amount) {
        //? if >=1.21.2 {
        return entity.hurtServer(level, source, amount);
        //?} else {
        /*return entity.hurt(source, amount);
        *///?}
    }

    /** Sets position and rotation without interpolating from where the entity was. */
    public static void placeAt(Entity entity, double x, double y, double z, float yaw, float pitch) {
        //? if >=26 {
        entity.snapTo(x, y, z, yaw, pitch);
        //?} else {
        /*entity.moveTo(x, y, z, yaw, pitch);
        *///?}
    }

    public static CommandSourceStack commandSourceOf(Entity entity, ServerLevel level) {
        //? if >=26 {
        return entity.createCommandSourceStackForNameResolution(level);
        //?} else {
        /*return entity.createCommandSourceStack();
        *///?}
    }

    /**
     * The source with at least the authority Java runs a function with.
     *
     * A command a pack's entity, block, dialogue or script runs is one the pack wrote, the way a
     * function is, and a mob carries no authority of its own.
     */
    public static CommandSourceStack withFunctionPermission(CommandSourceStack source) {
        //? if >=26 {
        return source.withMaximumPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
        //?} else {
        /*return source.withMaximumPermission(2);
        *///?}
    }

    /** Java's default for both the commands one run may queue and the forks one command may make. */
    private static final int COMMAND_LIMIT = 65536;

    /**
     * Runs a parsed line to its end before returning, functions it calls included, and answers what
     * it returned.
     *
     * From 1.20.3 a command runs inside an execution context, and the dispatcher alone refuses a
     * `function`. The server's own context runs a line queued while another command is running only
     * once that command is done, so the line gets a context of its own. A failure there reaches the
     * source as a message rather than being thrown, so the source has to be one that hears it.
     */
    public static int runToEnd(com.mojang.brigadier.ParseResults<CommandSourceStack> parsed, String line)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        //? if >=1.21 {
        com.mojang.brigadier.context.ContextChain<CommandSourceStack> chain =
            com.mojang.brigadier.context.ContextChain.tryFlatten(parsed.getContext().build(line))
                .orElseThrow(() -> com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS
                    .dispatcherUnknownCommand().createWithContext(parsed.getReader()));
        int[] returned = {0};
        CommandSourceStack counted = parsed.getContext().getSource()
            .withCallback((success, result) -> returned[0] += success ? result : 0);
        try (net.minecraft.commands.execution.ExecutionContext<CommandSourceStack> context =
            new net.minecraft.commands.execution.ExecutionContext<>(COMMAND_LIMIT, COMMAND_LIMIT,
                net.minecraft.util.profiling.InactiveProfiler.INSTANCE)) {
            net.minecraft.commands.execution.ExecutionContext.queueInitialCommandExecution(context, line, chain,
                counted, net.minecraft.commands.CommandResultCallback.EMPTY);
            context.runCommandQueue();
        }
        return returned[0];
        //?} else {
        /*return parsed.getContext().getSource().getServer().getCommands().getDispatcher().execute(parsed);
        *///?}
    }

    /** A line read against a tree other than the one the command reading it sits in. */
    public interface ReadLine {
        com.mojang.brigadier.ParseResults<CommandSourceStack> parsed();

        String line();

        /** Asked once the line, and everything it started, has run. */
        void finish();
    }

    @FunctionalInterface
    public interface LineReader {
        /**
         * Null for a line with nothing in it to run.
         *
         * `topLevel` is whether the command is the one a chat line or a command block started, as
         * against a line of a function. From 1.20.3 a function's lines run one frame deeper; before
         * that nothing tells them apart and it is always false.
         */
        ReadLine read(com.mojang.brigadier.context.CommandContext<CommandSourceStack> command,
            CommandSourceStack source, boolean topLevel) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    /**
     * A command that reads a line of its own and runs it as part of itself.
     *
     * From 1.20.3 the line is queued on the execution the command is part of, which keeps the
     * command limit, the depth a function call may reach and `execute store` working across it, and
     * a function the line calls runs inside the same execution rather than one of its own. Before
     * that a command runs to its end on the spot, and a function a running function calls is queued
     * by the function manager itself.
     */
    public static com.mojang.brigadier.Command<CommandSourceStack> runningLine(LineReader reader) {
        //? if >=1.21 {
        return new RunningLine(reader);
        //?} else {
        /*return command -> {
            ReadLine read = reader.read(command, command.getSource(), false);
            if (read == null) return 0;
            try {
                return runToEnd(read.parsed(), read.line());
            } finally {
                read.finish();
            }
        };
        *///?}
    }

    //? if >=1.21 {
    private static final class RunningLine
        extends net.minecraft.commands.execution.CustomCommandExecutor.WithErrorHandling<CommandSourceStack>
        implements net.minecraft.commands.execution.CustomCommandExecutor.CommandAdapter<CommandSourceStack> {
        private final LineReader reader;

        private RunningLine(LineReader reader) {
            this.reader = reader;
        }

        @Override
        protected void runGuarded(CommandSourceStack sender,
            com.mojang.brigadier.context.ContextChain<CommandSourceStack> step,
            net.minecraft.commands.execution.ChainModifiers modifiers,
            net.minecraft.commands.execution.ExecutionControl<CommandSourceStack> output)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ReadLine read = reader.read(step.getTopContext().copyFor(sender), sender,
                output.currentFrame().depth() == 0);
            if (read == null) return;

            com.mojang.brigadier.context.ContextChain<CommandSourceStack> chain;
            try {
                chain = com.mojang.brigadier.context.ContextChain
                    .tryFlatten(read.parsed().getContext().build(read.line()))
                    .orElseThrow(() -> com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS
                        .dispatcherUnknownCommand().createWithContext(read.parsed().getReader()));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException unfinished) {
                read.finish();
                throw unfinished;
            }
            output.queueNext(new net.minecraft.commands.execution.tasks.BuildContexts.Continuation<>(read.line(),
                chain, modifiers, sender, List.of(sender)));
            output.queueNext((context, frame) -> read.finish());
        }
    }
    //?}

    public static GameType gameMode(ServerPlayer player) {
        //? if >=26 {
        return player.gameMode();
        //?} else {
        /*return player.gameMode.getGameModeForPlayer();
        *///?}
    }

    /** The builder started naming its type with a registry key rather than a string in 1.21.2. */
    public static <T extends Entity> EntityType<T> buildEntityType(EntityType.Builder<T> builder, Identifier id) {
        //? if >=1.21.2 {
        return builder.build(ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id));
        //?} else {
        /*return builder.build(id.toString());
        *///?}
    }

    /**
     * Whether a summon refuses this type while the difficulty is peaceful, as Bedrock's does.
     *
     * 26 asks the type itself; before that the types Java removes on peaceful are its monsters.
     */
    public static boolean refusedInPeaceful(EntityType<?> type, ServerLevel level) {
        if (level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL) return false;
        //? if >=26 {
        return !type.isAllowedInPeaceful();
        //?} else {
        /*return type.getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
        *///?}
    }

    /** One chunk's coordinates packed into the number Java keys its chunk maps by. */
    public static long chunkKey(int x, int z) {
        //? if >=26 {
        return net.minecraft.world.level.ChunkPos.pack(x, z);
        //?} else {
        /*return net.minecraft.world.level.ChunkPos.asLong(x, z);
        *///?}
    }

    /** The chunks a level holds forced, by their packed coordinates. */
    public static it.unimi.dsi.fastutil.longs.LongSet forcedChunks(ServerLevel level) {
        //? if >=26 {
        return level.getForceLoadedChunks();
        //?} else {
        /*return level.getForcedChunks();
        *///?}
    }

    /** The placed feature a level's registries hold under that id, or null. */
    public static net.minecraft.world.level.levelgen.placement.PlacedFeature placedFeature(ServerLevel level,
        Identifier id) {
        //? if >=26 {
        return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE)
            .getValue(id);
        //?} else {
        /*return level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE)
            .get(id);
        *///?}
    }

    /**
     * Whether a jigsaw can be placed without waterlogging what it puts into water. 1.20.1 has no
     * liquid settings and waterlogs every block that can be.
     */
    public static boolean placesJigsawsDry() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Java's `place jigsaw` with the settings Bedrock's command adds. False when nothing fits at that
     * position.
     */
    public static boolean placeJigsaw(ServerLevel level,
        net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool> pool,
        Identifier target, int depth, net.minecraft.core.BlockPos at, JigsawSettings settings) {
        return place(level, pool, Optional.of(target), depth, at, false, Optional.empty(), null, settings);
    }

    /**
     * A jigsaw structure placed from its own definition with the settings Bedrock's `place structure`
     * adds. Its start height is sampled at the position's column unless `ignoreStartHeight` puts the
     * start at the position's own height, which also drops the heightmap projection. False when
     * nothing fits there.
     */
    public static boolean placeStructure(ServerLevel level,
        net.minecraft.world.level.levelgen.structure.structures.JigsawStructure structure,
        net.minecraft.core.BlockPos at, boolean ignoreStartHeight, JigsawSettings settings) {
        var fields = (at.ioexception.mcaddon.mixin.JigsawStructureAccessor) (Object) structure;
        var generator = level.getChunkSource().getGenerator();
        var start = ignoreStartHeight ? at : at.atY(fields.mcaddon$startHeight().sample(level.getRandom(),
            new net.minecraft.world.level.levelgen.WorldGenerationContext(generator, level)));
        return place(level, fields.mcaddon$startPool(), fields.mcaddon$startJigsawName(), fields.mcaddon$maxDepth(),
            start, fields.mcaddon$useExpansionHack(),
            ignoreStartHeight ? Optional.empty() : fields.mcaddon$projectStartToHeightmap(), structure, settings);
    }

    /**
     * Builds the pieces of a jigsaw and places them, counting the entities the pieces bring along as
     * new when they were not standing in the area beforehand. A null `from` is a pool placed on its
     * own, which has no structure definition to take the limits from.
     */
    private static boolean place(ServerLevel level,
        net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool> pool,
        Optional<Identifier> target, int depth, net.minecraft.core.BlockPos start, boolean expansionHack,
        Optional<net.minecraft.world.level.levelgen.Heightmap.Types> project,
        net.minecraft.world.level.levelgen.structure.structures.JigsawStructure from, JigsawSettings settings) {
        net.minecraft.world.level.chunk.ChunkGenerator generator = level.getChunkSource().getGenerator();
        net.minecraft.world.level.levelgen.RandomState random = level.getChunkSource().randomState();
        net.minecraft.world.level.levelgen.structure.Structure.GenerationContext context =
            new net.minecraft.world.level.levelgen.structure.Structure.GenerationContext(level.registryAccess(),
                //? if >=26.3 {
                /*generator, generator.getBiomeSource(),
                random.createClimateSampler(
                    net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED),
                random,
                *///?} else {
                generator, generator.getBiomeSource(), random,
                //?}
                structureTemplates(level.getServer()), level.getSeed(),
                new net.minecraft.world.level.ChunkPos(start.getX() >> 4, start.getZ() >> 4), level, biome -> true);
        var fields = (at.ioexception.mcaddon.mixin.JigsawStructureAccessor) (Object) from;
        Optional<net.minecraft.world.level.levelgen.structure.Structure.GenerationStub> stub;
        //? if >=1.21 {
        var liquid = settings.waterlog()
            ? net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.APPLY_WATERLOGGING
            : net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.IGNORE_WATERLOGGING;
        //? if >=26 {
        var maxDistance = from == null
            ? new net.minecraft.world.level.levelgen.structure.structures.JigsawStructure.MaxDistance(128)
            : fields.mcaddon$maxDistanceFromCenter();
        //?} else {
        /*int maxDistance = from == null ? 128 : fields.mcaddon$maxDistanceFromCenter();
        *///?}
        var aliases = from == null
            ? net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup.EMPTY
            : net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup.create(
                fields.mcaddon$poolAliases(), start, level.getSeed());
        var padding = from == null
            ? net.minecraft.world.level.levelgen.structure.structures.JigsawStructure.DEFAULT_DIMENSION_PADDING
            : fields.mcaddon$dimensionPadding();
        stub = net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement.addPieces(context, pool, target,
            depth, start, expansionHack, project, maxDistance, aliases, padding, liquid);
        //?} else {
        /*int maxDistance = from == null ? 128 : fields.mcaddon$maxDistanceFromCenter();
        stub = net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement.addPieces(context, pool, target,
            depth, start, expansionHack, project, maxDistance);
        *///?}
        if (stub.isEmpty()) return false;

        var pieces = stub.get().getPiecesBuilder().build();
        var area = net.minecraft.world.phys.AABB.of(pieces.calculateBoundingBox());
        java.util.Set<Entity> before = new java.util.HashSet<>(level.getEntities((Entity) null, area, entity -> true));
        pieces.pieces().stream()
            .filter(net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece.class::isInstance)
            .map(net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece.class::cast)
            .forEach(piece -> piece.place(level, level.structureManager(), generator, level.getRandom(),
                net.minecraft.world.level.levelgen.structure.BoundingBox.infinite(), start, settings.keepJigsaws()));
        if (!settings.includeEntities()) {
            level.getEntities((Entity) null, area,
                entity -> !before.contains(entity) && !(entity instanceof net.minecraft.world.entity.player.Player))
                .forEach(Entity::discard);
        }
        return true;
    }

    /** A player's own entry in the operator list, made or taken away. */
    public static void setOperator(net.minecraft.server.MinecraftServer server, ServerPlayer player,
        boolean operator) {
        //? if >=26 {
        if (operator) server.getPlayerList().op(player.nameAndId());
        else server.getPlayerList().deop(player.nameAndId());
        //?} else {
        /*if (operator) server.getPlayerList().op(player.getGameProfile());
        else server.getPlayerList().deop(player.getGameProfile());
        *///?}
    }

    public static boolean isOperator(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        //? if >=26 {
        return server.getPlayerList().isOp(player.nameAndId());
        //?} else {
        /*return server.getPlayerList().isOp(player.getGameProfile());
        *///?}
    }

    /** Whether the player is the one whose single player world this server is, cheats or none. */
    public static boolean isHost(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        //? if >=26 {
        return server.isSingleplayerOwner(player.nameAndId());
        //?} else {
        /*return server.isSingleplayerOwner(player.getGameProfile());
        *///?}
    }

    /** Whether a bed used here blows up rather than letting anybody sleep, which 26 asks the dimension. */
    public static boolean bedExplodes(Level level, net.minecraft.core.BlockPos pos) {
        //? if >=26.3 {
        /*return level.environmentAttributes()
            .getValue(net.minecraft.world.attribute.EnvironmentAttributes.BED_RULE, pos).destroyOnUse();
        *///?} elif >=26 {
        return level.environmentAttributes()
            .getValue(net.minecraft.world.attribute.EnvironmentAttributes.BED_RULE, pos).explodes();
        //?} else {
        /*return !net.minecraft.world.level.block.BedBlock.canSetSpawn(level);
        *///?}
    }

    /** Whether a player's recipe book holds the recipe of this id. */
    public static boolean knowsRecipe(ServerPlayer player, Identifier recipe) {
        //? if >=1.21.2 {
        return player.getRecipeBook().contains(ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
            recipe));
        //?} else {
        /*return player.getRecipeBook().contains(recipe);
        *///?}
    }

    /** Puts a rider on a vehicle whether or not the rider could climb on by itself. */
    public static boolean mount(Entity rider, Entity vehicle) {
        //? if >=26 {
        return rider.startRiding(vehicle, true, true);
        //?} else {
        /*return rider.startRiding(vehicle, true);
        *///?}
    }

    public static Entity createMob(EntityType<?> type, ServerLevel level) {
        //? if >=1.21.2 {
        return type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        //?} else {
        /*return type.create(level);
        *///?}
    }

    /**
     * False when no effect is registered under that id.
     *
     * Three forms rather than two: the effect instance started taking a holder in 1.20.5, and the
     * registry lookup that returns one was `getHolder` until 26 renamed it to `get`.
     */
    public static boolean addEffect(LivingEntity entity, Identifier effect, int duration, int amplifier,
        boolean ambient, boolean particles) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect)
            .map(holder -> entity.addEffect(new MobEffectInstance(holder, duration, amplifier, ambient, particles)))
            .orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect)
            .map(holder -> entity.addEffect(new MobEffectInstance(holder, duration, amplifier, ambient, particles)))
            .orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null
            && entity.addEffect(new MobEffectInstance(found, duration, amplifier, ambient, particles));
        *///?}
    }

    /** Whether the entity currently has that effect. The lookup returns a holder from 1.21. */
    public static boolean hasEffect(LivingEntity entity, Identifier effect) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect).map(entity::hasEffect).orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect).map(entity::hasEffect).orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null && entity.hasEffect(found);
        *///?}
    }

    public static boolean removeEffect(LivingEntity entity, Identifier effect) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect).map(entity::removeEffect).orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect).map(entity::removeEffect).orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null && entity.removeEffect(found);
        *///?}
    }

    /** The slot the player is holding. It was a public field before 26 gave it an accessor. */
    public static int selectedSlot(ServerPlayer player) {
        //? if >=26 {
        return player.getInventory().getSelectedSlot();
        //?} else {
        /*return player.getInventory().selected;
        *///?}
    }

    /**
     * A pack the game reads out of a directory, built without consulting its metadata.
     *
     * The metadata is supplied here rather than read back off disk so the pack is always reported as
     * compatible: one converted directory is contributed to both the resource and the data pack
     * repository, and a single `pack.mcmeta` cannot carry the right format number for both.
     *
     * `knownAs` is what one installation of an add-on calls itself to another. A pack that names
     * itself is a pack the game registers entries from as stable, which is what keeps a world
     * holding a converted registry entry from opening behind a screen asking whether to load it.
     * The same name on two machines is what lets a server leave the content of those entries out of
     * what it sends, and a name nothing on the far side answers to costs only the saving.
     */
    public static Pack directoryPack(String id, String title, Path directory, PackType type,
        String knownAs, String knownVersion) {
        Component name = Component.literal(title);
        //? if >=1.21 {
        Optional<net.minecraft.server.packs.repository.KnownPack> known = Optional.of(
            new net.minecraft.server.packs.repository.KnownPack(McAddon.MOD_ID, knownAs, knownVersion));
        PackLocationInfo location = new PackLocationInfo(id, name, PackSource.BUILT_IN, known);
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            //? if >=26.3 {
            /*@Override
            public net.minecraft.server.packs.PackMetadataResources openMetadata(PackLocationInfo info) {
                return new PathPackResources(info, directory);
            }

            @Override
            public java.util.stream.Stream<PackResources> openResources(PackLocationInfo info,
                Pack.Metadata metadata) {
                return java.util.stream.Stream.of(new PathPackResources(info, directory));
            }
            *///?} else {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return new PathPackResources(info, directory);
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return new PathPackResources(info, directory);
            }
            //?}
        };
        // The features a pack asks for are what the world ends up enabled for, and a world enabled
        // for anything other than the ordinary set is one the game calls experimental: every world
        // with this mod installed would open behind a warning asking whether to load it at all.
        Pack.Metadata metadata = new Pack.Metadata(name, PackCompatibility.COMPATIBLE,
            FeatureFlags.VANILLA_SET, List.of());
        return new Pack(location, resources, metadata, new PackSelectionConfig(true, Pack.Position.TOP, true));
        //?} else {
        /*Pack.ResourcesSupplier resources = ignored -> new PathPackResources(id, directory, false);
        return Pack.readMetaAndCreate(id, name, true, resources, type, Pack.Position.TOP, PackSource.BUILT_IN);
        *///?}
    }

    /** Fabric renamed its creative tab builder in 26 to follow the game's own rename. */
    public static net.minecraft.world.item.CreativeModeTab.Builder creativeTab() {
        //? if >=26 {
        return net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder();
        //?} else {
        /*return net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder();
        *///?}
    }

    /**
     * Puts items into a tab the game already has each time it is built, under the name Fabric gives
     * that event in 26.
     */
    public static void addToCreativeTab(
        net.minecraft.resources.ResourceKey<net.minecraft.world.item.CreativeModeTab> tab,
        java.util.function.Supplier<java.util.List<net.minecraft.world.item.Item>> items) {
        //? if >=26 {
        net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents.modifyOutputEvent(tab)
            .register(output -> items.get().forEach(output::accept));
        //?} else {
        /*net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(tab)
            .register(entries -> items.get().forEach(entries::accept));
        *///?}
    }

    /**
     * One villager trade.
     *
     * What a trade costs became an `ItemCost` in 1.21, which carries the item and the count where a
     * stack used to.
     */
    public static net.minecraft.world.item.trading.MerchantOffer offer(net.minecraft.world.item.ItemStack costA,
        net.minecraft.world.item.ItemStack costB, net.minecraft.world.item.ItemStack result,
        int maxUses, int experience, float priceMultiplier) {
        //? if >=1.21 {
        return new net.minecraft.world.item.trading.MerchantOffer(
            new net.minecraft.world.item.trading.ItemCost(costA.getItem(), costA.getCount()),
            costB.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(new net.minecraft.world.item.trading.ItemCost(
                    costB.getItem(), costB.getCount())),
            result, maxUses, experience, priceMultiplier);
        //?} else {
        /*return new net.minecraft.world.item.trading.MerchantOffer(costA, costB, result, maxUses,
            experience, priceMultiplier);
        *///?}
    }

    /**
     * Enchants a stack the way a loot table or a trade asks for.
     *
     * 1.21 made enchantments data-driven, so what may be picked comes from the world's registries
     * and the treasure flag became a tag naming a choice; with no tag named, every enchantment the
     * game holds is on the list.
     */
    public static net.minecraft.world.item.ItemStack enchant(net.minecraft.util.RandomSource random,
        net.minecraft.world.item.ItemStack stack, int levels, boolean treasure,
        net.minecraft.core.RegistryAccess registries) {
        //? if >=1.21 {
        return EnchantmentHelper.enchantItem(random, stack, Math.max(1, levels), registries,
            java.util.Optional.empty());
        //?} else {
        /*return EnchantmentHelper.enchantItem(random, stack, Math.max(1, levels), treasure);
        *///?}
    }

    /**
     * Adds one natural spawn to a biome.
     *
     * The weight moved out of the spawner entry and into the call in 26, and Fabric renamed its
     * accessor for the settings in the same release.
     */
    public static void addSpawn(net.fabricmc.fabric.api.biome.v1.BiomeModificationContext context,
        MobCategory category, EntityType<?> type, int weight, int minimum, int maximum) {
        //? if >=26.3 {
        /*context.getMobSpawnSettings().addSpawn(category,
            new net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData(type,
                net.minecraft.util.valueproviders.UniformInt.of(minimum, maximum)), weight);
        *///?} elif >=26 {
        context.getMobSpawnSettings().addSpawn(category,
            new net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData(type, minimum, maximum), weight);
        //?} else {
        /*context.getSpawnSettings().addSpawn(category,
            new net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData(type, weight, minimum, maximum));
        *///?}
    }

    /**
     * Makes an item edible.
     *
     * The saturation field was renamed in 1.21, and from 1.21.2 how long eating takes moved out of
     * the food properties and into a separate consumable component. Before that a food is eaten in
     * one of two times, 1.6 seconds or the 0.8 of a fast one, so a pack asking for 0.8 or less gets
     * the fast one.
     */
    public static Item.Properties food(Item.Properties properties, int nutrition, float saturation,
        boolean alwaysEdible, float seconds) {
        //? if >=1.21.2 {
        net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties
            .Builder().nutrition(nutrition).saturationModifier(saturation);
        if (alwaysEdible) food = food.alwaysEdible();
        return properties.food(food.build(), net.minecraft.world.item.component.Consumable.builder()
            .consumeSeconds(seconds).build());
        //?} else if >=1.21 {
        /*net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties
            .Builder().nutrition(nutrition).saturationModifier(saturation);
        if (alwaysEdible) food = food.alwaysEdible();
        if (seconds <= FAST_FOOD_SECONDS) food = food.fast();
        return properties.food(food.build());
        *///?} else {
        /*net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties
            .Builder().nutrition(nutrition).saturationMod(saturation);
        if (alwaysEdible) food = food.alwaysEat();
        if (seconds <= FAST_FOOD_SECONDS) food = food.fast();
        return properties.food(food.build());
        *///?}
    }

    //? if <1.21.2 {
    /*private static final float FAST_FOOD_SECONDS = 0.8f;
    *///?}

    /**
     * An item a pack said can be worn, in the slot it named.
     *
     * What carries that differs by version. From 1.21.2 any item can say it is equippable through a
     * data component. Before that only an armour item could be worn, and an armour item needs a
     * material, so the item is built as one with a material that adds no protection and no armour
     * layer of its own: a pack's hat is a costume, and its attachable is what draws it.
     *
     * How many of it fit in a slot is whatever the properties already say, because an item carrying
     * durability is one to a slot already and saying so twice is what the older versions refuse.
     */
    public static Item buildWearable(Item.Properties properties, Identifier id,
        net.minecraft.world.entity.EquipmentSlot slot) {
        //? if >=1.21.2 {
        return buildItem(properties.component(net.minecraft.core.component.DataComponents.EQUIPPABLE,
            net.minecraft.world.item.equipment.Equippable.builder(slot).build()), id);
        //?} else {
        /*net.minecraft.world.item.ArmorItem.Type type = armourType(slot);
        if (type == null) return buildItem(properties, id);

        return new net.minecraft.world.item.ArmorItem(costume(), type, properties);
        *///?}
    }

    //? if <1.21.2 {
    /*// The armour slot a Bedrock wearable names, or null for a slot Java has no armour item for.
    private static net.minecraft.world.item.ArmorItem.Type armourType(
        net.minecraft.world.entity.EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> net.minecraft.world.item.ArmorItem.Type.HELMET;
            case CHEST -> net.minecraft.world.item.ArmorItem.Type.CHESTPLATE;
            case LEGS -> net.minecraft.world.item.ArmorItem.Type.LEGGINGS;
            case FEET -> net.minecraft.world.item.ArmorItem.Type.BOOTS;
            default -> null;
        };
    }
    *///?}

    //? if >=1.21 && <1.21.2 {
    /*// A material that protects nothing and draws nothing, which is what a costume is.
    private static net.minecraft.core.Holder<net.minecraft.world.item.ArmorMaterial> costume() {
        return net.minecraft.core.Holder.direct(new net.minecraft.world.item.ArmorMaterial(
            java.util.Map.of(), 0, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER,
            () -> net.minecraft.world.item.crafting.Ingredient.EMPTY, java.util.List.of(), 0f, 0f));
    }
    *///?}

    //? if <1.21 {
    /*// A material that protects nothing and draws nothing, which is what a costume is.
    private static net.minecraft.world.item.ArmorMaterial costume() {
        return new net.minecraft.world.item.ArmorMaterial() {
            @Override
            public int getDurabilityForType(net.minecraft.world.item.ArmorItem.Type type) {
                return 0;
            }

            @Override
            public int getDefenseForType(net.minecraft.world.item.ArmorItem.Type type) {
                return 0;
            }

            @Override
            public int getEnchantmentValue() {
                return 0;
            }

            @Override
            public net.minecraft.sounds.SoundEvent getEquipSound() {
                return net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER;
            }

            @Override
            public net.minecraft.world.item.crafting.Ingredient getRepairIngredient() {
                return net.minecraft.world.item.crafting.Ingredient.EMPTY;
            }

            @Override
            public String getName() {
                return "mcaddon_costume";
            }

            @Override
            public float getToughness() {
                return 0f;
            }

            @Override
            public float getKnockbackResistance() {
                return 0f;
            }
        };
    }
    *///?}

    /** A block carries its own registry id from 1.21.2, the same way an item does. */
    public static Block buildBlock(BlockBehaviour.Properties properties, Identifier id) {
        //? if >=1.21.2 {
        return new Block(properties.setId(ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, id)));
        //?} else {
        /*return new Block(properties);
        *///?}
    }

    /** The same as {@link #buildBlock}, for a block class that declares its own states. */
    public static at.ioexception.mcaddon.block.AddonBlock buildStatefulBlock(
        BlockBehaviour.Properties properties, Identifier id,
        java.util.List<net.minecraft.world.level.block.state.properties.Property<?>> states) {
        return at.ioexception.mcaddon.block.AddonBlock.build(named(properties, id), states,
            at.ioexception.mcaddon.block.AddonBlock::new);
    }

    /** Properties carrying the registry id a block built from them is registered under. */
    public static BlockBehaviour.Properties named(BlockBehaviour.Properties properties, Identifier id) {
        //? if >=1.21.2 {
        return properties.setId(ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, id));
        //?} else {
        /*return properties;
        *///?}
    }

    /** Java's coloured dust, whose colour is a packed integer from 1.21.2 and a vector before. */
    public static net.minecraft.core.particles.DustParticleOptions dust(int rgb, float scale) {
        //? if >=1.21.2 {
        return new net.minecraft.core.particles.DustParticleOptions(rgb, scale);
        //?} else {
        /*return new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(
            (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f), scale);
        *///?}
    }

    public static Item buildBlockItem(Block block, Item.Properties properties, Identifier id) {
        //? if >=1.21.2 {
        return new net.minecraft.world.item.BlockItem(block,
            properties.setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id)));
        //?} else {
        /*return new net.minecraft.world.item.BlockItem(block, properties);
        *///?}
    }

    /**
     * Whether a jukebox reads what it plays out of a registry a data pack writes into.
     *
     * 1.21 moved a record's sound, length and comparator reading out of the item class and into a
     * `jukebox_song` entry, which the converter writes as a file. Before that a record carries all
     * three itself and there is no such registry to write to.
     */
    public static boolean jukeboxSongsAreARegistry() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Names the song a record plays, which from 1.21 is all the item itself says about it.
     *
     * The song is named rather than held, so the entry it names is looked up when a jukebox needs
     * it and not while items are being registered, which is before any data pack has been read.
     */
    public static Item.Properties jukeboxPlayable(Item.Properties properties, Identifier song) {
        //? if >=1.21 {
        return properties.jukeboxPlayable(ResourceKey.create(
            net.minecraft.core.registries.Registries.JUKEBOX_SONG, song));
        //?} else {
        /*return properties;
        *///?}
    }

    /**
     * The class a record is before 1.21, or null on the versions where a plain item is one.
     *
     * A 1.20.1 jukebox tells its client which record is playing by the item's numeric id, and the
     * client asks the `RecordItem` at that id for the sound to play, so a record that is not one of
     * those plays nothing however it is described.
     */
    public static Item buildRecord(Item.Properties properties, Identifier sound,
        int comparatorOutput, int lengthInSeconds) {
        //? if >=1.21 {
        return null;
        //?} else {
        /*net.minecraft.sounds.SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(sound);
        if (event == null) return null;
        // The constructor is protected, and a record is meant to be one of the game's own.
        return new net.minecraft.world.item.RecordItem(comparatorOutput, event, properties, lengthInSeconds) {
        };
        *///?}
    }

    /** An item has carried its own registry id since 1.21.2, so it must be set before registering. */
    public static Item buildItem(Item.Properties properties, Identifier id) {
        //? if >=1.21.2 {
        return new Item(properties.setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id)));
        //?} else {
        /*return new Item(properties);
        *///?}
    }

    /**
     * A bag that holds other items, which is what Bedrock's `minecraft:storage_item` describes.
     *
     * Java keeps the picking up and putting back in `BundleItem` rather than in a component, and
     * from 1.20.5 the contents are a component the item has to carry from the start.
     */
    public static Item buildStorage(Item.Properties properties, Identifier id) {
        Item.Properties bag = properties.stacksTo(1);
        //? if >=1.21.2 {
        return new net.minecraft.world.item.BundleItem(bag
            .setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id))
            .component(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                net.minecraft.world.item.component.BundleContents.EMPTY));
        //?} else if >=1.21 {
        /*return new net.minecraft.world.item.BundleItem(bag.component(
            net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
            net.minecraft.world.item.component.BundleContents.EMPTY));
        *///?} else {
        /*return new net.minecraft.world.item.BundleItem(bag);
        *///?}
    }

    /**
     * A spawn egg for an entity type.
     *
     * From 1.21.2 the entity moved into a data component, but the behaviour still lives in
     * `SpawnEggItem`: a plain item carrying the component registers, shows an icon, and spawns
     * nothing.
     */
    public static Item spawnEgg(EntityType<?> type, Identifier id, int baseColour, int overlayColour) {
        //? if >=1.21.2 {
        // From 1.21.4 the two colours are tints in the item's own model definition, which the
        // conversion writes, so the item itself carries only the entity it spawns.
        return new net.minecraft.world.item.SpawnEggItem(new Item.Properties()
            .spawnEgg(type)
            .setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id)));
        //?} else {
        /*return new net.minecraft.world.item.SpawnEggItem(castType(type), baseColour, overlayColour,
            new Item.Properties());
        *///?}
    }

    //? if <1.21.2 {
    /*@SuppressWarnings("unchecked")
    private static net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>
        castType(EntityType<?> type) {
        return (net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>) type;
    }
    *///?}

    /**
     * Whether a data pack's directories carry their singular names.
     *
     * 1.21 renamed every one of them: `recipes`, `loot_tables`, `functions`, `advancements` and the
     * registry directories under `tags` all lost their plural. A file written under the other
     * spelling is read by nobody and reports nothing, so this is what decides where they go.
     */
    public static boolean singularDataDirectories() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether a recipe's ingredient is written as a bare item id.
     *
     * 1.21.2 replaced the object that named one. Before that the id goes under an `item` key.
     */
    public static boolean bareRecipeIngredients() {
        //? if >=1.21.2 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether dyeing an item is stated as a recipe naming it.
     *
     * Up to 1.21 anything in `#minecraft:dyeable` could be dyed and washed. 26 dropped that tag for
     * a `minecraft:crafting_dye` recipe per item, and kept a tag only for what a cauldron washes.
     */
    public static boolean dyeingIsARecipe() {
        //? if >=26 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether brewing, furnace fuel and composting are written in a data pack.
     *
     * From 26.3 a brewing stand reads `minecraft:brewing` recipes, and an item burns or composts
     * by a component naming a `context_int_provider` entry. Before that the game builds all three
     * as lists in code.
     */
    public static boolean brewingFuelAndCompostAreData() {
        //? if >=26.3 {
        /*return true;
        *///?} else {
        return false;
        //?}
    }

    /**
     * What a crafting grid one square wide makes out of these stacks, by the recipes the data packs
     * loaded, or an empty stack when no recipe takes them.
     *
     * 1.21 replaced the container a recipe matched against with an input of its own, and 26 stopped
     * handing the registries to `assemble`.
     */
    public static net.minecraft.world.item.ItemStack crafted(ServerLevel level,
        List<net.minecraft.world.item.ItemStack> column) {
        net.minecraft.world.item.crafting.RecipeManager recipes = level.getServer().getRecipeManager();
        //? if >=26 {
        net.minecraft.world.item.crafting.CraftingInput input =
            net.minecraft.world.item.crafting.CraftingInput.of(1, column.size(), column);
        return recipes.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level)
            .map(found -> found.value().assemble(input))
            .orElse(net.minecraft.world.item.ItemStack.EMPTY);
        //?} else if >=1.21 {
        /*net.minecraft.world.item.crafting.CraftingInput input =
            net.minecraft.world.item.crafting.CraftingInput.of(1, column.size(), column);
        return recipes.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level)
            .map(found -> found.value().assemble(input, level.registryAccess()))
            .orElse(net.minecraft.world.item.ItemStack.EMPTY);
        *///?} else {
        /*net.minecraft.world.inventory.TransientCraftingContainer grid =
            new net.minecraft.world.inventory.TransientCraftingContainer(null, 1, column.size(),
                net.minecraft.core.NonNullList.of(net.minecraft.world.item.ItemStack.EMPTY,
                    column.toArray(new net.minecraft.world.item.ItemStack[0])));
        return recipes.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, grid, level)
            .map(found -> found.assemble(grid, level.registryAccess()))
            .orElse(net.minecraft.world.item.ItemStack.EMPTY);
        *///?}
    }

    /**
     * What a recipe result names its item under.
     *
     * 1.21 renamed the field from `item` to `id`, a version before the ingredient changed shape.
     */
    public static String recipeResultKey() {
        //? if >=1.21 {
        return "id";
        //?} else {
        /*return "item";
        *///?}
    }

    /**
     * The result of a cooking or a stonecutting recipe, which those two state their own way.
     *
     * 1.20.1 names the item directly beside the recipe and puts a stonecutter's count beside it;
     * 1.21 gave both the same result object every other recipe type carries.
     */
    public static void simpleResult(com.google.gson.JsonObject json, String item, int count) {
        //? if >=1.21 {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("id", item);
        result.addProperty("count", Math.max(1, count));
        json.add("result", result);
        //?} else {
        /*json.addProperty("result", item);
        if (count > 1) json.addProperty("count", count);
        *///?}
    }

    /**
     * States which trim a smithing trim recipe applies, where the running version asks for one.
     *
     * 26 moved the pattern off the template item and onto the recipe. A trim pattern lives in a
     * registry a data pack writes into, and a world holding such a pack is one the game calls
     * experimental, so the only patterns available are the ones the game already ships: the name is
     * read back out of a template called `<pattern>_armor_trim_smithing_template`. False when the
     * template is a tag or is named some other way, which leaves the recipe unwritten and reported.
     */
    public static boolean smithingTrimPattern(com.google.gson.JsonObject json, String template) {
        //? if >=26 {
        String suffix = "_armor_trim_smithing_template";
        if (template.startsWith("#") || !template.endsWith(suffix)) {
            at.ioexception.mcaddon.registry.AddonEntities.unsupported(
                "recipe smithing trim pattern of " + template);
            return false;
        }
        json.addProperty("pattern", template.substring(0, template.length() - suffix.length()));
        //?}
        return true;
    }

    /**
     * The loot function that raises a drop's count with the killer's looting level.
     *
     * 1.21 made enchantments data-driven and renamed the function with them, from one that meant
     * looting alone to one naming whichever enchantment it counts.
     */
    public static void lootingCount(com.google.gson.JsonObject function,
        com.google.gson.JsonElement count, com.google.gson.JsonElement limit) {
        //? if >=1.21 {
        function.addProperty("function", "minecraft:enchanted_count_increase");
        function.addProperty("enchantment", "minecraft:looting");
        //?} else {
        /*function.addProperty("function", "minecraft:looting_enchant");
        *///?}
        function.add("count", count);
        if (limit != null) function.add("limit", limit);
    }

    /**
     * Which enchantments `minecraft:enchant_with_levels` may pick from.
     *
     * 1.21 replaced the treasure flag with a tag naming the choice, and the tag it ships holds the
     * treasure enchantments alongside the rest, so asking for treasure and not asking for it reach
     * the same list there.
     */
    public static void enchantmentChoice(com.google.gson.JsonObject function, boolean treasure) {
        //? if >=1.21 {
        function.addProperty("options", "#minecraft:on_random_loot");
        //?} else {
        /*function.addProperty("treasure", treasure);
        *///?}
    }

    /**
     * A chance that rises with the killer's looting level, as the running version states one.
     *
     * 1.21 replaced the looting-only condition with one naming its enchantment and stating the
     * enchanted chance as a line through the level rather than as a multiplier.
     */
    public static void lootingChance(com.google.gson.JsonObject condition, double chance,
        double multiplier) {
        //? if >=1.21 {
        condition.addProperty("condition", "minecraft:random_chance_with_enchanted_bonus");
        condition.addProperty("enchantment", "minecraft:looting");
        condition.addProperty("unenchanted_chance", chance);

        com.google.gson.JsonObject enchanted = new com.google.gson.JsonObject();
        enchanted.addProperty("type", "minecraft:linear");
        enchanted.addProperty("base", chance + multiplier);
        enchanted.addProperty("per_level_above_first", multiplier);
        condition.add("enchanted_chance", enchanted);
        //?} else {
        /*condition.addProperty("condition", "minecraft:random_chance_with_looting");
        condition.addProperty("chance", chance);
        condition.addProperty("looting_multiplier", multiplier);
        *///?}
    }

    /**
     * What an entity predicate calls one of its own fields.
     *
     * 26 moved the fields of a predicate into a registry and gave each the namespace that goes with
     * it, and renamed the one naming an entity type along the way.
     */
    public static String entityPredicateKey(String field) {
        //? if >=26 {
        return "minecraft:" + field;
        //?} else {
        /*return "entity_type".equals(field) ? "type" : field;
        *///?}
    }

    /**
     * Whether the game ships a structure tag for every map a Bedrock loot table can ask for.
     *
     * 1.20.1 has three of them: the buried treasure, the woodland mansion and the ocean monument.
     * 1.21 added one per village, the jungle temple, the swamp hut and the trial chambers.
     */
    public static boolean everyExplorerMapTag() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether the game ships the armour trim a pack names, material and pattern both.
     *
     * A trim pattern and a trim material are entries in registries a data pack writes into, and a
     * world holding such a pack is one the game calls experimental, so a pack may only name a trim
     * the game already has. 1.21 added the two trial chamber patterns and 26 added the resin
     * material; a pack naming one the running version has not is told rather than left with a table
     * the game refuses to parse.
     */
    public static boolean shipsTrim(String material, String pattern) {
        return TRIM_MATERIALS.contains(material) && TRIM_PATTERNS.contains(pattern)
            && !LATER_TRIMS.contains(material) && !LATER_TRIMS.contains(pattern);
    }

    private static final Set<String> TRIM_PATTERNS = Set.of("bolt", "coast", "dune", "eye", "flow",
        "host", "raiser", "rib", "sentry", "shaper", "silence", "snout", "spire", "tide", "vex",
        "ward", "wayfinder", "wild");

    private static final Set<String> TRIM_MATERIALS = Set.of("amethyst", "copper", "diamond",
        "emerald", "gold", "iron", "lapis", "netherite", "quartz", "redstone", "resin");

    /** The trims a later version added, which the running one has no registry entry for. */
    private static final Set<String> LATER_TRIMS = Set.of(
        //? if >=26 {
        //?} else if >=1.21 {
        /*"resin"
        *///?} else {
        /*"resin", "bolt", "flow"
        *///?}
    );

    /**
     * The loot function that gives a drop an armour trim.
     *
     * 1.20.5 replaced the item's whole NBT tag with components, so what was one nested compound
     * under `Trim` became the `minecraft:trim` component set beside every other one.
     */
    public static void armorTrim(com.google.gson.JsonObject function, String material,
        String pattern) {
        //? if >=1.21 {
        com.google.gson.JsonObject trim = new com.google.gson.JsonObject();
        trim.addProperty("material", "minecraft:" + material);
        trim.addProperty("pattern", "minecraft:" + pattern);

        com.google.gson.JsonObject components = new com.google.gson.JsonObject();
        components.add("minecraft:trim", trim);
        function.addProperty("function", "minecraft:set_components");
        function.add("components", components);
        //?} else {
        /*function.addProperty("function", "minecraft:set_nbt");
        function.addProperty("tag", "{Trim:{material:\"minecraft:" + material
            + "\",pattern:\"minecraft:" + pattern + "\"}}");
        *///?}
    }

    /**
     * The map a trade hands over, pointing at the nearest structure the named tag holds.
     *
     * Empty when the world has none to point at, which is the answer Java's own cartographer gives
     * to the same question. The search is the one `minecraft:exploration_map` makes, with the radius,
     * the zoom and the skip flag that function defaults to: it reads saved chunk data and the
     * generator's own placement rule rather than generating terrain to look in, and it passes over a
     * structure whose chunk already references it, which is how Java keeps an explorer map off
     * somewhere the player has been.
     */
    public static net.minecraft.world.item.ItemStack explorerMap(
        net.minecraft.world.item.ItemStack stack, Entity trader, String tag) {
        if (!(trader.level() instanceof ServerLevel level)) return net.minecraft.world.item.ItemStack.EMPTY;

        at.ioexception.mcaddon.convert.Names.Identifier name =
            at.ioexception.mcaddon.convert.Names.split(tag, "minecraft");
        net.minecraft.core.BlockPos found = level.findNearestMapStructure(
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.STRUCTURE,
                id(name.namespace(), name.path())),
            trader.blockPosition(), MAP_SEARCH_CHUNKS, true);
        if (found == null) return net.minecraft.world.item.ItemStack.EMPTY;

        net.minecraft.world.item.ItemStack map = net.minecraft.world.item.MapItem.create(level,
            found.getX(), found.getZ(), (byte) 2, true, true);
        net.minecraft.world.item.MapItem.renderBiomePreviewMap(level, map);
        //? if >=1.21 {
        net.minecraft.world.level.saveddata.maps.MapItemSavedData.addTargetDecoration(map, found, "+",
            net.minecraft.world.level.saveddata.maps.MapDecorationTypes.RED_X);
        //?} else {
        /*net.minecraft.world.level.saveddata.maps.MapItemSavedData.addTargetDecoration(map, found, "+",
            net.minecraft.world.level.saveddata.maps.MapDecoration.Type.RED_X);
        *///?}
        map.setCount(Math.max(1, stack.getCount()));
        return map;
    }

    /** How far out a map looks, in chunks, which is the radius the loot function defaults to. */
    private static final int MAP_SEARCH_CHUNKS = 50;

    /**
     * Makes the stack an enchanted book carrying one enchantment a villager may trade, and answers
     * the level it landed on, which is what prices the trade. Zero when nothing could be picked.
     *
     * 1.21 made enchantments data-driven and named the tradeable ones in a tag of their own, where
     * 1.20.1 asks each enchantment whether it may be traded and writes a book's enchantment through
     * the item class rather than onto the stack.
     */
    public static int enchantBook(net.minecraft.util.RandomSource random,
        net.minecraft.world.item.ItemStack stack, net.minecraft.core.RegistryAccess registries) {
        //? if >=26 {
        net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> registry =
            registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        //?} else {
        /*net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> registry =
            registries.registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        *///?}

        //? if >=1.21 {
        List<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> tradeable =
            new java.util.ArrayList<>();
        registry.getTagOrEmpty(net.minecraft.tags.EnchantmentTags.TRADEABLE).forEach(tradeable::add);
        if (tradeable.isEmpty()) return 0;

        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> chosen =
            tradeable.get(random.nextInt(tradeable.size()));
        int level = levelWithin(random, chosen.value());
        stack.enchant(chosen, level);
        //?} else {
        /*List<net.minecraft.world.item.enchantment.Enchantment> tradeable = registry.stream()
            .filter(net.minecraft.world.item.enchantment.Enchantment::isTradeable).toList();
        if (tradeable.isEmpty()) return 0;

        net.minecraft.world.item.enchantment.Enchantment chosen =
            tradeable.get(random.nextInt(tradeable.size()));
        int level = levelWithin(random, chosen);
        net.minecraft.world.item.EnchantedBookItem.addEnchantment(stack,
            new net.minecraft.world.item.enchantment.EnchantmentInstance(chosen, level));
        *///?}
        return level;
    }

    /**
     * A book carrying one named enchantment at a stated level, as a Bedrock loot function asks for.
     *
     * The stack a pack's entry names is an ordinary book, which Java has no way of writing a stored
     * enchantment onto, so the book handed back is the enchanted one. An enchantment the game does
     * not have leaves the stack as it was and is named.
     */
    public static net.minecraft.world.item.ItemStack enchantedBook(
        net.minecraft.world.item.ItemStack stack, net.minecraft.core.RegistryAccess registries,
        String named, int level) {
        //? if >=26 {
        net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> registry =
            registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        //?} else {
        /*net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> registry =
            registries.registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        *///?}

        Identifier id = Identifier.tryParse(named.indexOf(':') < 0 ? "minecraft:" + named : named);
        java.util.Optional<net.minecraft.world.item.enchantment.Enchantment> found =
            id == null ? java.util.Optional.empty() : registry.getOptional(id);
        if (found.isEmpty()) {
            at.ioexception.mcaddon.registry.AddonEntities.unsupported("loot enchantment " + named);
            return stack;
        }

        net.minecraft.world.item.ItemStack book = new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.ENCHANTED_BOOK, Math.max(1, stack.getCount()));
        //? if >=1.21 {
        book.enchant(registry.wrapAsHolder(found.get()), level);
        //?} else {
        /*net.minecraft.world.item.EnchantedBookItem.addEnchantment(book,
            new net.minecraft.world.item.enchantment.EnchantmentInstance(found.get(), level));
        *///?}
        return book;
    }

    /**
     * How many enchantments a book is holding for whoever reads it.
     *
     * 1.20.5 moved them out of the item's own NBT list and onto a component beside every other one.
     */
    public static int storedEnchantmentCount(net.minecraft.world.item.ItemStack stack) {
        //? if >=1.21 {
        net.minecraft.world.item.enchantment.ItemEnchantments stored =
            stack.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
        return stored == null ? 0 : stored.size();
        //?} else {
        /*return net.minecraft.world.item.EnchantedBookItem.getEnchantments(stack).size();
        *///?}
    }

    private static int levelWithin(net.minecraft.util.RandomSource random,
        net.minecraft.world.item.enchantment.Enchantment enchantment) {
        int lowest = enchantment.getMinLevel();
        return lowest + random.nextInt(Math.max(1, enchantment.getMaxLevel() - lowest + 1));
    }

    /**
     * Paints a stack one of the sixteen dye colours, which is what a trade's `random_dye` asks for.
     *
     * 1.20.5 moved the colour off the item's display tag and onto a component of its own, and 26
     * dropped the flag beside it that said the item had been dyed rather than born coloured.
     */
    public static net.minecraft.world.item.ItemStack dyed(net.minecraft.world.item.ItemStack stack,
        net.minecraft.world.item.DyeColor colour) {
        //? if >=26 {
        stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
            new net.minecraft.world.item.component.DyedItemColor(colour.getTextureDiffuseColor()));
        //?} else if >=1.21 {
        /*stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR,
            new net.minecraft.world.item.component.DyedItemColor(colour.getTextureDiffuseColor(),
                true));
        *///?} else {
        /*float[] channels = colour.getTextureDiffuseColors();
        stack.getOrCreateTagElement("display").putInt("color",
            ((int) (channels[0] * 255) << 16) | ((int) (channels[1] * 255) << 8)
                | (int) (channels[2] * 255));
        *///?}
        return stack;
    }

    /**
     * The entity a loot table is describing the drops of, or null when there is none.
     *
     * 26 moved a loot context's parameters into the general context keys and renamed the readers
     * with them.
     */
    public static Entity lootSubject(
        net.minecraft.world.level.storage.loot.LootContext context) {
        //? if >=26.3 {
        /*return context.getOptional(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY);
        *///?} elif >=26 {
        return context.getOptionalParameter(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY);
        //?} else {
        /*return context.getParamOrNull(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY);
        *///?}
    }

    /**
     * Whatever dealt the blow, or null when nothing did.
     *
     * 1.21 renamed the parameter from the killer to the attacker, because the same one answers a
     * blow that did not kill.
     */
    public static Entity lootKiller(
        net.minecraft.world.level.storage.loot.LootContext context) {
        //? if >=26.3 {
        /*return context.getOptional(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.ATTACKING_ENTITY);
        *///?} elif >=26 {
        return context.getOptionalParameter(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.ATTACKING_ENTITY);
        //?} else if >=1.21 {
        /*return context.getParamOrNull(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.ATTACKING_ENTITY);
        *///?} else {
        /*return context.getParamOrNull(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.KILLER_ENTITY);
        *///?}
    }

    private static net.minecraft.core.BlockPos lootOrigin(
        net.minecraft.world.level.storage.loot.LootContext context) {
        //? if >=26.3 {
        /*net.minecraft.world.phys.Vec3 origin = context.getOptional(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN);
        *///?} elif >=26 {
        net.minecraft.world.phys.Vec3 origin = context.getOptionalParameter(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN);
        //?} else {
        /*net.minecraft.world.phys.Vec3 origin = context.getParamOrNull(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN);
        *///?}
        return origin == null ? net.minecraft.core.BlockPos.ZERO
            : net.minecraft.core.BlockPos.containing(origin);
    }

    /**
     * How far into a place's own difficulty the drop is happening, between zero and one.
     *
     * Java works the same number out of the world difficulty, how long the world has been played
     * and the moon, and uses it for the chances a mob spawns armed and enchanted.
     */
    public static double regionalDifficulty(
        net.minecraft.world.level.storage.loot.LootContext context) {
        return context.getLevel().getCurrentDifficultyAt(lootOrigin(context)).getSpecialMultiplier();
    }

    /** The id of the biome the drop is happening in, which is what a tag is read of. */
    public static String lootBiome(
        net.minecraft.world.level.storage.loot.LootContext context) {
        return context.getLevel().getBiome(lootOrigin(context)).unwrapKey()
            .map(key -> idOf(key).toString()).orElse("");
    }

    /**
     * One item an advancement waits for a player to hold.
     *
     * 1.20.5 gave an item predicate a single field naming either an item or a tag, where before a
     * tag had a key of its own and an item was written as a list.
     */
    public static com.google.gson.JsonObject heldItem(String item) {
        com.google.gson.JsonObject predicate = new com.google.gson.JsonObject();
        //? if >=1.21 {
        predicate.addProperty("items", item);
        //?} else {
        /*if (item.startsWith("#")) {
            predicate.addProperty("tag", item.substring(1));
            return predicate;
        }
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        items.add(item);
        predicate.add("items", items);
        *///?}
        return predicate;
    }

    /** Whether the game has an ominous bottle for a loot table to set the amplifier of. */
    public static boolean hasOminousBottle() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Who is holding this entity on a lead, or null when nobody is.
     *
     * 1.21 moved being led off `Mob` and onto an interface, so a boat and a leash knot answer for
     * themselves where only a mob used to.
     */
    public static Entity leashHolder(Entity entity) {
        //? if >=1.21 {
        return entity instanceof net.minecraft.world.entity.Leashable led && led.isLeashed()
            ? led.getLeashHolder() : null;
        //?} else {
        /*return entity instanceof net.minecraft.world.entity.Mob led && led.isLeashed()
            ? led.getLeashHolder() : null;
        *///?}
    }

    /** False for an entity that cannot be led at all, which is the answer Bedrock gives. */
    public static boolean leashTo(Entity entity, Entity holder) {
        //? if >=1.21 {
        if (!(entity instanceof net.minecraft.world.entity.Leashable led)) return false;
        //?} else {
        /*if (!(entity instanceof net.minecraft.world.entity.Mob led)) return false;
        *///?}
        led.setLeashedTo(holder, true);
        return true;
    }

    /**
     * Whether a player's lead would go on the entity, which is the question Java asks before tying.
     *
     * 26 asks it of the holder as well, which is where taking a mob off a post is decided; 1.21 asks
     * the entity alone and 1.20.1 asks a mob with the player in hand.
     */
    public static boolean acceptsLead(Entity entity, net.minecraft.world.entity.player.Player player) {
        //? if >=26 {
        return entity instanceof net.minecraft.world.entity.Leashable led
            && led.canHaveALeashAttachedTo(player);
        //?} else if >=1.21 {
        /*return entity instanceof net.minecraft.world.entity.Leashable led
            && led.canHaveALeashAttachedToIt();
        *///?} else {
        /*return entity instanceof net.minecraft.world.entity.Mob led && led.canBeLeashed(player);
        *///?}
    }

    /** Drops the lead where the entity stands, the way shearing one off does. */
    public static boolean unleash(Entity entity) {
        //? if >=26 {
        if (!(entity instanceof net.minecraft.world.entity.Leashable led)) return false;
        led.dropLeash();
        //?} else if >=1.21 {
        /*if (!(entity instanceof net.minecraft.world.entity.Leashable led)) return false;
        led.dropLeash(true, true);
        *///?} else {
        /*if (!(entity instanceof net.minecraft.world.entity.Mob led)) return false;
        led.dropLeash(true, true);
        *///?}
        return true;
    }

    /**
     * A game rule, by the name Bedrock calls it, as text.
     *
     * Null for a name Java has nothing that means the same as, such as whether coordinates are
     * shown, which is what lets a pack be told the rule went unanswered instead of reading a
     * default that was never Java's.
     */
    public static String gameRule(net.minecraft.server.MinecraftServer server, String bedrock) {
        //? if >=26 {
        GameRules rules = server.getGameRules();
        if (bedrock.equals("doFireTick")) {
            return String.valueOf(rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) != 0);
        }

        GameRule<?> rule = rule(bedrock);
        return rule == null ? null : rules.getAsString(rule);
        //?} else {
        /*net.minecraft.world.level.GameRules rules = server.getGameRules();
        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue> flag =
            flagRule(bedrock);
        if (flag != null) return rules.getRule(flag).serialize();

        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue> count =
            countRule(bedrock);
        return count == null ? null : rules.getRule(count).serialize();
        *///?}
    }

    /**
     * The name Java's own `gamerule` command knows a rule by, or null when Java has no such rule.
     *
     * The two editions agree on which rules exist far more than on what to call them, and 26 renamed
     * Java's own set again, so the name has to be asked for rather than written down.
     */
    public static String gameRuleName(String bedrock) {
        //? if >=26 {
        if (bedrock.equals("doFireTick")) return GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.id();
        if (bedrock.equals("playerWaypoints")) return GameRules.LOCATOR_BAR.id();

        GameRule<?> rule = rule(bedrock);
        return rule == null ? null : rule.id();
        //?} else {
        /*net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue> flag =
            flagRule(bedrock);
        if (flag != null) return flag.getId();

        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue> count =
            countRule(bedrock);
        return count == null ? null : count.getId();
        *///?}
    }

    /**
     * The radius Java's own fire rule takes for a Bedrock fire flag, or null where it holds a flag.
     *
     * 26 replaced the rule that switched fire spreading with one naming how far from a player fire
     * spreads at all, so the flag becomes the distance the game ships or none of it.
     */
    public static Integer fireSpreadRadius(boolean spreading) {
        //? if >=26 {
        return spreading ? GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.defaultValue() : 0;
        //?} else {
        /*return null;
        *///?}
    }

    /** The Java slot a saddle sits in, which 26 moved off the horse and onto every entity. */
    public static String saddleSlot() {
        //? if >=26 {
        return "saddle";
        //?} else {
        /*return "horse.saddle";
        *///?}
    }

    /** The Java slot body armour sits in, or null on a version with no slot for it. */
    public static String bodyArmourSlot() {
        //? if >=1.21 {
        return "armor.body";
        //?} else {
        /*return null;
        *///?}
    }

    /**
     * The Java slot a horse's armour and a llama's carpet sit in. Before 1.20.5 that is the horse's
     * own slot rather than one every entity has.
     */
    public static String horseArmourSlot() {
        //? if >=1.21 {
        return "armor.body";
        //?} else {
        /*return "horse.armor";
        *///?}
    }

    /** What a horse wears as armour, which sat in its chest slot before 1.20.5. */
    public static net.minecraft.world.item.ItemStack horseArmourOf(LivingEntity horse) {
        //? if >=1.21 {
        return horse.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.BODY);
        //?} else {
        /*return horse.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        *///?}
    }

    /**
     * `execute if items`, which asks whether a slot holds an item, or false on 1.20.1, which predates
     * the clause.
     */
    public static boolean testsSlots() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * The rule that decides whether pillager patrols appear, by the name this version calls it.
     *
     * Bedrock has no game rule for either of these: it switches them through `mobevent`, which is
     * why they are asked for by what they do rather than through the Bedrock name table.
     */
    public static String patrolSpawningRule() {
        //? if >=26 {
        return GameRules.SPAWN_PATROLS.id();
        //?} else {
        /*return net.minecraft.world.level.GameRules.RULE_DO_PATROL_SPAWNING.getId();
        *///?}
    }

    /**
     * The tag that lets a snow layer lie on a block whatever the shape of its top face.
     *
     * Without it a snow layer only settles where the face below it is full, which is the rule
     * `minecraft:precipitation_interactions` overrides when it asks for snow to gather.
     */
    public static String snowSupportTag() {
        //? if >=26 {
        return "minecraft:support_override_snow_layer";
        //?} else {
        /*return "minecraft:snow_layer_can_survive_on";
        *///?}
    }

    /** The rule that decides whether a wandering trader appears, by the name this version calls it. */
    public static String traderSpawningRule() {
        //? if >=26 {
        return GameRules.SPAWN_WANDERING_TRADERS.id();
        //?} else {
        /*return net.minecraft.world.level.GameRules.RULE_DO_TRADER_SPAWNING.getId();
        *///?}
    }

    /**
     * Writes a game rule, answering whether Java holds one that means the same.
     *
     * Text that does not read as the rule's own type leaves the rule where it was, the way the
     * command does with an argument it cannot parse.
     */
    public static boolean setGameRule(net.minecraft.server.MinecraftServer server, String bedrock,
        String value) {
        //? if >=26 {
        if (bedrock.equals("doFireTick")) {
            server.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,
                Boolean.parseBoolean(value)
                    ? GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.defaultValue() : 0,
                server);
            return true;
        }

        GameRule<?> rule = rule(bedrock);
        if (rule == null) return false;
        assign(server, rule, value);
        return true;
        //?} else {
        /*net.minecraft.world.level.GameRules rules = server.getGameRules();
        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue> flag =
            flagRule(bedrock);
        if (flag != null) {
            rules.getRule(flag).set(Boolean.parseBoolean(value), server);
            return true;
        }

        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue> count =
            countRule(bedrock);
        if (count == null) return false;

        Integer parsed = whole(value);
        if (parsed != null) rules.getRule(count).set(parsed, server);
        return true;
        *///?}
    }

    //? if >=26 {
    /**
     * The Java rule a Bedrock rule name means.
     *
     * Bedrock's fire rule is absent because 26 turned it into a radius, which the two callers
     * handle themselves. The rest are named apart from their Bedrock spelling often enough that the
     * table is written out rather than derived.
     */
    private static GameRule<?> rule(String bedrock) {
        return switch (bedrock) {
            case "commandBlockOutput" -> GameRules.COMMAND_BLOCK_OUTPUT;
            case "commandBlocksEnabled" -> GameRules.COMMAND_BLOCKS_WORK;
            case "doDayLightCycle" -> GameRules.ADVANCE_TIME;
            case "doEntityDrops" -> GameRules.ENTITY_DROPS;
            case "doImmediateRespawn" -> GameRules.IMMEDIATE_RESPAWN;
            case "doInsomnia" -> GameRules.SPAWN_PHANTOMS;
            case "doLimitedCrafting" -> GameRules.LIMITED_CRAFTING;
            case "doMobLoot" -> GameRules.MOB_DROPS;
            case "doMobSpawning" -> GameRules.SPAWN_MOBS;
            case "doTileDrops" -> GameRules.BLOCK_DROPS;
            case "doWeatherCycle" -> GameRules.ADVANCE_WEATHER;
            case "drowningDamage" -> GameRules.DROWNING_DAMAGE;
            case "fallDamage" -> GameRules.FALL_DAMAGE;
            case "fireDamage" -> GameRules.FIRE_DAMAGE;
            case "freezeDamage" -> GameRules.FREEZE_DAMAGE;
            case "keepInventory" -> GameRules.KEEP_INVENTORY;
            case "maxCommandChainLength" -> GameRules.MAX_COMMAND_SEQUENCE_LENGTH;
            case "mobGriefing" -> GameRules.MOB_GRIEFING;
            case "naturalRegeneration" -> GameRules.NATURAL_HEALTH_REGENERATION;
            case "playersSleepingPercentage" -> GameRules.PLAYERS_SLEEPING_PERCENTAGE;
            case "projectilesCanBreakBlocks" -> GameRules.PROJECTILES_CAN_BREAK_BLOCKS;
            case "pvp" -> GameRules.PVP;
            case "randomTickSpeed" -> GameRules.RANDOM_TICK_SPEED;
            case "sendCommandFeedback" -> GameRules.SEND_COMMAND_FEEDBACK;
            case "showDeathMessages" -> GameRules.SHOW_DEATH_MESSAGES;
            case "spawnRadius" -> GameRules.RESPAWN_RADIUS;
            case "tntExplodes" -> GameRules.TNT_EXPLODES;
            case "tntExplosionDropDecay" -> GameRules.TNT_EXPLOSION_DROP_DECAY;
            default -> null;
        };
    }

    /** Reads text as the rule's own value type, leaving the rule alone when it does not read. */
    private static <T> void assign(net.minecraft.server.MinecraftServer server, GameRule<T> rule,
        String value) {
        rule.deserialize(value).result()
            .ifPresent(parsed -> server.getGameRules().set(rule, parsed, server));
    }
    //?}

    //? if <26 {
    /*// The Java rule a Bedrock rule name means, among the rules holding a flag.
    private static net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue>
        flagRule(String bedrock) {
        return switch (bedrock) {
            case "commandBlockOutput" -> net.minecraft.world.level.GameRules.RULE_COMMANDBLOCKOUTPUT;
            case "doDayLightCycle" -> net.minecraft.world.level.GameRules.RULE_DAYLIGHT;
            case "doEntityDrops" -> net.minecraft.world.level.GameRules.RULE_DOENTITYDROPS;
            case "doFireTick" -> net.minecraft.world.level.GameRules.RULE_DOFIRETICK;
            case "doImmediateRespawn" -> net.minecraft.world.level.GameRules.RULE_DO_IMMEDIATE_RESPAWN;
            case "doInsomnia" -> net.minecraft.world.level.GameRules.RULE_DOINSOMNIA;
            case "doLimitedCrafting" -> net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING;
            case "doMobLoot" -> net.minecraft.world.level.GameRules.RULE_DOMOBLOOT;
            case "doMobSpawning" -> net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING;
            case "doTileDrops" -> net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS;
            case "doWeatherCycle" -> net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE;
            case "drowningDamage" -> net.minecraft.world.level.GameRules.RULE_DROWNING_DAMAGE;
            case "fallDamage" -> net.minecraft.world.level.GameRules.RULE_FALL_DAMAGE;
            case "fireDamage" -> net.minecraft.world.level.GameRules.RULE_FIRE_DAMAGE;
            case "freezeDamage" -> net.minecraft.world.level.GameRules.RULE_FREEZE_DAMAGE;
            case "keepInventory" -> net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY;
            case "mobGriefing" -> net.minecraft.world.level.GameRules.RULE_MOBGRIEFING;
            case "naturalRegeneration" -> net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION;
            case "sendCommandFeedback" -> net.minecraft.world.level.GameRules.RULE_SENDCOMMANDFEEDBACK;
            case "showDeathMessages" -> net.minecraft.world.level.GameRules.RULE_SHOWDEATHMESSAGES;
            case "tntExplosionDropDecay" -> net.minecraft.world.level.GameRules.RULE_TNT_EXPLOSION_DROP_DECAY;
            default -> null;
        };
    }

    // The Java rule a Bedrock rule name means, among the rules holding a count.
    private static net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue>
        countRule(String bedrock) {
        return switch (bedrock) {
            case "maxCommandChainLength" -> net.minecraft.world.level.GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH;
            case "playersSleepingPercentage" -> net.minecraft.world.level.GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE;
            case "randomTickSpeed" -> net.minecraft.world.level.GameRules.RULE_RANDOMTICKING;
            case "spawnRadius" -> net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS;
            default -> null;
        };
    }

    // A count a pack wrote, or null when the text does not read as one.
    private static Integer whole(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }
    *///?}

    /**
     * One participant's score, or null when they have none on that objective.
     *
     * A score was keyed by a plain name until 1.20.3 and by a score holder after it, so reading one
     * is the only part of the scoreboard that differs between the supported versions.
     */
    public static Integer score(net.minecraft.world.scores.Scoreboard scoreboard,
        net.minecraft.world.scores.Objective objective, String participant) {
        //? if >=1.21 {
        net.minecraft.world.scores.ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(
            net.minecraft.world.scores.ScoreHolder.forNameOnly(participant), objective);
        return info == null ? null : info.value();
        //?} else {
        /*return scoreboard.getPlayerScores(participant).containsKey(objective)
            ? scoreboard.getOrCreatePlayerScore(participant, objective).getScore() : null;
        *///?}
    }

    /** Everyone the scoreboard is tracking, by name. */
    public static String[] scoreboardParticipants(net.minecraft.world.scores.Scoreboard scoreboard) {
        //? if >=1.21 {
        return scoreboard.getTrackedPlayers().stream()
            .map(net.minecraft.world.scores.ScoreHolder::getScoreboardName)
            .toArray(String[]::new);
        //?} else {
        /*return scoreboard.getTrackedPlayers().toArray(String[]::new);
        *///?}
    }

    /**
     * Whether a command source is allowed to run something at a given permission level.
     *
     * Java counted permission in integers until 26 replaced them with a set of named permissions, so
     * Bedrock's ladder is mapped onto whichever the version has: `GameDirectors` and above need more
     * than an ordinary player, and `Any` needs nothing.
     */
    public static boolean allowedAt(CommandSourceStack source, int bedrockLevel) {
        if (bedrockLevel <= 0) return true;
        //? if >=26 {
        net.minecraft.server.permissions.PermissionLevel level = switch (bedrockLevel) {
            case 1 -> net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS;
            case 2 -> net.minecraft.server.permissions.PermissionLevel.ADMINS;
            default -> net.minecraft.server.permissions.PermissionLevel.OWNERS;
        };
        return source.permissions().hasPermission(
            new net.minecraft.server.permissions.Permission.HasCommandLevel(level));
        //?} else {
        /*return source.hasPermission(Math.min(4, bedrockLevel + 1));
        *///?}
    }

    /**
     * Reads a template out of Java's structure NBT, its block names looked up in the registry.
     *
     * A registry is its own lookup from 1.21.2; before that it hands one out.
     */
    public static void loadTemplate(
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template,
        net.minecraft.nbt.CompoundTag tag) {
        //? if >=1.21.2 {
        template.load(BuiltInRegistries.BLOCK, tag);
        //?} else {
        /*template.load(BuiltInRegistries.BLOCK.asLookup(), tag);
        *///?}
    }

    /** Where a structure the world saved under this id is written. */
    public static java.nio.file.Path savedStructureFile(
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager, Identifier id) {
        //? if >=26 {
        return manager.worldTemplates().createAndValidatePathToStructure(id, manager.WORLD_STRUCTURE_LISTER);
        //?} else if >=1.21 {
        /*return manager.createAndValidatePathToGeneratedStructure(id, ".nbt");
        *///?} else {
        /*return manager.getPathToGeneratedStructure(id, ".nbt");
        *///?}
    }

    /** Whether a block a template places into water keeps the water, which 1.21 made a setting. */
    public static void keepLiquids(
        net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings, boolean keep) {
        //? if >=1.21 {
        settings.setLiquidSettings(keep
            ? net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.APPLY_WATERLOGGING
            : net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.IGNORE_WATERLOGGING);
        //?} else {
        /*settings.setKeepLiquids(keep);
        *///?}
    }

    /**
     * Copies a region of the world into a structure template, leaving out structure voids the way a
     * structure block does in either edition.
     */
    public static void fillFromWorld(
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template,
        ServerLevel level, net.minecraft.core.BlockPos corner, net.minecraft.core.Vec3i size, boolean entities) {
        //? if >=1.21.2 {
        template.fillFromWorld(level, corner, size, entities,
            List.of(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID));
        //?} else {
        /*template.fillFromWorld(level, corner, size, entities,
            net.minecraft.world.level.block.Blocks.STRUCTURE_VOID);
        *///?}
    }

    /**
     * The render type a Bedrock material asks for.
     *
     * Bedrock's entity materials disable culling by default and use an alpha test, which is Java's
     * cutout-no-cull; a material that asks for blending gets a translucent type, and one that glows
     * gets the emissive variant. 26 swapped two of the names: its `entityCutout` is the one that
     * does not cull, and `entityCutoutNoCull` is gone.
     */
    public static RenderType renderType(Identifier texture, boolean blend, boolean cull,
        boolean emissive) {
        if (emissive) return RenderTypes.entityTranslucentEmissive(texture);
        if (blend) return RenderTypes.entityTranslucent(texture);
        return cull ? RenderTypes.entityCutoutCull(texture) : RenderTypes.entityCutout(texture);
    }

    /**
     * A villager's profession by the name its registry holds it under, `none` for one without a job.
     * 1.21.5 made the profession and the type registry holders.
     */
    public static String villagerProfession(VillagerData data) {
        //? if >=1.21.5 {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.profession().value()).getPath();
        //?} else {
        /*return BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()).getPath();
        *///?}
    }

    /** A villager's level, from one for a novice to five for a master. */
    public static int villagerLevel(VillagerData data) {
        //? if >=1.21.5 {
        return data.level();
        //?} else {
        /*return data.getLevel();
        *///?}
    }

    /** The biome a villager comes from, by the name its type is registered under. */
    public static String villagerType(VillagerData data) {
        //? if >=1.21.5 {
        return BuiltInRegistries.VILLAGER_TYPE.getKey(data.type().value()).getPath();
        //?} else {
        /*return BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType()).getPath();
        *///?}
    }

    /**
     * Sends a player on to another server, which is what Bedrock's `/transfer` does.
     *
     * Java gained the packet in 1.20.5 and 1.20.1 has no form of it, so there the player stays and
     * the caller says so.
     */
    public static boolean transfer(ServerPlayer player, String host, int port) {
        //? if >=1.21 {
        player.connection.send(
            new net.minecraft.network.protocol.common.ClientboundTransferPacket(host, port));
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /** Turns a pose stack. 26.3 gave the turn a name of its own. */
    public static void rotate(com.mojang.blaze3d.vertex.PoseStack poseStack, org.joml.Quaternionf turn) {
        //? if >=26.3 {
        /*poseStack.rotate(turn);
        *///?} else {
        poseStack.mulPose(turn);
        //?}
    }

    /** Multiplies a whole transform onto a pose stack. 1.21 renamed the matrix form of the call. */
    public static void mulPose(com.mojang.blaze3d.vertex.PoseStack poseStack, org.joml.Matrix4f matrix) {
        //? if >=1.21 {
        poseStack.mulPose(matrix);
        //?} else {
        /*poseStack.mulPoseMatrix(matrix);
        *///?}
    }

    /**
     * How much of a stack's use cooldown is still to run, between nothing and one.
     *
     * 1.21.2 moved the question from the item to the stack, because a cooldown became something a
     * stack's own components can name a group for.
     */
    public static float cooldownLeft(net.minecraft.world.entity.player.Player player,
        net.minecraft.world.item.ItemStack stack) {
        //? if >=1.21.2 {
        return player.getCooldowns().getCooldownPercent(stack, 0f);
        //?} else {
        /*return player.getCooldowns().getCooldownPercent(stack.getItem(), 0f);
        *///?}
    }

    /**
     * An entity's score under one objective, or zero while it has none.
     *
     * 1.21 gave a score holder an identity of its own, where before a score was filed under the name
     * the entity goes by on the scoreboard.
     */
    public static double score(Entity entity, String objective) {
        net.minecraft.world.scores.Scoreboard scoreboard = entity.level().getScoreboard();
        net.minecraft.world.scores.Objective named = scoreboard.getObjective(objective);
        if (named == null) return 0;

        //? if >=1.21 {
        net.minecraft.world.scores.ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(entity, named);
        return score == null ? 0 : score.value();
        //?} else {
        /*String holder = entity.getScoreboardName();
        return scoreboard.hasPlayerScore(holder, named)
            ? scoreboard.getOrCreatePlayerScore(holder, named).getScore() : 0;
        *///?}
    }

    /**
     * Paints a pack's dyeable items in the colour they carry.
     *
     * From 1.21.4 the colour is a tint named in the item's own definition file, which the converter
     * writes, so nothing is registered here. 1.21 reads tints from a colour provider instead, and
     * 1.20.1 has no dyed colour on a stack at all.
     */
    /**
     * What a layer drawn over the heads-up display or a screen may do: fill a rectangle, write a line,
     * draw a mob and stretch a whole texture over a rectangle.
     */
    public interface Painter {
        int width();

        int height();

        void fill(int left, int top, int right, int bottom, int argb);

        /** Writes one line with a shadow under it, in the game's own font. */
        void text(String line, int left, int top, int argb);

        /**
         * Draws a mob standing in a box the way the inventory draws the player, turned to look at a
         * point `lookX` and `lookY` from the middle of the box.
         */
        void entity(LivingEntity entity, int left, int top, int right, int bottom, int size, float lookX,
            float lookY);

        /** Stretches the whole of a texture over a rectangle. */
        void image(Identifier texture, int left, int top, int width, int height);
    }

    /**
     * Where a point in the world lands on the heads-up display, in its own scaled units, or null for a
     * point behind the camera.
     *
     * Before 26 the camera keeps no field of view of its own, so the player's chosen one stands in
     * for it there, which leaves out what sprinting or a spyglass does to it.
     */
    public static float[] onScreen(net.minecraft.world.phys.Vec3 point, int width, int height) {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        //? if >=26.2 {
        net.minecraft.client.Camera camera = client.gameRenderer.mainCamera();
        org.joml.Vector3fc forward = camera.forwardVector();
        org.joml.Vector3fc up = camera.upVector();
        org.joml.Vector3fc left = camera.leftVector();
        float fov = camera.getFov();
        //?} elif >=26 {
        /*net.minecraft.client.Camera camera = client.gameRenderer.getMainCamera();
        org.joml.Vector3fc forward = camera.forwardVector();
        org.joml.Vector3fc up = camera.upVector();
        org.joml.Vector3fc left = camera.leftVector();
        float fov = camera.getFov();
        *///?} else {
        /*net.minecraft.client.Camera camera = client.gameRenderer.getMainCamera();
        org.joml.Vector3fc forward = camera.getLookVector();
        org.joml.Vector3fc up = camera.getUpVector();
        org.joml.Vector3fc left = camera.getLeftVector();
        float fov = client.options.fov().get();
        *///?}
        net.minecraft.world.phys.Vec3 offset = point.subtract(cameraPosition());
        double depth = offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z();
        if (depth < 0.05) return null;

        double scale = height / 2.0 / Math.tan(Math.toRadians(fov) / 2) / depth;
        return new float[] {
            (float) (width / 2.0 - (offset.x * left.x() + offset.y * left.y() + offset.z * left.z()) * scale),
            (float) (height / 2.0 - (offset.x * up.x() + offset.y * up.y() + offset.z * up.z()) * scale)};
    }

    /**
     * How far the player reaches an entity or a block. 1.20.1 holds both on the game mode, as the
     * game's own pick does: three blocks to an entity, or six in creative, and the pick range to a
     * block.
     */
    public static double interactionRange(net.minecraft.world.entity.player.Player player, boolean entity) {
        //? if >=1.21 {
        return entity ? player.entityInteractionRange() : player.blockInteractionRange();
        //?} else {
        /*net.minecraft.client.multiplayer.MultiPlayerGameMode mode =
            net.minecraft.client.Minecraft.getInstance().gameMode;
        return entity ? (mode.hasFarPickRange() ? 6 : 3) : mode.getPickRange();
        *///?}
    }

    /** Draws a layer of this mod's own over everything the heads-up display draws, every frame. */
    public static void drawOverHud(Identifier id, java.util.function.Consumer<Painter> layer) {
        //? if >=26 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(id, (graphics, delta) ->
            layer.accept(painter(graphics)));
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((graphics, delta) ->
            layer.accept(painter(graphics)));
        *///?}
    }

    /** Something a screen draws among its widgets through a painter. */
    public static net.minecraft.client.gui.components.Renderable renderable(
        java.util.function.Consumer<Painter> layer) {
        return (graphics, mouseX, mouseY, delta) -> layer.accept(painter(graphics));
    }

    //? if >=26 {
    private static Painter painter(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        return new Painter() {
            @Override
            public int width() {
                return graphics.guiWidth();
            }

            @Override
            public int height() {
                return graphics.guiHeight();
            }

            @Override
            public void fill(int left, int top, int right, int bottom, int argb) {
                graphics.fill(left, top, right, bottom, argb);
            }

            @Override
            public void text(String line, int left, int top, int argb) {
                graphics.text(net.minecraft.client.Minecraft.getInstance().font, line, left, top, argb, true);
            }

            @Override
            public void entity(LivingEntity entity, int left, int top, int right, int bottom, int size,
                float lookX, float lookY) {
                net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(
                    graphics, left, top, right, bottom, size, 0.0625F,
                    (left + right) / 2F + lookX, (top + bottom) / 2F + lookY, entity);
            }

            @Override
            public void image(Identifier texture, int left, int top, int width, int height) {
                graphics.blit(texture, left, top, left + width, top + height, 0, 1, 0, 1);
            }
        };
    }
    //?} else {
    /*private static Painter painter(net.minecraft.client.gui.GuiGraphics graphics) {
        return new Painter() {
            @Override
            public int width() {
                return graphics.guiWidth();
            }

            @Override
            public int height() {
                return graphics.guiHeight();
            }

            @Override
            public void fill(int left, int top, int right, int bottom, int argb) {
                graphics.fill(left, top, right, bottom, argb);
            }

            @Override
            public void text(String line, int left, int top, int argb) {
                graphics.drawString(net.minecraft.client.Minecraft.getInstance().font, line, left, top, argb, true);
            }

            @Override
            public void entity(LivingEntity entity, int left, int top, int right, int bottom, int size,
                float lookX, float lookY) {
                //? if >=1.21 {
                net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, left, top, right, bottom, size, 0.0625F,
                    (left + right) / 2F + lookX, (top + bottom) / 2F + lookY, entity);
                //?} else {
                /^net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, (left + right) / 2, bottom, size, -lookX, -lookY, entity);
                ^///?}
            }

            @Override
            public void image(Identifier texture, int left, int top, int width, int height) {
                graphics.blit(texture, left, top, 0, 0, width, height, width, height);
            }
        };
    }
    *///?}

    public static void registerDyeTint(java.util.List<Item> items) {
        //? if >=1.21 && <26 {
        /*if (items.isEmpty()) return;

        // 0xA06540 is the leather brown a stack carrying no colour of its own falls back to.
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
            (stack, layer) -> layer == 0
                ? net.minecraft.world.item.component.DyedItemColor.getOrDefault(stack, 0xA06540)
                : -1,
            items.toArray(new Item[0]));
        *///?}
    }

    /**
     * Tells the client how to make one kind of particle.
     *
     * Fabric renamed both the registry and the sprite set it hands over in 26, and the sprite set is
     * the game's own interface in both, so only the name of the registry differs here.
     */
    public static void registerParticle(net.minecraft.core.particles.SimpleParticleType type,
        java.util.function.Function<net.minecraft.client.particle.SpriteSet,
            net.minecraft.client.particle.ParticleProvider<net.minecraft.core.particles.SimpleParticleType>>
            provider) {
        //? if >=26 {
        net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance()
            .register(type, provider::apply);
        //?} else {
        /*net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry.getInstance()
            .register(type, provider::apply);
        *///?}
    }

    /** The registry id of an active effect. What the instance carries became a holder in 1.21. */
    public static String effectId(net.minecraft.world.effect.MobEffectInstance active) {
        //? if >=1.21 {
        return net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
            .getKey(active.getEffect().value()).toString();
        //?} else {
        /*return net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
            .getKey(active.getEffect()).toString();
        *///?}
    }

    /**
     * A goal that follows whoever is holding what the pack named.
     *
     * Bedrock names the items in the component and Java asks a different question in each version:
     * an ingredient until 1.21.2 and a test on the stack after. A pack naming nothing is tempted by
     * anything edible, which is what such a list amounts to.
     */
    public static net.minecraft.world.entity.ai.goal.Goal temptGoal(
        net.minecraft.world.entity.PathfinderMob mob, double speed, Set<String> items) {
        //? if >=1.21.2 {
        return new net.minecraft.world.entity.ai.goal.TemptGoal(mob, speed,
            stack -> items.isEmpty()
                ? stack.has(net.minecraft.core.component.DataComponents.FOOD)
                : tempts(items, stack), false);
        //?} else {
        /*return new net.minecraft.world.entity.ai.goal.TemptGoal(mob, speed,
            ingredient(items), false);
        *///?}
    }

    /** Whether a stack is one of the items a pack's goal named, in either edition's spelling. */
    public static boolean tempts(Set<String> items, net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.Item held = stack.getItem();
        return items.stream().anyMatch(name -> at.ioexception.mcaddon.registry.AddonItems.named(name) == held);
    }

    //? if <1.21.2 {
    /*private static net.minecraft.world.item.crafting.Ingredient ingredient(Set<String> items) {
        net.minecraft.world.item.ItemStack[] stacks = items.stream()
            .map(at.ioexception.mcaddon.registry.AddonItems::named)
            .filter(java.util.Objects::nonNull)
            .map(net.minecraft.world.item.ItemStack::new)
            .toArray(net.minecraft.world.item.ItemStack[]::new);
        return stacks.length == 0
            ? net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.world.item.Items.WHEAT)
            : net.minecraft.world.item.crafting.Ingredient.of(stacks);
    }
    *///?}

    /**
     * Ties a mob to a place it will not wander far from.
     *
     * This is what Bedrock's `minecraft:home` and `minecraft:dweller` describe, and Java has had it
     * all along under a name that changed in 26.
     */
    public static void setHome(net.minecraft.world.entity.Mob mob, net.minecraft.core.BlockPos at,
        int radius) {
        //? if >=26 {
        mob.setHomeTo(at, radius);
        //?} else {
        /*mob.restrictTo(at, radius);
        *///?}
    }

    /** How high the mob steps, which 1.21 took off the entity and made an attribute of. */
    public static float stepHeight(net.minecraft.world.entity.Mob mob) {
        //? if >=1.21 {
        return (float) mob.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        //?} else {
        /*return mob.maxUpStep();
        *///?}
    }

    /** Sets how high the mob steps, on whichever of the two the version holds it. */
    public static void setStepHeight(net.minecraft.world.entity.Mob mob, float height) {
        //? if >=1.21 {
        net.minecraft.world.entity.ai.attributes.AttributeInstance step = mob.getAttribute(
            net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (step != null) step.setBaseValue(height);
        //?} else {
        /*mob.setMaxUpStep(height);
        *///?}
    }

    /** What water costs the mob's pathfinder. The enum naming a kind of ground was renamed in 1.21. */
    public static float waterMalus(net.minecraft.world.entity.Mob mob) {
        //? if >=1.21 {
        return mob.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER);
        //?} else {
        /*return mob.getPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.WATER);
        *///?}
    }

    /** Prices water at what Java charges every mob for it, or at nothing. */
    public static void priceWater(net.minecraft.world.entity.Mob mob, boolean avoided) {
        //? if >=1.21 {
        price(mob, avoided, net.minecraft.world.level.pathfinder.PathType.WATER);
        //?} else {
        /*price(mob, avoided, net.minecraft.world.level.pathfinder.BlockPathTypes.WATER);
        *///?}
    }

    /**
     * Prices a block that hurts and the ground beside one at what Java charges for them, or at
     * nothing. 26 renamed the four kinds of ground this covers.
     */
    public static void priceDamagingBlocks(net.minecraft.world.entity.Mob mob, boolean avoided) {
        //? if >=26 {
        price(mob, avoided, net.minecraft.world.level.pathfinder.PathType.FIRE,
            net.minecraft.world.level.pathfinder.PathType.FIRE_IN_NEIGHBOR,
            net.minecraft.world.level.pathfinder.PathType.DAMAGING,
            net.minecraft.world.level.pathfinder.PathType.DAMAGING_IN_NEIGHBOR);
        //?} else if >=1.21 {
        /*price(mob, avoided, net.minecraft.world.level.pathfinder.PathType.DAMAGE_FIRE,
            net.minecraft.world.level.pathfinder.PathType.DANGER_FIRE,
            net.minecraft.world.level.pathfinder.PathType.DAMAGE_OTHER,
            net.minecraft.world.level.pathfinder.PathType.DANGER_OTHER);
        *///?} else {
        /*price(mob, avoided, net.minecraft.world.level.pathfinder.BlockPathTypes.DAMAGE_FIRE,
            net.minecraft.world.level.pathfinder.BlockPathTypes.DANGER_FIRE,
            net.minecraft.world.level.pathfinder.BlockPathTypes.DAMAGE_OTHER,
            net.minecraft.world.level.pathfinder.BlockPathTypes.DANGER_OTHER);
        *///?}
    }

    /** Prices lava at what Java charges for it, which is refusing it, or at nothing. */
    public static void priceLava(net.minecraft.world.entity.Mob mob, boolean avoided) {
        //? if >=1.21 {
        price(mob, avoided, net.minecraft.world.level.pathfinder.PathType.LAVA);
        //?} else {
        /*price(mob, avoided, net.minecraft.world.level.pathfinder.BlockPathTypes.LAVA);
        *///?}
    }

    //? if >=1.21 {
    private static void price(net.minecraft.world.entity.Mob mob, boolean avoided,
        net.minecraft.world.level.pathfinder.PathType... kinds) {
        for (net.minecraft.world.level.pathfinder.PathType kind : kinds) {
            mob.setPathfindingMalus(kind, avoided ? kind.getMalus() : 0f);
        }
    }
    //?} else {
    /*private static void price(net.minecraft.world.entity.Mob mob, boolean avoided,
        net.minecraft.world.level.pathfinder.BlockPathTypes... kinds) {
        for (net.minecraft.world.level.pathfinder.BlockPathTypes kind : kinds) {
            mob.setPathfindingMalus(kind, avoided ? kind.getMalus() : 0f);
        }
    }
    *///?}

    /** What a block that hurts costs the mob's pathfinder, a cactus or a berry bush among them. */
    public static float damagingMalus(net.minecraft.world.entity.Mob mob) {
        //? if >=26 {
        return mob.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGING);
        //?} else if >=1.21 {
        /*return mob.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGE_OTHER);
        *///?} else {
        /*return mob.getPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.DAMAGE_OTHER);
        *///?}
    }

    /** What lava costs the mob's pathfinder, which is below nothing while it may not go there. */
    public static float lavaMalus(net.minecraft.world.entity.Mob mob) {
        //? if >=1.21 {
        return mob.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LAVA);
        //?} else {
        /*return mob.getPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.LAVA);
        *///?}
    }

    /** Whether the mob is already held to somewhere, so a place it was given is not moved. */
    public static boolean hasHome(net.minecraft.world.entity.Mob mob) {
        //? if >=26 {
        return mob.hasHome();
        //?} else {
        /*return mob.hasRestriction();
        *///?}
    }

    /** Whether a place lies where the mob is kept, which is anywhere for a mob kept nowhere. */
    public static boolean withinHome(net.minecraft.world.entity.PathfinderMob mob, net.minecraft.core.BlockPos at) {
        //? if >=26 {
        return mob.isWithinHome(at);
        //?} else {
        /*return mob.isWithinRestriction(at);
        *///?}
    }

    /** Lets the mob wander again, which is what taking the component away amounts to. */
    public static void clearHome(net.minecraft.world.entity.Mob mob) {
        //? if >=26 {
        mob.clearHome();
        //?} else {
        /*mob.clearRestriction();
        *///?}
    }

    /**
     * Multiplies how fast a mob moves, or puts it back when the multiplier is one.
     *
     * A modifier rather than a new base value, so whatever else changed the speed keeps its say.
     * 1.21 took the string name off a modifier and gave it a registry id instead.
     */
    public static void scaleSpeed(net.minecraft.world.entity.Mob mob, double multiplier) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance speed =
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed == null) return;

        //? if >=1.21 {
        Identifier id = id("mcaddon", "boost");
        speed.removeModifier(id);
        if (multiplier != 1) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                id, multiplier - 1,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        //?} else {
        /*java.util.UUID id = java.util.UUID.fromString("7f3d1c90-2d9a-4a1e-9a1f-0b5f2c8d6e41");
        speed.removeModifier(id);
        if (multiplier != 1) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                id, "mcaddon boost", multiplier - 1,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE));
        }
        *///?}
    }

    /** Whether an effect is one an entity would want. What the instance carries became a holder in 1.21. */
    public static boolean beneficial(net.minecraft.world.effect.MobEffectInstance active) {
        //? if >=1.21 {
        return active.getEffect().value().isBeneficial();
        //?} else {
        /*return active.getEffect().isBeneficial();
        *///?}
    }

    /**
     * An iron golem inside the area that is holding a flower out, or null when none is.
     *
     * A golem offering one is what `minecraft:behavior.take_flower` waits for. The class moved into
     * its own package in 26, and the offer itself is a countdown the golem exposes either way.
     */
    public static net.minecraft.world.entity.Entity offeredFlower(
        net.minecraft.world.entity.Entity near, net.minecraft.world.phys.AABB area) {
        return near.level().getEntities(near, area, Platform::offersFlower).stream().findFirst().orElse(null);
    }

    public static boolean offersFlower(net.minecraft.world.entity.Entity golem) {
        //? if >=26 {
        return golem instanceof net.minecraft.world.entity.animal.golem.IronGolem holding
            && holding.getOfferFlowerTick() > 0;
        //?} else {
        /*return golem instanceof net.minecraft.world.entity.animal.IronGolem holding
            && holding.getOfferFlowerTick() > 0;
        *///?}
    }

    /** Holds a flower out from a golem, or takes it back off the golem, which ends the offer. */
    public static void offerFlower(net.minecraft.world.entity.Entity golem, boolean offering) {
        //? if >=26 {
        if (golem instanceof net.minecraft.world.entity.animal.golem.IronGolem holding) {
            holding.offerFlower(offering);
        }
        //?} else {
        /*if (golem instanceof net.minecraft.world.entity.animal.IronGolem holding) {
            holding.offerFlower(offering);
        }
        *///?}
    }

    /** Lets go of whatever is leashed to a mob. What the call takes changed in 1.21.2. */
    public static void dropLeash(net.minecraft.world.entity.Mob mob) {
        //? if >=1.21.2 {
        mob.dropLeash();
        //?} else {
        /*mob.dropLeash(true, true);
        *///?}
    }

    /** Whether a raid at this point has just been won, which is what a celebration follows. */
    public static boolean raidWon(ServerLevel level, net.minecraft.core.BlockPos at) {
        net.minecraft.world.entity.raid.Raid raid = level.getRaidAt(at);
        return raid != null && raid.isVictory();
    }

    /** Where a raid still being fought around this point is centred, or null when none is. */
    public static net.minecraft.core.BlockPos raidCentre(ServerLevel level, net.minecraft.core.BlockPos at) {
        net.minecraft.world.entity.raid.Raid raid = level.getRaidAt(at);
        return raid == null || !raid.isActive() || raid.isVictory() ? null : raid.getCenter();
    }

    /**
     * Which switch in `minecraft:game_event_movement_tracking` decides whether this event is raised.
     *
     * Null for every other event, which the component says nothing about. The game event itself
     * became a holder in 1.21, which is the whole difference between the two forms.
     */
    //? if >=1.21 {
    public static String movementEvent(
        net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event) {
        net.minecraft.world.level.gameevent.GameEvent raised = event.value();
        if (raised == net.minecraft.world.level.gameevent.GameEvent.STEP.value()) return "emit_move";
        if (raised == net.minecraft.world.level.gameevent.GameEvent.SWIM.value()) return "emit_swim";
        return raised == net.minecraft.world.level.gameevent.GameEvent.FLAP.value() ? "emit_flap" : null;
    }
    //?} else {
    /*public static String movementEvent(net.minecraft.world.level.gameevent.GameEvent event) {
        if (event == net.minecraft.world.level.gameevent.GameEvent.STEP) return "emit_move";
        if (event == net.minecraft.world.level.gameevent.GameEvent.SWIM) return "emit_swim";
        return event == net.minecraft.world.level.gameevent.GameEvent.FLAP ? "emit_flap" : null;
    }
    *///?}

    /**
     * The reason a chunk stays loaded and ticking while this mod is holding it.
     *
     * Deliberately not the one `/forceload` uses: that one is written into the saved world, so a
     * server that stopped while a chunk was held would keep it loaded for good, with nothing left in
     * the game able to say why. This one is forgotten when the server stops.
     */
    //? if >=26 {
    private static final net.minecraft.server.level.TicketType HELD =
        new net.minecraft.server.level.TicketType(net.minecraft.server.level.TicketType.NO_TIMEOUT,
            net.minecraft.server.level.TicketType.FLAG_LOADING
                | net.minecraft.server.level.TicketType.FLAG_SIMULATION);
    //?} else {
    /*private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos>
        HELD = net.minecraft.server.level.TicketType.create("mcaddon_held",
            java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    *///?}

    /** Chunks a ticket covers around the one it names, matching what a forced chunk gets. */
    private static final int HELD_RADIUS = 2;

    /** Keeps one chunk loaded and ticking until it is let go of again. */
    public static void holdChunk(ServerLevel level, net.minecraft.world.level.ChunkPos at) {
        //? if >=26 {
        level.getChunkSource().addTicketWithRadius(HELD, at, HELD_RADIUS);
        //?} else {
        /*level.getChunkSource().addRegionTicket(HELD, at, HELD_RADIUS, at);
        *///?}
    }

    /** Lets go of a held chunk, which loads and ticks on its own terms again. */
    public static void releaseChunk(ServerLevel level, net.minecraft.world.level.ChunkPos at) {
        //? if >=26 {
        level.getChunkSource().removeTicketWithRadius(HELD, at, HELD_RADIUS);
        //?} else {
        /*level.getChunkSource().removeRegionTicket(HELD, at, HELD_RADIUS, at);
        *///?}
    }

    /**
     * Pushes a button through the block itself, so everything watching a button hears about it.
     *
     * Setting the powered state by hand would light the button and nothing else: the sound, the
     * neighbour update and the scheduled pop back out all live in here. 1.21 added the pusher.
     */
    public static void pressButton(net.minecraft.world.level.block.ButtonBlock button,
        net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos at, net.minecraft.world.entity.player.Player pusher) {
        //? if >=1.21 {
        button.press(state, level, at, pusher);
        //?} else {
        /*button.press(state, level, at);
        *///?}
    }

    /** Flips a lever the same way, which 1.21 also gave a puller and a void return. */
    public static void pullLever(net.minecraft.world.level.block.LeverBlock lever,
        net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos at, net.minecraft.world.entity.player.Player puller) {
        //? if >=1.21 {
        lever.pull(state, level, at, puller);
        //?} else {
        /*lever.pull(state, level, at);
        *///?}
    }

    /** The picture the game draws into, which 26.2 moved from the client onto its renderer. */
    public static com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget(
        net.minecraft.client.Minecraft client) {
        //? if >=26.2 {
        return client.gameRenderer.mainRenderTarget();
        //?} else {
        /*return client.getMainRenderTarget();
        *///?}
    }

    /** What F1 does. 26.2 keeps it on the heads-up display as a toggle rather than as an option. */
    public static void hideHud(net.minecraft.client.Minecraft client, boolean hidden) {
        //? if >=26.2 {
        if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
        //?} else {
        /*client.options.hideGui = hidden;
        *///?}
    }

    /**
     * Opens a singleplayer world to the LAN on a port, without asking its guests to authenticate:
     * a development guest has no account, and 1.21.1 and 26 make the integrated server demand one.
     * The scope argument arrived in 26.2.
     */
    public static boolean publishServer(net.minecraft.client.server.IntegratedServer server, int port) {
        server.setUsesAuthentication(false);
        //? if >=26.3 {
        /*return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, port);
        *///?} elif >=26.2 {
        return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN,
            net.minecraft.world.level.GameType.SURVIVAL, false, port);
        //?} else {
        /*return server.publishServer(net.minecraft.world.level.GameType.SURVIVAL, false, port);
        *///?}
    }

    /** Opens a saved singleplayer world by its directory name, the way the world list does. */
    public static void openWorld(String name) {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        //? if >=1.21 {
        client.createWorldOpenFlows().openWorld(name, () -> { });
        //?} else {
        /*client.createWorldOpenFlows().loadLevel(client.screen, name);
        *///?}
    }

    /**
     * Queues a task for the client thread, even when called on it: `execute` runs a task called on
     * the client thread at once, inside whatever that thread is in the middle of. Renamed in 26.
     */
    public static void schedule(Runnable task) {
        //? if >=26 {
        net.minecraft.client.Minecraft.getInstance().schedule(task);
        //?} else {
        /*net.minecraft.client.Minecraft.getInstance().tell(task);
        *///?}
    }

    /**
     * Gives an item registered after startup what the game gives every item as it starts.
     *
     * Before 26 the item renderer maps every item to its inventory model once, when it is built,
     * and a reload only rebuilds what that map points at; and a spawn egg is coloured by a provider
     * registered once, for the eggs there were. From 26 an item's model is looked up by its id and
     * its colours are tints in that model, so nothing is missing.
     */
    public static void prepareLateItem(Item item) {
        //? if >=1.21 && <26 {
        /*net.minecraft.client.Minecraft.getInstance().getItemRenderer().getItemModelShaper().register(item,
            net.minecraft.client.resources.model.ModelResourceLocation.inventory(BuiltInRegistries.ITEM.getKey(item)));
        *///?} else if <1.21 {
        /*net.minecraft.client.Minecraft.getInstance().getItemRenderer().getItemModelShaper().register(item,
            new net.minecraft.client.resources.model.ModelResourceLocation(BuiltInRegistries.ITEM.getKey(item),
                "inventory"));
        *///?}
        //? if <1.21.2 {
        /*if (item instanceof net.minecraft.world.item.SpawnEggItem egg) {
            net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, layer) -> egg.getColor(layer), egg);
        }
        *///?}
    }

    /** Opens a screen, or closes whatever is open when given null. Renamed in 26. */
    public static void setScreen(net.minecraft.client.gui.screens.Screen screen) {
        //? if >=26 {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
        //?} else {
        /*net.minecraft.client.Minecraft.getInstance().setScreen(screen);
        *///?}
    }

    /** The screen open on this client, or null. 26.2 moved it onto the heads-up display. */
    public static net.minecraft.client.gui.screens.Screen currentScreen() {
        //? if >=26.2 {
        return net.minecraft.client.Minecraft.getInstance().gui.screen();
        //?} else {
        /*return net.minecraft.client.Minecraft.getInstance().screen;
        *///?}
    }

    /**
     * Where the player is looking from.
     *
     * Both accessors lost their `get` prefix on the way to 26, but not in the same release: the
     * camera's own did first and the renderer's followed in 26.2.
     */
    public static net.minecraft.world.phys.Vec3 cameraPosition() {
        //? if >=26.2 {
        return net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
        //?} elif >=26 {
        /*return net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().position();
        *///?} else {
        /*return net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        *///?}
    }

    //? if >=26.3 {
    /*/^*
     * A pipeline drawing one screen covering triangle from no vertices, reading one texture and one
     * uniform block. 26.3 moved the device API into a package of its own and declares a sampler as
     * one more uniform of its layout.
     ^/
    public static com.mojang.renderpearl.api.pipeline.RenderPipeline screenPipeline(Identifier location,
        Identifier shader, String sampler, String uniformBlock) {
        return com.mojang.renderpearl.api.pipeline.RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withBindGroupLayout(com.mojang.renderpearl.api.pipeline.BindGroupLayout.builder()
                .withUniform(sampler, com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform(uniformBlock, com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
                .build())
            .withDepthStencilState(java.util.Optional.empty())
            .withCull(false)
            .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
            .build();
    }

    public static com.mojang.renderpearl.api.commands.RenderPass screenPass(
        com.mojang.renderpearl.api.commands.CommandEncoder encoder, java.util.function.Supplier<String> label,
        com.mojang.renderpearl.api.textures.GpuTextureView colour) {
        return encoder.createRenderPass(label, colour, java.util.Optional.empty());
    }

    public static void drawScreenTriangle(com.mojang.renderpearl.api.commands.RenderPass pass) {
        pass.draw(3, 1, 0, 0);
    }
    *///?} elif >=26.2 {
    /**
     * A pipeline drawing one screen covering triangle from no vertices, reading one texture and one
     * uniform block. 26.2 groups a pipeline's bindings into layouts and names its topology alone.
     */
    public static com.mojang.blaze3d.pipeline.RenderPipeline screenPipeline(Identifier location, Identifier shader,
        String sampler, String uniformBlock) {
        return com.mojang.blaze3d.pipeline.RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withBindGroupLayout(com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
                .withSampler(sampler)
                .withUniform(uniformBlock, com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                .build())
            .withDepthStencilState(java.util.Optional.empty())
            .withCull(false)
            .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
            .build();
    }

    public static com.mojang.blaze3d.systems.RenderPass screenPass(com.mojang.blaze3d.systems.CommandEncoder encoder,
        java.util.function.Supplier<String> label, com.mojang.blaze3d.textures.GpuTextureView colour) {
        return encoder.createRenderPass(label, colour, java.util.Optional.empty());
    }

    public static void drawScreenTriangle(com.mojang.blaze3d.systems.RenderPass pass) {
        pass.draw(3, 1, 0, 0);
    }
    //?} elif >=26 {
    /*public static com.mojang.blaze3d.pipeline.RenderPipeline screenPipeline(Identifier location, Identifier shader,
        String sampler, String uniformBlock) {
        return com.mojang.blaze3d.pipeline.RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withSampler(sampler)
            .withUniform(uniformBlock, com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withDepthStencilState(java.util.Optional.empty())
            .withCull(false)
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.EMPTY,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES)
            .build();
    }

    public static com.mojang.blaze3d.systems.RenderPass screenPass(com.mojang.blaze3d.systems.CommandEncoder encoder,
        java.util.function.Supplier<String> label, com.mojang.blaze3d.textures.GpuTextureView colour) {
        return encoder.createRenderPass(label, colour, java.util.OptionalInt.empty());
    }

    public static void drawScreenTriangle(com.mojang.blaze3d.systems.RenderPass pass) {
        pass.draw(0, 3);
    }
    *///?}

    /** The resource pack format this version reads, taken from the game rather than a table. */
    public static int resourcePackFormat() {
        //? if >=26 {
        return net.minecraft.SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        //?} else {
        /*return net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
        *///?}
    }

    /**
     * Sets the weather outright and how long it lasts, in ticks.
     *
     * The level held the four numbers itself until 26 moved them into saved data of their own.
     */
    public static void setWeather(ServerLevel level, boolean raining, boolean thundering, int duration) {
        //? if >=26 {
        net.minecraft.world.level.saveddata.WeatherData weather = level.getWeatherData();
        weather.setRaining(raining);
        weather.setThundering(thundering);
        weather.setRainTime(raining ? duration : 0);
        weather.setThunderTime(thundering ? duration : 0);
        weather.setClearWeatherTime(raining ? 0 : duration);
        //?} else {
        /*level.setWeatherParameters(raining ? 0 : duration, duration, raining, thundering);
        *///?}
    }

    /** The buildable height range, lowest block and one past the highest. */
    public static int[] heightRange(ServerLevel level) {
        //? if >=26 {
        return new int[] {level.getMinY(), level.getMaxY() + 1};
        //?} else {
        /*return new int[] {level.getMinBuildHeight(), level.getMaxBuildHeight()};
        *///?}
    }

    /** Who a tamed animal belongs to. What it stores became an entity reference in 26. */
    public static UUID ownerOf(net.minecraft.world.entity.TamableAnimal animal) {
        //? if >=26 {
        return animal.getOwnerReference() == null ? null : animal.getOwnerReference().getUUID();
        //?} else {
        /*return animal.getOwnerUUID();
        *///?}
    }

    /**
     * Whether a creaking still has the heart that put it there, and null where the game has neither.
     *
     * The creaking and its heart arrived in 1.21.4, so the two older versions have no entity this
     * can be asked about and a pack asking is told the answer is not available rather than no.
     */
    public static Boolean boundToCreakingHeart(Entity entity) {
        //? if >=1.21.4 {
        return entity instanceof net.minecraft.world.entity.monster.creaking.Creaking creaking
            ? creaking.isHeartBound() : null;
        //?} else {
        /*return null;
        *///?}
    }

    /** Where a mob is kept near, or null when it is kept nowhere. A restriction until 26. */
    public static net.minecraft.core.BlockPos homeOf(net.minecraft.world.entity.Mob mob) {
        //? if >=26 {
        return mob.hasHome() ? mob.getHomePosition() : null;
        //?} else {
        /*return mob.hasRestriction() ? mob.getRestrictCenter() : null;
        *///?}
    }

    /** The enchantments on a stack, by name, with their levels. They became data-driven holders in 1.21. */
    public static Map<String, Integer> enchantmentsOf(net.minecraft.world.item.ItemStack stack) {
        //? if >=1.21 {
        net.minecraft.world.item.enchantment.ItemEnchantments enchantments =
            EnchantmentHelper.getEnchantmentsForCrafting(stack);
        return enchantments.keySet().stream().collect(Collectors.toMap(
            holder -> holder.unwrapKey().map(key -> idOf(key).getPath()).orElse(""),
            enchantments::getLevel, Math::max));
        //?} else {
        /*return EnchantmentHelper.getEnchantments(stack).entrySet().stream()
            .filter(enchantment -> BuiltInRegistries.ENCHANTMENT.getKey(enchantment.getKey()) != null)
            .collect(Collectors.toMap(
                enchantment -> BuiltInRegistries.ENCHANTMENT.getKey(enchantment.getKey()).getPath(),
                Map.Entry::getValue, Math::max));
        *///?}
    }

    /** Which chat type a message was sent as. The bound type became a holder in 1.20.5. */
    public static ResourceKey<net.minecraft.network.chat.ChatType> chatTypeOf(
        net.minecraft.network.chat.ChatType.Bound bound, net.minecraft.core.RegistryAccess access) {
        //? if >=1.21 {
        return bound.chatType().unwrapKey().orElse(null);
        //?} else {
        /*return access.registryOrThrow(net.minecraft.core.registries.Registries.CHAT_TYPE)
            .getResourceKey(bound.chatType()).orElse(null);
        *///?}
    }

    /**
     * The key a loot table is asked for by, which became a registry key in 1.21.
     *
     * 1.20.1 names a table by its id alone, so there is nothing for it to be turned into there.
     */
    //? if >=1.21 {
    public static ResourceKey<net.minecraft.world.level.storage.loot.LootTable>
        lootTableKey(Identifier table) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table);
    }
    //?}

    /** The stand-in player a game test acts through. 1.21 made it say which mode it is playing in. */
    /** A player using what is in their hand on the entity, as a click does. 26 asks where it landed. */
    public static net.minecraft.world.InteractionResult interactOn(net.minecraft.world.entity.player.Player player,
        Entity entity,
        net.minecraft.world.InteractionHand hand) {
        //? if >=26 {
        return player.interactOn(entity, hand, entity.position());
        //?} else {
        /*return player.interactOn(entity, hand);
        *///?}
    }

    /** Names a stack the way an anvil does, which is what a name tag reads its name from. */
    public static void setCustomName(net.minecraft.world.item.ItemStack stack, String name) {
        //? if >=1.21 {
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal(name));
        //?} else {
        /*stack.setHoverName(net.minecraft.network.chat.Component.literal(name));
        *///?}
    }

    public static net.minecraft.world.entity.player.Player mockPlayer(
        net.minecraft.gametest.framework.GameTestHelper helper) {
        //? if >=1.21 {
        return helper.makeMockPlayer(GameType.SURVIVAL);
        //?} else {
        /*return helper.makeMockPlayer();
        *///?}
    }

    /**
     * Whether mobs are allowed to change the world around them.
     *
     * A Bedrock behaviour that takes or places a block says whether it obeys this rule, so a goal
     * doing either has to ask. 26 moved the rules into their own package and typed them by value.
     */
    public static boolean mobGriefing(ServerLevel level) {
        //? if >=26 {
        return level.getGameRules().get(GameRules.MOB_GRIEFING);
        //?} else {
        /*return level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
        *///?}
    }

    /**
     * A blast drawn with gusts rather than smoke where `gust` is set, and heard as `sound`.
     *
     * Java chose a blast's particles and sound itself before 1.21, which is what a blast there gets.
     * From 26 the call also takes the block debris, which the gusts of a wind charge go without.
     */
    public static void explode(ServerLevel level, Entity source,
        net.minecraft.world.level.ExplosionDamageCalculator rules, net.minecraft.world.phys.Vec3 at, float power,
        boolean fire, net.minecraft.world.level.Level.ExplosionInteraction interaction, boolean gust,
        net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound) {
        //? if >=1.21 {
        net.minecraft.core.particles.ParticleOptions small = gust
            ? net.minecraft.core.particles.ParticleTypes.GUST_EMITTER_SMALL
            : net.minecraft.core.particles.ParticleTypes.EXPLOSION;
        net.minecraft.core.particles.ParticleOptions large = gust
            ? net.minecraft.core.particles.ParticleTypes.GUST_EMITTER_LARGE
            : net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER;
        //?}
        //? if >=26 {
        level.explode(source, null, rules, at.x, at.y, at.z, power, fire, interaction, small, large,
            net.minecraft.util.random.WeightedList.of(), sound);
        //?} else if >=1.21 {
        /*level.explode(source, null, rules, at.x, at.y, at.z, power, fire, interaction, small, large, sound);
        *///?} else {
        /*level.explode(source, null, rules, at.x, at.y, at.z, power, fire, interaction);
        *///?}
    }

    /**
     * Spares a player thrown by a blast the fall that follows, until it lands, the way a wind charge
     * spares whoever it throws. Nothing before 1.21 keeps track of a throw.
     */
    public static void spareTheFall(net.minecraft.world.entity.player.Player player) {
        //? if >=26 {
        player.setIgnoreFallDamageFromCurrentImpulse(true, player.position());
        //?} else if >=1.21 {
        /*player.currentImpulseImpactPos = player.position();
        player.setIgnoreFallDamageFromCurrentImpulse(true);
        *///?}
    }

    /** A blast that leaves blocks standing and works the switches among them, which 1.20.1 has not got. */
    public static net.minecraft.world.level.Level.ExplosionInteraction toggling() {
        //? if >=1.21 {
        return net.minecraft.world.level.Level.ExplosionInteraction.TRIGGER;
        //?} else {
        /*return net.minecraft.world.level.Level.ExplosionInteraction.NONE;
        *///?}
    }

    /** A null level clears the spawn point. The setter took a record from 26 and five values before. */
    public static void respawnAt(net.minecraft.server.level.ServerPlayer player, ServerLevel level,
        net.minecraft.core.BlockPos at) {
        //? if >=26 {
        net.minecraft.server.level.ServerPlayer.RespawnConfig config = level == null ? null
            : new net.minecraft.server.level.ServerPlayer.RespawnConfig(
                net.minecraft.world.level.storage.LevelData.RespawnData.of(level.dimension(), at, 0f, 0f),
                false);
        player.setRespawnPosition(config, false);
        //?} else {
        /*player.setRespawnPosition(
            level == null ? net.minecraft.world.level.Level.OVERWORLD : level.dimension(),
            level == null ? null : at, 0f, false, false);
        *///?}
    }

    /**
     * Scoops a pack's entity into the bucket the player holds, which Java's own fish do the same way.
     * Empty when the player holds no water bucket. The interface moved into `entity` in 26.2.
     */
    public static java.util.Optional<net.minecraft.world.InteractionResult> scoop(
        net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
        at.ioexception.mcaddon.entity.AddonEntity entity) {
        //? if >=26.2 {
        return net.minecraft.world.entity.Bucketable.bucketMobPickup(player, hand, entity);
        //?} else {
        /*return net.minecraft.world.entity.animal.Bucketable.bucketMobPickup(player, hand, entity);
        *///?}
    }

    /** What Java keeps of every mob it carries in a bucket: its name, its health and its flags. */
    public static void saveBucketDefaults(net.minecraft.world.entity.Mob mob,
        net.minecraft.world.item.ItemStack bucket) {
        //? if >=26.2 {
        net.minecraft.world.entity.Bucketable.saveDefaultDataToBucketTag(mob, bucket);
        //?} else {
        /*net.minecraft.world.entity.animal.Bucketable.saveDefaultDataToBucketTag(mob, bucket);
        *///?}
    }

    public static void loadBucketDefaults(net.minecraft.world.entity.Mob mob,
        net.minecraft.nbt.CompoundTag kept) {
        //? if >=26.2 {
        net.minecraft.world.entity.Bucketable.loadDefaultDataFromBucketTag(mob, kept);
        //?} else {
        /*net.minecraft.world.entity.animal.Bucketable.loadDefaultDataFromBucketTag(mob, kept);
        *///?}
    }

    /**
     * Writes one text into what a bucket keeps of the mob inside it, which is handed back to the mob
     * as it is let go. From 1.20.5 that is a component of its own; before, the stack's own tag.
     */
    public static void putBucketText(net.minecraft.world.item.ItemStack bucket, String key, String text) {
        //? if >=1.21 {
        net.minecraft.world.item.component.CustomData.update(
            net.minecraft.core.component.DataComponents.BUCKET_ENTITY_DATA, bucket,
            tag -> tag.putString(key, text));
        //?} else {
        /*bucket.getOrCreateTag().putString(key, text);
        *///?}
    }

    /** Null where nothing was written under that key. 26 made a missing key an empty answer. */
    public static String bucketText(net.minecraft.nbt.CompoundTag kept, String key) {
        //? if >=26 {
        return kept.getString(key).orElse(null);
        //?} else {
        /*return kept.contains(key) ? kept.getString(key) : null;
        *///?}
    }

    /** Where the player respawns, or null while they respawn wherever the world says. */
    public static net.minecraft.core.BlockPos respawnPosition(
        net.minecraft.server.level.ServerPlayer player) {
        //? if >=26 {
        net.minecraft.server.level.ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config == null ? null : config.respawnData().pos();
        //?} else {
        /*return player.getRespawnPosition();
        *///?}
    }

    /**
     * What a loot table hands out, rolled without anything having to die for it.
     *
     * Bedrock names a table on a behaviour as readily as on a death, which is how a sneeze drops an
     * item and a swallowed mob leaves one behind. The table is still an entity's, so it is rolled
     * with what an entity table asks for: who it is about, where it stands and what hurt it. 1.21
     * moved the tables out of their own manager and into the reloadable registries, and started
     * naming them by registry key.
     */
    public static java.util.List<net.minecraft.world.item.ItemStack> rollLoot(ServerLevel level,
        Identifier table, Entity about) {
        return java.util.List.copyOf(lootTable(level, table).getRandomItems(lootAbout(level, about)
            .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.ENTITY)));
    }

    /**
     * The same table rolled the way a block a blast broke is, which is what explosion decay reads.
     *
     * A block's roll is the one that carries the blast's radius, so the table is asked as though
     * an air block at the entity's feet had been blown apart.
     */
    public static java.util.List<net.minecraft.world.item.ItemStack> rollLootInBlast(
        ServerLevel level, Identifier table, Entity about, float radius) {
        net.minecraft.world.level.storage.loot.LootParams asked =
            new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.BLOCK_STATE,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.ORIGIN, about.position())
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.TOOL, net.minecraft.world.item.ItemStack.EMPTY)
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.THIS_ENTITY, about)
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.EXPLOSION_RADIUS, radius)
                .create(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParamSets.BLOCK);
        return java.util.List.copyOf(lootTable(level, table).getRandomItems(asked));
    }

    private static net.minecraft.world.level.storage.loot.LootTable lootTable(ServerLevel level,
        Identifier table) {
        //? if >=1.21 {
        return level.getServer().reloadableRegistries().getLootTable(lootTableKey(table));
        //?} else {
        /*return level.getServer().getLootData().getLootTable(table);
        *///?}
    }

    private static net.minecraft.world.level.storage.loot.LootParams.Builder lootAbout(
        ServerLevel level, Entity about) {
        return new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
            .withParameter(net.minecraft.world.level.storage.loot.parameters
                .LootContextParams.THIS_ENTITY, about)
            .withParameter(net.minecraft.world.level.storage.loot.parameters
                .LootContextParams.ORIGIN, about.position())
            .withParameter(net.minecraft.world.level.storage.loot.parameters
                .LootContextParams.DAMAGE_SOURCE, level.damageSources().generic());
    }

    /** A boss bar the server draws. 26 gave it the id every other bar already carried. */
    public static net.minecraft.server.level.ServerBossEvent bossBar(Component name) {
        //? if >=26 {
        return new net.minecraft.server.level.ServerBossEvent(UUID.randomUUID(), name,
            net.minecraft.world.BossEvent.BossBarColor.PURPLE,
            net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        //?} else {
        /*return new net.minecraft.server.level.ServerBossEvent(name,
            net.minecraft.world.BossEvent.BossBarColor.PURPLE,
            net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        *///?}
    }

    /** Whether bone meal would do anything here. 1.21 dropped the flag naming which side is asking. */
    public static boolean canBonemeal(net.minecraft.world.level.block.BonemealableBlock crop,
        ServerLevel level, net.minecraft.core.BlockPos at,
        net.minecraft.world.level.block.state.BlockState state) {
        //? if >=26.3 {
        /*return crop.isValidBonemealTarget(level, at, state,
            net.minecraft.world.level.block.BonemealSource.MOB);
        *///?} elif >=1.21 {
        return crop.isValidBonemealTarget(level, at, state);
        //?} else {
        /*return crop.isValidBonemealTarget(level, at, state, false);
        *///?}
    }

    /**
     * How far a wolf is through shaking the water off, which runs from nothing to two.
     *
     * 26 answers the count itself. The older versions keep the count private and offer only the
     * shade the wolf is drawn at, which is that count and nothing else, so reading the shade
     * backwards gives the count up to the moment it reaches full.
     */
    public static double wolfShakeAngle(Wolf wolf, float partialTick) {
        //? if >=26 {
        return wolf.getShakeAnim(partialTick);
        //?} elif >=1.21 {
        /*return (wolf.getWetShade(partialTick) - 0.75) * 8;
        *///?} else {
        /*return (wolf.getWetShade(partialTick) - 0.5) * 4;
        *///?}
    }

    /**
     * The atlas sprite a block's own particles are cut from.
     *
     * 26 moved the question from the block renderer to the model manager, and answers it with the
     * material the sprite belongs to rather than with the sprite.
     */
    public static net.minecraft.client.renderer.texture.TextureAtlasSprite blockParticleSprite(
        net.minecraft.world.level.block.state.BlockState state) {
        //? if >=26 {
        return net.minecraft.client.Minecraft.getInstance().getModelManager()
            .getBlockStateModelSet().getParticleMaterial(state).sprite();
        //?} else {
        /*return net.minecraft.client.Minecraft.getInstance().getBlockRenderer()
            .getBlockModelShaper().getParticleIcon(state);
        *///?}
    }

    /**
     * Whether the entity is a lingering effect rather than one that lands and is gone.
     *
     * 26 gave the two kinds of thrown potion a class each. Before that one class carried both, and
     * the item it was thrown as is what tells them apart.
     */
    public static boolean isLingering(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.AreaEffectCloud) return true;

        //? if >=26 {
        return entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile
            .ThrownLingeringPotion;
        //?} else {
        /*return entity instanceof net.minecraft.world.entity.projectile.ThrownPotion potion
            && potion.getItem().is(net.minecraft.world.item.Items.LINGERING_POTION);
        *///?}
    }

    /**
     * How many swings a second the held item allows, before anything acting on whoever holds it.
     *
     * A weapon states its speed as an attribute modifier on the stack, which 1.21 moved from a
     * multimap the item answers with into a component the stack carries.
     */
    public static double baseAttackSpeed(LivingEntity living, net.minecraft.world.item.ItemStack held) {
        double speed = living.getAttributeBaseValue(
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);

        //? if >=1.21 {
        return speed + held.getOrDefault(
                net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
            .modifiers().stream()
            .filter(entry -> entry.attribute()
                .equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)
                && entry.slot().test(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                && entry.modifier().operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE)
            .mapToDouble(entry -> entry.modifier().amount())
            .sum();
        //?} else {
        /*return speed + held
            .getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
            .get(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED).stream()
            .filter(modifier -> modifier.getOperation()
                == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION)
            .mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount)
            .sum();
        *///?}
    }

    /** The colour an effect paints its swirls, which 1.21 reached through a holder. */
    public static int effectColour(MobEffectInstance active) {
        //? if >=1.21 {
        return active.getEffect().value().getColor();
        //?} else {
        /*return active.getEffect().getColor();
        *///?}
    }

    /**
     * How long the cooldown a use puts the item under lasts, in seconds, or nothing while it has none.
     *
     * 26 carries the length on the stack, which is where the game's own items keep theirs. The older
     * versions hold a cooldown while it runs and nowhere say how long it was.
     */
    public static float cooldownSeconds(net.minecraft.world.item.ItemStack stack) {
        //? if >=26 {
        net.minecraft.world.item.component.UseCooldown cooldown =
            stack.get(net.minecraft.core.component.DataComponents.USE_COOLDOWN);
        return cooldown == null ? 0f : cooldown.seconds();
        //?} else {
        /*return 0f;
        *///?}
    }

    /**
     * Whether a pack's worldgen is converted at all on this version.
     *
     * An entry a data pack puts in a worldgen registry is stable only while the pack it came from
     * carries a `KnownPack`, and 1.20.1 has no such class: everything a pack that is not built in
     * supplies decodes as experimental there, the level data keeps that lifecycle, and the client
     * then asks the player whether to load the world at all, on every open, with no way to answer
     * once. A pack whose biomes would do that to a world is a pack too new for the version, so its
     * worldgen is named through `/mcaddon entities` and none of it is written.
     */
    public static boolean convertsWorldgen() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether a pack's own dimensions are converted at all on this version.
     *
     * `WorldDimensions.bake` decides the level stem registry's lifecycle from the set of dimensions
     * the world ends up with, and the two editions of that test disagree. From 26 it is
     * `keys.containsAll(BUILTIN_ORDER)`, so a world carrying the three the game ships stays stable
     * however many more a pack adds. Before that it is `list.size() == VANILLA_DIMENSION_COUNT`, so
     * a fourth dimension of any kind makes the world experimental and a client asks the player
     * whether to load it at all, every time they open it. A pack whose dimensions would do that is
     * a pack too new for the version, so they are named through `/mcaddon entities` and none of
     * them is written.
     */
    public static boolean convertsDimensions() {
        //? if >=26 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether a pack's lighting and atmospherics are converted at all on this version.
     *
     * A settings file states a value against the time of day, and the registry that holds such a
     * table is `Registries.TIMELINE`, which 26 added along with `EnvironmentAttribute` and the
     * `HolderSet<Timeline>` a `DimensionType` names. Before that a data pack has nowhere to write a
     * curve to: the sky colour, the sun angle and the sky light level are worked out in code from
     * the time of day and no file reaches them. Both directories are named through
     * `/mcaddon entities` there instead.
     */
    public static boolean convertsTimelines() {
        //? if >=26 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * How many timelines of a namespace's own the overworld is given, or -1 where there are none.
     *
     * The answer is what proves a converted timeline reached the game rather than merely being
     * written: the registry decoded it, the tag merged with the one the game ships, and the
     * dimension type resolved that tag. Versions before 26 have no such registry and answer -1.
     */
    public static int overworldTimelines(net.minecraft.server.level.ServerLevel level,
        String namespace) {
        //? if >=26 {
        int found = 0;
        for (net.minecraft.core.Holder<net.minecraft.world.timeline.Timeline> timeline
            : level.dimensionType().timelines()) {
            java.util.Optional<net.minecraft.resources.ResourceKey<
                net.minecraft.world.timeline.Timeline>> key = timeline.unwrapKey();
            if (key.isPresent() && key.get().identifier().getNamespace().equals(namespace)) found++;
        }
        return found;
        //?} else {
        /*return -1;
        *///?}
    }

    /**
     * The empty carver list a converted biome carries.
     *
     * A Bedrock biome states no carvers, and the field is required either way. 26 made it one list;
     * before that it is a map from the carving step to a list, and the two steps are `air` and
     * `liquid`.
     */
    public static void emptyCarvers(com.google.gson.JsonObject biome) {
        //? if >=26 {
        biome.add("carvers", new com.google.gson.JsonArray());
        //?} else {
        /*biome.add("carvers", new com.google.gson.JsonObject());
        *///?}
    }

    /**
     * How a converted biome looks and sounds, as the version writes it.
     *
     * Up to 1.21 a biome states its own fog, sky and water fog beside the water colour, and all
     * four are required. Its ambient particle, its three ambient sound moments and its music are
     * fields of the same `effects` block. 26 left the water colour and the tints in `effects` and
     * moved the rest onto environment attributes, which a biome contributes a layer of, and which
     * carry the two fog distances, the music volume and an underwater track as well.
     */
    public static void biomeEffects(com.google.gson.JsonObject biome,
        at.ioexception.mcaddon.bedrock.ClientBiomeDefinition painted) {
        at.ioexception.mcaddon.bedrock.FogDefinition fog = painted.fog();
        at.ioexception.mcaddon.bedrock.BiomeSounds sounds = painted.sounds();
        at.ioexception.mcaddon.bedrock.BiomeMusic music = painted.music();

        com.google.gson.JsonObject effects = new com.google.gson.JsonObject();
        effects.addProperty("water_color", painted.water() >= 0 ? painted.water()
            : at.ioexception.mcaddon.bedrock.ClientBiomeDefinition.DEFAULT_WATER);
        if (painted.grass() >= 0) effects.addProperty("grass_color", painted.grass());
        if (painted.foliage() >= 0) effects.addProperty("foliage_color", painted.foliage());

        //? if >=26 {
        if (painted.dryFoliage() >= 0) {
            effects.addProperty("dry_foliage_color", painted.dryFoliage());
        }

        com.google.gson.JsonObject attributes = new com.google.gson.JsonObject();
        if (painted.sky() >= 0) attributes.addProperty("minecraft:visual/sky_color", painted.sky());
        attribute(attributes, "fog", fog.air());
        attribute(attributes, "water_fog", fog.water());

        if (painted.particles().painted()) {
            com.google.gson.JsonArray drawn = new com.google.gson.JsonArray();
            drawn.add(ambientParticle(painted.particles()));
            attributes.add("minecraft:visual/ambient_particles", drawn);
        }
        if (sounds.heard()) {
            com.google.gson.JsonObject heard = new com.google.gson.JsonObject();
            if (sounds.loop() != null) heard.addProperty("loop", sounds.loop());
            if (sounds.mood() != null) heard.add("mood", moodSound(sounds.mood()));
            if (sounds.addition() != null) {
                com.google.gson.JsonArray additions = new com.google.gson.JsonArray();
                additions.add(additionsSound(sounds));
                heard.add("additions", additions);
            }
            attributes.add("minecraft:audio/ambient_sounds", heard);
        }
        if (music.track() != null || music.underwater() != null) {
            com.google.gson.JsonObject background = new com.google.gson.JsonObject();
            if (music.track() != null) background.add("default", music(music.track()));
            if (music.underwater() != null) {
                background.add("underwater", music(music.underwater()));
            }
            attributes.add("minecraft:audio/background_music", background);
        }
        if (music.volume() >= 0) {
            attributes.addProperty("minecraft:audio/music_volume", music.volume());
        }
        if (attributes.size() > 0) biome.add("attributes", attributes);
        //?} else {
        /*effects.addProperty("sky_color", painted.sky() >= 0 ? painted.sky()
            : at.ioexception.mcaddon.convert.Worldgen.skyColour(
                biome.get("temperature").getAsFloat()));
        effects.addProperty("fog_color", fog.air().colour() >= 0 ? fog.air().colour() : 12638463);
        effects.addProperty("water_fog_color",
            fog.water().colour() >= 0 ? fog.water().colour() : 329011);
        if (painted.dryFoliage() >= 0) {
            at.ioexception.mcaddon.registry.AddonEntities
                .unsupported("client biome minecraft:dry_foliage_color");
        }
        if (fog.air().start() >= 0 || fog.air().end() >= 0
            || fog.water().start() >= 0 || fog.water().end() >= 0) {
            at.ioexception.mcaddon.registry.AddonEntities
                .unsupported("fog distance (needs 26 or newer)");
        }

        if (painted.particles().painted()) {
            effects.add("particle", ambientParticle(painted.particles()));
        }
        if (sounds.loop() != null) effects.addProperty("ambient_sound", sounds.loop());
        if (sounds.mood() != null) effects.add("mood_sound", moodSound(sounds.mood()));
        if (sounds.addition() != null) effects.add("additions_sound", additionsSound(sounds));
        if (music.track() != null) effects.add("music", music(music.track()));
        if (music.underwater() != null) {
            at.ioexception.mcaddon.registry.AddonEntities.unsupported(
                "client biome minecraft:biome_music underwater_music (needs 26 or newer)");
        }
        if (music.volume() >= 0) {
            at.ioexception.mcaddon.registry.AddonEntities.unsupported(
                "client biome minecraft:biome_music volume_multiplier (needs 26 or newer)");
        }
        *///?}
        biome.add("effects", effects);
    }

    /**
     * The dimension type a converted dimension stands in, as the version spells one.
     *
     * A Bedrock dimension states a band of height and nothing else about the world it makes, so
     * everything beside `min_y` and `height` is the overworld's own answer. What differs is the
     * rest of the field list: up to 1.21 a dimension type carries `ultrawarm`, `natural`,
     * `bed_works`, `respawn_anchor_works`, `piglin_safe`, `has_raids` and an `effects` name, and 26
     * replaced all seven with environment attributes it reads off the biome and a flag for the
     * dragon fight.
     */
    public static com.google.gson.JsonObject dimensionType(int minimumY, int height) {
        com.google.gson.JsonObject type = new com.google.gson.JsonObject();
        type.addProperty("has_skylight", true);
        type.addProperty("has_ceiling", false);
        type.addProperty("coordinate_scale", 1.0);
        type.addProperty("ambient_light", 0.0f);
        type.addProperty("min_y", minimumY);
        type.addProperty("height", height);
        type.addProperty("logical_height", height);
        type.addProperty("infiniburn", "#minecraft:infiniburn_overworld");
        type.addProperty("monster_spawn_block_light_limit", 0);

        type.add("monster_spawn_light_level", monsterSpawnLight());

        //? if >=26 {
        type.addProperty("has_ender_dragon_fight", false);
        //?} else {
        /*type.addProperty("ultrawarm", false);
        type.addProperty("natural", true);
        type.addProperty("bed_works", true);
        type.addProperty("respawn_anchor_works", false);
        type.addProperty("piglin_safe", false);
        type.addProperty("has_raids", true);
        type.addProperty("effects", "minecraft:overworld");
        *///?}
        return type;
    }

    /**
     * The light levels monsters spawn at, which is the overworld's own band of nought to seven.
     *
     * It is an int provider, and the two editions of Java write a dispatched value differently: from
     * 1.21 the chosen type's own fields sit beside the type name, and before that they sit under a
     * `value` block of their own.
     */
    private static com.google.gson.JsonObject monsterSpawnLight() {
        com.google.gson.JsonObject light = new com.google.gson.JsonObject();
        light.addProperty("type", "minecraft:uniform");
        //? if >=1.21 {
        light.addProperty("min_inclusive", 0);
        light.addProperty("max_inclusive", 7);
        //?} else {
        /*com.google.gson.JsonObject band = new com.google.gson.JsonObject();
        band.addProperty("min_inclusive", 0);
        band.addProperty("max_inclusive", 7);
        light.add("value", band);
        *///?}
        return light;
    }

    /**
     * The particle a biome drops through its air, and how often.
     *
     * Both versions state a particle and a probability. 26 keys the particle under `particle` and
     * holds a list of these, and before that it is `options` and one entry at most.
     */
    private static com.google.gson.JsonObject ambientParticle(
        at.ioexception.mcaddon.bedrock.BiomeParticles particles) {
        com.google.gson.JsonObject options = new com.google.gson.JsonObject();
        options.addProperty("type", particles.particle());

        com.google.gson.JsonObject drawn = new com.google.gson.JsonObject();
        //? if >=26 {
        drawn.add("particle", options);
        //?} else {
        /*drawn.add("options", options);
        *///?}
        drawn.addProperty("probability", particles.probability());
        return drawn;
    }

    /**
     * The mood sound, with the timing Java's own caves use.
     *
     * Bedrock names the sound and nothing else, so how long the silence is, how far a dark block is
     * searched for and how far in front of it the sound plays are the numbers Java states for the
     * mood every biome without one of its own falls back to.
     */
    private static com.google.gson.JsonObject moodSound(String sound) {
        com.google.gson.JsonObject mood = new com.google.gson.JsonObject();
        mood.addProperty("sound", sound);
        mood.addProperty("tick_delay", 6000);
        mood.addProperty("block_search_extent", 8);
        mood.addProperty("offset", 2.0);
        return mood;
    }

    private static com.google.gson.JsonObject additionsSound(
        at.ioexception.mcaddon.bedrock.BiomeSounds sounds) {
        com.google.gson.JsonObject additions = new com.google.gson.JsonObject();
        additions.addProperty("sound", sounds.addition());
        additions.addProperty("tick_chance", sounds.additionChance());
        return additions;
    }

    /**
     * One track and the silence around it.
     *
     * Bedrock has no equivalent of Java's `replace_current_music`, and a biome the player walks
     * into waits for the current track to end, which is what false means.
     */
    private static com.google.gson.JsonObject music(
        at.ioexception.mcaddon.bedrock.MusicDefinitions track) {
        com.google.gson.JsonObject playing = new com.google.gson.JsonObject();
        playing.addProperty("sound", track.event());
        playing.addProperty("min_delay", track.minDelay());
        playing.addProperty("max_delay", track.maxDelay());
        playing.addProperty("replace_current_music", false);
        return playing;
    }

    //? if >=26 {
    /**
     * One camera location as the environment attributes a biome layer states it with.
     *
     * An attribute written as a bare value replaces whatever the dimension beneath it holds, which
     * is what a Bedrock fog does to the location it names.
     */
    private static void attribute(com.google.gson.JsonObject attributes, String name,
        at.ioexception.mcaddon.bedrock.FogDefinition.Location location) {
        if (!location.painted()) return;

        if (location.colour() >= 0) {
            attributes.addProperty("minecraft:visual/" + name + "_color", location.colour());
        }
        if (location.start() >= 0) {
            attributes.addProperty("minecraft:visual/" + name + "_start_distance", location.start());
        }
        if (location.end() >= 0) {
            attributes.addProperty("minecraft:visual/" + name + "_end_distance", location.end());
        }
    }
    //?}

    /** Grows a crop the way a mob's bone meal does. 26.3 names who is applying it. */
    public static void bonemeal(net.minecraft.world.level.block.BonemealableBlock crop, ServerLevel level,
        net.minecraft.util.RandomSource random, net.minecraft.core.BlockPos at,
        net.minecraft.world.level.block.state.BlockState state) {
        //? if >=26.3 {
        /*crop.performBonemeal(level, random, at, state, net.minecraft.world.level.block.BonemealSource.MOB);
        *///?} else {
        crop.performBonemeal(level, random, at, state);
        //?}
    }

    /** The structure templates a server saves and loads by name. */
    public static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
        structureTemplates(net.minecraft.server.MinecraftServer server) {
        //? if >=26.3 {
        /*return server.getStructureTemplateManager();
        *///?} else {
        return server.getStructureManager();
        //?}
    }

    /** Sends an entity's new velocity to its watchers on the next tick, which a push set from outside needs. */
    public static void syncVelocity(Entity entity) {
        //? if >=26.3 {
        /*entity.syncVelocity = true;
        *///?} else {
        entity.hurtMarked = true;
        //?}
    }

    /**
     * The ticks left in which an entity shrugs off another blow. From 26.3 a living entity counts
     * these in a damage cooldown of its own, and the entity's invulnerable time is a separate guard.
     */
    public static int invulnerableTime(Entity entity) {
        //? if >=26.3 {
        /*return entity instanceof LivingEntity living ? living.damageCooldownTime : entity.getInvulnerableTime();
        *///?} else {
        return entity.invulnerableTime;
        //?}
    }

    public static void setInvulnerableTime(Entity entity, int ticks) {
        //? if >=26.3 {
        /*if (entity instanceof LivingEntity living) living.damageCooldownTime = ticks;
        else entity.setInvulnerableTime(ticks);
        *///?} else {
        entity.invulnerableTime = ticks;
        //?}
    }

    /**
     * Swings an entity's arm and shows the swing to whoever watches it.
     *
     * From 26.3 a swing carries the animation of the item in that hand, the attack one for a blow and
     * the interaction one otherwise.
     */
    public static void swing(LivingEntity entity, net.minecraft.world.InteractionHand hand, boolean attack) {
        //? if >=26.3 {
        /*net.minecraft.world.item.ItemStack held = entity.getItemInHand(hand);
        entity.swing(hand, attack ? held.getAttackAnimation() : held.getInteractAnimation(), false);
        *///?} else {
        entity.swing(hand);
        //?}
    }

    public static boolean isSwinging(LivingEntity entity) {
        //? if >=26.3 {
        /*return entity.isSwinging();
        *///?} else {
        return entity.swinging;
        //?}
    }

    /**
     * Whether a block stops an entity moving through it.
     *
     * 26.3 answers this from the `blocks_motion` block tag; before it from the block being solid,
     * cobwebs and bamboo shoots aside.
     */
    public static boolean blocksMotion(net.minecraft.world.level.block.state.BlockState state) {
        //? if >=26.3 {
        /*return state.is(net.minecraft.tags.BlockTags.BLOCKS_MOTION);
        *///?} else {
        return state.blocksMotion();
        //?}
    }

    /** The blocks of a box, nearest first by Manhattan distance, out to the sum of the three sizes. */
    public static Iterable<net.minecraft.core.BlockPos> withinManhattan(net.minecraft.core.BlockPos centre,
        int x, int y, int z) {
        //? if >=26.3 {
        /*return net.minecraft.core.BlockPos.withinBoxByManhattanDistance(centre, x, y, z);
        *///?} else {
        return net.minecraft.core.BlockPos.withinManhattan(centre, x, y, z);
        //?}
    }

    /** Java's piston reactions, which 26.3 renamed after Bedrock's own. */
    //? if >=26.3 {
    /*public static final net.minecraft.world.level.material.PushReaction PUSH_PULL =
        net.minecraft.world.level.material.PushReaction.PUSH_PULL;
    public static final net.minecraft.world.level.material.PushReaction PUSH_ONLY =
        net.minecraft.world.level.material.PushReaction.PUSH;
    public static final net.minecraft.world.level.material.PushReaction POPPED =
        net.minecraft.world.level.material.PushReaction.POPPED;
    public static final net.minecraft.world.level.material.PushReaction IMMOVABLE =
        net.minecraft.world.level.material.PushReaction.IMMOVEABLE;
    public static final net.minecraft.world.level.material.PushReaction IGNORED =
        net.minecraft.world.level.material.PushReaction.IGNORE_ENTITY;
    *///?} else {
    public static final net.minecraft.world.level.material.PushReaction PUSH_PULL =
        net.minecraft.world.level.material.PushReaction.NORMAL;
    public static final net.minecraft.world.level.material.PushReaction PUSH_ONLY =
        net.minecraft.world.level.material.PushReaction.PUSH_ONLY;
    public static final net.minecraft.world.level.material.PushReaction POPPED =
        net.minecraft.world.level.material.PushReaction.DESTROY;
    public static final net.minecraft.world.level.material.PushReaction IMMOVABLE =
        net.minecraft.world.level.material.PushReaction.BLOCK;
    public static final net.minecraft.world.level.material.PushReaction IGNORED =
        net.minecraft.world.level.material.PushReaction.IGNORE;
    //?}

}
