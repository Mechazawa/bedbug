// The @minecraft/server surface, as far as this mod implements it.
//
// Written in JavaScript rather than as Java host objects for three reasons, all of which real packs
// depend on: packs monkey-patch Entity.prototype, wrapper identity per entity has to stay stable so
// what they stash there survives, and getters and instanceof are cheaper to express here.
//
// Everything that touches the game goes through `bridge`, a Java object of primitives and handles.
// An entity handle is its UUID string; a dimension handle is its id.

const bridge = globalThis.__mcaddon_bridge__;

function unsupported(member) {
    bridge.unsupported(member);
    return undefined;
}

export class Vector3 {
    constructor(x, y, z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}

function vector(triple) {
    return { x: triple[0], y: triple[1], z: triple[2] };
}

// A Java array reaches the guest as a host object with no JavaScript methods on it, so every list the
// bridge returns is copied into a real array before a pack can call `map` or `includes` on it.
function list(hostArray) {
    return hostArray === null || hostArray === undefined ? [] : Array.from(hostArray);
}

export const Direction = Object.freeze({
    Down: "Down", Up: "Up", North: "North", South: "South", West: "West", East: "East",
});

export const GameMode = Object.freeze({
    Survival: "survival", Creative: "creative", Adventure: "adventure", Spectator: "spectator",
});

export const EquipmentSlot = Object.freeze({
    Head: "Head", Chest: "Chest", Legs: "Legs", Feet: "Feet", Mainhand: "Mainhand",
    Offhand: "Offhand", Body: "Body",
});

export const EntityComponentTypes = Object.freeze({
    Health: "minecraft:health", Inventory: "minecraft:inventory", Equippable: "minecraft:equippable",
    IsBaby: "minecraft:is_baby", Item: "minecraft:item", Leashable: "minecraft:leashable",
    MarkVariant: "minecraft:mark_variant", Variant: "minecraft:variant", SkinId: "minecraft:skin_id",
    Projectile: "minecraft:projectile", Rideable: "minecraft:rideable", Riding: "minecraft:riding",
    TypeFamily: "minecraft:type_family", CursorInventory: "minecraft:cursor_inventory",
    Tameable: "minecraft:tameable", Npc: "minecraft:npc", Movement: "minecraft:movement",
});

export const ItemComponentTypes = Object.freeze({
    Durability: "minecraft:durability", Enchantable: "minecraft:enchantable",
    Food: "minecraft:food", Cooldown: "minecraft:cooldown",
});

export const ScriptEventSource = Object.freeze({
    Block: "Block", Entity: "Entity", NPCDialogue: "NPCDialogue", Server: "Server",
});

export const CommandPermissionLevel = Object.freeze({
    Any: 0, GameDirectors: 1, Admin: 2, Host: 3, Owner: 4,
});

export const CustomCommandStatus = Object.freeze({ Success: 0, Failure: 1 });

export const CustomCommandParamType = Object.freeze({
    Boolean: "Boolean", Integer: "Integer", Float: "Float", String: "String", Entity: "Entity",
    Player: "Player", Location: "Location", BlockType: "BlockType", ItemType: "ItemType",
    EntityType: "EntityType", EntitySelector: "EntitySelector", Enum: "Enum",
});

export const EntityInitializationCause = Object.freeze({
    Born: "Born", Event: "Event", Loaded: "Loaded", Spawned: "Spawned", Transformed: "Transformed",
});

export const EntityDamageCause = Object.freeze({
    anvil: "anvil", blockExplosion: "blockExplosion", campfire: "campfire", charging: "charging",
    contact: "contact", drowning: "drowning", entityAttack: "entityAttack",
    entityExplosion: "entityExplosion", fall: "fall", fallingBlock: "fallingBlock", fire: "fire",
    fireTick: "fireTick", fireworks: "fireworks", flyIntoWall: "flyIntoWall", freezing: "freezing",
    lava: "lava", lightning: "lightning", magic: "magic", magma: "magma", none: "none",
    override: "override", piston: "piston", projectile: "projectile", ramAttack: "ramAttack",
    selfDestruct: "selfDestruct", sonicBoom: "sonicBoom", soulCampfire: "soulCampfire",
    stalactite: "stalactite", stalagmite: "stalagmite", starve: "starve", suffocation: "suffocation",
    suicide: "suicide", temperature: "temperature", thorns: "thorns", void: "void", wither: "wither",
});

export const StructureRotation = Object.freeze({
    None: "None", Rotate90: "Rotate90", Rotate180: "Rotate180", Rotate270: "Rotate270",
});

export const StructureSaveMode = Object.freeze({ Memory: "Memory", World: "World" });

export class BlockVolume {
    constructor(from, to) {
        this.from = from;
        this.to = to;
    }
}

export class BlockPermutation {
    static resolve(type, states) {
        return new BlockPermutation(type, states ?? {});
    }

    constructor(type, states) {
        this.type = { id: type };
        this.#states = states;
    }

    #states;

    getState(name) {
        return this.#states[name];
    }

    getAllStates() {
        return { ...this.#states };
    }

    matches(type, states) {
        if (this.type.id !== type) return false;
        for (const key of Object.keys(states ?? {})) {
            if (this.#states[key] !== states[key]) return false;
        }
        return true;
    }
}

export class ItemStack {
    constructor(itemType, amount) {
        this.typeId = typeof itemType === "string" ? itemType : itemType?.id;
        this.amount = amount ?? 1;
        this.nameTag = undefined;
    }

    clone() {
        const copy = new ItemStack(this.typeId, this.amount);
        copy.nameTag = this.nameTag;
        return copy;
    }

    getComponent(component) {
        return unsupported("ItemStack.getComponent(" + component + ")");
    }

    getLore() {
        return [];
    }

    getTags() {
        return list(bridge.itemTags(this.typeId));
    }

    hasTag(tag) {
        return list(bridge.itemTags(this.typeId)).includes(tag);
    }
}

export class Block {
    constructor(dimensionId, x, y, z) {
        this.dimension = dimensionOf(dimensionId);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    get location() {
        return { x: this.x, y: this.y, z: this.z };
    }

    get center() {
        return { x: this.x + 0.5, y: this.y + 0.5, z: this.z + 0.5 };
    }

    get typeId() {
        return bridge.blockTypeId(this.dimension.id, this.x, this.y, this.z);
    }

    get type() {
        return { id: this.typeId };
    }

    get isAir() {
        return this.typeId === "minecraft:air";
    }

    get isLiquid() {
        return bridge.blockIsLiquid(this.dimension.id, this.x, this.y, this.z);
    }

    get permutation() {
        return BlockPermutation.resolve(this.typeId,
            JSON.parse(bridge.blockStates(this.dimension.id, this.x, this.y, this.z)));
    }

    getState(name) {
        return this.permutation.getState(name);
    }

    offset(delta) {
        return new Block(this.dimension.id, this.x + delta.x, this.y + delta.y, this.z + delta.z);
    }

    above(steps) {
        return this.offset({ x: 0, y: steps ?? 1, z: 0 });
    }

    below(steps) {
        return this.offset({ x: 0, y: -(steps ?? 1), z: 0 });
    }

    north(steps) {
        return this.offset({ x: 0, y: 0, z: -(steps ?? 1) });
    }

    south(steps) {
        return this.offset({ x: 0, y: 0, z: steps ?? 1 });
    }

    east(steps) {
        return this.offset({ x: steps ?? 1, y: 0, z: 0 });
    }

    west(steps) {
        return this.offset({ x: -(steps ?? 1), y: 0, z: 0 });
    }

    setType(type) {
        bridge.setBlockType(this.dimension.id, this.x, this.y, this.z,
            typeof type === "string" ? type : type?.id);
    }

    setPermutation(permutation) {
        this.setType(permutation.type.id);
    }

    getCapacity() {
        return 0;
    }
}

// Component classes. A pack imports these to name a type, and the objects `getComponent` hands back
// are plain objects, so these exist to satisfy the import and any `instanceof` written against them.
export class EntityComponent {
}

export class EntityHealthComponent extends EntityComponent {
}

export class EntityEquippableComponent extends EntityComponent {
}

export class EntityInventoryComponent extends EntityComponent {
}

export class EntityRideableComponent extends EntityComponent {
}

export class EntityRidingComponent extends EntityComponent {
}

export class EntityVariantComponent extends EntityComponent {
}

export class EntityMarkVariantComponent extends EntityComponent {
}

export class EntitySkinIdComponent extends EntityComponent {
}

export class EntityTypeFamilyComponent extends EntityComponent {
}

export class EntityIsBabyComponent extends EntityComponent {
}

export class EntityItemComponent extends EntityComponent {
}

export class EntityLeashableComponent extends EntityComponent {
}

export class EntityProjectileComponent extends EntityComponent {
}

export class ItemComponent {
}

export class ItemDurabilityComponent extends ItemComponent {
}

export class ItemEnchantableComponent extends ItemComponent {
}

export class ItemFoodComponent extends ItemComponent {
}

export class ItemCooldownComponent extends ItemComponent {
}

export class ContainerSlot {
}

export class Container {
}

export class Effect {
}

export class EffectType {
}

export class EntityQueryOptions {
}

const entityCache = new Map();

export class Entity {
    constructor(id) {
        this.id = id;
    }

    get typeId() {
        return bridge.entityTypeId(this.id);
    }

    get isValid() {
        return bridge.entityIsValid(this.id);
    }

    get dimension() {
        return dimensionOf(bridge.entityDimension(this.id));
    }

    get location() {
        return vector(bridge.entityLocation(this.id));
    }

    get nameTag() {
        return bridge.entityNameTag(this.id);
    }

    set nameTag(value) {
        bridge.setEntityNameTag(this.id, value ?? "");
    }

    get isSneaking() {
        return bridge.entityFlag(this.id, "sneaking");
    }

    get isOnGround() {
        return bridge.entityFlag(this.id, "onGround");
    }

    get isInWater() {
        return bridge.entityFlag(this.id, "inWater");
    }

    get isSleeping() {
        return bridge.entityFlag(this.id, "sleeping");
    }

    get isSwimming() {
        return bridge.entityFlag(this.id, "swimming");
    }

    get isClimbing() {
        return bridge.entityFlag(this.id, "climbing");
    }

    get isFalling() {
        return bridge.entityFlag(this.id, "falling");
    }

    getHeadLocation() {
        return vector(bridge.entityHeadLocation(this.id));
    }

    getRotation() {
        const rotation = bridge.entityRotation(this.id);
        return { x: rotation[0], y: rotation[1] };
    }

    setRotation(rotation) {
        bridge.setEntityRotation(this.id, rotation.x, rotation.y);
    }

    getViewDirection() {
        return vector(bridge.entityViewDirection(this.id));
    }

    getVelocity() {
        return vector(bridge.entityVelocity(this.id));
    }

    applyImpulse(vectorValue) {
        bridge.applyImpulse(this.id, vectorValue.x, vectorValue.y, vectorValue.z);
    }

    clearVelocity() {
        bridge.applyImpulse(this.id, 0, 0, 0);
    }

    teleport(location, options) {
        bridge.teleport(this.id, location.x, location.y, location.z,
            options?.dimension?.id ?? null,
            options?.rotation?.x ?? null, options?.rotation?.y ?? null);
    }

    tryTeleport(location, options) {
        this.teleport(location, options);
        return true;
    }

    remove() {
        entityCache.delete(this.id);
        bridge.removeEntity(this.id);
    }

    kill() {
        return bridge.killEntity(this.id);
    }

    hasTag(tag) {
        return list(bridge.entityTags(this.id)).includes(tag);
    }

    addTag(tag) {
        return bridge.addEntityTag(this.id, tag);
    }

    removeTag(tag) {
        return bridge.removeEntityTag(this.id, tag);
    }

    getTags() {
        return list(bridge.entityTags(this.id));
    }

    matches(options) {
        return bridge.entityMatches(this.id, JSON.stringify(options ?? {}));
    }

    runCommand(command) {
        return { successCount: bridge.runCommandAs(this.id, command) };
    }

    runCommandAsync(command) {
        return Promise.resolve(this.runCommand(command));
    }

    playSound(soundId, options) {
        bridge.playSoundAt(this.id, soundId, options?.volume ?? 1, options?.pitch ?? 1);
    }

    playAnimation(animation) {
        bridge.playAnimation(this.id, animation);
    }

    getProperty(name) {
        return bridge.entityProperty(this.id, name);
    }

    setProperty(name, value) {
        bridge.setEntityProperty(this.id, name, value);
    }

    getDynamicProperty(name) {
        return bridge.entityDynamicProperty(this.id, name);
    }

    setDynamicProperty(name, value) {
        bridge.setEntityDynamicProperty(this.id, name, value === undefined ? null : value);
    }

    getDynamicPropertyIds() {
        return list(bridge.entityDynamicPropertyIds(this.id));
    }

    clearDynamicProperties() {
        for (const name of this.getDynamicPropertyIds()) this.setDynamicProperty(name, undefined);
    }

    triggerEvent(event) {
        bridge.triggerEntityEvent(this.id, event);
    }

    hasComponent(component) {
        return bridge.entityHasComponent(this.id, normaliseComponent(component));
    }

    getComponent(component) {
        const name = normaliseComponent(component);
        if (!bridge.entityHasComponent(this.id, name)) return undefined;
        return componentFor(this, name);
    }

    getComponents() {
        return list(bridge.entityComponents(this.id)).map(name => componentFor(this, name));
    }

    getEffects() {
        return [];
    }

    getEffect() {
        return undefined;
    }

    addEffect(type, duration, options) {
        bridge.addEffect(this.id, typeof type === "string" ? type : type?.id, duration,
            options?.amplifier ?? 0, options?.showParticles !== false);
    }

    removeEffect(type) {
        return bridge.removeEffect(this.id, typeof type === "string" ? type : type?.id);
    }

    getRiders() {
        return list(bridge.entityRiders(this.id)).map(entityFor);
    }

    addRider(rider) {
        return bridge.addRider(this.id, rider.id);
    }

    ejectRider(rider) {
        bridge.ejectRider(this.id, rider.id);
    }

    ejectRiders() {
        for (const rider of this.getRiders()) this.ejectRider(rider);
    }

    applyDamage(amount, options) {
        return bridge.applyDamage(this.id, amount, options?.cause ?? "none",
            options?.damagingEntity?.id ?? null);
    }
}

export class Player extends Entity {
    get name() {
        return bridge.playerName(this.id);
    }

    get selectedSlotIndex() {
        return bridge.playerSelectedSlot(this.id);
    }

    get onScreenDisplay() {
        const id = this.id;
        return {
            setActionBar(text) {
                bridge.setActionBar(id, rawText(text));
            },
            setTitle(text) {
                bridge.setTitle(id, rawText(text));
            },
            updateSubtitle(text) {
                bridge.setSubtitle(id, rawText(text));
            },
        };
    }

    sendMessage(message) {
        bridge.sendMessageTo(this.id, rawText(message));
    }

    getGameMode() {
        return bridge.playerGameMode(this.id);
    }

    setGameMode(mode) {
        bridge.setPlayerGameMode(this.id, mode);
    }

    playSound(soundId, options) {
        bridge.playSoundAt(this.id, soundId, options?.volume ?? 1, options?.pitch ?? 1);
    }

    getSpawnPoint() {
        return undefined;
    }
}

/** Bedrock accepts a string, a RawMessage, or a list of either, wherever text is taken. */
function rawText(message) {
    if (typeof message === "string") return message;
    if (Array.isArray(message)) return message.map(rawText).join("");
    if (message?.text !== undefined) return message.text;
    if (message?.translate !== undefined) return bridge.translate(message.translate);
    if (message?.rawtext !== undefined) return rawText(message.rawtext);
    return String(message);
}

function normaliseComponent(component) {
    const name = typeof component === "string" ? component : String(component);
    return name.includes(":") ? name : "minecraft:" + name;
}

/** Only the components real packs read are modelled; the rest report themselves as unsupported. */
function componentFor(entity, name) {
    switch (name) {
        case "minecraft:health":
            return {
                typeId: name, entity,
                get currentValue() {
                    return bridge.entityHealth(entity.id);
                },
                get effectiveMax() {
                    return bridge.entityMaxHealth(entity.id);
                },
                setCurrentValue(value) {
                    bridge.setEntityHealth(entity.id, value);
                },
                resetToMaxValue() {
                    bridge.setEntityHealth(entity.id, bridge.entityMaxHealth(entity.id));
                },
            };
        case "minecraft:is_baby":
        case "minecraft:is_sheared":
        case "minecraft:is_tamed":
        case "minecraft:is_angry":
            return { typeId: name, entity };
        case "minecraft:variant":
            return { typeId: name, entity, value: bridge.entityProperty(entity.id, "variant") ?? 0 };
        case "minecraft:mark_variant":
            return { typeId: name, entity, value: bridge.entityProperty(entity.id, "mark_variant") ?? 0 };
        case "minecraft:skin_id":
            return { typeId: name, entity, value: bridge.entityProperty(entity.id, "skin_id") ?? 0 };
        case "minecraft:type_family":
            return {
                typeId: name, entity,
                getTypeFamilies() {
                    return list(bridge.entityFamilies(entity.id));
                },
                hasTypeFamily(family) {
                    return list(bridge.entityFamilies(entity.id)).includes(family);
                },
            };
        case "minecraft:riding":
            return { typeId: name, entity, get entity() {
                const vehicle = bridge.entityVehicle(entity.id);
                return vehicle === null ? undefined : entityFor(vehicle);
            } };
        case "minecraft:equippable":
            return {
                typeId: name, entity,
                getEquipment(slot) {
                    const item = bridge.equipment(entity.id, slot);
                    return item === null ? undefined : itemFrom(JSON.parse(item));
                },
                setEquipment(slot, item) {
                    return bridge.setEquipment(entity.id, slot, item?.typeId ?? null, item?.amount ?? 1);
                },
            };
        default:
            return { typeId: name, entity };
    }
}

function itemFrom(handle) {
    const stack = new ItemStack(handle.typeId, handle.amount);
    stack.nameTag = handle.nameTag === "" ? undefined : handle.nameTag;
    return stack;
}

export function entityFor(id) {
    if (id === null || id === undefined) return undefined;
    let wrapper = entityCache.get(id);
    if (wrapper === undefined) {
        wrapper = bridge.entityIsPlayer(id) ? new Player(id) : new Entity(id);
        entityCache.set(id, wrapper);
    }
    return wrapper;
}

const dimensionCache = new Map();

export class Dimension {
    constructor(id) {
        this.id = id;
    }

    get heightRange() {
        const range = bridge.dimensionHeightRange(this.id);
        return { min: range[0], max: range[1] };
    }

    getEntities(options) {
        return list(bridge.dimensionEntities(this.id, JSON.stringify(options ?? {}))).map(entityFor);
    }

    getPlayers(options) {
        return this.getEntities({ ...(options ?? {}), type: "minecraft:player" });
    }

    getEntitiesAtBlockLocation(location) {
        return this.getEntities({ location, maxDistance: 1 });
    }

    getBlock(location) {
        return bridge.isChunkLoaded(this.id, location.x, location.z)
            ? new Block(this.id, Math.floor(location.x), Math.floor(location.y), Math.floor(location.z))
            : undefined;
    }

    getTopmostBlock(location) {
        const y = bridge.topmostBlockY(this.id, Math.floor(location.x), Math.floor(location.z));
        return y === null ? undefined : new Block(this.id, Math.floor(location.x), y, Math.floor(location.z));
    }

    getBlockFromRay() {
        return unsupported("Dimension.getBlockFromRay");
    }

    isChunkLoaded(location) {
        return bridge.isChunkLoaded(this.id, location.x ?? location.from?.x ?? 0, location.z ?? location.from?.z ?? 0);
    }

    setBlockType(location, type) {
        bridge.setBlockType(this.id, Math.floor(location.x), Math.floor(location.y), Math.floor(location.z),
            typeof type === "string" ? type : type?.id);
    }

    spawnEntity(typeId, location) {
        const id = bridge.spawnEntity(this.id, typeId, location.x, location.y, location.z);
        if (id === null) throw new Error("Could not spawn " + typeId);
        return entityFor(id);
    }

    spawnItem(itemStack, location) {
        const id = bridge.spawnItem(this.id, itemStack.typeId, itemStack.amount,
            location.x, location.y, location.z);
        return id === null ? undefined : entityFor(id);
    }

    playSound(soundId, location, options) {
        bridge.playSoundAtLocation(this.id, soundId, location.x, location.y, location.z,
            options?.volume ?? 1, options?.pitch ?? 1);
    }

    runCommand(command) {
        return { successCount: bridge.runCommandIn(this.id, command) };
    }

    runCommandAsync(command) {
        return Promise.resolve(this.runCommand(command));
    }
}

function dimensionOf(id) {
    let dimension = dimensionCache.get(id);
    if (dimension === undefined) {
        dimension = new Dimension(id);
        dimensionCache.set(id, dimension);
    }
    return dimension;
}

export const DimensionTypes = Object.freeze({
    get(id) {
        return list(bridge.dimensionIds()).includes(id) ? { typeId: id } : undefined;
    },
    getAll() {
        return list(bridge.dimensionIds()).map(id => ({ typeId: id }));
    },
});

class EventSignal {
    #listeners = [];

    subscribe(callback, options) {
        this.#listeners.push({ callback, options });
        return callback;
    }

    unsubscribe(callback) {
        const index = this.#listeners.findIndex(listener => listener.callback === callback);
        if (index >= 0) this.#listeners.splice(index, 1);
    }

    get size() {
        return this.#listeners.length;
    }

    emit(event) {
        for (const listener of this.#listeners.slice()) {
            try {
                listener.callback(event);
            } catch (error) {
                bridge.reportError(String(error && error.stack ? error.stack : error));
            }
        }
        return event;
    }
}

/** Every event name the mod can raise. An unknown name still gets a signal, so subscribing is safe. */
function eventGroup() {
    return new Proxy({}, {
        get(target, name) {
            if (typeof name !== "string") return undefined;
            if (target[name] === undefined) target[name] = new EventSignal();
            return target[name];
        },
    });
}

export class World {
    #afterEvents = eventGroup();
    #beforeEvents = eventGroup();

    get afterEvents() {
        return this.#afterEvents;
    }

    get beforeEvents() {
        return this.#beforeEvents;
    }

    get gameRules() {
        return new Proxy({}, {
            get(target, name) {
                return bridge.gameRule(String(name));
            },
            set(target, name, value) {
                bridge.setGameRule(String(name), value);
                return true;
            },
        });
    }

    get isHardcore() {
        return bridge.isHardcore();
    }

    getDimension(id) {
        const full = id.includes(":") ? id : "minecraft:" + id;
        if (!list(bridge.dimensionIds()).includes(full)) throw new Error("Unknown dimension " + id);
        return dimensionOf(full);
    }

    getAllPlayers() {
        return list(bridge.players()).map(entityFor);
    }

    getPlayers(options) {
        const players = this.getAllPlayers();
        return options === undefined ? players : players.filter(player => player.matches(options));
    }

    getEntity(id) {
        return bridge.entityIsValid(id) ? entityFor(id) : undefined;
    }

    sendMessage(message) {
        bridge.broadcastMessage(rawText(message));
    }

    playSound(soundId, location, options) {
        this.getDimension("overworld").playSound(soundId, location, options);
    }

    getTimeOfDay() {
        return bridge.timeOfDay();
    }

    setTimeOfDay(time) {
        bridge.setTimeOfDay(typeof time === "number" ? time : 0);
    }

    getDay() {
        return bridge.day();
    }

    getAbsoluteTime() {
        return bridge.absoluteTime();
    }

    getMoonPhase() {
        return bridge.moonPhase();
    }

    getDefaultSpawnLocation() {
        return vector(bridge.defaultSpawnLocation());
    }

    getDynamicProperty(name) {
        return bridge.worldDynamicProperty(name);
    }

    setDynamicProperty(name, value) {
        bridge.setWorldDynamicProperty(name, value === undefined ? null : value);
    }

    getDynamicPropertyIds() {
        return list(bridge.worldDynamicPropertyIds());
    }

    clearDynamicProperties() {
        for (const name of this.getDynamicPropertyIds()) this.setDynamicProperty(name, undefined);
    }

    getDynamicPropertyTotalByteCount() {
        return bridge.worldDynamicPropertyBytes();
    }

    broadcastClientMessage() {
        return unsupported("World.broadcastClientMessage");
    }

    get scoreboard() {
        return {
            getObjective() {
                return unsupported("Scoreboard.getObjective");
            },
            getObjectives() {
                return [];
            },
            addObjective() {
                return unsupported("Scoreboard.addObjective");
            },
        };
    }

    get structureManager() {
        return {
            get() {
                return unsupported("StructureManager.get");
            },
            place() {
                return unsupported("StructureManager.place");
            },
        };
    }
}

export const world = new World();

/**
 * Scheduling.
 *
 * Every queued callback runs from the server tick, in insertion order, and the pump stops early once
 * the tick budget is spent so a pack cannot stall the server indefinitely. A job is a generator the
 * pump advances while there is budget left.
 */
class ScriptSystem {
    #next = 1;
    #runs = new Map();
    #jobs = new Map();
    #afterEvents = eventGroup();
    #beforeEvents = eventGroup();

    get currentTick() {
        return bridge.currentTick();
    }

    get afterEvents() {
        return this.#afterEvents;
    }

    get beforeEvents() {
        return this.#beforeEvents;
    }

    run(callback) {
        return this.runTimeout(callback, 0);
    }

    runTimeout(callback, ticks) {
        const handle = this.#next++;
        this.#runs.set(handle, { callback, due: this.currentTick + Math.max(0, ticks ?? 0), every: 0 });
        return handle;
    }

    runInterval(callback, ticks) {
        const handle = this.#next++;
        const every = Math.max(1, ticks ?? 1);
        this.#runs.set(handle, { callback, due: this.currentTick + every, every });
        return handle;
    }

    clearRun(handle) {
        this.#runs.delete(handle);
        this.#jobs.delete(handle);
    }

    runJob(generator) {
        const handle = this.#next++;
        this.#jobs.set(handle, generator);
        return handle;
    }

    clearJob(handle) {
        this.#jobs.delete(handle);
    }

    waitTicks(ticks) {
        return new Promise(resolve => this.runTimeout(resolve, ticks));
    }

    /** Called once per server tick from Java. */
    pump(tick) {
        for (const [handle, run] of [...this.#runs]) {
            if (run.due > tick) continue;
            if (run.every > 0) {
                run.due = tick + run.every;
            } else {
                this.#runs.delete(handle);
            }
            try {
                run.callback();
            } catch (error) {
                bridge.reportError(String(error && error.stack ? error.stack : error));
            }
            if (bridge.outOfBudget()) return;
        }

        for (const [handle, job] of [...this.#jobs]) {
            try {
                while (!bridge.outOfBudget()) {
                    if (job.next().done) {
                        this.#jobs.delete(handle);
                        break;
                    }
                }
            } catch (error) {
                this.#jobs.delete(handle);
                bridge.reportError(String(error && error.stack ? error.stack : error));
            }
            if (bridge.outOfBudget()) return;
        }
    }
}

export const system = new ScriptSystem();

/**
 * The commands a pack registers during startup.
 *
 * Bedrock hands the registry to the startup event and expects commands to exist from then on. Java
 * builds its command tree before a pack's script has run, so what a pack registers is collected here
 * and added to the live dispatcher once the script has loaded.
 */
const customCommands = [];
const customEnums = new Map();

// What Java calls into. Kept off the module exports so a pack cannot reach it through an import.
globalThis.__mcaddon_runtime__ = {
    tick(tick) {
        system.pump(tick);
    },
    entityFor,
    dimensionOf,
    worldAfter(name) {
        return world.afterEvents[name];
    },
    worldBefore(name) {
        return world.beforeEvents[name];
    },
    systemAfter(name) {
        return system.afterEvents[name];
    },
    systemBefore(name) {
        return system.beforeEvents[name];
    },

    /** Raises the startup event and returns what the pack registered on it, as JSON for Java. */
    startup() {
        const event = {
            customCommandRegistry: {
                registerEnum(name, values) {
                    customEnums.set(name, Array.from(values ?? []));
                },
                registerCommand(spec, callback) {
                    customCommands.push({ spec, callback });
                },
            },
            blockComponentRegistry: {
                registerCustomComponent(name) {
                    bridge.unsupported("blockComponentRegistry.registerCustomComponent(" + name + ")");
                },
            },
            itemComponentRegistry: {
                registerCustomComponent(name) {
                    bridge.unsupported("itemComponentRegistry.registerCustomComponent(" + name + ")");
                },
            },
        };
        system.beforeEvents.startup.emit(event);

        return JSON.stringify(customCommands.map(entry => ({
            name: entry.spec?.name ?? "",
            description: entry.spec?.description ?? "",
            permissionLevel: entry.spec?.permissionLevel ?? 0,
            cheatsRequired: entry.spec?.cheatsRequired !== false,
            mandatory: (entry.spec?.mandatoryParameters ?? []).map(parameter),
            optional: (entry.spec?.optionalParameters ?? []).map(parameter),
        })));
    },

    /** Runs one registered command; Java passes the origin and the parsed arguments as JSON. */
    runCommand(index, originJson, argumentsJson) {
        const entry = customCommands[index];
        if (entry === undefined) return JSON.stringify({ status: 1, message: "" });

        const origin = JSON.parse(originJson);
        const resolved = {
            sourceEntity: origin.entity === null ? undefined : entityFor(origin.entity),
            sourceBlock: undefined,
            sourceType: origin.type,
            initiator: undefined,
        };

        try {
            const outcome = entry.callback(resolved, ...JSON.parse(argumentsJson));
            return JSON.stringify({
                status: outcome?.status ?? 0,
                message: outcome?.message === undefined ? "" : rawText(outcome.message),
            });
        } catch (error) {
            bridge.reportError(String(error && error.stack ? error.stack : error));
            return JSON.stringify({ status: 1, message: "" });
        }
    },

    /** The values of an enum a pack registered, so Java can offer them as command completions. */
    enumValues(name) {
        return JSON.stringify(customEnums.get(name) ?? []);
    },
};

/** A parameter declaration, reduced to what Java needs to build a Brigadier argument. */
function parameter(declaration) {
    if (typeof declaration === "string") return { name: declaration, type: "String" };
    return { name: declaration?.name ?? "", type: declaration?.type ?? "String" };
}
