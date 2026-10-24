package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.state.WorldAddonList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Takes the content of the add-ons a world's list turns off out of that world, as Bedrock does.
 *
 * Such an add-on stays registered, so its blocks, entities and items load. As each chunk and entity
 * loads, its blocks become air, its entities are discarded and its items are taken out of every
 * inventory, ender chest, container, item frame, dropped stack, piece of equipment and shulker box
 * contents. What is taken is gone for good.
 *
 * It is worked out per server from the list that server opened its world with, so a client visiting
 * another server, and a server handing an add-on to a guest as inactive, take nothing.
 * The work runs at the end of the server tick: a chunk or an entity is still being added to its
 * level when its load event is raised.
 */
public final class RemovedAddons {
    private static final Map<MinecraftServer, RemovedAddons> BY_SERVER = new ConcurrentHashMap<>();
    private static final int CONTAINER_SLOTS = 256;

    private final Set<Block> blocks;
    private final Set<Item> items;
    private final Set<EntityType<?>> types;
    private final List<Entity> loadedEntities = new ArrayList<>();
    private final List<LoadedChunk> loadedChunks = new ArrayList<>();

    private record LoadedChunk(ServerLevel level, ChunkPos pos) {}

    private RemovedAddons(Set<Block> blocks, Set<Item> items, Set<EntityType<?>> types) {
        this.blocks = blocks;
        this.items = items;
        this.types = types;
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            RemovedAddons removed = tryFrom(ActiveAddons.world(), ActiveAddons.installed());
            if (removed != null) BY_SERVER.put(server, removed);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(BY_SERVER::remove);
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            RemovedAddons removed = BY_SERVER.get(level.getServer());
            if (removed != null && removed.holdsAny(chunk)) {
                removed.loadedChunks.add(new LoadedChunk(level, chunk.getPos()));
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            RemovedAddons removed = BY_SERVER.get(level.getServer());
            if (removed != null) removed.loadedEntities.add(entity);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RemovedAddons removed = BY_SERVER.get(server);
            if (removed != null) removed.sweep();
        });
    }

    /**
     * What a world playing `list` takes out of the add-ons installed beside it, or nothing when the
     * list turns none of them off. An object an add-on that stays on also declares is kept.
     */
    private static RemovedAddons tryFrom(WorldAddonList list, AddonSet installed) {
        Set<UUID> on = list.resolve(installed).converted().stream()
            .map(one -> one.pack().manifest().uuid())
            .collect(Collectors.toSet());
        AddonSet off = installed.only(one -> !on.contains(one.pack().manifest().uuid()));
        if (off.converted().isEmpty()) return null;

        AddonSet kept = installed.only(one -> on.contains(one.pack().manifest().uuid()));
        Set<String> keptIds = shapes(kept).map(AddonRegistry.Shape::key).collect(Collectors.toSet());
        List<AddonRegistry.Shape> gone = shapes(off).filter(shape -> !keptIds.contains(shape.key())).toList();

        Set<Block> blocks = ofKind(gone, "block").map(BuiltInRegistries.BLOCK::getValue)
            .filter(block -> block != Blocks.AIR).collect(Collectors.toSet());
        Set<EntityType<?>> types = ofKind(gone, "entity")
            .flatMap(id -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).stream())
            .collect(Collectors.toSet());
        Set<Item> items = Stream.of(ofKind(gone, "item"), ofKind(gone, "block"),
                ofKind(gone, "entity").map(id -> Platform.id(id.getNamespace(), id.getPath() + "_spawn_egg")))
            .flatMap(ids -> ids)
            .filter(AddonRegistry::registeredItem)
            .map(BuiltInRegistries.ITEM::getValue)
            .collect(Collectors.toSet());
        McAddon.LOGGER.info("This world turns off {}; their {} blocks, {} entity types and {} items are removed "
                + "as they load.", off.converted().stream().map(ConvertedPack::displayName).toList(),
            blocks.size(), types.size(), items.size());
        return new RemovedAddons(blocks, items, types);
    }

    private static Stream<AddonRegistry.Shape> shapes(AddonSet set) {
        return Stream.of(AddonEntities.shapes(set.packs()), AddonBlocks.shapes(set.packs()),
                AddonItems.shapes(set.packs()))
            .flatMap(List::stream);
    }

    /** The identifiers of one kind, leaving out the game's own, which a pack may describe but never adds. */
    private static Stream<Identifier> ofKind(List<AddonRegistry.Shape> shapes, String kind) {
        return shapes.stream()
            .filter(shape -> shape.kind().equals(kind))
            .map(shape -> Names.split(shape.identifier(), "minecraft"))
            .filter(split -> !split.namespace().isEmpty() && !split.path().isEmpty()
                && !split.namespace().equals("minecraft"))
            .map(split -> Platform.id(split.namespace(), split.path()));
    }

    /** Whether a chunk holds a removed block or a container, judged from each section's palette. */
    private boolean holdsAny(LevelChunk chunk) {
        return chunk.getBlockEntities().values().stream().anyMatch(Container.class::isInstance)
            || Stream.of(chunk.getSections()).anyMatch(section -> !section.hasOnlyAir()
                && section.maybeHas(state -> blocks.contains(state.getBlock())));
    }

    private void sweep() {
        List<LoadedChunk> chunks = List.copyOf(loadedChunks);
        loadedChunks.clear();
        chunks.forEach(this::clear);
        List<Entity> entities = List.copyOf(loadedEntities);
        loadedEntities.clear();
        entities.stream().filter(entity -> !entity.isRemoved()).forEach(this::clear);
    }

    private void clear(LoadedChunk loaded) {
        LevelChunk chunk = loaded.level().getChunkSource().getChunkNow(loaded.pos().x(), loaded.pos().z());
        if (chunk == null) return;

        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> blocks.contains(state.getBlock()))) continue;
            int bottom = chunk.getSectionYFromSectionIndex(index) * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!blocks.contains(section.getBlockState(x, y, z).getBlock())) continue;
                        BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, bottom + y,
                            chunk.getPos().getMinBlockZ() + z);
                        loaded.level().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        List.copyOf(chunk.getBlockEntities().values()).stream()
            .filter(Container.class::isInstance)
            .map(Container.class::cast)
            .forEach(this::clear);
    }

    private void clear(Entity entity) {
        if (types.contains(entity.getType()) || entity instanceof ItemEntity dropped && removes(dropped.getItem())) {
            entity.discard();
            return;
        }
        if (entity instanceof ItemFrame frame) {
            if (removes(frame.getItem())) frame.setItem(ItemStack.EMPTY);
            else clearContents(frame.getItem());
        }
        if (entity instanceof LivingEntity living) {
            EquipmentSlot.VALUES.forEach(slot -> {
                ItemStack worn = living.getItemBySlot(slot);
                if (removes(worn)) living.setItemSlot(slot, ItemStack.EMPTY);
                else clearContents(worn);
            });
        }
        if (entity instanceof Player player) {
            clear(player.getInventory());
            clear(player.getEnderChestInventory());
        }
        if (entity instanceof InventoryCarrier carrier) clear(carrier.getInventory());
        if (entity instanceof Container container) clear(container);
    }

    private void clear(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (removes(stack)) {
                container.setItem(slot, ItemStack.EMPTY);
            } else {
                clearContents(stack);
            }
        }
    }

    /** Takes removed items out of what a shulker box holds. */
    private void clearContents(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return;

        NonNullList<ItemStack> held = NonNullList.withSize(CONTAINER_SLOTS, ItemStack.EMPTY);
        contents.copyInto(held);
        if (held.stream().noneMatch(this::removes)) return;

        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
            held.stream().map(one -> removes(one) ? ItemStack.EMPTY : one).toList()));
    }

    private boolean removes(ItemStack stack) {
        return !stack.isEmpty() && items.contains(stack.getItem());
    }
}
