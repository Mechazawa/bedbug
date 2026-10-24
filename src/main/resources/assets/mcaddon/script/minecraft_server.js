// The @minecraft/server surface, as far as this mod implements it.
//
// Written in JavaScript rather than as Java host objects for three reasons, all of which real packs
// depend on: packs monkey-patch Entity.prototype, wrapper identity per entity has to stay stable so
// what they stash there survives, and getters and instanceof are cheaper to express here.
//
// Everything that touches the game goes through `bridge`, a Java object of primitives and handles.
// An entity handle is its UUID string; a dimension handle is its id.

const bridge = globalThis.__mcaddon_bridge__;

function unsupported(member, answer) {
    bridge.unsupported(member);
    return answer;
}

export class Vector3 {
    constructor(x, y, z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}

// A dynamic property holds a boolean, a number, a string or a Vector3, which crosses as a triple.
function storedProperty(value) {
    if (value === null || value === undefined) return undefined;
    return typeof value === "object" ? new Vector3(...list(value)) : value;
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
    Survival: "Survival", Creative: "Creative", Adventure: "Adventure", Spectator: "Spectator",
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
    Book: "minecraft:book", Compostable: "minecraft:compostable", Cooldown: "minecraft:cooldown",
    Durability: "minecraft:durability", Dyeable: "minecraft:dyeable",
    Enchantable: "minecraft:enchantable", Food: "minecraft:food", Inventory: "minecraft:inventory",
    Potion: "minecraft:potion",
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
    lava: "lava", lightning: "lightning", maceSmash: "maceSmash", magic: "magic", magma: "magma", none: "none",
    override: "override", piston: "piston", projectile: "projectile", ramAttack: "ramAttack",
    selfDestruct: "selfDestruct", sonicBoom: "sonicBoom", soulCampfire: "soulCampfire",
    stalactite: "stalactite", stalagmite: "stalagmite", starve: "starve", suffocation: "suffocation",
    suicide: "suicide", temperature: "temperature", thorns: "thorns", void: "void", wither: "wither",
});

export const StructureRotation = Object.freeze({
    None: "None", Rotate90: "Rotate90", Rotate180: "Rotate180", Rotate270: "Rotate270",
});

export const StructureSaveMode = Object.freeze({ Memory: "Memory", World: "World" });

export const CameraShakeType = Object.freeze({ Positional: "Positional", Rotational: "Rotational" });

/** Java's own `/clone` modes are `normal`, `force` and `move`, in the order these number them. */
export const CloneMode = Object.freeze({ Copy: 0, ForceCopy: 1, Move: 2 });

function blockPosition(location) {
    return { x: Math.floor(location.x), y: Math.floor(location.y), z: Math.floor(location.z) };
}

function corner(from, to, pick) {
    const one = blockPosition(from);
    const other = blockPosition(to);
    return { x: pick(one.x, other.x), y: pick(one.y, other.y), z: pick(one.z, other.z) };
}

function moved(location, delta) {
    return { x: location.x + delta.x, y: location.y + delta.y, z: location.z + delta.z };
}

function samePosition(one, other) {
    return one.x === other.x && one.y === other.y && one.z === other.z;
}

function* between(min, max) {
    for (let x = min.x; x <= max.x; x++) {
        for (let y = min.y; y <= max.y; y++) {
            for (let z = min.z; z <= max.z; z++) yield { x, y, z };
        }
    }
}

/** The positions of a volume, handed out one at a time so a large region costs nothing to ask for. */
export class BlockLocationIterator {
    #positions;

    constructor(positions) {
        this.#positions = positions[Symbol.iterator]();
    }

    [Symbol.iterator]() {
        return this;
    }

    next() {
        return this.#positions.next();
    }
}

/** What every volume answers, worked out from the two corners its own shape reports. */
export class BlockVolumeBase {
    /** The three a shape supplies. A base volume carries no positions, so it has no corners. */
    getMin() {
        return unsupported("BlockVolumeBase.getMin");
    }

    getMax() {
        return unsupported("BlockVolumeBase.getMax");
    }

    translate() {
        return unsupported("BlockVolumeBase.translate");
    }

    getSpan() {
        const min = this.getMin();
        const max = this.getMax();
        return { x: max.x - min.x + 1, y: max.y - min.y + 1, z: max.z - min.z + 1 };
    }

    getCapacity() {
        const span = this.getSpan();
        return span.x * span.y * span.z;
    }

    getBlockLocationIterator() {
        return new BlockLocationIterator(between(this.getMin(), this.getMax()));
    }

    isInside(location) {
        const at = blockPosition(location);
        const min = this.getMin();
        const max = this.getMax();
        return at.x >= min.x && at.x <= max.x && at.y >= min.y && at.y <= max.y
            && at.z >= min.z && at.z <= max.z;
    }

    /** The `count` positions nearest the location, nearest first. */
    getClosest(count, location) {
        return this.#byDistance(count, location, 1);
    }

    /** The `count` positions farthest from the location, farthest first. */
    getFarthest(count, location) {
        return this.#byDistance(count, location, -1);
    }

    /** Each position is measured from its own corner, which is the position the volume hands back. */
    #byDistance(count, location, direction) {
        if (!Number.isInteger(count) || count < 0) {
            throw new RangeError("A count of positions has to be a whole number of at least zero.");
        }
        const away = at => (at.x - location.x) ** 2 + (at.y - location.y) ** 2
            + (at.z - location.z) ** 2;
        return Array.from(this.getBlockLocationIterator())
            .sort((one, other) => direction * (away(one) - away(other)))
            .slice(0, count);
    }
}

export class BlockVolume extends BlockVolumeBase {
    constructor(from, to) {
        super();
        this.from = from;
        this.to = to;
    }

    getMin() {
        return corner(this.from, this.to, Math.min);
    }

    getMax() {
        return corner(this.from, this.to, Math.max);
    }

    translate(delta) {
        this.from = moved(this.from, delta);
        this.to = moved(this.to, delta);
    }

    /** Disjoint, contained whole, or overlapping, which is the three Bedrock reports. */
    intersects(other) {
        const min = this.getMin();
        const max = this.getMax();
        const otherMin = other.getMin();
        const otherMax = other.getMax();
        if (otherMin.x > max.x || otherMax.x < min.x || otherMin.y > max.y || otherMax.y < min.y
            || otherMin.z > max.z || otherMax.z < min.z) {
            return BlockVolumeIntersection.Disjoint;
        }
        return this.isInside(otherMin) && this.isInside(otherMax)
            ? BlockVolumeIntersection.Contains : BlockVolumeIntersection.Intersects;
    }

    /** A position inside the volume and on one of its outer planes rather than in the middle. */
    doesLocationTouchFaces(location) {
        const at = blockPosition(location);
        const min = this.getMin();
        const max = this.getMax();
        return this.isInside(at) && (at.x === min.x || at.x === max.x || at.y === min.y
            || at.y === max.y || at.z === min.z || at.z === max.z);
    }

    /** Overlapping without sitting clear of every outer plane, which is what touching a face is. */
    doesVolumeTouchFaces(other) {
        if (this.intersects(other) === BlockVolumeIntersection.Disjoint) return false;

        const min = this.getMin();
        const max = this.getMax();
        const otherMin = other.getMin();
        const otherMax = other.getMax();
        return otherMin.x <= min.x || otherMax.x >= max.x || otherMin.y <= min.y
            || otherMax.y >= max.y || otherMin.z <= min.z || otherMax.z >= max.z;
    }
}

/** The positions a fill or a search touched, which is how Bedrock hands a scattered region back. */
export class ListBlockVolume extends BlockVolumeBase {
    #positions;

    constructor(locations) {
        super();
        this.#positions = (locations ?? []).map(blockPosition);
    }

    add(locations) {
        for (const location of locations ?? []) {
            if (!this.isInside(location)) this.#positions.push(blockPosition(location));
        }
    }

    remove(locations) {
        const dropped = (locations ?? []).map(blockPosition);
        this.#positions = this.#positions.filter(
            held => !dropped.some(at => samePosition(held, at)));
    }

    getMin() {
        return this.#extreme(Math.min);
    }

    getMax() {
        return this.#extreme(Math.max);
    }

    getCapacity() {
        return this.#positions.length;
    }

    getBlockLocationIterator() {
        return new BlockLocationIterator(this.#positions.slice());
    }

    isInside(location) {
        const at = blockPosition(location);
        return this.#positions.some(held => samePosition(held, at));
    }

    translate(delta) {
        this.#positions = this.#positions.map(at => moved(at, delta));
    }

    #extreme(pick) {
        if (this.#positions.length === 0) return { x: 0, y: 0, z: 0 };
        return this.#positions.reduce((found, at) => corner(found, at, pick));
    }
}

/**
 * The boxes a volume covers.
 *
 * The bridge measures a region by its two corners, so a volume of scattered positions is asked for
 * one position at a time and a volume that is already a box in one call.
 */
function boxesOf(volume) {
    if (volume?.from !== undefined && volume?.to !== undefined) {
        return [[corner(volume.from, volume.to, Math.min), corner(volume.from, volume.to, Math.max)]];
    }
    return [...(volume?.getBlockLocationIterator?.() ?? [])].map(at => [at, at]);
}

/** A run of positions reaches the guest as one flat array of x, y, z. */
function positionsIn(flat) {
    const found = [];
    const values = list(flat);
    for (let i = 0; i + 2 < values.length; i += 3) {
        found.push({ x: values[i], y: values[i + 1], z: values[i + 2] });
    }
    return found;
}

const blockTypeCache = new Map();

/** A block type, which is its id and the key its name is translated under. */
export class BlockType {
    constructor(id) {
        this.id = id;
        this.localizationKey = bridge.blocks.localizationKey(id) ?? id;
        Object.freeze(this);
    }
}

function blockType(id) {
    let type = blockTypeCache.get(id);
    if (type === undefined) {
        type = new BlockType(id);
        blockTypeCache.set(id, type);
    }
    return type;
}

function typeIdOf(value) {
    if (typeof value === "string") return value;
    return value?.type?.id ?? value?.id;
}

// The id a block name reports itself under, which is Bedrock's spelling whichever edition's a pack
// wrote, so `minecraft:powered_rail` and `golden_rail` both compare equal to `minecraft:golden_rail`.
// A comparison is made often and the answer never changes, so each name is asked about once.
const canonicalBlockIds = new Map();

function blockNameOf(value) {
    const id = typeIdOf(value);
    if (typeof id !== "string") return id;

    let canonical = canonicalBlockIds.get(id);
    if (canonical === undefined) {
        canonical = bridge.blocks.canonicalId(id) ?? (id.includes(":") ? id : "minecraft:" + id);
        canonicalBlockIds.set(id, canonical);
    }
    return canonical;
}

export class BlockPermutation {
    /** The states a block really carries, which is what was asked for over the block's defaults. */
    static resolve(type, states) {
        const id = typeIdOf(type);
        const resolved = bridge.blocks.resolve(id, JSON.stringify(states ?? {}));
        if (resolved === null) throw new Error("No block type named " + id);

        const permutation = JSON.parse(resolved);
        return new BlockPermutation(permutation.typeId, permutation.states);
    }

    constructor(type, states) {
        this.type = blockType(type);
        this.#states = states;
    }

    #states;

    get localizationKey() {
        return this.type.localizationKey;
    }

    getState(name) {
        return this.#states[name];
    }

    getAllStates() {
        return { ...this.#states };
    }

    withState(name, value) {
        return BlockPermutation.resolve(this.type.id, { ...this.#states, [name]: value });
    }

    matches(type, states) {
        if (this.type.id !== blockNameOf(type)) return false;
        for (const key of Object.keys(states ?? {})) {
            if (this.#states[key] !== states[key]) return false;
        }
        return true;
    }

    getItemStack(amount) {
        const item = bridge.blocks.itemId(this.type.id);
        return item === null ? undefined : new ItemStack(item, amount ?? 1);
    }

    getTags() {
        return list(bridge.blocks.tags(this.type.id));
    }

    hasTag(tag) {
        return this.getTags().includes(tag);
    }

    /** Water is the only liquid Bedrock names, so the argument cannot pick out another one. */
    canContainLiquid() {
        return bridge.blocks.canContainLiquid(this.type.id);
    }

    isLiquidBlocking() {
        return bridge.blocks.isLiquidBlocking(this.type.id, JSON.stringify(this.#states));
    }

    canBeDestroyedByLiquidSpread() {
        return bridge.blocks.destroyedByLiquid(this.type.id, JSON.stringify(this.#states));
    }

    /** Java breaks such a block and drops it in one step, so this is the answer above as well. */
    liquidSpreadCausesSpawn() {
        return bridge.blocks.destroyedByLiquid(this.type.id, JSON.stringify(this.#states));
    }
}

export class BlockTypes {
    static get(typeName) {
        const id = bridge.blocks.canonicalId(typeName);
        return id === null ? undefined : blockType(id);
    }

    static getAll() {
        return list(bridge.blocks.typeIds()).map(id => blockType(id));
    }
}

/** One state name a block may carry, against every value the game accepts for it. */
export class BlockStateType {
    constructor(fields) {
        this.id = fields.id;
        this.validValues = fields.validValues;
        Object.freeze(this);
    }
}

// The catalogue is the same for as long as the game runs, and building it reads every block there
// is, so it is read once and handed out as a copy.
let blockStateTypes;

export class BlockStates {
    static get(stateName) {
        return BlockStates.getAll().find(state => state.id === stateName);
    }

    static getAll() {
        if (blockStateTypes === undefined) {
            blockStateTypes = JSON.parse(bridge.blocks.stateTypes()).map(one => new BlockStateType(one));
        }
        return blockStateTypes.slice();
    }
}

// A kind of item is the same for as long as the game runs, so what the game says about one is read
// once and handed back frozen, which also keeps one ItemType object per id for a pack to compare.
const itemTypeCache = new Map();

/** An id with no namespace names a vanilla thing, wherever Bedrock takes one. */
function namespaced(name) {
    const id = typeof name === "string" ? name : name?.id;
    return id !== undefined && id !== null && !id.includes(":") ? "minecraft:" + id : id;
}

export class ItemType {
    constructor(fields) {
        this.id = fields.id;
        this.localizationKey = fields.localizationKey;
        Object.freeze(this);
    }
}

/** What the game knows about a kind of item, with no fields at all for an id nothing registers. */
function itemInfo(typeId) {
    let info = itemTypeCache.get(typeId);
    if (info === undefined) {
        info = JSON.parse(bridge.items.describe(typeId ?? ""));
        info.type = info.id === undefined ? undefined : new ItemType(info);
        Object.freeze(info);
        itemTypeCache.set(typeId, info);
    }
    return info;
}

/**
 * The id an item name reports itself under, which is Bedrock's spelling whichever edition's a pack
 * wrote: `minecraft:item_frame` is `minecraft:frame` and `minecraft:muttonRaw` is `minecraft:mutton`.
 * A name no item answers keeps its spelling, with the namespace a bare one implies.
 */
function itemIdOf(name) {
    const id = namespaced(name);
    return itemInfo(id).id ?? id;
}

export class ItemTypes {
    static get(itemId) {
        return itemInfo(namespaced(itemId)).type;
    }

    static getAll() {
        return list(bridge.items.all()).map(id => itemInfo(id).type);
    }
}

/** An enchantment type is its id and the highest level the game gives it. Undefined for a bad id. */
function enchantmentType(type) {
    const id = namespaced(type);
    const maxLevel = id === undefined ? 0 : bridge.items.enchantmentMaxLevel(id);
    return maxLevel === 0 ? undefined : Object.freeze({ id, maxLevel });
}

/** The pair a pack hands in, as the game's own type and a level the game allows that type. */
function enchantmentOf(enchantment) {
    const type = enchantmentType(enchantment?.type);
    if (type === undefined) {
        throw new EnchantmentTypeUnknownIdError("No enchantment named " + namespaced(enchantment?.type));
    }
    if (!(enchantment.level >= 1 && enchantment.level <= type.maxLevel)) {
        throw new EnchantmentLevelOutOfBoundsError(
            "Level " + enchantment.level + " is outside 1 to " + type.maxLevel + " for " + type.id);
    }
    return { type, level: enchantment.level };
}

/**
 * The blocks a pack limits a stack to in adventure mode, checked against the game.
 *
 * Nothing carries the limit onto the item the world holds, so a list with anything in it is
 * reported even though the stack answers it back.
 */
function placementBlocks(blockIdentifiers, member) {
    const ids = (blockIdentifiers ?? []).map(namespaced);
    const missing = ids.find(id => !bridge.items.blockExists(id));
    if (missing !== undefined) throw new Error("No block type named " + missing);
    if (ids.length > 0) unsupported(member);
    return ids;
}

/**
 * Whether two stacks name the same item carrying the same data, however many each holds.
 *
 * This is what finding an item in a container asks, and it is separate from whether the two would
 * stack: a sword is one to a slot and still the sword a pack is looking for.
 */
function sameItem(one, other) {
    return one !== undefined && other !== undefined && one.typeId === other.typeId
        && (one.nameTag ?? "") === (other.nameTag ?? "")
        && (one.damage ?? 0) === (other.damage ?? 0)
        && JSON.stringify(one.getLore?.() ?? []) === JSON.stringify(other.getLore?.() ?? [])
        && JSON.stringify(one.properties ?? {}) === JSON.stringify(other.properties ?? {});
}

// The components a kind of item can carry here, which is what getComponents() offers a pack.
// The two items that carry pages. Every other item answers that it has no book.
const bookItems = Object.freeze(["minecraft:writable_book", "minecraft:written_book"]);

/**
 * A stack of items, held as a value of its own and not as a slot in the world.
 *
 * What the game decides about the kind of item is read from the registry every time it is asked
 * for. What a pack decides about this one stack (its lore, its dynamic properties, the blocks it
 * may break) lives on the object, and reaches the world only as far as the container it is written
 * into carries it.
 */
export class ItemStack {
    constructor(itemType, amount) {
        this.typeId = itemIdOf(itemType);
        this.amount = amount ?? 1;
        this.nameTag = undefined;
    }

    get type() {
        return itemInfo(this.typeId).type;
    }

    get localizationKey() {
        return itemInfo(this.typeId).localizationKey ?? this.typeId;
    }

    get maxAmount() {
        return itemInfo(this.typeId).maxAmount ?? 64;
    }

    get isStackable() {
        return this.maxAmount > 1 && !this.nameTag && this.getLore().length === 0
            && this.getDynamicPropertyIds().length === 0;
    }

    /** Bedrock counts four units per item, and nothing here carries a storage weight modifier. */
    get weight() {
        return this.amount * 4;
    }

    // Java honours neither of Bedrock's two restrictions on carrying an item, so both read as the
    // default they have when a pack has not asked for anything, and asking for anything else is
    // reported.

    get keepOnDeath() {
        return false;
    }

    set keepOnDeath(value) {
        if (value) unsupported("ItemStack.keepOnDeath");
    }

    get lockMode() {
        return ItemLockMode.none;
    }

    set lockMode(value) {
        if (value !== ItemLockMode.none) unsupported("ItemStack.lockMode");
    }

    clone() {
        const copy = new ItemStack(this.typeId, this.amount);
        copy.nameTag = this.nameTag;
        copy.damage = this.damage;
        copy.maxDurability = this.maxDurability;
        copy.unbreakable = this.unbreakable;
        copy.lore = [...(this.lore ?? [])];
        copy.properties = { ...(this.properties ?? {}) };
        copy.enchantments = [...(this.enchantments ?? [])];
        copy.dyeColor = this.dyeColor;
        copy.canDestroy = [...(this.canDestroy ?? [])];
        copy.canPlaceOn = [...(this.canPlaceOn ?? [])];
        copy.book = this.book === undefined
            ? undefined : { ...this.book, pages: [...this.book.pages] };
        return copy;
    }

    /** States belong to blocks, so a stack of a block item answers with the ones it would place. */
    matches(itemName, states) {
        if (itemIdOf(itemName) !== this.typeId) return false;

        const wanted = Object.entries(states ?? {});
        if (wanted.length === 0) return true;

        const resolved = bridge.blocks.resolve(this.typeId, "{}");
        if (resolved === null) return false;

        const placed = JSON.parse(resolved).states;
        return wanted.every(([state, value]) => placed[state] === value);
    }

    getCanDestroy() {
        return [...(this.canDestroy ?? [])];
    }

    setCanDestroy(blockIdentifiers) {
        this.canDestroy = placementBlocks(blockIdentifiers, "ItemStack.setCanDestroy");
    }

    getCanPlaceOn() {
        return [...(this.canPlaceOn ?? [])];
    }

    setCanPlaceOn(blockIdentifiers) {
        this.canPlaceOn = placementBlocks(blockIdentifiers, "ItemStack.setCanPlaceOn");
    }

    getComponent(component) {
        const name = normaliseComponent(component);
        const build = ITEM_COMPONENTS[name];
        if (build === undefined) return unsupported("ItemStack.getComponent(" + name + ")");

        const declared = build.declaredOn(this);
        return declared === undefined ? undefined : new build(this, name, declared);
    }

    getComponents() {
        return Object.keys(ITEM_COMPONENTS)
            .map(name => this.getComponent(name))
            .filter(component => component !== undefined);
    }

    hasComponent(component) {
        return this.getComponent(component) !== undefined;
    }

    getLore() {
        return [...(this.lore ?? [])];
    }

    setLore(loreList) {
        this.lore = (loreList ?? []).map(rawText);
    }

    getRawLore() {
        return this.getLore().map(text => ({ text }));
    }

    getTags() {
        return list(bridge.itemTags(this.typeId));
    }

    hasTag(tag) {
        return this.getTags().includes(namespaced(tag));
    }

    getDynamicProperty(identifier) {
        return this.properties?.[identifier];
    }

    setDynamicProperty(identifier, value) {
        const values = { ...(this.properties ?? {}) };
        if (value === undefined || value === null) delete values[identifier];
        else values[identifier] = value;
        this.properties = values;
    }

    setDynamicProperties(values) {
        for (const [identifier, value] of Object.entries(values ?? {})) {
            this.setDynamicProperty(identifier, value);
        }
    }

    getDynamicPropertyIds() {
        return Object.keys(this.properties ?? {});
    }

    getDynamicPropertyTotalByteCount() {
        return Object.entries(this.properties ?? {})
            .reduce((total, [identifier, value]) => total + identifier.length + String(value).length, 0);
    }

    clearDynamicProperties() {
        this.properties = {};
    }

    /** Whether the two carry the same item and the same extra data, and stack at all. */
    isStackableWith(itemStack) {
        return this.isStackable && sameItem(this, itemStack);
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

    center() {
        return { x: this.x + 0.5, y: this.y + 0.5, z: this.z + 0.5 };
    }

    bottomCenter() {
        return { x: this.x + 0.5, y: this.y, z: this.z + 0.5 };
    }

    get typeId() {
        return bridge.blockTypeId(this.dimension.id, this.x, this.y, this.z);
    }

    get type() {
        return blockType(this.typeId);
    }

    get localizationKey() {
        return this.type.localizationKey;
    }

    get isValid() {
        return bridge.blocks.exists(this.dimension.id, this.x, this.y, this.z);
    }

    get isAir() {
        return this.typeId === "minecraft:air";
    }

    get isLiquid() {
        return bridge.blockIsLiquid(this.dimension.id, this.x, this.y, this.z);
    }

    get isSolid() {
        return bridge.blocks.isSolid(this.dimension.id, this.x, this.y, this.z);
    }

    get isWaterlogged() {
        return bridge.blocks.isWaterlogged(this.dimension.id, this.x, this.y, this.z);
    }

    get permutation() {
        const here = JSON.parse(bridge.blocks.permutationAt(this.dimension.id, this.x, this.y, this.z));
        return new BlockPermutation(here.typeId, here.states);
    }

    getState(name) {
        return this.permutation.getState(name);
    }

    matches(type, states) {
        return this.permutation.matches(type, states);
    }

    getDynamicProperty(name) {
        // A property never set crosses as null, and Bedrock answers undefined.
        return storedProperty(bridge.blockDynamicProperty(...blockAt(this), name));
    }

    setDynamicProperty(name, value) {
        bridge.setBlockDynamicProperty(...blockAt(this), name, value === undefined ? null : value);
    }

    setDynamicProperties(values) {
        Object.entries(values ?? {}).forEach(([name, value]) => this.setDynamicProperty(name, value));
    }

    getDynamicPropertyIds() {
        return list(bridge.blockDynamicPropertyIds(...blockAt(this)));
    }

    getDynamicPropertyTotalByteCount() {
        return bridge.blockDynamicPropertyBytes(...blockAt(this));
    }

    clearDynamicProperties() {
        for (const name of this.getDynamicPropertyIds()) this.setDynamicProperty(name, undefined);
    }

    getTags() {
        return list(bridge.blocks.tags(this.typeId));
    }

    hasTag(tag) {
        return this.getTags().includes(tag);
    }

    /** Undefined for a block nothing can hold, and for one whose chunk is not loaded. */
    getItemStack(amount) {
        const item = bridge.blocks.itemId(this.typeId);
        return item === null ? undefined : new ItemStack(item, amount ?? 1);
    }

    getRedstonePower() {
        return bridge.blocks.redstonePower(this.dimension.id, this.x, this.y, this.z) ?? undefined;
    }

    /**
     * Bedrock's multi-block pieces.
     *
     * Java writes a bed or a door as two blocks that know nothing of each other, so there is no
     * list of parts to hand back.
     */
    getParts() {
        return unsupported("Block.getParts");
    }

    getLightLevel() {
        return bridge.worlds.lightLevel(this.dimension.id, this.x, this.y, this.z);
    }

    getSkyLightLevel() {
        return bridge.worlds.skyLightLevel(this.dimension.id, this.x, this.y, this.z);
    }

    canContainLiquid() {
        return bridge.blocks.canContainLiquid(this.typeId);
    }

    isLiquidBlocking() {
        return this.permutation.isLiquidBlocking();
    }

    canBeDestroyedByLiquidSpread() {
        return this.permutation.canBeDestroyedByLiquidSpread();
    }

    liquidSpreadCausesSpawn() {
        return this.permutation.liquidSpreadCausesSpawn();
    }

    /** The direction the liquid comes from, which is the face of this block it arrives at. */
    liquidCanFlowFromDirection(liquidType, flowDirection) {
        return bridge.blocks.liquidCanFlowFrom(this.dimension.id, this.x, this.y, this.z,
            flowDirection ?? null);
    }

    /** A face names where the block would go; without one it would replace this block. */
    canPlace(blockToPlace, faceToPlaceOn) {
        const states = blockToPlace instanceof BlockPermutation ? blockToPlace.getAllStates() : {};
        return bridge.blocks.canPlace(this.dimension.id, this.x, this.y, this.z,
            typeIdOf(blockToPlace), JSON.stringify(states), faceToPlaceOn ?? null);
    }

    offset(delta) {
        return this.dimension.getBlock({ x: this.x + delta.x, y: this.y + delta.y, z: this.z + delta.z });
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
        bridge.setBlockType(this.dimension.id, this.x, this.y, this.z, typeIdOf(type));
    }

    setPermutation(permutation) {
        bridge.blocks.setPermutation(this.dimension.id, this.x, this.y, this.z,
            permutation.type.id, JSON.stringify(permutation.getAllStates()));
    }

    setWaterlogged(waterlogged) {
        bridge.blocks.setWaterlogged(this.dimension.id, this.x, this.y, this.z, waterlogged === true);
    }

    /** Undefined for a block that has no such component; one this mod cannot build is reported. */
    getComponent(component) {
        const name = normaliseComponent(component);
        if (BLOCK_COMPONENTS[name] === undefined) {
            return unsupported("Block.getComponent(" + name + ")");
        }
        return blockComponentFor(this, name);
    }

    getComponents() {
        return Object.keys(BLOCK_COMPONENTS)
            .map(name => blockComponentFor(this, name))
            .filter(component => component !== undefined);
    }

    hasComponent(component) {
        const name = normaliseComponent(component);
        if (BLOCK_COMPONENTS[name] === undefined) {
            unsupported("Block.hasComponent(" + name + ")");
            return false;
        }
        return blockComponentFor(this, name) !== undefined;
    }
}

// Where a block stands, as the four arguments every component reader takes.
function blockAt(block) {
    return [block.dimension.id, block.x, block.y, block.z];
}

/** The bridge answers null for a block that does not carry the component, and JSON for one that does. */
function blockComponent(state) {
    return state === null ? undefined : JSON.parse(state);
}

/** What every block component carries: the block it was read off, and the name it was asked for. */
export class BlockComponent {
    constructor(block, typeId) {
        this.block = block;
        this.typeId = typeId;
    }

    get isValid() {
        return bridge.blocks.exists(...blockAt(this.block));
    }

    /** The component reads the block again for every answer, so nothing here is a stale copy. */
    read() {
        const reader = COMPONENT_READERS[this.typeId];
        return reader === undefined
            ? undefined : blockComponent(bridge.blocks[reader](...blockAt(this.block)));
    }

    /** Whether this block carries the component at all, which decides what `getComponent` hands back. */
    present() {
        return this.read() !== undefined;
    }
}

/** The component the block carries under that name, or undefined when it carries none. */
function blockComponentFor(block, name) {
    const component = new BLOCK_COMPONENTS[name](block, name);
    return component.present() ? component : undefined;
}

/** The bridge call each block component reads itself from. */
const COMPONENT_READERS = Object.freeze({
    "minecraft:movable": "movement",
    "minecraft:sign": "sign",
    "minecraft:piston": "piston",
    "minecraft:map_color": "mapColour",
    "minecraft:redstone_producer": "redstoneProducer",
});

/** Every block answers how a piston moves it, so this one is carried by all of them. */
export class BlockMovableComponent extends BlockComponent {
    static componentId = "minecraft:movable";

    present() {
        return true;
    }

    get movementType() {
        return this.read()?.movementType;
    }

    get stickyType() {
        return this.read()?.stickyType;
    }
}

export class BlockPistonComponent extends BlockComponent {
    static componentId = "minecraft:piston";

    get isMoving() {
        return this.read()?.isMoving === true;
    }

    get state() {
        return this.read()?.state;
    }

    getAttachedBlocksLocations() {
        return (this.read()?.attached ?? []).map(vector);
    }

    getAttachedBlocks() {
        return this.getAttachedBlocksLocations()
            .map(location => this.block.dimension.getBlock(location))
            .filter(found => found !== undefined);
    }
}

/** Java gives a block one map colour, so the tinted colour is that same colour untouched. */
export class BlockMapColorComponent extends BlockComponent {
    static componentId = "minecraft:map_color";

    tintMethod = TintMethod.None;

    get color() {
        return this.read();
    }

    get tintedColor() {
        return this.read();
    }
}

export class BlockRedstoneProducerComponent extends BlockComponent {
    static componentId = "minecraft:redstone_producer";

    get power() {
        return this.read()?.power ?? 0;
    }

    getConnectedFaces() {
        return this.read()?.connectedFaces ?? [];
    }

    getStronglyPoweredFace() {
        return this.read()?.stronglyPoweredFace ?? undefined;
    }
}

/** The container a chest, a hopper or a furnace holds, read again on every call like the rest. */
export class BlockInventoryComponent extends BlockComponent {
    static componentId = "minecraft:inventory";

    present() {
        return this.container !== undefined;
    }

    get container() {
        return containerAt(...blockAt(this.block));
    }
}

/** A side is the front unless the back was asked for by name, which is how Bedrock reads one. */
export class BlockSignComponent extends BlockComponent {
    static componentId = "minecraft:sign";

    get isWaxed() {
        return this.read()?.isWaxed === true;
    }

    #text(side) {
        return this.read()?.[side === SignSide.Back ? "back" : "front"];
    }

    getText(side) {
        return this.#text(side)?.text;
    }

    getRawText(side) {
        return { rawtext: [{ text: this.#text(side)?.text ?? "" }] };
    }

    getTextDyeColor(side) {
        return this.#text(side)?.color;
    }

    setText(message, side) {
        bridge.blocks.setSignText(...blockAt(this.block), side !== SignSide.Back, rawText(message));
    }

    setTextDyeColor(color, side) {
        bridge.blocks.setSignDye(...blockAt(this.block), side !== SignSide.Back, color ?? null);
    }

    setWaxed(waxed) {
        bridge.blocks.setSignWaxed(...blockAt(this.block), waxed === true);
    }
}

/** What a note block plays off this block, which every block answers for. */
export class BlockInstrumentComponent extends BlockComponent {
    static componentId = "minecraft:instrument_sound";

    present() {
        return true;
    }

    getInstrumentName(face) {
        const faces = JSON.parse(bridge.blocks.instrument(...blockAt(this.block)));
        if (face !== Direction.Up && face !== Direction.Down) {
            throw new RangeError("Only the up and down faces of a block play an instrument.");
        }
        return face === Direction.Up ? faces.up : faces.down;
    }

    playInstrumentSound(face, soundOptions) {
        if (face !== Direction.Up && face !== Direction.Down) {
            throw new RangeError("Only the up and down faces of a block play an instrument.");
        }
        bridge.blocks.playInstrument(...blockAt(this.block), face, soundOptions?.volume ?? 1,
            soundOptions?.pitch ?? 1);
    }
}

/** The store a block keeps of its own, which `minecraft:block_entity` asks for. */
export class BlockDynamicPropertiesComponent extends BlockComponent {
    static componentId = "minecraft:dynamic_properties";

    present() {
        return bridge.blockKeepsProperties(...blockAt(this.block));
    }

    get(key) {
        return storedProperty(bridge.blockDynamicProperty(...blockAt(this.block), key));
    }

    set(key, value) {
        bridge.setBlockDynamicProperty(...blockAt(this.block), key, value === undefined ? null : value);
    }

    totalByteCount() {
        return bridge.blockDynamicPropertyBytes(...blockAt(this.block));
    }
}

const BLOCK_COMPONENTS = Object.freeze({
    "minecraft:dynamic_properties": BlockDynamicPropertiesComponent,
    "minecraft:instrument_sound": BlockInstrumentComponent,
    "minecraft:inventory": BlockInventoryComponent,
    "minecraft:movable": BlockMovableComponent,
    "minecraft:sign": BlockSignComponent,
    "minecraft:piston": BlockPistonComponent,
    "minecraft:map_color": BlockMapColorComponent,
    "minecraft:redstone_producer": BlockRedstoneProducerComponent,
});

// Component classes. A pack imports one to name a type, and `getComponent` hands back an instance of
// whichever of these matches the name it asked for.
export class EntityComponent {
    #entity;
    #typeId;

    constructor(entity, typeId) {
        this.#entity = entity;
        this.#typeId = typeId;
    }

    get entity() {
        return this.#entity;
    }

    get typeId() {
        return this.#typeId;
    }

    get isValid() {
        return bridge.entityIsValid(this.#entity.id);
    }
}

/**
 * One number the game keeps about an entity, which Bedrock hands out as a component per number.
 *
 * Java holds these in one attribute map plus a few fields beside it, so the component id says which
 * to read. A Bedrock attribute this game has no counterpart for answers nothing and reports itself.
 */
export class EntityAttributeComponent extends EntityComponent {
    #read(field) {
        const found = bridge.entityMembers.attribute(this.entity.id, this.typeId);
        return found === null
            ? unsupported("EntityAttributeComponent(" + this.typeId + ")") : JSON.parse(found)[field];
    }

    get currentValue() {
        return this.#read("current");
    }

    get defaultValue() {
        return this.#read("default");
    }

    get effectiveMax() {
        return this.#read("max");
    }

    get effectiveMin() {
        return this.#read("min");
    }

    setCurrentValue(value) {
        const set = bridge.entityMembers.setAttribute(this.entity.id, this.typeId, value);
        if (!set) unsupported("EntityAttributeComponent(" + this.typeId + ")");
        return set;
    }

    resetToDefaultValue() {
        this.setCurrentValue(this.defaultValue);
    }

    resetToMaxValue() {
        this.setCurrentValue(this.effectiveMax);
    }

    resetToMinValue() {
        this.setCurrentValue(this.effectiveMin);
    }
}

export class EntityHealthComponent extends EntityAttributeComponent {
    static componentId = "minecraft:health";
}

export class EntityMovementComponent extends EntityAttributeComponent {
    static componentId = "minecraft:movement";
}

export class EntityHungerComponent extends EntityAttributeComponent {
    static componentId = "minecraft:player.hunger";
}

export class EntitySaturationComponent extends EntityAttributeComponent {
    static componentId = "minecraft:player.saturation";
}

export class EntityOnFireComponent extends EntityComponent {
    static componentId = "minecraft:onfire";

    get onFireTicksRemaining() {
        return bridge.entityMembers.fireTicks(this.entity.id);
    }
}

export class EntityEquippableComponent extends EntityComponent {
    static componentId = "minecraft:equippable";

    getEquipment(slot) {
        const item = bridge.equipment(this.entity.id, slot);
        return item === null ? undefined : itemFrom(JSON.parse(item));
    }

    setEquipment(slot, item) {
        return bridge.setEquipment(this.entity.id, slot, item?.typeId ?? null, item?.amount ?? 1);
    }

    // A slot is a handle into a container the guest can hold on to, which an equipment slot on a
    // Java entity is not: armour and held items sit in fields rather than in the inventory.
    getEquipmentSlot() {
        return unsupported("EntityEquippableComponent.getEquipmentSlot");
    }

    get totalArmor() {
        return bridge.entityMembers.armour(this.entity.id);
    }

    get totalToughness() {
        return bridge.entityMembers.armourToughness(this.entity.id);
    }
}

export class EntityInventoryComponent extends EntityComponent {
    static componentId = "minecraft:inventory";

    additionalSlotsPerStrength = 0;
    canBeSiphonedFrom = false;
    private = false;
    restrictToOwner = false;

    /** The pack says what kind of container its entity carries; Java has one kind of inventory. */
    get containerType() {
        const declared = bridge.entityMembers.packComponent(this.entity.id, EntityInventoryComponent.componentId);
        return declared === null ? "inventory" : (JSON.parse(declared).container_type ?? "inventory");
    }

    get container() {
        const handle = bridge.containers.ofEntity(this.entity.id);
        return handle === null ? undefined : new Container(handle);
    }

    get inventorySize() {
        return this.container?.size ?? 0;
    }
}

export class EntityRidingComponent extends EntityComponent {
    static componentId = "minecraft:riding";

    get entityRidingOn() {
        const vehicle = bridge.entityVehicle(this.entity.id);
        return vehicle === null ? undefined : entityFor(vehicle);
    }
}

/** The entity properties Bedrock reads as a component, each one number the pack or the game set. */
class PropertyComponent extends EntityComponent {
    get value() {
        return bridge.entityProperty(this.entity.id, this.typeId.slice("minecraft:".length)) ?? 0;
    }
}

export class EntityVariantComponent extends PropertyComponent {
    static componentId = "minecraft:variant";
}

export class EntityMarkVariantComponent extends PropertyComponent {
    static componentId = "minecraft:mark_variant";
}

export class EntitySkinIdComponent extends PropertyComponent {
    static componentId = "minecraft:skin_id";
}

export class EntityTypeFamilyComponent extends EntityComponent {
    static componentId = "minecraft:type_family";

    getTypeFamilies() {
        return list(bridge.entityFamilies(this.entity.id));
    }

    hasTypeFamily(family) {
        return this.getTypeFamilies().includes(family);
    }
}

export class EntityIsBabyComponent extends EntityComponent {
    static componentId = "minecraft:is_baby";
}

export class EntityItemComponent extends EntityComponent {
    static componentId = "minecraft:item";
}

/**
 * A component answered from what the pack declared for the entity rather than from the live game.
 *
 * Java holds none of this. Its pathfinding keeps no record of whether a mob may open a door and its
 * arrows carry no bounce flag, so the behaviour pack is the only place the answer exists. The pack
 * is read on every access, and a field the pack left out falls back to Bedrock's own default for it.
 */
class DeclaredComponent extends EntityComponent {
    field(name, fallback) {
        const body = bridge.entityMembers.packComponent(this.entity.id, this.typeId);
        const declared = body === null ? undefined : JSON.parse(body)[name];
        return declared === undefined ? fallback : declared;
    }
}

/**
 * Gives a component class one getter per declared field.
 *
 * Each entry is the name a script reads the field under, the name the pack writes it under, and the
 * value Bedrock uses when the pack leaves it out.
 */
function declaredFields(component, fields) {
    fields.forEach(([member, field, fallback]) => Object.defineProperty(component.prototype, member, {
        enumerable: true,
        get() {
            return this.field(field, fallback);
        },
    }));
}

export class EntityNavigationComponent extends DeclaredComponent {
}

declaredFields(EntityNavigationComponent, [
    ["avoidDamageBlocks", "avoid_damage_blocks", false],
    ["avoidPortals", "avoid_portals", false],
    ["avoidSun", "avoid_sun", false],
    ["avoidWater", "avoid_water", false],
    ["canBreach", "can_breach", false],
    ["canBreakDoors", "can_break_doors", false],
    ["canFloat", "can_float", false],
    ["canJump", "can_jump", true],
    ["canOpenDoors", "can_open_doors", false],
    ["canOpenIronDoors", "can_open_iron_doors", false],
    ["canPassDoors", "can_pass_doors", true],
    ["canPathFromAir", "can_path_from_air", false],
    ["canPathOverLava", "can_path_over_lava", false],
    ["canPathOverWater", "can_path_over_water", false],
    ["canSink", "can_sink", true],
    ["canSwim", "can_swim", false],
    ["canWalk", "can_walk", true],
    ["canWalkInLava", "can_walk_in_lava", false],
    ["isAmphibious", "is_amphibious", false],
]);

export class EntityBreathableComponent extends DeclaredComponent {
    static componentId = "minecraft:breathable";

    getBreatheBlocks() {
        return this.field("breathe_blocks", []).map(blockPermutationOf);
    }

    getNonBreatheBlocks() {
        return this.field("non_breathe_blocks", []).map(blockPermutationOf);
    }
}

declaredFields(EntityBreathableComponent, [
    ["breathesAir", "breathes_air", true],
    ["breathesLava", "breathes_lava", false],
    ["breathesSolids", "breathes_solids", false],
    ["breathesWater", "breathes_water", false],
    ["generatesBubbles", "generates_bubbles", true],
    ["inhaleTime", "inhale_time", 0],
    ["suffocateTime", "suffocate_time", -20],
    ["totalSupply", "total_supply", 15],
]);

/** A pack names a block either bare or as an object carrying its states. */
function blockPermutationOf(block) {
    return typeof block === "string"
        ? BlockPermutation.resolve(normaliseComponent(block))
        : BlockPermutation.resolve(normaliseComponent(block.name), block.states);
}

export class EntityLeashableComponent extends DeclaredComponent {
    static componentId = "minecraft:leashable";

    get isLeashed() {
        return bridge.entityMembers.leashHolder(this.entity.id) !== null;
    }

    get leashHolder() {
        return entityFor(bridge.entityMembers.leashHolder(this.entity.id));
    }

    get leashHolderEntityId() {
        return bridge.entityMembers.leashHolder(this.entity.id) ?? undefined;
    }

    leashTo(leashHolder) {
        if (!bridge.entityMembers.leashTo(this.entity.id, leashHolder?.id)) {
            unsupported("EntityLeashableComponent.leashTo");
        }
    }

    unleash() {
        if (!bridge.entityMembers.unleash(this.entity.id)) {
            unsupported("EntityLeashableComponent.unleash");
        }
    }
}

declaredFields(EntityLeashableComponent, [
    ["canBeStolen", "can_be_stolen", false],
    ["hardDistance", "hard_distance", 55],
    ["maxDistance", "max_distance", 10],
    ["softDistance", "soft_distance", 4],
]);

export class EntityRideableComponent extends DeclaredComponent {
    static componentId = "minecraft:rideable";

    getFamilyTypes() {
        return this.field("family_types", []);
    }

    getRiders() {
        return list(bridge.entityRiders(this.entity.id)).map(id => entityFor(id));
    }

    getSeats() {
        return this.field("seats", []).map(seat => new Seat(seat));
    }

    addRider(rider) {
        return bridge.addRider(this.entity.id, rider.id);
    }

    ejectRider(rider) {
        bridge.ejectRider(this.entity.id, rider.id);
    }

    ejectRiders() {
        this.getRiders().forEach(rider => this.ejectRider(rider));
    }
}

declaredFields(EntityRideableComponent, [
    ["controllingSeat", "controlling_seat", 0],
    ["crouchingSkipInteract", "crouching_skip_interact", true],
    ["interactText", "interact_text", ""],
    ["passengerMaxWidth", "passenger_max_width", 0],
    ["pullInEntities", "pull_in_entities", false],
    ["riderCanInteract", "rider_can_interact", false],
    ["seatCount", "seat_count", 1],
]);

/** One seat of a rideable entity, as the pack wrote it. */
export class Seat {
    constructor(declared) {
        this.lockRiderRotation = declared.lock_rider_rotation ?? 181;
        this.maxRiderCount = declared.max_rider_count ?? 1;
        this.minRiderCount = declared.min_rider_count ?? 0;
        this.position = vector(declared.position ?? [0, 0, 0]);
        this.seatRotation = declared.rotate_rider_by ?? 0;
    }
}

export class EntityProjectileComponent extends DeclaredComponent {
    static componentId = "minecraft:projectile";

    /** Bedrock keeps who fired a projectile; Java keeps it per projectile class and not on an entity. */
    get owner() {
        return unsupported("EntityProjectileComponent.owner");
    }

    /**
     * Throws the entity along a velocity, dropping whatever it was already doing.
     *
     * An impulse of nothing clears the movement, so the two calls together set the velocity rather
     * than adding to it.
     */
    shoot(velocity, options) {
        if (options?.uncertainty !== undefined) {
            unsupported("ProjectileShootOptions.uncertainty");
        }
        bridge.applyImpulse(this.entity.id, 0, 0, 0);
        bridge.applyImpulse(this.entity.id, velocity.x, velocity.y, velocity.z);
    }
}

declaredFields(EntityProjectileComponent, [
    ["airInertia", "inertia", 0.99],
    ["catchFireOnHurt", "catch_fire", false],
    ["critParticlesOnProjectileHurt", "crit_particle_on_hurt", false],
    ["destroyOnProjectileHurt", "destroy_on_hurt", false],
    ["gravity", "gravity", 0.05],
    ["hitEntitySound", "hit_sound", undefined],
    ["hitGroundSound", "hit_ground_sound", undefined],
    ["hitParticle", "particle", undefined],
    ["lightningStrikeOnHit", "lightning", false],
    ["liquidInertia", "liquid_inertia", 0.6],
    ["onFireTime", "on_fire_time", 5],
    ["shouldBounceOnHit", "should_bounce", false],
    ["stopOnHit", "stop_on_hit", false],
]);

export class EntityNavigationClimbComponent extends EntityNavigationComponent {
    static componentId = "minecraft:navigation.climb";
}

export class EntityNavigationFloatComponent extends EntityNavigationComponent {
    static componentId = "minecraft:navigation.float";
}

export class EntityNavigationFlyComponent extends EntityNavigationComponent {
    static componentId = "minecraft:navigation.fly";
}

export class EntityNavigationGenericComponent extends EntityNavigationComponent {
    static componentId = "minecraft:navigation.generic";
}

export class EntityNavigationHoverComponent extends EntityNavigationComponent {
    static componentId = "minecraft:navigation.hover";
}

export class EntityNavigationWalkComponent extends EntityNavigationComponent {
    static componentId = "minecraft:navigation.walk";
}

/** How fast a mob turns while it moves, which every one of Bedrock's movement kinds carries. */
export class EntityBaseMovementComponent extends DeclaredComponent {
}

declaredFields(EntityBaseMovementComponent, [["maxTurn", "max_turn", 30]]);

export class EntityMovementAmphibiousComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.amphibious";
}

export class EntityMovementBasicComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.basic";
}

export class EntityMovementFlyComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.fly";
}

export class EntityMovementGenericComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.generic";
}

export class EntityMovementGlideComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.glide";
}

declaredFields(EntityMovementGlideComponent, [
    ["speedWhenTurning", "speed_when_turning", 0.2],
    ["startSpeed", "start_speed", 0.1],
]);

export class EntityMovementHoverComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.hover";
}

export class EntityMovementJumpComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.jump";
}

export class EntityMovementSkipComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.skip";
}

export class EntityMovementSwayComponent extends EntityBaseMovementComponent {
    static componentId = "minecraft:movement.sway";
}

declaredFields(EntityMovementSwayComponent, [
    ["swayAmplitude", "sway_amplitude", 0.05],
    ["swayFrequency", "sway_frequency", 0.5],
]);

export class EntityStrengthComponent extends DeclaredComponent {
    static componentId = "minecraft:strength";
}

declaredFields(EntityStrengthComponent, [["max", "max", 5], ["value", "value", 1]]);

export class EntityAddRiderComponent extends DeclaredComponent {
    static componentId = "minecraft:addrider";
}

declaredFields(EntityAddRiderComponent, [
    ["entityType", "entity_type", ""],
    ["spawnEvent", "spawn_event", ""],
]);

/** An event the pack names on a component, which is all a `Trigger` is. */
export class Trigger {
    constructor(declared) {
        this.eventName = typeof declared === "string" ? declared : (declared?.event ?? "");
    }
}

/** One item a young entity is fed, and how much of its growing up that feeding is worth. */
export class EntityDefinitionFeedItem {
    constructor(declared) {
        this.item = typeof declared === "string" ? declared : declared.item;
        this.growth = typeof declared === "string" ? 0 : (declared.growth ?? 0);
        this.resultItem = typeof declared === "string" ? undefined : declared.result_item;
    }
}

export class EntityAgeableComponent extends DeclaredComponent {
    static componentId = "minecraft:ageable";

    get growUp() {
        return new Trigger(this.field("grow_up", ""));
    }

    getDropItems() {
        return this.field("drop_items", []);
    }

    getFeedItems() {
        return this.field("feed_items", []).map(item => new EntityDefinitionFeedItem(item));
    }
}

declaredFields(EntityAgeableComponent, [
    ["duration", "duration", 1200],
    ["transformToItem", "transform_to_item", ""],
]);

/** One effect a feeding puts on the entity, as the healable component writes it. */
export class FeedItemEffect {
    constructor(declared) {
        this.name = declared.name;
        this.amplifier = declared.amplifier ?? 0;
        this.chance = declared.chance ?? 1;
        this.duration = declared.duration ?? 0;
    }
}

export class FeedItem {
    constructor(declared) {
        this.item = typeof declared === "string" ? declared : declared.item;
        this.healAmount = typeof declared === "string" ? 1 : (declared.heal_amount ?? 1);
        this.resultItem = typeof declared === "string" ? undefined : declared.replace_item;
        this.#effects = typeof declared === "string" ? [] : (declared.effects ?? []);
    }

    #effects;

    getEffects() {
        return this.#effects.map(effect => new FeedItemEffect(effect));
    }
}

export class EntityHealableComponent extends DeclaredComponent {
    static componentId = "minecraft:healable";

    getFeedItems() {
        return this.field("items", []).map(item => new FeedItem(item));
    }
}

declaredFields(EntityHealableComponent, [["forceUse", "force_use", false]]);

export class EntityTameableComponent extends DeclaredComponent {
    static componentId = "minecraft:tameable";

    /** Published as a property rather than as a call, which is how Bedrock spells this one. */
    get getTameItems() {
        return this.field("tame_items", []).map(item => new ItemStack(namespaced(item), 1));
    }

    get isTamed() {
        return bridge.entityMembers.owner(this.entity.id) !== null;
    }

    get tamedToPlayerId() {
        return bridge.entityMembers.owner(this.entity.id) ?? undefined;
    }

    get tamedToPlayer() {
        return entityFor(bridge.entityMembers.owner(this.entity.id));
    }

    tame(player) {
        return bridge.entityMembers.tame(this.entity.id, player?.id);
    }
}

declaredFields(EntityTameableComponent, [["probability", "probability", 1]]);

/**
 * A mount a player tames by riding it.
 *
 * Java keeps a horse's temper and its owner on the horse class itself rather than on the tameable
 * mixin every other tamed mob uses, so none of the live state here has a reader and each of these
 * says so. What the pack declared about the mount is read the same way as every other component.
 */
export class EntityTameMountComponent extends DeclaredComponent {
    static componentId = "minecraft:tamemount";

    get isTamed() {
        return unsupported("EntityTameMountComponent.isTamed");
    }

    get isTamedToPlayer() {
        return unsupported("EntityTameMountComponent.isTamedToPlayer");
    }

    get tamedToPlayer() {
        return unsupported("EntityTameMountComponent.tamedToPlayer");
    }

    get tamedToPlayerId() {
        return unsupported("EntityTameMountComponent.tamedToPlayerId");
    }

    tame() {
        unsupported("EntityTameMountComponent.tame");
    }

    tameToPlayer() {
        return unsupported("EntityTameMountComponent.tameToPlayer");
    }
}

// Every component this mod builds from the pack's own definition, against the class that reads it.
const DECLARED_COMPONENTS = Object.freeze({
    "minecraft:breathable": EntityBreathableComponent,
    "minecraft:leashable": EntityLeashableComponent,
    "minecraft:projectile": EntityProjectileComponent,
    "minecraft:rideable": EntityRideableComponent,
    "minecraft:navigation.climb": EntityNavigationClimbComponent,
    "minecraft:navigation.float": EntityNavigationFloatComponent,
    "minecraft:navigation.fly": EntityNavigationFlyComponent,
    "minecraft:navigation.generic": EntityNavigationGenericComponent,
    "minecraft:navigation.hover": EntityNavigationHoverComponent,
    "minecraft:navigation.walk": EntityNavigationWalkComponent,
    "minecraft:movement.amphibious": EntityMovementAmphibiousComponent,
    "minecraft:movement.basic": EntityMovementBasicComponent,
    "minecraft:movement.fly": EntityMovementFlyComponent,
    "minecraft:movement.generic": EntityMovementGenericComponent,
    "minecraft:movement.glide": EntityMovementGlideComponent,
    "minecraft:movement.hover": EntityMovementHoverComponent,
    "minecraft:movement.jump": EntityMovementJumpComponent,
    "minecraft:movement.skip": EntityMovementSkipComponent,
    "minecraft:movement.sway": EntityMovementSwayComponent,
    "minecraft:strength": EntityStrengthComponent,
    "minecraft:addrider": EntityAddRiderComponent,
    "minecraft:ageable": EntityAgeableComponent,
    "minecraft:healable": EntityHealableComponent,
    "minecraft:tameable": EntityTameableComponent,
    "minecraft:tamemount": EntityTameMountComponent,
});

/**
 * What every item component carries.
 *
 * A component is handed out per call rather than kept, so each one holds the stack it was read off
 * and what the game says about that kind of item. The two accessors are this mod's own: Bedrock
 * publishes neither, and the components that write a value back need both.
 */
export class ItemComponent {
    #stack;
    #declared;

    constructor(stack, typeId, declared) {
        this.#stack = stack;
        this.typeId = typeId;
        this.#declared = declared;
    }

    /** A stack here is a plain object a pack holds, so it is valid for as long as the pack has it. */
    get isValid() {
        return true;
    }

    stack() {
        return this.#stack;
    }

    declared() {
        return this.#declared;
    }

    /** What `getComponent` reads to decide whether this kind of item carries the component at all. */
    static declaredOn() {
        return {};
    }
}

/**
 * The store of the block an item places, carried on the item until it is placed.
 *
 * Java carries a block entity's data on an item as the data the block entity writes, and nothing
 * here moves a script's store onto an item or back off it, so each member reports itself.
 */
export class ItemBlockDynamicPropertiesComponent extends ItemComponent {
    static componentId = "minecraft:block_actor_dynamic_properties";

    get() {
        return unsupported("ItemBlockDynamicPropertiesComponent.get");
    }

    set() {
        unsupported("ItemBlockDynamicPropertiesComponent.set");
    }

    totalByteCount() {
        return unsupported("ItemBlockDynamicPropertiesComponent.totalByteCount", 0);
    }
}

export class ItemDurabilityComponent extends ItemComponent {
    static componentId = "minecraft:durability";

    static declaredOn(stack) {
        const max = stack.maxDurability ?? bridge.itemMaxDurability(stack.typeId);
        return max > 0 ? { maxDurability: max } : undefined;
    }

    constructor(stack, typeId, declared) {
        super(stack, typeId, declared);
        this.maxDurability = declared.maxDurability;
    }

    get damage() {
        return this.stack().damage ?? 0;
    }

    set damage(value) {
        this.stack().damage = value;
    }

    /** Kept on the stack and answered back; nothing carries the flag onto the item in the world. */
    get unbreakable() {
        return this.stack().unbreakable ?? false;
    }

    set unbreakable(value) {
        this.stack().unbreakable = value;
        if (value) unsupported("ItemDurabilityComponent.unbreakable");
    }

    /** Java damages a tool on every use, so the chance of it is the whole range at once. */
    getDamageChance() {
        return 100;
    }

    getDamageChanceRange() {
        return { min: 100, max: 100 };
    }
}

export class ItemFoodComponent extends ItemComponent {
    static componentId = "minecraft:food";

    static declaredOn(stack) {
        return itemInfo(stack.typeId).food;
    }

    constructor(stack, typeId, declared) {
        super(stack, typeId, declared);
        this.canAlwaysEat = declared.canAlwaysEat;
        this.nutrition = declared.nutrition;
        this.saturationModifier = declared.saturationModifier;
        this.usingConvertsTo = declared.usingConvertsTo;
    }
}

export class ItemCompostableComponent extends ItemComponent {
    static componentId = "minecraft:compostable";

    static declaredOn(stack) {
        return itemInfo(stack.typeId).compostable;
    }

    constructor(stack, typeId, declared) {
        super(stack, typeId, declared);
        this.compostingChance = declared.compostingChance;
    }
}

/** A cooldown is read from a player, because it is the player and not the item that holds one. */
export class ItemCooldownComponent extends ItemComponent {
    static componentId = "minecraft:cooldown";

    static declaredOn(stack) {
        return itemInfo(stack.typeId).cooldown;
    }

    constructor(stack, typeId, declared) {
        super(stack, typeId, declared);
        this.cooldownCategory = declared.cooldownCategory;
        this.cooldownTicks = declared.cooldownTicks;
    }

    isCooldownCategory(category) {
        return category === this.cooldownCategory;
    }

    getCooldownTicksRemaining(player) {
        return bridge.items.cooldownRemaining(player.id, this.stack().typeId);
    }

    startCooldown(player) {
        bridge.items.startCooldown(player.id, this.stack().typeId, this.cooldownTicks);
    }
}

/**
 * An enchantment a pack adds is kept on the stack and answered back. Nothing carries it onto the
 * item the world holds, so adding one is reported.
 */
export class ItemEnchantableComponent extends ItemComponent {
    static componentId = "minecraft:enchantable";

    static declaredOn(stack) {
        return itemInfo(stack.typeId).enchantable;
    }

    constructor(stack, typeId, declared) {
        super(stack, typeId, declared);
        this.slots = [...declared.slots];
    }

    #held() {
        return this.stack().enchantments ?? [];
    }

    getEnchantments() {
        return this.#held().slice();
    }

    getEnchantment(type) {
        return this.#held().find(on => on.type.id === namespaced(type));
    }

    hasEnchantment(type) {
        return this.#held().some(on => on.type.id === namespaced(type));
    }

    canAddEnchantment(enchantment) {
        const asked = enchantmentOf(enchantment);
        return !this.#held().some(on => on.type.id === asked.type.id);
    }

    addEnchantment(enchantment) {
        const added = enchantmentOf(enchantment);
        this.stack().enchantments = [
            ...this.#held().filter(on => on.type.id !== added.type.id), added,
        ];
        unsupported("ItemEnchantableComponent.addEnchantment");
    }

    addEnchantments(enchantments) {
        (enchantments ?? []).forEach(enchantment => this.addEnchantment(enchantment));
    }

    removeEnchantment(type) {
        this.stack().enchantments = this.#held().filter(on => on.type.id !== namespaced(type));
    }

    removeAllEnchantments() {
        this.stack().enchantments = [];
    }
}

/** A colour a pack dyes the stack is answered back, and nothing carries it into the world. */
export class ItemDyeableComponent extends ItemComponent {
    static componentId = "minecraft:dyeable";

    static declaredOn(stack) {
        return itemInfo(stack.typeId).dyeable;
    }

    constructor(stack, typeId, declared) {
        super(stack, typeId, declared);
        this.defaultColor = { ...declared.defaultColor };
    }

    get color() {
        return this.stack().dyeColor ?? { ...this.defaultColor };
    }

    set color(value) {
        this.stack().dyeColor = value;
        unsupported("ItemDyeableComponent.color");
    }
}

/** The shape a potion is thrown in, which Bedrock names apart from the effect it carries. */
export class PotionDeliveryType {
    constructor(id) {
        this.id = id;
        Object.freeze(this);
    }
}

/** Which effect the potion carries is written on the stack in the world, not on its type. */
export class ItemPotionComponent extends ItemComponent {
    static componentId = "minecraft:potion";

    static declaredOn(stack) {
        return itemInfo(stack.typeId).potion;
    }

    constructor(stack, typeId, declared) {
        super(stack, typeId, declared);
        this.potionDeliveryType = new PotionDeliveryType(declared.potionDeliveryType);
    }

    get potionEffectType() {
        return unsupported("ItemPotionComponent.potionEffectType");
    }
}

/**
 * The pages of a writable or a written book, kept on the stack the way its lore is.
 *
 * A page reads and writes as plain text whichever kind of book it is; a signed book stores its
 * pages as text components, and that conversion happens where the stack reaches the world.
 */
export class ItemBookComponent extends ItemComponent {
    static componentId = "minecraft:book";

    static declaredOn(stack) {
        return bookItems.includes(stack.typeId) ? {} : undefined;
    }

    #book() {
        this.stack().book = this.stack().book ?? { signed: false, pages: [] };
        return this.stack().book;
    }

    /** Bedrock raises rather than growing the book when a pack names a page that is not there. */
    #page(pageIndex) {
        const pages = this.#book().pages;
        if (!(pageIndex >= 0 && pageIndex < pages.length)) {
            throw new BookPageContentError(
                "No page " + pageIndex + " in a book of " + pages.length, pageIndex);
        }
        return pages;
    }

    get isSigned() {
        return this.#book().signed;
    }

    get title() {
        return this.#book().title;
    }

    get author() {
        return this.#book().author;
    }

    get pageCount() {
        return this.#book().pages.length;
    }

    get contents() {
        return [...this.#book().pages];
    }

    get rawContents() {
        return this.#book().pages.map(text => ({ text }));
    }

    getPageContent(pageIndex) {
        return this.#book().pages[pageIndex];
    }

    getRawPageContent(pageIndex) {
        const page = this.getPageContent(pageIndex);
        return page === undefined ? undefined : { text: page };
    }

    setContents(contents) {
        this.#book().pages = (contents ?? []).map(rawText);
    }

    setPageContent(pageIndex, content) {
        this.#page(pageIndex)[pageIndex] = rawText(content);
    }

    insertPage(pageIndex, content) {
        this.#book().pages.splice(pageIndex, 0, rawText(content));
    }

    removePage(pageIndex) {
        this.#page(pageIndex).splice(pageIndex, 1);
    }

    /** Signing turns the stack into the other item, which is what carries a title and an author. */
    signBook(title, author) {
        const book = this.#book();
        book.signed = true;
        book.title = title;
        book.author = author;
        this.stack().typeId = "minecraft:written_book";
    }
}

// Every component this mod answers for a stack, by the name a pack asks for it under. Each class
// says for itself whether the kind of item carries it, and undefined means it does not.
const ITEM_COMPONENTS = Object.freeze({
    "minecraft:book": ItemBookComponent,
    "minecraft:compostable": ItemCompostableComponent,
    "minecraft:cooldown": ItemCooldownComponent,
    "minecraft:durability": ItemDurabilityComponent,
    "minecraft:dyeable": ItemDyeableComponent,
    "minecraft:enchantable": ItemEnchantableComponent,
    "minecraft:food": ItemFoodComponent,
    "minecraft:potion": ItemPotionComponent,
});

/**
 * One slot of a container, held as the container it belongs to and the index in it.
 *
 * Every member reads the slot through the bridge rather than caching a stack, so a slot a pack keeps
 * a reference to reports what is in it rather than what was there when it was handed out.
 */
export class ContainerSlot {
    #handle;
    #slot;

    constructor(handle, slot) {
        this.#handle = handle;
        this.#slot = slot;
    }

    #read() {
        const item = bridge.containers.item(this.#handle, this.#slot);
        return item === null ? undefined : itemFrom(JSON.parse(item));
    }

    #write(itemStack) {
        bridge.containers.setItem(this.#handle, this.#slot, itemJson(itemStack));
    }

    /** Reads, changes and writes back in one step, so a setter cannot drop the rest of the stack. */
    #edit(change) {
        const held = this.#read();
        if (held === undefined) return;
        change(held);
        bridge.containers.editItem(this.#handle, this.#slot, itemJson(held));
    }

    get isValid() {
        return this.#slot >= 0 && this.#slot < bridge.containers.size(this.#handle);
    }

    get amount() {
        return this.#read()?.amount ?? 0;
    }

    set amount(value) {
        this.#edit(held => {
            held.amount = value;
        });
    }

    get typeId() {
        return this.#read()?.typeId;
    }

    get type() {
        return this.#read()?.type;
    }

    get maxAmount() {
        return this.#read()?.maxAmount ?? 0;
    }

    get isStackable() {
        return this.#read()?.isStackable ?? false;
    }

    get nameTag() {
        return this.#read()?.nameTag;
    }

    set nameTag(value) {
        this.#edit(held => {
            held.nameTag = value === "" ? undefined : value;
        });
    }

    // Java honours neither of Bedrock's two per-slot restrictions, so both read as the default they
    // have when a pack has not asked for anything, and asking for anything else is reported.

    get keepOnDeath() {
        return false;
    }

    set keepOnDeath(value) {
        if (value) unsupported("ContainerSlot.keepOnDeath");
    }

    get lockMode() {
        return ItemLockMode.none;
    }

    set lockMode(value) {
        if (value !== ItemLockMode.none) unsupported("ContainerSlot.lockMode");
    }

    getItem() {
        return this.#read();
    }

    setItem(itemStack) {
        this.#write(itemStack);
    }

    hasItem() {
        return this.#read() !== undefined;
    }

    getLore() {
        return this.#read()?.getLore() ?? [];
    }

    getRawLore() {
        return this.getLore().map(text => ({ text }));
    }

    setLore(loreList) {
        this.#edit(held => held.setLore(loreList));
    }

    getTags() {
        return this.#read()?.getTags() ?? [];
    }

    hasTag(tag) {
        return this.getTags().includes(tag);
    }

    isStackableWith(itemStack) {
        return this.#read()?.isStackableWith(itemStack) ?? false;
    }

    // Adventure mode reads Java's own block predicates, which this mod does not write.

    getCanDestroy() {
        return [];
    }

    getCanPlaceOn() {
        return [];
    }

    setCanDestroy() {
        unsupported("ContainerSlot.setCanDestroy");
    }

    setCanPlaceOn() {
        unsupported("ContainerSlot.setCanPlaceOn");
    }

    getDynamicProperty(identifier) {
        return this.#read()?.getDynamicProperty(identifier);
    }

    setDynamicProperty(identifier, value) {
        this.#edit(held => held.setDynamicProperty(identifier, value));
    }

    setDynamicProperties(values) {
        this.#edit(held => held.setDynamicProperties(values));
    }

    getDynamicPropertyIds() {
        return this.#read()?.getDynamicPropertyIds() ?? [];
    }

    getDynamicPropertyTotalByteCount() {
        return this.#read()?.getDynamicPropertyTotalByteCount() ?? 0;
    }

    clearDynamicProperties() {
        this.#edit(held => held.clearDynamicProperties());
    }
}

/**
 * The items a player, an entity or a block holds.
 *
 * The handle names where to find the container again rather than holding it, so a container whose
 * chest was broken or whose entity died answers as invalid instead of writing into nothing.
 */
export class Container {
    #handle;

    constructor(handle) {
        this.#handle = handle;
    }

    get isValid() {
        return bridge.containers.isValid(this.#handle);
    }

    get size() {
        return bridge.containers.size(this.#handle);
    }

    /** Bedrock's weight and item rules belong to a container kind Java has nothing that matches. */
    get containerRules() {
        return unsupported("Container.containerRules");
    }

    get emptySlotsCount() {
        return bridge.containers.emptySlots(this.#handle);
    }

    /** Bedrock weighs a bundle's contents. Java has no weight to read, so nothing answers this. */
    get weight() {
        unsupported("Container.weight");
        return 0;
    }

    getItem(slot) {
        const item = bridge.containers.item(this.#handle, slot);
        return item === null ? undefined : itemFrom(JSON.parse(item));
    }

    setItem(slot, itemStack) {
        bridge.containers.setItem(this.#handle, slot, itemJson(itemStack));
    }

    getSlot(slot) {
        return new ContainerSlot(this.#handle, slot);
    }

    addItem(itemStack) {
        const left = bridge.containers.addItem(this.#handle, itemJson(itemStack));
        return left === null ? undefined : itemFrom(JSON.parse(left));
    }

    clearAll() {
        bridge.containers.clearAll(this.#handle);
    }

    moveItem(fromSlot, toSlot, toContainer) {
        bridge.containers.moveItem(this.#handle, fromSlot, toSlot, toContainer.#handle);
    }

    swapItems(slot, otherSlot, otherContainer) {
        bridge.containers.swapItems(this.#handle, slot, otherSlot, otherContainer.#handle);
    }

    transferItem(fromSlot, toContainer) {
        const left = bridge.containers.transferItem(this.#handle, fromSlot, toContainer.#handle);
        return left === null ? undefined : itemFrom(JSON.parse(left));
    }

    contains(itemStack) {
        return this.find(itemStack) !== undefined;
    }

    find(itemStack) {
        return this.#search(slot => sameItem(this.getItem(slot), itemStack), 1);
    }

    findLast(itemStack) {
        return this.#search(slot => sameItem(this.getItem(slot), itemStack), -1);
    }

    firstEmptySlot() {
        return this.#search(slot => this.getItem(slot) === undefined, 1);
    }

    firstItem() {
        return this.#search(slot => this.getItem(slot) !== undefined, 1);
    }

    /** The first slot the test keeps, walking forwards when `step` is 1 and backwards when it is -1. */
    #search(test, step) {
        const size = this.size;
        for (let i = 0; i < size; i++) {
            const slot = step === 1 ? i : size - 1 - i;
            if (test(slot)) return slot;
        }
        return undefined;
    }
}

/** The container a block holds, which is how the block surface reaches one. */
export function containerAt(dimensionId, x, y, z) {
    const handle = bridge.containers.ofBlock(dimensionId, x, y, z);
    return handle === null ? undefined : new Container(handle);
}

/** One effect an entity is under, read at the moment the pack asked for it. */
export class Effect {
    constructor(fields) {
        this.typeId = fields.typeId;
        this.amplifier = fields.amplifier;
        this.displayName = fields.displayName;
        this.duration = fields.duration;
        this.isValid = true;
    }
}

export class EffectType {
    constructor(id) {
        this.id = id;
    }

    /** Bedrock names an effect without its namespace, so `minecraft:speed` reads back as `speed`. */
    getName() {
        return this.id.startsWith("minecraft:") ? this.id.slice("minecraft:".length) : this.id;
    }
}

// The static lookup tables, each a view of one of the game's registries, so what a pack finds is
// what this game has. Bedrock writes a vanilla id without its namespace, and every id handed back
// carries one.

export class EffectTypes {
    static get(identifier) {
        return bridge.registries.hasEffectType(identifier)
            ? new EffectType(namespaced(identifier)) : undefined;
    }

    static getAll() {
        return list(bridge.registries.effectTypes()).map(id => new EffectType(id));
    }
}

export class EntityType {
    constructor(fields) {
        this.id = fields.id;
        this.localizationKey = fields.localizationKey;
    }
}

export class EntityTypes {
    static get(identifier) {
        const found = bridge.registries.entityType(identifier);
        return found === null ? undefined : new EntityType(JSON.parse(found));
    }

    static getAll() {
        return JSON.parse(bridge.registries.entityTypes()).map(fields => new EntityType(fields));
    }
}

/** A pack may build one of these itself, which is why an id nothing answers to is an error here. */
export class EnchantmentType {
    constructor(enchantmentId) {
        const found = bridge.registries.enchantment(enchantmentId);
        if (found === null) {
            throw new EnchantmentTypeUnknownIdError("No enchantment named " + namespaced(enchantmentId));
        }

        const fields = JSON.parse(found);
        this.id = fields.id;
        this.maxLevel = fields.maxLevel;
    }
}

export class EnchantmentTypes {
    static get(enchantmentId) {
        return bridge.registries.enchantment(enchantmentId) === null
            ? undefined : new EnchantmentType(enchantmentId);
    }

    static getAll() {
        return JSON.parse(bridge.registries.enchantments()).map(fields => new EnchantmentType(fields.id));
    }
}

/** An instant effect has no duration, so `durationTicks` is absent on one instead of zero. */
export class PotionEffectType {
    constructor(fields) {
        this.id = fields.id;
        this.durationTicks = fields.durationTicks;
    }
}

export class Potions {
    static getEffectType(potionEffectId) {
        const found = bridge.registries.potionEffectType(potionEffectId);
        return found === null ? undefined : new PotionEffectType(JSON.parse(found));
    }

    static getAllEffectTypes() {
        return JSON.parse(bridge.registries.potionEffectTypes()).map(fields => new PotionEffectType(fields));
    }

    // Java has no registry of potion delivery shapes: a drunk, splash or lingering potion is three
    // items. That leaves nothing to answer a delivery type with, and nothing for `resolve` to build.

    static getDeliveryType() {
        return unsupported("Potions.getDeliveryType");
    }

    static getAllDeliveryTypes() {
        return unsupported("Potions.getAllDeliveryTypes");
    }

    static resolve() {
        return unsupported("Potions.resolve");
    }
}

export class BiomeType {
    constructor(id) {
        this.id = id;
    }

    getTags() {
        return list(bridge.registries.biomeTags(this.id));
    }

    hasTags(tags) {
        const carried = this.getTags();
        return tags.every(tag => carried.includes(namespaced(tag)));
    }
}

export class BiomeTypes {
    static get(typeName) {
        return bridge.registries.hasBiomeType(typeName) ? new BiomeType(namespaced(typeName)) : undefined;
    }

    static getAll() {
        return list(bridge.registries.biomeTypes()).map(id => new BiomeType(id));
    }
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

    // How the name over an entity is drawn. Java draws every one of them the same way, through the
    // renderer rather than off the entity, so neither can be asked nor set per entity.

    get nameplateDepthTested() {
        return unsupported("Entity.nameplateDepthTested");
    }

    get nameplateRenderDistance() {
        return unsupported("Entity.nameplateRenderDistance");
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

    get isSprinting() {
        return bridge.entityMembers.isSprinting(this.id);
    }

    get localizationKey() {
        return bridge.entityMembers.localizationKey(this.id);
    }

    get scoreboardIdentity() {
        return identityOf(this.id);
    }

    getHeadLocation() {
        return vector(bridge.entityHeadLocation(this.id));
    }

    getAABB() {
        const box = bridge.entityMembers.boundingBox(this.id);
        return { center: vector(box), extent: { x: box[3], y: box[4], z: box[5] } };
    }

    /** The block the entity is standing on, which is the one under its feet. */
    getBlockStandingOn() {
        const at = bridge.entityLocation(this.id);
        return new Block(bridge.entityDimension(this.id),
            Math.floor(at[0]), Math.floor(at[1]) - 1, Math.floor(at[2]));
    }

    getAllBlocksStandingOn(options) {
        const dimensionId = bridge.entityDimension(this.id);
        return JSON.parse(bridge.entityMembers.blocksStandingOn(this.id, JSON.stringify(options ?? {})))
            .map(at => new Block(dimensionId, at[0], at[1], at[2]));
    }

    getBlockFromViewDirection(options) {
        const found = bridge.entityMembers.blockFromViewDirection(this.id, JSON.stringify(options ?? {}));
        if (found === null) return undefined;

        const hit = JSON.parse(found);
        return {
            block: new Block(bridge.entityDimension(this.id), hit.x, hit.y, hit.z),
            face: ["Down", "Up", "North", "South", "West", "East"][hit.face] ?? "Up",
            faceLocation: vector(hit.faceLocation),
        };
    }

    /** The entity filter is applied here, where the query a pack wrote is already understood. */
    getEntitiesFromViewDirection(options) {
        return JSON.parse(bridge.entityMembers.entitiesFromViewDirection(this.id, JSON.stringify(options ?? {})))
            .map(hit => ({ entity: entityFor(hit.id), distance: hit.distance }))
            .filter(hit => hit.entity.matches(options ?? {}));
    }

    lookAt(target) {
        bridge.entityMembers.lookAt(this.id, target.x, target.y, target.z);
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

    /** 2.0 folded `(directionX, directionZ, horizontalStrength, verticalStrength)` into a vector. */
    applyKnockback(...given) {
        const [force, vertical] = given.length >= 4
            ? [{ x: given[0] * given[2], z: given[1] * given[2] }, given[3]]
            : [given[0] ?? {}, given[1]];
        bridge.entityMembers.applyKnockback(this.id, force.x ?? 0, force.z ?? 0, vertical ?? 0);
    }

    setOnFire(seconds) {
        return bridge.entityMembers.setOnFire(this.id, seconds);
    }

    extinguishFire() {
        return bridge.entityMembers.extinguishFire(this.id);
    }

    teleport(location, options) {
        return bridge.teleport(this.id, location.x, location.y, location.z, JSON.stringify({
            dimension: options?.dimension?.id,
            rotation: options?.rotation && { x: options.rotation.x, y: options.rotation.y },
            facingLocation: options?.facingLocation && {
                x: options.facingLocation.x, y: options.facingLocation.y, z: options.facingLocation.z,
            },
            keepVelocity: options?.keepVelocity === true,
            checkForBlocks: options?.checkForBlocks === true,
        }));
    }

    tryTeleport(location, options) {
        return this.teleport(location, options) === true;
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
        bridge.playSoundAt(this.id, soundIdOf(soundId), options?.volume ?? 1, options?.pitch ?? 1);
        return new SoundInstance(soundIdOf(soundId));
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

    resetProperty(name) {
        return bridge.entityMembers.resetProperty(this.id, name);
    }

    getDynamicProperty(name) {
        // A property never set crosses as null, and Bedrock answers undefined.
        return storedProperty(bridge.entityDynamicProperty(this.id, name));
    }

    setDynamicProperty(name, value) {
        bridge.setEntityDynamicProperty(this.id, name, value === undefined ? null : value);
    }

    setDynamicProperties(values) {
        Object.entries(values ?? {}).forEach(([name, value]) => this.setDynamicProperty(name, value));
    }

    getDynamicPropertyIds() {
        return list(bridge.entityDynamicPropertyIds(this.id));
    }

    getDynamicPropertyTotalByteCount() {
        return bridge.entityMembers.dynamicPropertyBytes(this.id);
    }

    clearDynamicProperties() {
        for (const name of this.getDynamicPropertyIds()) this.setDynamicProperty(name, undefined);
    }

    triggerEvent(event) {
        bridge.triggerEntityEvent(this.id, event);
    }

    hasComponent(component) {
        return hasComponent(this, normaliseComponent(component));
    }

    getComponent(component) {
        const name = normaliseComponent(component);
        return hasComponent(this, name) ? componentFor(this, name) : undefined;
    }

    getComponents() {
        const names = new Set(list(bridge.entityComponents(this.id)));
        if (hasComponent(this, "minecraft:inventory")) names.add("minecraft:inventory");
        declaredComponents(this).forEach(name => names.add(name));
        return [...names].map(name => componentFor(this, name));
    }

    /**
     * Puts a stack into whatever the entity carries, and answers with what would not fit.
     *
     * An entity with no inventory of its own holds nothing, so the whole stack comes back.
     */
    addItem(itemStack) {
        const container = this.getComponent(EntityInventoryComponent.componentId)?.container;
        return container === undefined ? itemStack : container.addItem(itemStack);
    }

    getEffects() {
        return JSON.parse(bridge.entityEffects(this.id)).map(fields => new Effect(fields));
    }

    getEffect(type) {
        const id = typeof type === "string" ? type : type?.id;
        const wanted = id?.includes(":") ? id : "minecraft:" + id;
        return this.getEffects().find(effect => effect.typeId === wanted);
    }

    addEffect(type, duration, options) {
        bridge.addEffect(this.id, typeof type === "string" ? type : type?.id, duration,
            options?.amplifier ?? 0, options?.showParticles !== false);
    }

    removeEffect(type) {
        return bridge.removeEffect(this.id, typeof type === "string" ? type : type?.id);
    }

    getRiders() {
        return list(bridge.entityRiders(this.id)).map(id => entityFor(id));
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
        // The projectile form of the options carries no cause of its own.
        return bridge.applyDamage(this.id, amount,
            options?.damagingProjectile ? "projectile" : options?.cause ?? "none",
            (options?.damagingEntity ?? options?.damagingProjectile)?.id ?? null);
    }
}

export class Player extends Entity {
    get name() {
        return bridge.playerName(this.id);
    }

    get selectedSlotIndex() {
        return bridge.playerSelectedSlot(this.id);
    }

    set selectedSlotIndex(index) {
        bridge.players.setSelectedSlot(this.id, index);
    }

    get level() {
        return bridge.players.experienceLevel(this.id);
    }

    get xpEarnedAtCurrentLevel() {
        return bridge.players.xpEarnedAtCurrentLevel(this.id);
    }

    get totalXpNeededForNextLevel() {
        return bridge.players.totalXpNeededForNextLevel(this.id);
    }

    get isFlying() {
        return bridge.players.isFlying(this.id);
    }

    get isGliding() {
        return bridge.players.isGliding(this.id);
    }

    get isJumping() {
        return bridge.players.isJumping(this.id);
    }

    get commandPermissionLevel() {
        return bridge.players.commandPermissionLevel(this.id);
    }

    // Java grants one operator flag at a level the server's own settings decide, so a script cannot
    // put a player on a rung of Bedrock's ladder.
    set commandPermissionLevel(level) {
        unsupported("Player.commandPermissionLevel (write)");
    }

    get playerPermissionLevel() {
        return bridge.players.playerPermissionLevel(this.id);
    }

    get camera() {
        return new Camera(this.id);
    }

    get fogSettings() {
        return new FogSettings(this.id);
    }

    get clientSystemInfo() {
        return new ClientSystemInfo(this.id);
    }

    get graphicsMode() {
        return unsupported("Player.graphicsMode");
    }

    get inputInfo() {
        return new InputInfo();
    }

    get inputPermissions() {
        return new PlayerInputPermissions(this.id);
    }

    get isEmoting() {
        return unsupported("Player.isEmoting");
    }

    get locatorBar() {
        return unsupported("Player.locatorBar", new LocatorBar());
    }

    getAimAssist() {
        return new PlayerAimAssist(this.id);
    }

    getControlScheme() {
        return bridge.players.controlScheme(this.id);
    }

    /** With no scheme the player goes back to the one their camera starts on. */
    setControlScheme(controlScheme) {
        const refused = bridge.players.setControlScheme(this.id, controlScheme ?? null);
        if (refused !== null) throw new Error(refused);
    }

    // An override changes what this player's client draws the entity from, and nothing else.

    setPropertyOverrideForEntity(targetEntity, identifier, value) {
        const refusal = bridge.players.overrideProperty(this.id, targetEntity?.id, identifier, JSON.stringify(value ?? null));
        if (refusal !== null) throw new Error(refusal);
    }

    removePropertyOverrideForEntity(targetEntity, identifier) {
        const refusal = bridge.players.overrideProperty(this.id, targetEntity?.id, identifier, null);
        if (refusal !== null) throw new Error(refusal);
    }

    clearPropertyOverridesForEntity(targetEntity) {
        const id = typeof targetEntity === "string" ? targetEntity : targetEntity?.id;
        if (!bridge.players.clearPropertyOverrides(this.id, id)) throw new Error("The entity is not valid.");
    }

    get onScreenDisplay() {
        return new ScreenDisplay(this.id);
    }

    sendMessage(message) {
        bridge.players.sendMessage(this.id, JSON.stringify(message));
    }

    getGameMode() {
        return bridge.playerGameMode(this.id);
    }

    setGameMode(mode) {
        bridge.setPlayerGameMode(this.id, mode ?? bridge.players.defaultGameMode());
    }

    // Sent down this player's own connection, so nobody else hears it.

    playSound(soundId, options) {
        if (options?.location !== undefined) unsupported("PlayerSoundOptions.location");
        bridge.playSoundTo(this.id, soundIdOf(soundId), options?.volume ?? 1, options?.pitch ?? 1);
        return new SoundInstance(soundIdOf(soundId), this);
    }

    // Java has no track queue, so a queued track starts now, which is what `/music queue` does too.

    playMusic(trackId, options) {
        bridge.players.playMusic(this.id, trackId, JSON.stringify(options ?? {}));
    }

    queueMusic(trackId, options) {
        this.playMusic(trackId, options);
    }

    stopMusic() {
        bridge.players.stopMusic(this.id);
    }

    spawnParticle(effectName, location, molangVariables) {
        if (molangVariables !== undefined) unsupported("Player.spawnParticle molangVariables");
        bridge.players.spawnParticle(this.id, effectName, location.x, location.y, location.z);
    }

    startItemCooldown(category, ticks) {
        bridge.players.startItemCooldown(this.id, category, ticks);
    }

    getItemCooldown(category) {
        return bridge.players.itemCooldown(this.id, category);
    }

    addExperience(amount) {
        return bridge.players.addExperience(this.id, amount);
    }

    addLevels(amount) {
        return bridge.players.addLevels(this.id, amount);
    }

    getTotalXp() {
        return bridge.players.totalXp(this.id);
    }

    resetLevel() {
        bridge.players.resetLevel(this.id);
    }

    getSpawnPoint() {
        const spawn = bridge.players.spawnPoint(this.id);
        if (spawn === null) return undefined;

        const at = JSON.parse(spawn);
        return { dimension: dimensionOf(at.dimension), x: at.x, y: at.y, z: at.z };
    }

    setSpawnPoint(spawnPoint) {
        bridge.players.setSpawnPoint(this.id, spawnPoint?.dimension?.id ?? null,
            Math.floor(spawnPoint?.x ?? 0), Math.floor(spawnPoint?.y ?? 0), Math.floor(spawnPoint?.z ?? 0));
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

/**
 * The title, subtitle and action bar one player is being shown, and which pieces of their heads-up
 * display are hidden, which is the set `/hud` changes as well.
 */
export class ScreenDisplay {
    #playerId;

    constructor(playerId) {
        this.#playerId = playerId;
    }

    get isValid() {
        return bridge.entityIsValid(this.#playerId);
    }

    setActionBar(text) {
        bridge.players.setActionBar(this.#playerId, JSON.stringify(text));
    }

    setTitle(title, options) {
        bridge.players.setTitle(this.#playerId, JSON.stringify(title), JSON.stringify(options ?? null));
    }

    updateSubtitle(subtitle) {
        bridge.players.updateSubtitle(this.#playerId, JSON.stringify(subtitle));
    }

    getHiddenHudElements() {
        return Array.from(bridge.players.hiddenHud(this.#playerId));
    }

    hideAllExcept(hudElements) {
        bridge.players.hideHudExcept(this.#playerId, JSON.stringify(hudElements ?? []));
    }

    isForcedHidden(hudElement) {
        return this.getHiddenHudElements().includes(hudElement);
    }

    resetHudElementsVisibility() {
        bridge.players.changeHud(this.#playerId, false, null);
    }

    /** `/hud` for this one player, on every piece when none are named. */
    setHudVisibility(visible, hudElements) {
        bridge.players.changeHud(this.#playerId, visible === HudVisibility.Hide,
            hudElements === undefined ? null : JSON.stringify(hudElements));
    }
}

/** The strongest shake Bedrock lets one event ask for. */
const MAX_SHAKE = 4;

/** `InOutCubic`, which a script writes, as `in_out_cubic`, which `/camera` writes. */
function easeName(easingType) {
    return (easingType ?? EasingType.Linear).replace(/([a-z])([A-Z])/g, "$1_$2").toLowerCase();
}

/**
 * The camera one player sees through.
 *
 * A preset, its placement, a target, an attachment, a spline and a shake are what `/camera` and
 * `/camerashake` send, and the player's client draws them. Fades and the field of view are drawn by
 * Bedrock's client off a packet Java has no counterpart for, so each of those reports itself rather
 * than doing nothing.
 */

export class Camera {
    #playerId;

    constructor(playerId) {
        this.#playerId = playerId;
    }

    get isValid() {
        return bridge.entityIsValid(this.#playerId);
    }

    attachToEntity(options) {
        const target = options?.entity?.id;
        if (target === undefined) return;
        const refused = bridge.players.attachCamera(this.#playerId, target, options?.locator ?? null);
        if (refused !== null) throw new Error(refused);
    }

    clear() {
        bridge.players.clearCamera(this.#playerId);
    }

    /** `/camerashake add` for this one player, whose client draws the shake. */
    addShake(options) {
        const intensity = options?.intensity ?? 1;
        const duration = options?.duration ?? 1;
        if (!(intensity > 0 && intensity <= MAX_SHAKE) || !(duration > 0)) {
            throw new RangeError("A camera shake takes an intensity above zero and at most "
                + MAX_SHAKE + ", for a length of time above zero.");
        }
        bridge.players.shakeCamera(this.#playerId, intensity, duration,
            options?.type === CameraShakeType.Rotational);
    }

    stopShaking() {
        bridge.players.shakeCamera(this.#playerId, 0, 0, false);
    }

    fade() {
        unsupported("Camera.fade");
    }

    /**
     * `/camera play_spline` with the curve stated here, written in the spelling a pack's spline file
     * uses so one reader takes both.
     */
    playAnimation(splineType, cameraAnimationOptions) {
        const frame = (field, key) => ({
            time: key.timeSeconds ?? 0,
            [field]: field === "progress" ? key.alpha ?? 0 : [key.rotation?.x ?? 0, key.rotation?.y ?? 0, key.rotation?.z ?? 0],
            easing: easeName(key.easingFunc),
        });
        const animation = cameraAnimationOptions?.animation ?? {};
        const refused = bridge.players.playCameraSpline(this.#playerId, JSON.stringify({
            spline_type: splineType instanceof CatmullRomSpline ? "catmull_rom" : "linear",
            total_time: cameraAnimationOptions?.totalTimeSeconds ?? 0,
            control_points: (splineType?.controlPoints ?? []).map(point => ({ position: [point.x, point.y, point.z] })),
            progress_key_frames: (animation.progressKeyFrames ?? []).map(key => frame("progress", key)),
            rotation_key_frames: (animation.rotationKeyFrames ?? []).map(key => frame("rotation", key)),
        }));
        if (refused !== null) throw new Error(refused);
    }

    /** `/camera set` for this one player, with a fixed boom's offsets and a free camera's target. */
    setCamera(cameraPreset, setOptions) {
        this.#set(cameraPreset, {
            location: setOptions?.location,
            rotation: setOptions?.rotation,
            facingLocation: setOptions?.facingLocation,
            facingEntity: setOptions?.facingEntity?.id,
            viewOffset: setOptions?.viewOffset,
            entityOffset: setOptions?.entityOffset,
            targetEntity: setOptions?.targetEntity?.id,
            offsetFromTargetCenter: setOptions?.offsetFromTargetCenter,
        }, setOptions?.easeOptions);
    }

    /** `/camera set <preset> default`: the preset's own placement, with nothing stated over it. */
    setDefaultCamera(cameraPreset, easeOptions) {
        this.#set(cameraPreset, {}, easeOptions);
    }

    /** Bedrock writes an ease `InOutCubic` here and `in_out_cubic` in `/camera`. */
    #set(cameraPreset, placement, easeOptions) {
        const ease = easeOptions === undefined ? {} : {
            easeTime: easeOptions.easeTime ?? 1,
            easeType: easeName(easeOptions.easeType),
        };
        const refused = bridge.players.setCamera(this.#playerId, cameraPreset,
            JSON.stringify({ ...placement, ...ease }));
        if (refused !== null) throw new Error(refused);
    }

    setFov() {
        unsupported("Camera.setFov");
    }
}

/** What a fog stack throws for a fog no pack describes or a stack holding more than it may. */
export class FogSettingsError extends Error {}

/**
 * The fog stack `/fog` keeps for one player, which the game draws the top of.
 *
 * A layer is named by the fog it draws and by a tag the pack chose; one pushed without a tag carries
 * Bedrock's own `untagged`.
 */
export class FogSettings {
    #playerId;

    constructor(playerId) {
        this.#playerId = playerId;
    }

    getStack() {
        return list(bridge.players.fogs(this.#playerId));
    }

    getTags() {
        return list(bridge.players.fogTags(this.#playerId));
    }

    pop(tag) {
        return bridge.players.popFog(this.#playerId, tag ?? null) ?? undefined;
    }

    push(fogId, tag) {
        const at = bridge.players.pushFog(this.#playerId, fogId, tag ?? null);
        if (at < 0) {
            throw new FogSettingsError("No add-on describes a fog called " + fogId
                + ", or the stack already holds as many fogs as it may.");
        }
        return at;
    }

    remove(tag) {
        return bridge.players.removeFog(this.#playerId, tag ?? null);
    }

    setStack(fogIds, tag) {
        if (!bridge.players.replaceFogs(this.#playerId, JSON.stringify(fogIds ?? []), tag ?? null)) {
            throw new FogSettingsError("A fog stack holds at most sixteen fogs, each one an add-on "
                + "describes.");
        }
    }
}

/**
 * The device one player is on.
 *
 * A Java client tells the server its language and its render distance and nothing else about itself,
 * so the memory and graphics tiers Bedrock publishes have no answer here.
 */
export class ClientSystemInfo {
    #playerId;

    constructor(playerId) {
        this.#playerId = playerId;
    }

    get locale() {
        return bridge.players.locale(this.#playerId) ?? unsupported("ClientSystemInfo.locale");
    }

    get maxRenderDistance() {
        return bridge.players.renderDistance(this.#playerId)
            ?? unsupported("ClientSystemInfo.maxRenderDistance");
    }

    get memoryTier() {
        return unsupported("ClientSystemInfo.memoryTier");
    }

    get platformType() {
        return unsupported("ClientSystemInfo.platformType");
    }
}

/** What a Bedrock client reports about the controls being held, which a Java client never sends. */
export class InputInfo {
    get lastInputModeUsed() {
        return unsupported("InputInfo.lastInputModeUsed");
    }

    get touchOnlyAffectsHotbar() {
        return unsupported("InputInfo.touchOnlyAffectsHotbar");
    }

    getButtonState() {
        return unsupported("InputInfo.getButtonState");
    }

    getMovementVector() {
        unsupported("InputInfo.getMovementVector");
        return { x: 0, y: 0 };
    }
}

/**
 * The Molang variables a pack hands to a particle it spawns.
 *
 * A Bedrock particle reads these out of the emitter it is drawn from. Java's particles carry no
 * expressions at all, so nothing downstream could read a value that was stored here and each of
 * these says so instead.
 */
export class MolangVariableMap {
    setColorRGB() {
        unsupported("MolangVariableMap.setColorRGB");
    }

    setColorRGBA() {
        unsupported("MolangVariableMap.setColorRGBA");
    }

    setFloat() {
        unsupported("MolangVariableMap.setFloat");
    }

    setSpeedAndDirection() {
        unsupported("MolangVariableMap.setSpeedAndDirection");
    }

    setVector3() {
        unsupported("MolangVariableMap.setVector3");
    }
}

/** The kinds of input one player may give, which is the set `/inputpermission` changes as well. */
export class PlayerInputPermissions {
    #playerId;

    constructor(playerId) {
        this.#playerId = playerId;
    }

    isPermissionCategoryEnabled(permissionCategory) {
        return bridge.players.inputEnabled(this.#playerId, permissionCategory);
    }

    setPermissionCategory(permissionCategory, isEnabled) {
        bridge.players.setInputEnabled(this.#playerId, permissionCategory, isEnabled);
    }
}

function requireNamespace(identifier) {
    if (typeof identifier !== "string" || !identifier.includes(":")) {
        throw new NamespaceNameError("An aim assist id needs a namespace, and " + identifier + " has none.");
    }
}

/** A category the world holds, as the bridge answered it. */
export class AimAssistCategory {
    #json;

    constructor(json) {
        this.#json = json;
    }

    get defaultBlockPriority() {
        return this.#json.priorities.block_default;
    }

    get defaultEntityPriority() {
        return this.#json.priorities.entity_default;
    }

    get identifier() {
        return this.#json.name;
    }

    getBlockPriorities() {
        return { ...this.#json.priorities.blocks };
    }

    getBlockTagPriorities() {
        return { ...this.#json.priorities.block_tags };
    }

    getEntityPriorities() {
        return { ...this.#json.priorities.entities };
    }

    getEntityTypeFamilyPriorities() {
        return { ...this.#json.priorities.entity_type_families };
    }
}

/** What `AimAssistRegistry.addCategory` makes a category from. */
export class AimAssistCategorySettings {
    #identifier;
    #blocks = {};
    #blockTags = {};
    #entities = {};
    #families = {};

    constructor(identifier) {
        this.#identifier = identifier;
        this.defaultBlockPriority = 1;
        this.defaultEntityPriority = 1;
    }

    get identifier() {
        return this.#identifier;
    }

    getBlockPriorities() {
        return { ...this.#blocks };
    }

    getBlockTagPriorities() {
        return { ...this.#blockTags };
    }

    getEntityPriorities() {
        return { ...this.#entities };
    }

    getEntityTypeFamilyPriorities() {
        return { ...this.#families };
    }

    setBlockPriorities(blockPriorities) {
        this.#blocks = { ...blockPriorities };
    }

    setBlockTagPriorities(blockTagPriorities) {
        this.#blockTags = { ...blockTagPriorities };
    }

    setEntityPriorities(entityPriorities) {
        this.#entities = { ...entityPriorities };
    }

    setEntityTypeFamilyPriorities(entityTypeFamilyPriorities) {
        this.#families = { ...entityTypeFamilyPriorities };
    }
}

/** A preset the world holds, as the bridge answered it. */
export class AimAssistPreset {
    #json;

    constructor(json) {
        this.#json = json;
    }

    get defaultItemSettings() {
        return this.#json.default_item_settings;
    }

    get handSettings() {
        return this.#json.hand_settings;
    }

    get identifier() {
        return this.#json.identifier;
    }

    getExcludedBlockTagTargets() {
        return [...this.#json.excluded_block_tags];
    }

    getExcludedBlockTargets() {
        return [...this.#json.excluded_blocks];
    }

    getExcludedEntityTargets() {
        return [...this.#json.excluded_entities];
    }

    getExcludedEntityTypeFamilyTargets() {
        return [...this.#json.excluded_entity_type_families];
    }

    getItemSettings() {
        return { ...this.#json.item_settings };
    }

    getLiquidTargetingItems() {
        return [...this.#json.liquid_targeting_list];
    }
}

/** What `AimAssistRegistry.addPreset` makes a preset from. */
export class AimAssistPresetSettings {
    #identifier;
    #items = {};
    #blockTags;
    #blocks;
    #entities;
    #families;
    #liquids;

    constructor(identifier) {
        this.#identifier = identifier;
        this.defaultItemSettings = undefined;
        this.handSettings = undefined;
    }

    get identifier() {
        return this.#identifier;
    }

    getExcludedBlockTagTargets() {
        return this.#blockTags === undefined ? undefined : [...this.#blockTags];
    }

    getExcludedBlockTargets() {
        return this.#blocks === undefined ? undefined : [...this.#blocks];
    }

    getExcludedEntityTargets() {
        return this.#entities === undefined ? undefined : [...this.#entities];
    }

    getExcludedEntityTypeFamilyTargets() {
        return this.#families === undefined ? undefined : [...this.#families];
    }

    getItemSettings() {
        return { ...this.#items };
    }

    getLiquidTargetingItems() {
        return this.#liquids === undefined ? undefined : [...this.#liquids];
    }

    setExcludedBlockTagTargets(blockTagTargets) {
        this.#blockTags = blockTagTargets === undefined ? undefined : [...blockTagTargets];
    }

    setExcludedBlockTargets(blockTargets) {
        this.#blocks = blockTargets === undefined ? undefined : [...blockTargets];
    }

    setExcludedEntityTargets(entityTargets) {
        this.#entities = entityTargets === undefined ? undefined : [...entityTargets];
    }

    setExcludedEntityTypeFamilyTargets(entityTypeFamilyTargets) {
        this.#families = entityTypeFamilyTargets === undefined ? undefined : [...entityTypeFamilyTargets];
    }

    setItemSettings(itemSettings) {
        Object.values(itemSettings).forEach(requireNamespace);
        this.#items = { ...itemSettings };
    }

    setLiquidTargetingItems(items) {
        this.#liquids = items === undefined ? undefined : [...items];
    }
}

/**
 * The presets and categories the world holds: the game's own default of each, every one the packs'
 * files describe, and every one a script added.
 */
export class AimAssistRegistry {
    static DefaultCategoryId = "minecraft:default";
    static DefaultPresetId = "minecraft:aim_assist_default";

    addCategory(category) {
        requireNamespace(category.identifier);
        return new AimAssistCategory(JSON.parse(bridge.worlds.addAimAssistCategory(JSON.stringify({
            name: category.identifier,
            priorities: {
                entity_default: category.defaultEntityPriority,
                block_default: category.defaultBlockPriority,
                entities: category.getEntityPriorities(),
                blocks: category.getBlockPriorities(),
                entity_type_families: category.getEntityTypeFamilyPriorities(),
                block_tags: category.getBlockTagPriorities(),
            },
        }))));
    }

    addPreset(preset) {
        requireNamespace(preset.identifier);
        return new AimAssistPreset(JSON.parse(bridge.worlds.addAimAssistPreset(JSON.stringify({
            identifier: preset.identifier,
            item_settings: preset.getItemSettings(),
            default_item_settings: preset.defaultItemSettings,
            hand_settings: preset.handSettings,
            exclusion_list: [...(preset.getExcludedBlockTargets() ?? []), ...(preset.getExcludedEntityTargets() ?? [])],
            excluded_entity_type_families: preset.getExcludedEntityTypeFamilyTargets() ?? [],
            excluded_block_tags: preset.getExcludedBlockTagTargets() ?? [],
            liquid_targeting_list: preset.getLiquidTargetingItems() ?? [],
        }))));
    }

    getCategories() {
        return JSON.parse(bridge.worlds.aimAssistCategories()).map(json => new AimAssistCategory(json));
    }

    getCategory(categoryId) {
        return this.getCategories().find(category => category.identifier === categoryId);
    }

    getPreset(presetId) {
        return this.getPresets().find(preset => preset.identifier === presetId);
    }

    getPresets() {
        return JSON.parse(bridge.worlds.aimAssistPresets()).map(json => new AimAssistPreset(json));
    }
}

/** One player's aim assist, which is what `/aimassist` sets as well. */
export class PlayerAimAssist {
    #playerId;

    constructor(playerId) {
        this.#playerId = playerId;
    }

    get settings() {
        const json = bridge.players.aimAssist(this.#playerId);
        if (json === null) return undefined;

        const settings = JSON.parse(json);
        return {
            presetId: settings.preset,
            targetMode: settings.target_mode === "distance" ? AimAssistTargetMode.Distance : AimAssistTargetMode.Angle,
            viewAngle: { x: settings.angle[0], y: settings.angle[1] },
            distance: settings.distance,
        };
    }

    set(settings) {
        if (settings === undefined || settings === null) {
            bridge.players.setAimAssist(this.#playerId, null);
            return;
        }
        requireNamespace(settings.presetId);
        const refused = bridge.players.setAimAssist(this.#playerId, JSON.stringify({
            preset: settings.presetId,
            target_mode: settings.targetMode === AimAssistTargetMode.Distance ? "distance" : "angle",
            angle: [settings.viewAngle?.x ?? 50, settings.viewAngle?.y ?? 50],
            distance: settings.distance ?? 8,
        }));
        if (refused === null) return;
        if (refused.includes("must be between")) throw new RangeError(refused);
        throw new Error(refused);
    }
}

function normaliseComponent(component) {
    const name = typeof component === "string" ? component : String(component);
    return name.includes(":") ? name : "minecraft:" + name;
}

/**
 * Whether the entity carries a component.
 *
 * The inventory is answered by whether a container could be found for the entity, because a player
 * and a chest minecart hold their items in objects the entity component list knows nothing about.
 * Everything the pack declared is answered from the pack, which is the only place it was written.
 */
function hasComponent(entity, name) {
    if (name === "minecraft:inventory") return bridge.containers.ofEntity(entity.id) !== null;
    return (DECLARED_COMPONENTS[name] !== undefined && declaredComponents(entity).includes(name))
        || bridge.entityHasComponent(entity.id, name);
}

function declaredComponents(entity) {
    return list(bridge.entityMembers.packComponents(entity.id))
        .filter(name => DECLARED_COMPONENTS[name] !== undefined);
}

/**
 * The component class each name is answered with.
 *
 * A name with no entry here is answered with the base class, which carries the entity, the name and
 * whether it is still valid. That is everything Bedrock's own component carries for the ones that
 * are a flag rather than a value.
 */
const COMPONENT_CLASSES = Object.freeze({
    "minecraft:health": EntityHealthComponent,
    "minecraft:movement": EntityMovementComponent,
    "minecraft:player.hunger": EntityHungerComponent,
    "minecraft:player.saturation": EntitySaturationComponent,
    "minecraft:inventory": EntityInventoryComponent,
    "minecraft:equippable": EntityEquippableComponent,
    "minecraft:type_family": EntityTypeFamilyComponent,
    "minecraft:riding": EntityRidingComponent,
    "minecraft:is_baby": EntityIsBabyComponent,
    "minecraft:item": EntityItemComponent,
    "minecraft:onfire": EntityOnFireComponent,
    "minecraft:variant": EntityVariantComponent,
    "minecraft:mark_variant": EntityMarkVariantComponent,
    "minecraft:skin_id": EntitySkinIdComponent,
});

function componentFor(entity, name) {
    const build = DECLARED_COMPONENTS[name] ?? COMPONENT_CLASSES[name] ?? EntityComponent;
    return new build(entity, name);
}

function itemFrom(handle) {
    const stack = new ItemStack(handle.typeId, handle.amount);
    stack.nameTag = handle.nameTag === "" ? undefined : handle.nameTag;
    stack.damage = handle.damage ?? 0;
    stack.maxDurability = handle.maxDurability ?? 0;
    stack.lore = handle.lore ?? [];
    stack.properties = handle.properties ?? {};
    stack.book = handle.book;
    return stack;
}

/** The other way round, for a stack a pack is putting into a container. Null for an empty slot. */
function itemJson(stack) {
    if (stack === undefined || stack === null) return null;
    return JSON.stringify({
        typeId: stack.typeId,
        amount: stack.amount ?? 1,
        damage: stack.damage ?? 0,
        nameTag: stack.nameTag ?? "",
        lore: stack.lore ?? [],
        properties: stack.properties ?? {},
        book: stack.book,
    });
}

/**
 * The one wrapper an id gets, so a pack comparing two references to one entity sees them as equal.
 *
 * An id may be met before the game will say whose it is: a player's arrives while they are still
 * joining, and the player list does not have them yet. Caching that answer would leave a plain
 * entity standing in for somebody for the rest of the session, with none of the player members on
 * it, so a caller holding something it already knows to be a player says so and the wrapper is
 * replaced rather than kept.
 */
export function entityFor(id, knownPlayer) {
    if (id === null || id === undefined) return undefined;

    let wrapper = entityCache.get(id);
    if (wrapper === undefined || (knownPlayer === true && !(wrapper instanceof Player))) {
        wrapper = knownPlayer === true || bridge.entityIsPlayer(id) ? new Player(id) : new Entity(id);
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
        return list(bridge.dimensionEntities(this.id, JSON.stringify(options ?? {}))).map(id => entityFor(id));
    }

    getPlayers(options) {
        return list(bridge.dimensionEntities(this.id,
            JSON.stringify({ ...(options ?? {}), type: "minecraft:player" }))).map(id => entityFor(id, true));
    }

    getEntitiesAtBlockLocation(location) {
        return this.getEntities({ location, maxDistance: 1 });
    }

    getBlock(location) {
        const x = Math.floor(location.x);
        const y = Math.floor(location.y);
        const z = Math.floor(location.z);
        return bridge.blocks.exists(this.id, x, y, z) ? new Block(this.id, x, y, z) : undefined;
    }

    getTopmostBlock(locationXZ, minHeight) {
        const x = Math.floor(locationXZ.x);
        const z = Math.floor(locationXZ.z);
        const from = minHeight === undefined ? this.heightRange.max : Math.floor(minHeight);
        const y = bridge.worlds.topmostBlockY(this.id, x, z, from);
        return y === null ? undefined : new Block(this.id, x, y, z);
    }

    getBlockAbove(location, options) {
        return this.#blockAt(bridge.worlds.blockAbove(this.id, Math.floor(location.x),
            Math.floor(location.y), Math.floor(location.z), JSON.stringify(options ?? {})));
    }

    getBlockBelow(location, options) {
        return this.#blockAt(bridge.worlds.blockBelow(this.id, Math.floor(location.x),
            Math.floor(location.y), Math.floor(location.z), JSON.stringify(options ?? {})));
    }

    #blockAt(triple) {
        return triple === null ? undefined : new Block(this.id, triple[0], triple[1], triple[2]);
    }

    getBlockFromRay(location, direction, options) {
        const found = bridge.worlds.blockFromRay(this.id, location.x, location.y, location.z,
            direction.x, direction.y, direction.z, JSON.stringify(options ?? {}));
        if (found === null) return undefined;

        const hit = JSON.parse(found);
        return {
            block: new Block(this.id, hit.x, hit.y, hit.z),
            face: hit.face,
            faceLocation: { x: hit.faceX, y: hit.faceY, z: hit.faceZ },
        };
    }

    /** The entity filter is applied here, where the query a pack wrote is already understood. */
    getEntitiesFromRay(location, direction, options) {
        return JSON.parse(bridge.worlds.entitiesFromRay(this.id, location.x, location.y, location.z,
            direction.x, direction.y, direction.z, JSON.stringify(options ?? {})))
            .map(hit => ({ entity: entityFor(hit.entity), distance: hit.distance }))
            .filter(hit => hit.entity.matches(options ?? {}));
    }

    createExplosion(location, radius, options) {
        return bridge.worlds.createExplosion(this.id, location.x, location.y, location.z, radius,
            JSON.stringify({ ...(options ?? {}), source: options?.source?.id }));
    }

    /**
     * The positions a fill wrote, as the volume Bedrock hands back.
     *
     * The bridge fills with a block type's defaults, so filling with a permutation asks which
     * positions the fill would take and writes each of them with the states the pack chose.
     */
    fillBlocks(volume, block, options) {
        const states = block instanceof BlockPermutation ? block.getAllStates() : undefined;
        const written = [];
        for (const [min, max] of boxesOf(volume)) {
            const found = states === undefined
                ? bridge.worlds.fillBlocks(this.id, min.x, min.y, min.z, max.x, max.y, max.z,
                    typeIdOf(block), JSON.stringify(options ?? {}))
                : bridge.worlds.getBlocks(this.id, min.x, min.y, min.z, max.x, max.y, max.z,
                    JSON.stringify(options?.blockFilter ?? {}), options?.ignoreChunkBoundErrors === true);
            written.push(...positionsIn(found));
        }

        if (states !== undefined) {
            const chosen = JSON.stringify(states);
            for (const at of written) {
                bridge.blocks.setPermutation(this.id, at.x, at.y, at.z, block.type.id, chosen);
            }
        }
        return new ListBlockVolume(written);
    }

    cloneBlocks(beginLocation, endLocation, destination, cloneMode, filter) {
        const from = blockPosition(beginLocation);
        const to = blockPosition(endLocation);
        const at = blockPosition(destination);
        if (!bridge.worlds.cloneBlocks(this.id, from.x, from.y, from.z, to.x, to.y, to.z,
            at.x, at.y, at.z, cloneMode ?? CloneMode.Copy, JSON.stringify(filter ?? {}))) {
            throw new Error("The blocks could not be cloned: the region is not loaded, or a copy "
                + "would land on the region it copies from.");
        }
    }

    getBlocks(volume, filter, allowUnloadedChunks) {
        const found = [];
        for (const [min, max] of boxesOf(volume)) {
            found.push(...positionsIn(bridge.worlds.getBlocks(this.id, min.x, min.y, min.z,
                max.x, max.y, max.z, JSON.stringify(filter ?? {}), allowUnloadedChunks === true)));
        }
        return new ListBlockVolume(found);
    }

    containsBlock(volume, filter, allowUnloadedChunks) {
        return boxesOf(volume).some(([min, max]) => bridge.worlds.containsBlock(this.id,
            min.x, min.y, min.z, max.x, max.y, max.z,
            JSON.stringify(filter ?? {}), allowUnloadedChunks === true));
    }

    /** A volume of scattered positions is one box each, and a superset is answered by any of them. */
    containsBiomes(volume, biomeFilter, isSuperset) {
        const boxes = boxesOf(volume);
        const answers = ([min, max]) => bridge.worlds.containsBiomes(this.id, min.x, min.y, min.z,
            max.x, max.y, max.z, JSON.stringify(biomeFilter ?? {}), isSuperset === true);
        return isSuperset === true ? boxes.some(answers) : boxes.every(answers);
    }

    getBiome(location) {
        const id = bridge.worlds.biome(this.id, Math.floor(location.x), Math.floor(location.y),
            Math.floor(location.z));
        return id === null ? undefined : new BiomeType(id);
    }

    calculateClosestBiomeFromSeed() {
        return unsupported("Dimension.calculateClosestBiomeFromSeed");
    }

    getLightLevel(location) {
        return bridge.worlds.lightLevel(this.id, Math.floor(location.x), Math.floor(location.y),
            Math.floor(location.z));
    }

    getSkyLightLevel(location) {
        return bridge.worlds.skyLightLevel(this.id, Math.floor(location.x), Math.floor(location.y),
            Math.floor(location.z));
    }

    setWeather(weatherType, duration) {
        bridge.worlds.setWeather(this.id, weatherType, duration ?? 0);
    }

    getWeather() {
        return bridge.worlds.weather(this.id);
    }

    placeFeature(featureName, location, shouldThrow) {
        const placed = bridge.worlds.placeFeature(this.id, featureName, Math.floor(location.x),
            Math.floor(location.y), Math.floor(location.z));
        if (!placed && shouldThrow === true) throw new Error("Could not place feature " + featureName);
        return placed;
    }

    placeFeatureRule(featureRuleName, location) {
        return bridge.worlds.placeFeatureRule(this.id, featureRuleName, Math.floor(location.x),
            Math.floor(location.y), Math.floor(location.z));
    }

    /** A dimension's particle reaches everybody in it, and the bridge sends one to one player. */
    spawnParticle(effectName, location, molangVariables) {
        if (molangVariables !== undefined) unsupported("Dimension.spawnParticle molangVariables");
        for (const player of this.getPlayers()) {
            bridge.players.spawnParticle(player.id, effectName, location.x, location.y, location.z);
        }
    }

    get localizationKey() {
        return unsupported("Dimension.localizationKey");
    }

    isChunkLoaded(location) {
        return bridge.isChunkLoaded(this.id, location.x ?? location.from?.x ?? 0, location.z ?? location.from?.z ?? 0);
    }

    setBlockType(location, type) {
        bridge.setBlockType(this.id, Math.floor(location.x), Math.floor(location.y), Math.floor(location.z),
            typeIdOf(type));
    }

    setBlockPermutation(location, permutation) {
        bridge.blocks.setPermutation(this.id, Math.floor(location.x), Math.floor(location.y),
            Math.floor(location.z), permutation.type.id, JSON.stringify(permutation.getAllStates()));
    }

    spawnEntity(identifier, location, options) {
        const type = typeIdOf(identifier);
        const id = bridge.worlds.spawnEntity(this.id, type, location.x, location.y, location.z,
            JSON.stringify(options ?? {}));
        if (id === null) throw new Error("Could not spawn " + type);
        return entityFor(id);
    }

    spawnItem(itemStack, location) {
        const id = bridge.spawnItem(this.id, itemStack.typeId, itemStack.amount,
            location.x, location.y, location.z);
        return id === null ? undefined : entityFor(id);
    }

    playSound(soundId, location, options) {
        bridge.playSoundAtLocation(this.id, soundIdOf(soundId), location.x, location.y, location.z,
            options?.volume ?? 1, options?.pitch ?? 1);
        return new SoundInstance(soundIdOf(soundId));
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

export class DimensionType {
    constructor(typeId) {
        this.typeId = typeId;
        Object.freeze(this);
    }
}

export class DimensionTypes {
    static get(id) {
        return list(bridge.dimensionIds()).includes(id) ? new DimensionType(id) : undefined;
    }

    static getAll() {
        return list(bridge.dimensionIds()).map(id => new DimensionType(id));
    }
}

/**
 * What a listener asked to hear about, from the options it subscribed with.
 *
 * Bedrock narrows an event before the listener runs, and a pack relies on that: a listener on
 * `dataDrivenEntityTrigger` subscribed for one entity type often never checks the type itself. A
 * type a pack names is compared under the id `Entity.typeId` reports, so `minecraft:villager` and
 * `minecraft:villager_v2` name the same listener.
 */
class Subscription {
    #options;
    #types;

    constructor(callback, options, event) {
        this.callback = callback;
        this.#options = options ?? undefined;
        this.#types = options?.entityTypes?.map(type => EntityTypes.get(type)?.id ?? namespaced(type));
        for (const name of ["itemFilter", "accessSourceFilter"]) {
            if (options?.[name] !== undefined) bridge.unsupported(event + " options." + name);
        }
    }

    admits(event, subjectOf) {
        const options = this.#options;
        if (options === undefined || subjectOf === undefined) return true;

        const subject = subjectOf(event);
        if (options.entities !== undefined && !options.entities.some(one => one?.id === subject?.id)) return false;
        if (this.#types !== undefined && !this.#types.includes(subject?.typeId)) return false;
        if (options.entityFilter !== undefined && !(subject?.matches?.(options.entityFilter) ?? false)) return false;
        if (options.eventTypes !== undefined && !options.eventTypes.includes(event.eventId)) return false;
        if (options.allowedDamageCauses !== undefined
            && !options.allowedDamageCauses.includes(event.damageSource?.cause)) return false;
        if (options.allowedHealCauses !== undefined
            && !options.allowedHealCauses.includes(event.healSource?.cause)) return false;
        return true;
    }
}

/**
 * The entity each event is about, which is what a listener's options are measured against. An
 * event with no entry here takes no options in Bedrock, so whatever a pack passed it is ignored.
 * The after event of a removal names an entity that is gone, so its id and type stand in for it.
 */
const EVENT_SUBJECTS = {
    dataDrivenEntityTrigger: event => event.entity,
    effectAdd: event => event.entity,
    entityContainerClosed: event => event.entity,
    entityContainerOpened: event => event.entity,
    entityDie: event => event.deadEntity,
    entityHeal: event => event.healedEntity,
    entityHealthChanged: event => event.entity,
    entityHitBlock: event => event.damagingEntity,
    entityHitEntity: event => event.damagingEntity,
    entityHurt: event => event.hurtEntity,
    entityItemDrop: event => event.entity,
    entityItemPickup: event => event.entity,
    entityRemove: event => event.removedEntity ?? { id: event.removedEntityId, typeId: event.typeId },
};

class EventSignal {
    #listeners = [];

    subscribe(callback, options) {
        this.#listeners.push(new Subscription(callback, options, this.name));
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
        const shaped = this.shape ? this.shape(shapeFields(event)) : shapeFields(event);
        const ready = this.payload ? asPayload(this.payload, shaped) : shaped;
        for (const listener of this.#listeners.slice()) {
            if (!listener.admits(ready, this.subject)) continue;
            try {
                listener.callback(ready);
            } catch (error) {
                bridge.reportError(String(error && error.stack ? error.stack : error));
            }
        }
        return ready;
    }
}

function blockFrom(fields) {
    return new Block(fields.dimension, fields.x, fields.y, fields.z);
}

/**
 * Calls one callback of one custom component and says whether the pack cancelled what happened.
 *
 * A component that has no callback by that name is not a mistake: Bedrock calls only what the pack
 * wrote, so a component answering one moment and ignoring the rest is the ordinary case. A callback
 * that throws costs its own call and nothing else, the same as any other listener.
 */
function runCustomComponent(registered, name, callback, argumentsJson) {
    const component = registered.get(name);
    const answer = component?.[callback];
    if (typeof answer !== "function") return false;

    const fields = JSON.parse(argumentsJson);
    const event = {
        block: fields.block === undefined ? undefined : blockFrom(fields.block),
        dimension: fields.dimension === undefined ? undefined : dimensionOf(fields.dimension),
        source: fields.source === undefined ? undefined : entityFor(fields.source),
        player: fields.player === undefined ? undefined : entityFor(fields.player),
        entity: fields.entity === undefined ? undefined : entityFor(fields.entity),
        itemStack: undefined,
        cancel: false,
    };

    try {
        // The second argument is what the block or item wrote as the component's body, which
        // a name listed under `minecraft:custom_components` has none of.
        answer(event, { params: fields.params ?? {} });
    } catch (error) {
        bridge.reportError(String(error && error.stack ? error.stack : error));
    }
    return event.cancel === true;
}

function permutationFrom(fields) {
    return BlockPermutation.resolve(fields.typeId, { ...fields.states });
}

/**
 * The event fields that reach a listener as an object rather than as what Java sent.
 *
 * Only primitives and plain objects cross the boundary, so a block arrives as its coordinates and
 * its dimension id, an item stack as its type and count, and a dimension as its id alone. A field
 * means the same thing in every event that carries it, so one table covers all of them.
 */
const FIELD_SHAPES = {
    beforeItemStack: itemFrom,
    block: blockFrom,
    blockPermutation: permutationFrom,
    brokenBlockPermutation: permutationFrom,
    dimension: dimensionOf,
    explodedBlockPermutation: permutationFrom,
    fromDimension: dimensionOf,
    heldItemStack: itemFrom,
    hitBlock: blockFrom,
    hitBlockPermutation: permutationFrom,
    itemStack: itemFrom,
    itemStackAfterBreak: itemFrom,
    itemStackBeforeBreak: itemFrom,
    toDimension: dimensionOf,
};

// Bedrock spells an absent field `undefined`, and a pack guards one by testing for exactly that.
// Java has only null to hand across for a thing that is not there, so every field that arrived null
// is spelled the way the pack is going to test for before any of them is shaped.
function shapeFields(event) {
    for (const name of Object.keys(event)) {
        if (event[name] === null) event[name] = undefined;
    }
    for (const name of Object.keys(FIELD_SHAPES)) {
        if (event[name] !== undefined) event[name] = FIELD_SHAPES[name](event[name]);
    }
    return event;
}

/**
 * The events that carry more than the plain fields the mod raises them with.
 *
 * An event reaches here as an object of values, and a pack calls methods on some of them, so the
 * signal for those names fills the methods in before any listener sees the event.
 */
const EVENT_SHAPES = {
    dataDrivenEntityTrigger(event) {
        const modifiers = JSON.parse(event.modifiers ?? "[]");
        delete event.modifiers;
        event.getModifiers = () => modifiers.map(modifier => ({
            addedComponentGroups: [...modifier.addedComponentGroups],
            removedComponentGroups: [...modifier.removedComponentGroups],
            componentGroupsToAdd: [...modifier.addedComponentGroups],
            componentGroupsToRemove: [...modifier.removedComponentGroups],
        }));
        return event;
    },

    entityItemDrop(event) {
        // Bedrock hands over a list, and one throw drops one stack.
        event.items = [event.item];
        delete event.item;
        return event;
    },

    weatherChange(event) {
        // The one event that names its dimension by id rather than handing the dimension over.
        event.dimension = event.dimension?.id ?? event.dimension;
        return event;
    },

    explosion(event) {
        const blocks = (event.impactedBlocks ?? []).map(blockFrom);
        delete event.impactedBlocks;
        event.getImpactedBlocks = () => blocks;
        return event;
    },

    pistonActivate(event) {
        // The piston is the component of the block the event already carries.
        event.piston = event.block?.getComponent(BlockPistonComponent.componentId);
        return event;
    },
};

/** Every event name the mod can raise. An unknown name still gets a signal, so subscribing is safe. */
/**
 * The events of one group, each one a signal a pack can subscribe to.
 *
 * Subscribing to a name nothing raises has to be reported. A signal is handed back either way, so
 * the pack's own code runs on, but an unreported one would look like an implemented event that
 * simply never happened, which is what made the coverage report say everything was covered.
 */

/**
 * The class each raised event is published as, against the name a pack subscribes under.
 *
 * A listener is handed an instance of that class rather than a plain object carrying the same
 * fields, so a pack testing `event instanceof PlayerJoinAfterEvent` is answered the way Bedrock
 * answers it. An event with no published class of its own is handed over as it arrives.
 */

/**
 * The event the server sent, made an instance of the class it is published as.
 *
 * The object itself is kept rather than copied onto a new one. A pack cancels a before-event by
 * writing `cancel` back, and the Java side reads that field off the very object it handed over, so
 * a copy would take the cancellation with it. Giving the original the class as its prototype leaves
 * every field where it is: what the server sent stays the event's own, which is what shadows the
 * getter the class declares for a member the server did not send.
 */
function asPayload(kind, event) {
    Object.setPrototypeOf(event, kind.prototype);
    return event;
}

let payloadClasses;

/**
 * The published classes for one group of events, worked out the first time a group is asked for.
 *
 * Built on demand rather than at module scope: the classes are declared further down the file, and
 * a table naming them while the module is still being evaluated reaches them before they exist.
 */
function payloadsFor(group) {
    payloadClasses ??= {
    worldAfter: {
        dataDrivenEntityTrigger: DataDrivenEntityTriggerAfterEvent,
        entityDie: EntityDieAfterEvent, entityHitEntity: EntityHitEntityAfterEvent,
        entityHurt: EntityHurtAfterEvent, entityItemDrop: EntityItemDropAfterEvent,
        entityLoad: EntityLoadAfterEvent, entityRemove: EntityRemoveAfterEvent,
        entitySpawn: EntitySpawnAfterEvent, itemCompleteUse: ItemCompleteUseAfterEvent,
        itemReleaseUse: ItemReleaseUseAfterEvent, itemStartUse: ItemStartUseAfterEvent,
        itemStartUseOn: ItemStartUseOnAfterEvent, itemStopUse: ItemStopUseAfterEvent,
        itemUse: ItemUseAfterEvent, playerBreakBlock: PlayerBreakBlockAfterEvent,
        playerDimensionChange: PlayerDimensionChangeAfterEvent,
        playerGameModeChange: PlayerGameModeChangeAfterEvent,
        playerInputPermissionCategoryChange: PlayerInputPermissionCategoryChangeAfterEvent,
        playerInteractWithBlock: PlayerInteractWithBlockAfterEvent,
        playerInteractWithEntity: PlayerInteractWithEntityAfterEvent,
        playerInventoryItemChange: PlayerInventoryItemChangeAfterEvent,
        playerJoin: PlayerJoinAfterEvent, playerLeave: PlayerLeaveAfterEvent,
        playerPlaceBlock: PlayerPlaceBlockAfterEvent, playerSpawn: PlayerSpawnAfterEvent,
        worldLoad: WorldLoadAfterEvent, effectAdd: EffectAddAfterEvent,
        entityItemPickup: EntityItemPickupAfterEvent, buttonPush: ButtonPushAfterEvent,
        projectileHitEntity: ProjectileHitEntityAfterEvent,
        projectileHitBlock: ProjectileHitBlockAfterEvent,
        leverAction: LeverActionAfterEvent, entityHitBlock: EntityHitBlockAfterEvent,
        entityHeal: EntityHealAfterEvent, entityHealthChanged: EntityHealthChangedAfterEvent,
        weatherChange: WeatherChangeAfterEvent,
        playerHotbarSelectedSlotChange: PlayerHotbarSelectedSlotChangeAfterEvent,
        playerButtonInput: PlayerButtonInputAfterEvent,
        explosion: ExplosionAfterEvent, blockExplode: BlockExplodeAfterEvent,
        pistonActivate: PistonActivateAfterEvent, gameRuleChange: GameRuleChangeAfterEvent,
        pressurePlatePush: PressurePlatePushAfterEvent,
        pressurePlatePop: PressurePlatePopAfterEvent,
        tripWireTrip: TripWireTripAfterEvent, targetBlockHit: TargetBlockHitAfterEvent,
        playerStartBreakingBlock: PlayerStartBreakingBlockAfterEvent,
        playerCancelBreakingBlock: PlayerCancelBreakingBlockAfterEvent,
        playerSwingStart: PlayerSwingStartAfterEvent,
        itemStopUseOn: ItemStopUseOnAfterEvent,
        blockContainerOpened: BlockContainerOpenedAfterEvent,
        blockContainerClosed: BlockContainerClosedAfterEvent,
        entityContainerOpened: EntityContainerOpenedAfterEvent,
        entityContainerClosed: EntityContainerClosedAfterEvent,
        entityStartSneaking: EntityStartSneakingAfterEvent,
        entityStopSneaking: EntityStopSneakingAfterEvent,
        entityTamed: EntityTamedAfterEvent,
        packSettingChange: PackSettingChangeAfterEvent,
    },
    worldBefore: {
        entityTamed: EntityTamedBeforeEvent,
        entityHurt: EntityHurtBeforeEvent, entityRemove: EntityRemoveBeforeEvent,
        itemUse: ItemUseBeforeEvent, playerBreakBlock: PlayerBreakBlockBeforeEvent,
        playerInteractWithBlock: PlayerInteractWithBlockBeforeEvent,
        playerInteractWithEntity: PlayerInteractWithEntityBeforeEvent,
        playerLeave: PlayerLeaveBeforeEvent, effectAdd: EffectAddBeforeEvent,
        entityItemPickup: EntityItemPickupBeforeEvent, entityHeal: EntityHealBeforeEvent,
        weatherChange: WeatherChangeBeforeEvent, explosion: ExplosionBeforeEvent,
        playerGameModeChange: PlayerGameModeChangeBeforeEvent,
    },
    systemAfter: {
        scriptEventReceive: ScriptEventCommandMessageAfterEvent,
    },
    systemBefore: {
        startup: StartupEvent, shutdown: ShutdownEvent,
    },
};
    return payloadClasses[group];
}

let unraisedSignals;

/**
 * The signal class each published event this mod does not raise is handed back as.
 *
 * Built on demand for the same reason the payload table is: the classes are declared further down.
 * Subscribing to one of these reaches a signal of the class Bedrock names, so a pack keeping and
 * testing it is answered correctly; nothing ever arrives on it, which is what the report says.
 */
function unraisedFor(group) {
    unraisedSignals ??= {
    worldAfter: {
        entityUpgrade: EntityUpgradeAfterEventSignal,
        playerEmote: PlayerEmoteAfterEventSignal,
        playerInputModeChange: PlayerInputModeChangeAfterEventSignal,
        soundCompleted: SoundCompletedAfterEventSignal,
    },
    worldBefore: {},
    systemAfter: {},
    systemBefore: {},
};
    return unraisedSignals[group];
}

function eventGroup(raised, group) {
    return new Proxy({}, {
        get(target, name) {
            if (typeof name !== "string") return undefined;
            if (target[name] === undefined) {
                const known = unraisedFor(group)[name];
                const build = Object.hasOwn(raised, name) ? raised[name] : known;
                if (!Object.hasOwn(raised, name)) {
                    bridge.unsupported(name + (known ? " (event, never raised)" : " (event)"));
                }
                target[name] = new (build ?? EventSignal)();
                target[name].name = name;
                target[name].subject = EVENT_SUBJECTS[name];
                target[name].shape = EVENT_SHAPES[name];
                target[name].payload = payloadsFor(group)[name];
            }
            return target[name];
        },
    });
}

/** Every event name this mod actually raises, by the group it belongs to. */
/*
 * A signal per event this mod raises, so a pack holding one of these holds the class Bedrock names.
 * Every other published signal is a name in the block further down: subscribing to one reaches a
 * signal that works and an event that is reported as never raised.
 */
export class ButtonPushAfterEventSignal extends EventSignal {}
export class DataDrivenEntityTriggerAfterEventSignal extends EventSignal {}
export class EffectAddAfterEventSignal extends EventSignal {}
export class EffectAddBeforeEventSignal extends EventSignal {}
export class EntityDieAfterEventSignal extends EventSignal {}
export class EntityHitEntityAfterEventSignal extends EventSignal {}
export class EntityHurtAfterEventSignal extends EventSignal {}
export class EntityHurtBeforeEventSignal extends EventSignal {}
export class EntityItemDropAfterEventSignal extends EventSignal {}
export class EntityItemPickupAfterEventSignal extends EventSignal {}
export class EntityItemPickupBeforeEventSignal extends EventSignal {}
export class EntityLoadAfterEventSignal extends EventSignal {}
export class EntityRemoveAfterEventSignal extends EventSignal {}
export class EntityRemoveBeforeEventSignal extends EventSignal {}
export class EntitySpawnAfterEventSignal extends EventSignal {}
export class ItemCompleteUseAfterEventSignal extends EventSignal {}
export class ItemReleaseUseAfterEventSignal extends EventSignal {}
export class ItemStartUseAfterEventSignal extends EventSignal {}
export class ItemStartUseOnAfterEventSignal extends EventSignal {}
export class ItemStopUseAfterEventSignal extends EventSignal {}
export class ItemUseAfterEventSignal extends EventSignal {}
export class ItemUseBeforeEventSignal extends EventSignal {}
export class PlayerBreakBlockAfterEventSignal extends EventSignal {}
export class PlayerBreakBlockBeforeEventSignal extends EventSignal {}
export class PlayerDimensionChangeAfterEventSignal extends EventSignal {}
export class PlayerGameModeChangeAfterEventSignal extends EventSignal {}
export class PlayerInputPermissionCategoryChangeAfterEventSignal extends EventSignal {}
export class PlayerInteractWithBlockAfterEventSignal extends EventSignal {}
export class PlayerInteractWithBlockBeforeEventSignal extends EventSignal {}
export class PlayerInteractWithEntityAfterEventSignal extends EventSignal {}
export class PlayerInteractWithEntityBeforeEventSignal extends EventSignal {}
export class PlayerInventoryItemChangeAfterEventSignal extends EventSignal {}
export class PlayerJoinAfterEventSignal extends EventSignal {}
export class PlayerLeaveAfterEventSignal extends EventSignal {}
export class PlayerLeaveBeforeEventSignal extends EventSignal {}
export class PlayerPlaceBlockAfterEventSignal extends EventSignal {}
export class PlayerSpawnAfterEventSignal extends EventSignal {}
export class ProjectileHitBlockAfterEventSignal extends EventSignal {}
export class ProjectileHitEntityAfterEventSignal extends EventSignal {}
export class ScriptEventCommandMessageAfterEventSignal extends EventSignal {}
export class ShutdownBeforeEventSignal extends EventSignal {}
export class StartupBeforeEventSignal extends EventSignal {}
export class WorldLoadAfterEventSignal extends EventSignal {}
export class LeverActionAfterEventSignal extends EventSignal {}
export class EntityHitBlockAfterEventSignal extends EventSignal {}
export class EntityHealAfterEventSignal extends EventSignal {}
export class EntityHealBeforeEventSignal extends EventSignal {}
export class EntityHealthChangedAfterEventSignal extends EventSignal {}
export class WeatherChangeAfterEventSignal extends EventSignal {}
export class WeatherChangeBeforeEventSignal extends EventSignal {}
export class PlayerHotbarSelectedSlotChangeAfterEventSignal extends EventSignal {}
export class PlayerButtonInputAfterEventSignal extends EventSignal {}
export class BlockContainerClosedAfterEventSignal extends EventSignal {}
export class BlockContainerOpenedAfterEventSignal extends EventSignal {}
export class BlockExplodeAfterEventSignal extends EventSignal {}
export class EntityContainerClosedAfterEventSignal extends EventSignal {}
export class EntityContainerOpenedAfterEventSignal extends EventSignal {}
export class ExplosionAfterEventSignal extends EventSignal {}
export class ExplosionBeforeEventSignal extends EventSignal {}
export class GameRuleChangeAfterEventSignal extends EventSignal {}
export class ItemStopUseOnAfterEventSignal extends EventSignal {}
export class PistonActivateAfterEventSignal extends EventSignal {}
export class PlayerCancelBreakingBlockAfterEventSignal extends EventSignal {}
export class PlayerStartBreakingBlockAfterEventSignal extends EventSignal {}
export class PlayerSwingStartAfterEventSignal extends EventSignal {}
export class PressurePlatePopAfterEventSignal extends EventSignal {}
export class PressurePlatePushAfterEventSignal extends EventSignal {}
export class TargetBlockHitAfterEventSignal extends EventSignal {}
export class TripWireTripAfterEventSignal extends EventSignal {}
export class PlayerGameModeChangeBeforeEventSignal extends EventSignal {}
export class EntityStartSneakingAfterEventSignal extends EventSignal {}
export class EntityStopSneakingAfterEventSignal extends EventSignal {}
export class EntityTamedAfterEventSignal extends EventSignal {}
export class PackSettingChangeAfterEventSignal extends EventSignal {}
export class EntityTamedBeforeEventSignal extends EventSignal {}

const RAISED = {
    worldAfter: {
        dataDrivenEntityTrigger: DataDrivenEntityTriggerAfterEventSignal,
        entityDie: EntityDieAfterEventSignal, entityHitEntity: EntityHitEntityAfterEventSignal,
        entityHurt: EntityHurtAfterEventSignal, entityItemDrop: EntityItemDropAfterEventSignal,
        entityLoad: EntityLoadAfterEventSignal, entityRemove: EntityRemoveAfterEventSignal,
        entitySpawn: EntitySpawnAfterEventSignal, itemCompleteUse: ItemCompleteUseAfterEventSignal,
        itemReleaseUse: ItemReleaseUseAfterEventSignal, itemStartUse: ItemStartUseAfterEventSignal,
        itemStartUseOn: ItemStartUseOnAfterEventSignal, itemStopUse: ItemStopUseAfterEventSignal,
        itemUse: ItemUseAfterEventSignal, playerBreakBlock: PlayerBreakBlockAfterEventSignal,
        playerDimensionChange: PlayerDimensionChangeAfterEventSignal,
        playerGameModeChange: PlayerGameModeChangeAfterEventSignal,
        playerInputPermissionCategoryChange: PlayerInputPermissionCategoryChangeAfterEventSignal,
        playerInteractWithBlock: PlayerInteractWithBlockAfterEventSignal,
        playerInteractWithEntity: PlayerInteractWithEntityAfterEventSignal,
        playerInventoryItemChange: PlayerInventoryItemChangeAfterEventSignal,
        playerJoin: PlayerJoinAfterEventSignal, playerLeave: PlayerLeaveAfterEventSignal,
        playerPlaceBlock: PlayerPlaceBlockAfterEventSignal,
        playerSpawn: PlayerSpawnAfterEventSignal, worldLoad: WorldLoadAfterEventSignal,
        effectAdd: EffectAddAfterEventSignal, entityItemPickup: EntityItemPickupAfterEventSignal,
        buttonPush: ButtonPushAfterEventSignal,
        projectileHitEntity: ProjectileHitEntityAfterEventSignal,
        projectileHitBlock: ProjectileHitBlockAfterEventSignal,
        leverAction: LeverActionAfterEventSignal, entityHitBlock: EntityHitBlockAfterEventSignal,
        entityHeal: EntityHealAfterEventSignal,
        entityHealthChanged: EntityHealthChangedAfterEventSignal,
        weatherChange: WeatherChangeAfterEventSignal,
        playerHotbarSelectedSlotChange: PlayerHotbarSelectedSlotChangeAfterEventSignal,
        playerButtonInput: PlayerButtonInputAfterEventSignal,
        explosion: ExplosionAfterEventSignal, blockExplode: BlockExplodeAfterEventSignal,
        pistonActivate: PistonActivateAfterEventSignal,
        gameRuleChange: GameRuleChangeAfterEventSignal,
        pressurePlatePush: PressurePlatePushAfterEventSignal,
        pressurePlatePop: PressurePlatePopAfterEventSignal,
        tripWireTrip: TripWireTripAfterEventSignal,
        targetBlockHit: TargetBlockHitAfterEventSignal,
        playerStartBreakingBlock: PlayerStartBreakingBlockAfterEventSignal,
        playerCancelBreakingBlock: PlayerCancelBreakingBlockAfterEventSignal,
        playerSwingStart: PlayerSwingStartAfterEventSignal,
        itemStopUseOn: ItemStopUseOnAfterEventSignal,
        blockContainerOpened: BlockContainerOpenedAfterEventSignal,
        blockContainerClosed: BlockContainerClosedAfterEventSignal,
        entityContainerOpened: EntityContainerOpenedAfterEventSignal,
        entityContainerClosed: EntityContainerClosedAfterEventSignal,
        entityStartSneaking: EntityStartSneakingAfterEventSignal,
        entityStopSneaking: EntityStopSneakingAfterEventSignal,
        entityTamed: EntityTamedAfterEventSignal,
        packSettingChange: PackSettingChangeAfterEventSignal,
    },
    worldBefore: {
        entityTamed: EntityTamedBeforeEventSignal,
        entityHurt: EntityHurtBeforeEventSignal, entityRemove: EntityRemoveBeforeEventSignal,
        itemUse: ItemUseBeforeEventSignal, playerBreakBlock: PlayerBreakBlockBeforeEventSignal,
        playerInteractWithBlock: PlayerInteractWithBlockBeforeEventSignal,
        playerInteractWithEntity: PlayerInteractWithEntityBeforeEventSignal,
        playerLeave: PlayerLeaveBeforeEventSignal, effectAdd: EffectAddBeforeEventSignal,
        entityItemPickup: EntityItemPickupBeforeEventSignal,
        entityHeal: EntityHealBeforeEventSignal, weatherChange: WeatherChangeBeforeEventSignal,
        explosion: ExplosionBeforeEventSignal,
        playerGameModeChange: PlayerGameModeChangeBeforeEventSignal,
    },
    systemAfter: {
        scriptEventReceive: ScriptEventCommandMessageAfterEventSignal,
    },
    systemBefore: {
        startup: StartupBeforeEventSignal, shutdown: ShutdownBeforeEventSignal,
    },
};

/**
 * The game rules, under the names Bedrock gives them.
 *
 * Each name is a property with a getter and a setter, built from the two tables below because the
 * only thing that differs between one rule and the next is whether it holds a flag or a count. A
 * rule Java has nothing that means the same as reads as `undefined`, and the bridge reports it.
 */
const GAME_RULE_FLAGS = [
    "commandBlockOutput", "commandBlocksEnabled", "doDayLightCycle", "doEntityDrops", "doFireTick",
    "doImmediateRespawn", "doInsomnia", "doLimitedCrafting", "doMobLoot", "doMobSpawning",
    "doTileDrops", "doWeatherCycle", "drowningDamage", "fallDamage", "fireDamage", "freezeDamage",
    "keepInventory", "mobGriefing", "naturalRegeneration", "projectilesCanBreakBlocks", "pvp",
    "recipesUnlock", "respawnBlocksExplode", "sendCommandFeedback", "showBorderEffect",
    "showCoordinates", "showDaysPlayed", "showDeathMessages", "showRecipeMessages", "showTags",
    "tntExplodes", "tntExplosionDropDecay",
];

const GAME_RULE_COUNTS = [
    "functionCommandLimit", "maxCommandChainLength", "playersSleepingPercentage", "randomTickSpeed",
    "spawnRadius",
];

export class GameRules {}

function defineGameRule(name, read) {
    Object.defineProperty(GameRules.prototype, name, {
        enumerable: true,
        get() {
            const answer = bridge.gameRule(name);
            return answer === null || answer === undefined ? undefined : read(answer);
        },
        set(value) {
            bridge.setGameRule(name, value);
        },
    });
}

GAME_RULE_FLAGS.forEach(name => defineGameRule(name, answer => answer === "true"));
GAME_RULE_COUNTS.forEach(name => defineGameRule(name, Number));

export class World {
    #afterEvents = eventGroup(RAISED.worldAfter, "worldAfter");
    #beforeEvents = eventGroup(RAISED.worldBefore, "worldBefore");
    #gameRules = new GameRules();

    get afterEvents() {
        return this.#afterEvents;
    }

    get beforeEvents() {
        return this.#beforeEvents;
    }

    get gameRules() {
        return this.#gameRules;
    }

    get isHardcore() {
        return bridge.isHardcore();
    }

    get soundDefinitionRegistry() {
        return unsupported("World.soundDefinitionRegistry", new SoundDefinitionRegistry());
    }

    get primitiveShapesManager() {
        return unsupported("World.primitiveShapesManager", new PrimitiveShapesManager());
    }

    get seed() {
        return bridge.worlds.seed();
    }

    getAimAssist() {
        return new AimAssistRegistry();
    }

    getPackSettings() {
        return JSON.parse(bridge.worlds.packSettings());
    }

    get tickingAreaManager() {
        return tickingAreaManager;
    }

    getDimension(id) {
        const full = id.includes(":") ? id : "minecraft:" + id;
        if (!list(bridge.dimensionIds()).includes(full)) throw new Error("Unknown dimension " + id);
        return dimensionOf(full);
    }

    getAllPlayers() {
        return list(bridge.players()).map(id => entityFor(id, true));
    }

    getPlayers(options) {
        return options === undefined
            ? this.getAllPlayers()
            : list(bridge.matchingPlayers(JSON.stringify(options))).map(id => entityFor(id, true));
    }

    getEntity(id) {
        return bridge.entityIsValid(id) ? entityFor(id) : undefined;
    }

    sendMessage(message) {
        bridge.broadcastMessage(rawText(message));
    }

    playSound(soundId, location, options) {
        return this.getDimension("overworld").playSound(soundId, location, options);
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

    setAbsoluteTime(absoluteTime) {
        bridge.worlds.setAbsoluteTime(Math.floor(absoluteTime));
    }

    getMoonPhase() {
        return bridge.moonPhase();
    }

    getDefaultSpawnLocation() {
        return vector(bridge.defaultSpawnLocation());
    }

    setDefaultSpawnLocation(spawnLocation) {
        bridge.worlds.setDefaultSpawnLocation(Math.floor(spawnLocation.x), Math.floor(spawnLocation.y),
            Math.floor(spawnLocation.z));
    }

    getDifficulty() {
        return bridge.worlds.difficulty();
    }

    setDifficulty(difficulty) {
        bridge.worlds.setDifficulty(difficulty);
    }

    playMusic(trackId, musicOptions) {
        bridge.worlds.playMusic(trackId, JSON.stringify(musicOptions ?? {}), false);
    }

    queueMusic(trackId, musicOptions) {
        bridge.worlds.playMusic(trackId, JSON.stringify(musicOptions ?? {}), true);
    }

    stopMusic() {
        bridge.worlds.stopMusic();
    }

    getLootTableManager() {
        return lootTableManager;
    }

    getDynamicProperty(name) {
        // A property never set crosses as null, and Bedrock answers undefined.
        return storedProperty(bridge.worldDynamicProperty(name));
    }

    setDynamicProperty(name, value) {
        bridge.setWorldDynamicProperty(name, value === undefined ? null : value);
    }

    setDynamicProperties(values) {
        Object.entries(values ?? {}).forEach(([name, value]) => this.setDynamicProperty(name, value));
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
        return scoreboard;
    }

    get structureManager() {
        return structureManager;
    }
}

/**
 * One saved structure, named by its id.
 *
 * Java's structure templates keep their blocks in a palette nothing outside the class can reach, so
 * the members that read or write a single block inside a structure are reported rather than
 * answered. Everything about the structure as a whole is real.
 */
export class Structure {
    constructor(id) {
        this.id = id;
    }

    get isValid() {
        return bridge.structureExists(this.id);
    }

    get size() {
        return vector(bridge.structureSize(this.id));
    }

    saveToWorld() {
        bridge.saveStructureToWorld(this.id);
    }

    saveAs() {
        return unsupported("Structure.saveAs");
    }

    getBlockPermutation() {
        return unsupported("Structure.getBlockPermutation");
    }

    getIsWaterlogged() {
        return unsupported("Structure.getIsWaterlogged", false);
    }

    setBlockPermutation() {
        unsupported("Structure.setBlockPermutation");
    }
}

export class StructureManager {
    get(identifier) {
        return bridge.structureExists(identifier) ? new Structure(identifier) : undefined;
    }

    getWorldStructureIds() {
        return list(bridge.structureIds());
    }

    /** Java keeps a data pack's structures and the world's own in one place, under one set of ids. */
    getPackStructureIds() {
        return unsupported("StructureManager.getPackStructureIds", []);
    }

    place(structure, dimension, location, options) {
        bridge.placeStructure(dimension.id, idOfStructure(structure),
            Math.floor(location.x), Math.floor(location.y), Math.floor(location.z),
            options?.rotation ?? "None", options?.mirror ?? "None");
    }

    createFromWorld(identifier, dimension, from, to) {
        const low = corner(from, to, Math.min);
        const high = corner(from, to, Math.max);
        bridge.saveStructure(dimension.id, identifier, low.x, low.y, low.z,
            high.x - low.x + 1, high.y - low.y + 1, high.z - low.z + 1);
        return new Structure(identifier);
    }

    /** A template with no blocks in it has nothing Java can be asked to write them into. */
    createEmpty() {
        return unsupported("StructureManager.createEmpty");
    }

    delete(structure) {
        return bridge.deleteStructure(idOfStructure(structure));
    }

    placeJigsaw() {
        return unsupported("StructureManager.placeJigsaw");
    }

    placeJigsawStructure() {
        return unsupported("StructureManager.placeJigsawStructure");
    }
}

/** A pack names a structure either by its id or by handing back one it was given. */
function idOfStructure(structure) {
    return typeof structure === "string" ? structure : structure?.id;
}

const structureManager = new StructureManager();

/** A scoreboard objective, which is what a pack reads and writes scores through. */
export class ScoreboardObjective {
    constructor(id) {
        this.id = id;
    }

    get displayName() {
        return bridge.scoreboardDisplayName(this.id) ?? this.id;
    }

    get isValid() {
        return bridge.scoreboardHasObjective(this.id);
    }

    getScore(participant) {
        const score = bridge.score(this.id, nameOf(participant));
        return score === null ? undefined : score;
    }

    hasParticipant(participant) {
        return bridge.score(this.id, nameOf(participant)) !== null;
    }

    setScore(participant, value) {
        bridge.setScore(this.id, nameOf(participant), value);
    }

    addScore(participant, value) {
        const current = this.getScore(participant) ?? 0;
        this.setScore(participant, current + value);
        return current + value;
    }

    removeParticipant(participant) {
        bridge.removeParticipant(this.id, nameOf(participant));
        return true;
    }

    getParticipants() {
        return list(bridge.scoreboardParticipants())
            .filter(name => bridge.score(this.id, name) !== null)
            .map(identityFor);
    }

    getScores() {
        return this.getParticipants()
            .map(participant => new ScoreboardScoreInfo(participant, this.getScore(participant) ?? 0));
    }
}

/** A scoreboard participant: an entity, or a name that nothing in the world answers to. */
export class ScoreboardIdentity {
    constructor(displayName, id, type, entityId) {
        this.displayName = displayName;
        this.id = id;
        this.type = type;
        this.#entityId = entityId;
    }

    #entityId;

    get isValid() {
        return this.#entityId === undefined || bridge.entityIsValid(this.#entityId);
    }

    getEntity() {
        return this.#entityId === undefined ? undefined : entityFor(this.#entityId);
    }
}

/** Undefined when nothing answers to the handle, which is the answer for an entity that is gone. */
function identityOf(handle) {
    const found = bridge.entityMembers.scoreboardIdentity(handle);
    if (found === null) return undefined;

    const fields = JSON.parse(found);
    return new ScoreboardIdentity(fields.displayName, fields.id, fields.type, handle);
}

/** A scoreboard name is a player's name, the UUID of any other entity, or neither. */
function identityFor(name) {
    const handle = list(bridge.players()).find(id => bridge.playerName(id) === name) ?? name;
    return identityOf(handle)
        ?? new ScoreboardIdentity(name, 0, ScoreboardIdentityType.FakePlayer, undefined);
}

/** A participant is a player, an entity, or the plain name of either. */
function nameOf(participant) {
    if (typeof participant === "string") return participant;
    if (participant instanceof Player) return participant.name;
    if (participant instanceof Entity) return participant.id;
    return String(participant?.displayName ?? participant);
}

/** One participant's score on an objective, which is what a pack reads off `getScores`. */
export class ScoreboardScoreInfo {
    constructor(participant, score) {
        this.participant = participant;
        this.score = score;
        Object.freeze(this);
    }
}

export class Scoreboard {
    getObjective(id) {
        return bridge.scoreboardHasObjective(id) ? new ScoreboardObjective(id) : undefined;
    }

    getObjectives() {
        return list(bridge.scoreboardObjectives()).map(id => new ScoreboardObjective(id));
    }

    addObjective(id, displayName) {
        bridge.addObjective(id, displayName ?? id);
        return new ScoreboardObjective(id);
    }

    removeObjective(objective) {
        bridge.removeObjective(idOfObjective(objective));
        return true;
    }

    getParticipants() {
        return list(bridge.scoreboardParticipants()).map(identityFor);
    }

    // Bedrock carries a sort order alongside the objective. Java draws a slot in one fixed order, so
    // a pack asking for the other one is told; the display itself is set either way.
    getObjectiveAtDisplaySlot(slot) {
        const shown = bridge.objectiveAtDisplaySlot(slot);
        return shown === null ? undefined : { objective: new ScoreboardObjective(shown) };
    }

    setObjectiveAtDisplaySlot(slot, options) {
        if (options?.sortOrder !== undefined) {
            unsupported("ScoreboardObjectiveDisplayOptions.sortOrder");
        }

        const objective = options?.objective;
        bridge.setDisplaySlot(slot, idOfObjective(objective));
        return objective === undefined ? undefined : this.getObjective(idOfObjective(objective));
    }

    clearObjectiveAtDisplaySlot(slot) {
        const shown = this.getObjectiveAtDisplaySlot(slot)?.objective;
        bridge.setDisplaySlot(slot, "");
        return shown;
    }
}

/** A pack names an objective either by its id or by handing back one it was given. */
function idOfObjective(objective) {
    return typeof objective === "string" ? objective : objective?.id;
}

const scoreboard = new Scoreboard();

/** One area the game is keeping loaded and ticking, as the manager last described it. */
function tickingArea(fields) {
    return {
        identifier: fields.identifier,
        dimension: dimensionOf(fields.dimension),
        chunkCount: fields.chunkCount,
        isFullyLoaded: fields.isFullyLoaded,
        boundingBox: { min: fields.min, max: fields.max },
    };
}

/** A pack names an area either by its identifier or by handing back one it was given. */
function idOfArea(area) {
    return typeof area === "string" ? area : area?.identifier;
}

export class TickingAreaManager {
    get chunkCount() {
        return bridge.tickingAreas.chunkCount();
    }

    get maxChunkCount() {
        return bridge.tickingAreas.maxChunkCount();
    }

    /**
     * Adds an area and answers once it is held.
     *
     * Bedrock hands back a promise because its own areas load on a background thread. Here the
     * ticket is placed before this returns, so the promise is already settled by the time a pack
     * awaits it.
     */
    createTickingArea(identifier, options) {
        const from = options?.from ?? {};
        const to = options?.to ?? {};
        const refused = bridge.tickingAreas.createTickingArea(identifier, options?.dimension?.id,
            Math.floor(from.x), Math.floor(from.y), Math.floor(from.z),
            Math.floor(to.x), Math.floor(to.y), Math.floor(to.z));
        if (refused !== null) return Promise.reject(tickingAreaError(refused, identifier));
        return Promise.resolve();
    }

    getAllTickingAreas() {
        return JSON.parse(bridge.tickingAreas.tickingAreas()).map(tickingArea);
    }

    getTickingArea(identifier) {
        const found = bridge.tickingAreas.tickingArea(idOfArea(identifier));
        return found === null ? undefined : tickingArea(JSON.parse(found));
    }

    hasCapacity(options) {
        const from = options?.from ?? {};
        const to = options?.to ?? {};
        return bridge.tickingAreas.hasCapacity(Math.floor(from.x), Math.floor(from.z),
            Math.floor(to.x), Math.floor(to.z));
    }

    hasTickingArea(identifier) {
        return bridge.tickingAreas.hasTickingArea(identifier);
    }

    removeAllTickingAreas() {
        bridge.tickingAreas.removeAllTickingAreas();
    }

    removeTickingArea(identifier) {
        const name = idOfArea(identifier);
        if (!bridge.tickingAreas.removeTickingArea(name)) {
            throw tickingAreaError("UnknownIdentifier", name);
        }
    }
}

function tickingAreaError(reason, identifier) {
    return new TickingAreaError("Ticking area " + identifier + ": " + reason, reason);
}

const tickingAreaManager = new TickingAreaManager();

/**
 * What the game would drop, asked for without anything being broken or killed.
 *
 * Java rolls a block's loot through the block itself, which is why the three block members are real
 * and answer with the tool in hand counted. An entity's table and a table named by path are reached
 * through an internal registry whose entries carry none of the fields Bedrock publishes on a
 * `LootTable`, so those members say so rather than handing back an empty table.
 */
export class LootTableManager {
    generateLootFromBlock(block, tool) {
        const dropped = bridge.blocks.dropsAt(block.dimension.id, block.x, block.y, block.z,
            tool?.typeId ?? null);
        return dropped === null ? undefined : JSON.parse(dropped).map(itemFrom);
    }

    generateLootFromBlockPermutation(permutation, tool) {
        return dropsOfPermutation(permutation, tool);
    }

    generateLootFromBlockType(blockType, tool) {
        return dropsOfPermutation(BlockPermutation.resolve(typeIdOf(blockType)), tool);
    }

    generateLootFromEntity() {
        return unsupported("LootTableManager.generateLootFromEntity");
    }

    generateLootFromEntityType() {
        return unsupported("LootTableManager.generateLootFromEntityType");
    }

    generateLootFromTable() {
        return unsupported("LootTableManager.generateLootFromTable");
    }

    getLootTable() {
        return unsupported("LootTableManager.getLootTable");
    }
}

function dropsOfPermutation(permutation, tool) {
    const dropped = bridge.blocks.dropsOf(permutation.type.id,
        JSON.stringify(permutation.getAllStates()), tool?.typeId ?? null);
    return dropped === null ? undefined : JSON.parse(dropped).map(itemFrom);
}

const lootTableManager = new LootTableManager();

export const world = new World();

/**
 * Scheduling.
 *
 * Every queued callback runs from the server tick, in insertion order, and the pump stops early once
 * the tick budget is spent so a pack cannot stall the server indefinitely. A job is a generator the
 * pump advances while there is budget left.
 */
export class System {
    #next = 1;
    #runs = new Map();
    #jobs = new Map();
    #afterEvents = eventGroup(RAISED.systemAfter, "systemAfter");
    #beforeEvents = eventGroup(RAISED.systemBefore, "systemBefore");

    get currentTick() {
        return bridge.currentTick();
    }

    get afterEvents() {
        return this.#afterEvents;
    }

    get beforeEvents() {
        return this.#beforeEvents;
    }

    /** The editor is a Bedrock client mode, so a world this mod serves is never one. */
    get isEditorWorld() {
        return false;
    }

    get serverSystemInfo() {
        return new SystemInfo();
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

    sendScriptEvent(id, message) {
        bridge.worlds.sendScriptEvent(id, message);
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

export class SystemInfo {
    get memoryTier() {
        return bridge.memoryTier();
    }
}

export const system = new System();

/**
 * The commands a pack registers during startup.
 *
 * Bedrock hands the registry to the startup event and expects commands to exist from then on. Java
 * builds its command tree before a pack's script has run, so what a pack registers is collected here
 * and added to the live dispatcher once the script has loaded.
 */
const customCommands = [];
const customEnums = new Map();

/*
 * The custom components a pack registered, by the name its blocks and items name them under.
 *
 * A custom component is a plain object of callbacks. Bedrock calls the one whose name matches what
 * just happened, and the block or item names the component in its own `minecraft:custom_components`
 * rather than declaring the trigger the callback answers.
 */
const customBlockComponents = new Map();
const customItemComponents = new Map();

// What Java calls into, and where the other `@minecraft/*` modules read the helpers they share from.
// Kept off the module exports so a pack cannot reach it through an import.
globalThis.__mcaddon_runtime__ = {
    list,
    unsupported,
    blockPosition,
    typeIdOf,

    tick(tick) {
        system.pump(tick);
        // Only a pack importing `@minecraft/server-gametest` installs this, so its absence means no
        // pack ships tests and there is nothing to advance.
        globalThis.__mcaddon_gametests__?.pump(tick);
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
            customCommandRegistry: new CustomCommandRegistry(),
            blockComponentRegistry: new BlockComponentRegistry(),
            itemComponentRegistry: new ItemComponentRegistry(),
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
    /**
     * Calls one callback of one custom component, and says whether the pack cancelled what happened.
     *
     * Java knows which component names the block or item carries and which callback the moment it is
     * in corresponds to; everything about what the callback then does is the pack's own.
     */
    runBlockComponent(name, callback, argumentsJson) {
        return runCustomComponent(customBlockComponents, name, callback, argumentsJson);
    },

    runItemComponent(name, callback, argumentsJson) {
        return runCustomComponent(customItemComponents, name, callback, argumentsJson);
    },

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

/*
 * The rest of the module's surface, so importing a name never fails on its own.
 *
 * A JavaScript module is linked before it runs: `import { DisplaySlotId } from "@minecraft/server"`
 * on a module that does not export that name is an error that stops the whole of a pack's
 * script, not just the line that used it. The enums below carry Bedrock's own values, and the
 * classes stand in so that naming one, or testing a caught error against it, works. A member
 * called on one of these reports itself through the coverage report rather than throwing.
 */

export const BlockComponentTypes = Object.freeze({ FluidContainer: 'minecraft:fluid_container', Inventory: 'minecraft:inventory', MapColor: 'minecraft:map_color', Movable: 'minecraft:movable', Piston: 'minecraft:piston', PrecipitationInteractions: 'minecraft:precipitation_interactions', RecordPlayer: 'minecraft:record_player', RedstoneProducer: 'minecraft:redstone_producer', Sign: 'minecraft:sign' });
export const BlockPistonState = Object.freeze({ Expanded: 'Expanded', Expanding: 'Expanding', Retracted: 'Retracted', Retracting: 'Retracting' });
export const BlockVolumeIntersection = Object.freeze({ Disjoint: 0, Contains: 1, Intersects: 2 });
export const BookErrorReason = Object.freeze({ ExceedsMaxPageLength: 'ExceedsMaxPageLength', ExceedsMaxPages: 'ExceedsMaxPages', ExceedsTitleLength: 'ExceedsTitleLength' });
export const ButtonState = Object.freeze({ Pressed: 'Pressed', Released: 'Released' });
export const ContainerRulesErrorReason = Object.freeze({ BannedItem: 'BannedItem', NestedStorageItem: 'NestedStorageItem', NotAllowedItem: 'NotAllowedItem', OverWeightLimit: 'OverWeightLimit', ZeroWeightItem: 'ZeroWeightItem' });
export const ControlScheme = Object.freeze({ CameraRelative: 'CameraRelative', CameraRelativeStrafe: 'CameraRelativeStrafe', LockedPlayerRelativeStrafe: 'LockedPlayerRelativeStrafe', PlayerRelative: 'PlayerRelative', PlayerRelativeStrafe: 'PlayerRelativeStrafe' });
export const CustomCommandErrorReason = Object.freeze({ AlreadyRegistered: 'AlreadyRegistered', EnumDependencyMissing: 'EnumDependencyMissing', NamespaceMismatch: 'NamespaceMismatch', ParameterLimit: 'ParameterLimit', RegistryInvalid: 'RegistryInvalid', RegistryReadOnly: 'RegistryReadOnly' });
export const CustomCommandSource = Object.freeze({ Block: 'Block', Entity: 'Entity', NPCDialogue: 'NPCDialogue', Server: 'Server' });
export const CustomComponentNameErrorReason = Object.freeze({ NoNamespace: 1, DisallowedNamespace: 2 });
export const Difficulty = Object.freeze({ Easy: 'Easy', Hard: 'Hard', Normal: 'Normal', Peaceful: 'Peaceful' });
export const DisplaySlotId = Object.freeze({ BelowName: 'BelowName', List: 'List', Sidebar: 'Sidebar' });
export const DyeColor = Object.freeze({ Black: 'Black', Blue: 'Blue', Brown: 'Brown', Cyan: 'Cyan', Gray: 'Gray', Green: 'Green', LightBlue: 'LightBlue', Lime: 'Lime', Magenta: 'Magenta', Orange: 'Orange', Pink: 'Pink', Purple: 'Purple', Red: 'Red', Silver: 'Silver', White: 'White', Yellow: 'Yellow' });
export const EasingType = Object.freeze({ InBack: 'InBack', InBounce: 'InBounce', InCirc: 'InCirc', InCubic: 'InCubic', InElastic: 'InElastic', InExpo: 'InExpo', InOutBack: 'InOutBack', InOutBounce: 'InOutBounce', InOutCirc: 'InOutCirc', InOutCubic: 'InOutCubic', InOutElastic: 'InOutElastic', InOutExpo: 'InOutExpo', InOutQuad: 'InOutQuad', InOutQuart: 'InOutQuart', InOutQuint: 'InOutQuint', InOutSine: 'InOutSine', InQuad: 'InQuad', InQuart: 'InQuart', InQuint: 'InQuint', InSine: 'InSine', Linear: 'Linear', OutBack: 'OutBack', OutBounce: 'OutBounce', OutCirc: 'OutCirc', OutCubic: 'OutCubic', OutElastic: 'OutElastic', OutExpo: 'OutExpo', OutQuad: 'OutQuad', OutQuart: 'OutQuart', OutQuint: 'OutQuint', OutSine: 'OutSine', Spring: 'Spring' });
export const EnchantmentSlot = Object.freeze({ ArmorFeet: 'ArmorFeet', ArmorHead: 'ArmorHead', ArmorLegs: 'ArmorLegs', ArmorTorso: 'ArmorTorso', Axe: 'Axe', Bow: 'Bow', CarrotStick: 'CarrotStick', CosmeticHead: 'CosmeticHead', Crossbow: 'Crossbow', Elytra: 'Elytra', FishingRod: 'FishingRod', Flintsteel: 'Flintsteel', Hoe: 'Hoe', MeleeSpear: 'MeleeSpear', Pickaxe: 'Pickaxe', Shears: 'Shears', Shield: 'Shield', Shovel: 'Shovel', Spear: 'Spear', Sword: 'Sword' });
export const EntityAttachPoint = Object.freeze({ Body: 'Body', BreathingPoint: 'BreathingPoint', DropAttachPoint: 'DropAttachPoint', ExplosionPoint: 'ExplosionPoint', Eyes: 'Eyes', Feet: 'Feet', Head: 'Head', Mouth: 'Mouth', WeaponAttachPoint: 'WeaponAttachPoint' });
export const EntityHealCause = Object.freeze({ Heal: 'Heal', Regeneration: 'Regeneration', SelfHeal: 'SelfHeal' });
export const EntitySwingSource = Object.freeze({ Attack: 'Attack', Build: 'Build', DropItem: 'DropItem', Event: 'Event', Interact: 'Interact', Mine: 'Mine', None: 'None', ThrowItem: 'ThrowItem', UseItem: 'UseItem' });
export const FluidType = Object.freeze({ Lava: 'Lava', Potion: 'Potion', PowderSnow: 'PowderSnow', Water: 'Water' });
export const GameRule = Object.freeze({ CommandBlockOutput: 'commandBlockOutput', CommandBlocksEnabled: 'commandBlocksEnabled', DoDayLightCycle: 'doDayLightCycle', DoEntityDrops: 'doEntityDrops', DoFireTick: 'doFireTick', DoImmediateRespawn: 'doImmediateRespawn', DoInsomnia: 'doInsomnia', DoLimitedCrafting: 'doLimitedCrafting', DoMobLoot: 'doMobLoot', DoMobSpawning: 'doMobSpawning', DoTileDrops: 'doTileDrops', DoWeatherCycle: 'doWeatherCycle', DrowningDamage: 'drowningDamage', FallDamage: 'fallDamage', FireDamage: 'fireDamage', FreezeDamage: 'freezeDamage', FunctionCommandLimit: 'functionCommandLimit', KeepInventory: 'keepInventory', MaxCommandChainLength: 'maxCommandChainLength', MobGriefing: 'mobGriefing', NaturalRegeneration: 'naturalRegeneration', PlayersSleepingPercentage: 'playersSleepingPercentage', ProjectilesCanBreakBlocks: 'projectilesCanBreakBlocks', Pvp: 'pvp', RandomTickSpeed: 'randomTickSpeed', RecipesUnlock: 'recipesUnlock', RespawnBlocksExplode: 'respawnBlocksExplode', SendCommandFeedback: 'sendCommandFeedback', ShowBorderEffect: 'showBorderEffect', ShowCoordinates: 'showCoordinates', ShowDaysPlayed: 'showDaysPlayed', ShowDeathMessages: 'showDeathMessages', ShowRecipeMessages: 'showRecipeMessages', ShowTags: 'showTags', SpawnRadius: 'spawnRadius', TntExplodes: 'tntExplodes', TntExplosionDropDecay: 'tntExplosionDropDecay' });
export const GraphicsMode = Object.freeze({ Deferred: 'Deferred', Fancy: 'Fancy', RayTraced: 'RayTraced', Simple: 'Simple' });
export const HeldItemOption = Object.freeze({ AnyItem: 'AnyItem', NoItem: 'NoItem' });
export const HudElement = Object.freeze({ PaperDoll: 0, Armor: 1, ToolTips: 2, TouchControls: 3, Crosshair: 4, Hotbar: 5, Health: 6, ProgressBar: 7, Hunger: 8, AirBubbles: 9, HorseHealth: 10, StatusEffects: 11, ItemText: 12 });
export const HudVisibility = Object.freeze({ Hide: 0, Reset: 1 });
export const InputButton = Object.freeze({ Jump: 'Jump', Sneak: 'Sneak' });
export const InputMode = Object.freeze({ Gamepad: 'Gamepad', KeyboardAndMouse: 'KeyboardAndMouse', MotionController: 'MotionController', Touch: 'Touch' });
export const InputPermissionCategory = Object.freeze({ Camera: 1, Movement: 2, LateralMovement: 4, Sneak: 5, Jump: 6, Mount: 7, Dismount: 8, MoveForward: 9, MoveBackward: 10, MoveLeft: 11, MoveRight: 12 });
export const ItemLockMode = Object.freeze({ inventory: 'inventory', none: 'none', slot: 'slot' });
export const LiquidSettings = Object.freeze({ ApplyWaterlogging: 'ApplyWaterlogging', IgnoreWaterlogging: 'IgnoreWaterlogging' });
export const LiquidType = Object.freeze({ Water: 'Water' });
export const MemoryTier = Object.freeze({ SuperLow: 0, Low: 1, Mid: 2, High: 3, SuperHigh: 4 });
export const MoonPhase = Object.freeze({ FullMoon: 0, WaningGibbous: 1, FirstQuarter: 2, WaningCrescent: 3, NewMoon: 4, WaxingCrescent: 5, LastQuarter: 6, WaxingGibbous: 7 });
export const MovementType = Object.freeze({ Immovable: 'Immovable', Popped: 'Popped', Push: 'Push', PushPull: 'PushPull' });
export const NamespaceNameErrorReason = Object.freeze({ DisallowedNamespace: 'DisallowedNamespace', NoNamespace: 'NoNamespace' });
export const ObjectiveSortOrder = Object.freeze({ Ascending: 0, Descending: 1 });
export const PaletteColor = Object.freeze({ White: 0, Orange: 1, Magenta: 2, LightBlue: 3, Yellow: 4, Lime: 5, Pink: 6, Gray: 7, Silver: 8, Cyan: 9, Purple: 10, Blue: 11, Brown: 12, Green: 13, Red: 14, Black: 15 });
export const PlatformType = Object.freeze({ Console: 'Console', Desktop: 'Desktop', Mobile: 'Mobile' });
export const PlayerInventoryType = Object.freeze({ Hotbar: 'Hotbar', Inventory: 'Inventory' });
export const PlayerPermissionLevel = Object.freeze({ Visitor: 0, Member: 1, Operator: 2, Custom: 3 });
export const ScoreboardIdentityType = Object.freeze({ Entity: 'Entity', FakePlayer: 'FakePlayer', Player: 'Player' });
export const SignSide = Object.freeze({ Back: 'Back', Front: 'Front' });
export const StickyType = Object.freeze({ None: 'None', Same: 'Same' });
export const StructureAnimationMode = Object.freeze({ Blocks: 'Blocks', Layers: 'Layers', None: 'None' });
export const StructureMirrorAxis = Object.freeze({ None: 'None', X: 'X', XZ: 'XZ', Z: 'Z' });
export const TickingAreaErrorReason = Object.freeze({ IdentifierAlreadyExists: 'IdentifierAlreadyExists', OverChunkLimit: 'OverChunkLimit', SideLengthExceeded: 'SideLengthExceeded', UnknownIdentifier: 'UnknownIdentifier' });
export const TimeOfDay = Object.freeze({ Day: 1000, Noon: 6000, Sunset: 12000, Night: 13000, Midnight: 18000, Sunrise: 23000 });
export const TintMethod = Object.freeze({ BirchFoliage: 'BirchFoliage', DefaultFoliage: 'DefaultFoliage', DryFoliage: 'DryFoliage', EvergreenFoliage: 'EvergreenFoliage', Grass: 'Grass', None: 'None', Water: 'Water' });
export const WeatherType = Object.freeze({ Clear: 'Clear', Rain: 'Rain', Thunder: 'Thunder' });

export const HudElementsCount = 13;
export const HudVisibilityCount = 2;
export const MoonPhaseCount = 8;
export const TicksPerDay = 24000;
export const TicksPerSecond = 20;

/** Named so a pack can import one to describe a value; the game answers what they describe. */
export class BlockComponentBlockBreakEvent {
    get blockDestructionSource() {
        return unsupported("BlockComponentBlockBreakEvent.blockDestructionSource");
    }

    get brokenBlockPermutation() {
        return unsupported("BlockComponentBlockBreakEvent.brokenBlockPermutation");
    }

    get entitySource() {
        return unsupported("BlockComponentBlockBreakEvent.entitySource");
    }
}
export class BlockComponentEntityEvent {
    get blockPermutation() {
        return unsupported("BlockComponentEntityEvent.blockPermutation");
    }

    get entitySource() {
        return unsupported("BlockComponentEntityEvent.entitySource");
    }

    get name() {
        return unsupported("BlockComponentEntityEvent.name");
    }
}
export class BlockComponentEntityFallOnEvent {
    get entity() {
        return unsupported("BlockComponentEntityFallOnEvent.entity");
    }

    get fallDistance() {
        return unsupported("BlockComponentEntityFallOnEvent.fallDistance");
    }
}
export class BlockComponentOnPlaceEvent {
    get previousBlock() {
        return unsupported("BlockComponentOnPlaceEvent.previousBlock");
    }
}
export class BlockComponentPlayerBreakEvent {
    get brokenBlockPermutation() {
        return unsupported("BlockComponentPlayerBreakEvent.brokenBlockPermutation");
    }

    get player() {
        return unsupported("BlockComponentPlayerBreakEvent.player");
    }
}
export class BlockComponentPlayerInteractEvent {
    get face() {
        return unsupported("BlockComponentPlayerInteractEvent.face");
    }

    get faceLocation() {
        return unsupported("BlockComponentPlayerInteractEvent.faceLocation");
    }

    get player() {
        return unsupported("BlockComponentPlayerInteractEvent.player");
    }
}
export class BlockComponentPlayerPlaceBeforeEvent {
    get cancel() {
        return unsupported("BlockComponentPlayerPlaceBeforeEvent.cancel");
    }

    get face() {
        return unsupported("BlockComponentPlayerPlaceBeforeEvent.face");
    }

    get permutationToPlace() {
        return unsupported("BlockComponentPlayerPlaceBeforeEvent.permutationToPlace");
    }

    get player() {
        return unsupported("BlockComponentPlayerPlaceBeforeEvent.player");
    }
}
export class BlockComponentRandomTickEvent {}
export class BlockComponentRedstoneUpdateEvent {
    get powerLevel() {
        return unsupported("BlockComponentRedstoneUpdateEvent.powerLevel");
    }

    get previousPowerLevel() {
        return unsupported("BlockComponentRedstoneUpdateEvent.previousPowerLevel");
    }
}
export class BlockComponentRegistry {
    registerCustomComponent(name, component) {
        customBlockComponents.set(name, component ?? {});
    }
}
export class BlockComponentStepOffEvent {
    get entity() {
        return unsupported("BlockComponentStepOffEvent.entity");
    }
}
export class BlockComponentStepOnEvent {
    get entity() {
        return unsupported("BlockComponentStepOnEvent.entity");
    }
}
export class BlockComponentTickEvent {}
export class BlockCustomComponentInstance extends BlockComponent {
    get customComponentParameters() {
        return unsupported("BlockCustomComponentInstance.customComponentParameters");
    }
}
export class BlockEvent {
    get block() {
        return unsupported("BlockEvent.block");
    }

    get dimension() {
        return unsupported("BlockEvent.dimension");
    }
}
export class BlockExplodeAfterEvent extends BlockEvent {
    get explodedBlockPermutation() {
        return unsupported("BlockExplodeAfterEvent.explodedBlockPermutation");
    }

    get source() {
        return unsupported("BlockExplodeAfterEvent.source");
    }
}
export class BlockFluidContainerComponent extends BlockComponent {
    static componentId = "minecraft:fluid_container";

    get fillLevel() {
        return unsupported("BlockFluidContainerComponent.fillLevel");
    }

    get fluidColor() {
        return unsupported("BlockFluidContainerComponent.fluidColor");
    }

    addDye() {
        return unsupported("BlockFluidContainerComponent.addDye");
    }

    getFluidType() {
        return unsupported("BlockFluidContainerComponent.getFluidType");
    }

    setFluidType() {
        return unsupported("BlockFluidContainerComponent.setFluidType");
    }

    setPotion() {
        return unsupported("BlockFluidContainerComponent.setPotion");
    }
}
export class BlockPrecipitationInteractionsComponent extends BlockComponent {
    static componentId = "minecraft:precipitation_interactions";

    accumulatesSnow() {
        return unsupported("BlockPrecipitationInteractionsComponent.accumulatesSnow");
    }

    isSnowLoggable() {
        return unsupported("BlockPrecipitationInteractionsComponent.isSnowLoggable");
    }

    obstructsRain() {
        return unsupported("BlockPrecipitationInteractionsComponent.obstructsRain");
    }
}
export class BlockRecordPlayerComponent extends BlockComponent {
    static componentId = "minecraft:record_player";

    ejectRecord() {
        return unsupported("BlockRecordPlayerComponent.ejectRecord");
    }

    getRecord() {
        return unsupported("BlockRecordPlayerComponent.getRecord");
    }

    isPlaying() {
        return unsupported("BlockRecordPlayerComponent.isPlaying");
    }

    pauseRecord() {
        return unsupported("BlockRecordPlayerComponent.pauseRecord");
    }

    playRecord() {
        return unsupported("BlockRecordPlayerComponent.playRecord");
    }

    setRecord() {
        return unsupported("BlockRecordPlayerComponent.setRecord");
    }
}
export class ButtonPushAfterEvent {
    get block() {
        return unsupported("ButtonPushAfterEvent.block");
    }

    get dimension() {
        return unsupported("ButtonPushAfterEvent.dimension");
    }

    get source() {
        return unsupported("ButtonPushAfterEvent.source");
    }
}
/** A curve through every one of its points, which `Camera.playAnimation` carries a camera along. */
export class CatmullRomSpline {
    controlPoints = [];
}
export class CommandResult {
    get successCount() {
        return unsupported("CommandResult.successCount");
    }
}
export class Component {
    get isValid() {
        return unsupported("Component.isValid");
    }

    get typeId() {
        return unsupported("Component.typeId");
    }
}
export class CustomCommandOrigin {
    get initiator() {
        return unsupported("CustomCommandOrigin.initiator");
    }

    get sourceBlock() {
        return unsupported("CustomCommandOrigin.sourceBlock");
    }

    get sourceEntity() {
        return unsupported("CustomCommandOrigin.sourceEntity");
    }

    get sourceType() {
        return unsupported("CustomCommandOrigin.sourceType");
    }
}
/** What a pack registers its own commands on, handed to it by the startup event. */
export class CustomCommandRegistry {
    registerCommand(spec, callback) {
        customCommands.push({ spec, callback });
    }

    registerEnum(name, values) {
        customEnums.set(name, Array.from(values ?? []));
    }
}
export class CustomComponentParameters {
    get params() {
        return unsupported("CustomComponentParameters.params");
    }
}
export class DamagedByEntityCondition {
    get entityType() {
        return unsupported("DamagedByEntityCondition.entityType");
    }
}
export class DataDrivenEntityTriggerAfterEvent {
    get entity() {
        return unsupported("DataDrivenEntityTriggerAfterEvent.entity");
    }

    get eventId() {
        return unsupported("DataDrivenEntityTriggerAfterEvent.eventId");
    }

    getModifiers() {
        return unsupported("DataDrivenEntityTriggerAfterEvent.getModifiers");
    }
}
export class EffectAddAfterEvent {
    get effect() {
        return unsupported("EffectAddAfterEvent.effect");
    }

    get entity() {
        return unsupported("EffectAddAfterEvent.entity");
    }
}
export class EffectAddBeforeEvent {
    get cancel() {
        return unsupported("EffectAddBeforeEvent.cancel");
    }

    get duration() {
        return unsupported("EffectAddBeforeEvent.duration");
    }

    get effectType() {
        return unsupported("EffectAddBeforeEvent.effectType");
    }

    get entity() {
        return unsupported("EffectAddBeforeEvent.entity");
    }
}
export class EmptyLootItem {}
export class EnchantInfo {
    get enchantment() {
        return unsupported("EnchantInfo.enchantment");
    }

    get range() {
        return unsupported("EnchantInfo.range");
    }
}
export class EnchantRandomEquipmentFunction {
    get chance() {
        return unsupported("EnchantRandomEquipmentFunction.chance");
    }
}
export class EnchantRandomlyFunction {
    get treasure() {
        return unsupported("EnchantRandomlyFunction.treasure");
    }
}
export class EnchantWithLevelsFunction {
    get levels() {
        return unsupported("EnchantWithLevelsFunction.levels");
    }

    get treasure() {
        return unsupported("EnchantWithLevelsFunction.treasure");
    }
}
export class EntityCanClimbComponent {}
export class EntityCanFlyComponent {}
export class EntityCanPowerJumpComponent {}
export class EntityColor2Component extends EntityComponent {
    get value() {
        return unsupported("EntityColor2Component.value");
    }
}
export class EntityColorComponent extends EntityComponent {
    get value() {
        return unsupported("EntityColorComponent.value");
    }
}
export class EntityDieAfterEvent {
    get damageSource() {
        return unsupported("EntityDieAfterEvent.damageSource");
    }

    get deadEntity() {
        return unsupported("EntityDieAfterEvent.deadEntity");
    }
}
export class EntityExhaustionComponent {}
export class EntityFireImmuneComponent {}
export class EntityFloatsInLiquidComponent {}
export class EntityFlyingSpeedComponent extends EntityComponent {
    get value() {
        return unsupported("EntityFlyingSpeedComponent.value");
    }
}
export class EntityFrictionModifierComponent extends EntityComponent {
    get value() {
        return unsupported("EntityFrictionModifierComponent.value");
    }
}
export class EntityHasMarkVariantCondition {
    get value() {
        return unsupported("EntityHasMarkVariantCondition.value");
    }
}
export class EntityHasVariantCondition {
    get value() {
        return unsupported("EntityHasVariantCondition.value");
    }
}
export class EntityHealAfterEvent {
    get healedEntity() {
        return unsupported("EntityHealAfterEvent.healedEntity");
    }

    get healing() {
        return unsupported("EntityHealAfterEvent.healing");
    }

    get healSource() {
        return unsupported("EntityHealAfterEvent.healSource");
    }
}
export class EntityHealBeforeEvent {
    get cancel() {
        return unsupported("EntityHealBeforeEvent.cancel");
    }

    get healedEntity() {
        return unsupported("EntityHealBeforeEvent.healedEntity");
    }

    get healing() {
        return unsupported("EntityHealBeforeEvent.healing");
    }

    get healSource() {
        return unsupported("EntityHealBeforeEvent.healSource");
    }
}
export class EntityHealSource {
    get cause() {
        return unsupported("EntityHealSource.cause");
    }
}
export class EntityHealthChangedAfterEvent {
    get entity() {
        return unsupported("EntityHealthChangedAfterEvent.entity");
    }

    get newValue() {
        return unsupported("EntityHealthChangedAfterEvent.newValue");
    }

    get oldValue() {
        return unsupported("EntityHealthChangedAfterEvent.oldValue");
    }
}
export class EntityHitBlockAfterEvent {
    get blockFace() {
        return unsupported("EntityHitBlockAfterEvent.blockFace");
    }

    get damagingEntity() {
        return unsupported("EntityHitBlockAfterEvent.damagingEntity");
    }

    get hitBlock() {
        return unsupported("EntityHitBlockAfterEvent.hitBlock");
    }

    get hitBlockPermutation() {
        return unsupported("EntityHitBlockAfterEvent.hitBlockPermutation");
    }
}
export class EntityHitEntityAfterEvent {
    get damagingEntity() {
        return unsupported("EntityHitEntityAfterEvent.damagingEntity");
    }

    get hitEntity() {
        return unsupported("EntityHitEntityAfterEvent.hitEntity");
    }
}
export class EntityHurtAfterEvent {
    get damage() {
        return unsupported("EntityHurtAfterEvent.damage");
    }

    get damageSource() {
        return unsupported("EntityHurtAfterEvent.damageSource");
    }

    get hurtEntity() {
        return unsupported("EntityHurtAfterEvent.hurtEntity");
    }
}
export class EntityHurtBeforeEvent {
    get cancel() {
        return unsupported("EntityHurtBeforeEvent.cancel");
    }

    get damage() {
        return unsupported("EntityHurtBeforeEvent.damage");
    }

    get damageSource() {
        return unsupported("EntityHurtBeforeEvent.damageSource");
    }

    get hurtEntity() {
        return unsupported("EntityHurtBeforeEvent.hurtEntity");
    }
}
export class EntityIsChargedComponent {}
export class EntityIsChestedComponent {}
export class EntityIsDyeableComponent {}
export class EntityIsHiddenWhenInvisibleComponent {}
export class EntityIsIgnitedComponent {}
export class EntityIsIllagerCaptainComponent {}
export class EntityIsSaddledComponent {}
export class EntityIsShakingComponent {}
export class EntityIsShearedComponent {}
export class EntityIsStackableComponent {}
export class EntityIsStunnedComponent {}
export class EntityIsTamedComponent {}
export class EntityItemDropAfterEvent {
    get entity() {
        return unsupported("EntityItemDropAfterEvent.entity");
    }

    get items() {
        return unsupported("EntityItemDropAfterEvent.items");
    }
}
export class EntityItemPickupAfterEvent {
    get entity() {
        return unsupported("EntityItemPickupAfterEvent.entity");
    }

    get items() {
        return unsupported("EntityItemPickupAfterEvent.items");
    }
}
export class EntityItemPickupBeforeEvent {
    get cancel() {
        return unsupported("EntityItemPickupBeforeEvent.cancel");
    }

    get entity() {
        return unsupported("EntityItemPickupBeforeEvent.entity");
    }

    get item() {
        return unsupported("EntityItemPickupBeforeEvent.item");
    }
}
export class EntityKilledCondition {
    get entityType() {
        return unsupported("EntityKilledCondition.entityType");
    }
}
export class EntityLavaMovementComponent {}
export class EntityLoadAfterEvent {
    get entity() {
        return unsupported("EntityLoadAfterEvent.entity");
    }
}
export class EntityStartSneakingAfterEvent {
    get entity() {
        return unsupported("EntityStartSneakingAfterEvent.entity");
    }
}
export class EntityStopSneakingAfterEvent {
    get entity() {
        return unsupported("EntityStopSneakingAfterEvent.entity");
    }
}
export class EntityTamedAfterEvent {
    get entity() {
        return unsupported("EntityTamedAfterEvent.entity");
    }

    get tamingEntity() {
        return unsupported("EntityTamedAfterEvent.tamingEntity");
    }
}
export class EntityTamedBeforeEvent {
    get cancel() {
        return unsupported("EntityTamedBeforeEvent.cancel");
    }

    get entity() {
        return unsupported("EntityTamedBeforeEvent.entity");
    }

    get tamingEntity() {
        return unsupported("EntityTamedBeforeEvent.tamingEntity");
    }
}
export class EntityPushThroughComponent extends EntityComponent {
    get value() {
        return unsupported("EntityPushThroughComponent.value");
    }
}
export class EntityRemoveAfterEvent {
    get removedEntityId() {
        return unsupported("EntityRemoveAfterEvent.removedEntityId");
    }

    get typeId() {
        return unsupported("EntityRemoveAfterEvent.typeId");
    }
}
export class EntityRemoveBeforeEvent {
    get removedEntity() {
        return unsupported("EntityRemoveBeforeEvent.removedEntity");
    }
}
export class EntityScaleComponent extends EntityComponent {
    get value() {
        return unsupported("EntityScaleComponent.value");
    }
}
export class EntitySpawnAfterEvent {
    get cause() {
        return unsupported("EntitySpawnAfterEvent.cause");
    }

    get entity() {
        return unsupported("EntitySpawnAfterEvent.entity");
    }
}
export class EntityUnderwaterMovementComponent {}
export class EntityWantsJockeyComponent {}
export class ExplorationMapFunction {
    get destination() {
        return unsupported("ExplorationMapFunction.destination");
    }
}
export class ExplosionAfterEvent {
    get dimension() {
        return unsupported("ExplosionAfterEvent.dimension");
    }

    get source() {
        return unsupported("ExplosionAfterEvent.source");
    }

    getImpactedBlocks() {
        return unsupported("ExplosionAfterEvent.getImpactedBlocks");
    }
}
export class ExplosionBeforeEvent extends ExplosionAfterEvent {
    get cancel() {
        return unsupported("ExplosionBeforeEvent.cancel");
    }

    setImpactedBlocks() {
        return unsupported("ExplosionBeforeEvent.setImpactedBlocks");
    }
}
export class ExplosionDecayFunction {}
export class FillContainerFunction {
    get lootTable() {
        return unsupported("FillContainerFunction.lootTable");
    }
}
export class FluidContainer {}
export class GameRuleChangeAfterEvent {
    get rule() {
        return unsupported("GameRuleChangeAfterEvent.rule");
    }

    get value() {
        return unsupported("GameRuleChangeAfterEvent.value");
    }
}
export class PackSettingChangeAfterEvent {
    get settingName() {
        return unsupported("PackSettingChangeAfterEvent.settingName");
    }

    get settingValue() {
        return unsupported("PackSettingChangeAfterEvent.settingValue");
    }
}
export class IsBabyCondition {}
export class ItemCompleteUseAfterEvent {
    get itemStack() {
        return unsupported("ItemCompleteUseAfterEvent.itemStack");
    }

    get source() {
        return unsupported("ItemCompleteUseAfterEvent.source");
    }

    get useDuration() {
        return unsupported("ItemCompleteUseAfterEvent.useDuration");
    }
}
export class ItemCompleteUseEvent {
    get itemStack() {
        return unsupported("ItemCompleteUseEvent.itemStack");
    }

    get source() {
        return unsupported("ItemCompleteUseEvent.source");
    }
}
export class ItemComponentBeforeDurabilityDamageEvent {
    get attackingEntity() {
        return unsupported("ItemComponentBeforeDurabilityDamageEvent.attackingEntity");
    }

    get durabilityDamage() {
        return unsupported("ItemComponentBeforeDurabilityDamageEvent.durabilityDamage");
    }

    get hitEntity() {
        return unsupported("ItemComponentBeforeDurabilityDamageEvent.hitEntity");
    }

    get itemStack() {
        return unsupported("ItemComponentBeforeDurabilityDamageEvent.itemStack");
    }
}
export class ItemComponentCompleteUseEvent {}
export class ItemComponentConsumeEvent {
    get itemStack() {
        return unsupported("ItemComponentConsumeEvent.itemStack");
    }

    get source() {
        return unsupported("ItemComponentConsumeEvent.source");
    }
}
export class ItemComponentHitEntityEvent {
    get attackingEntity() {
        return unsupported("ItemComponentHitEntityEvent.attackingEntity");
    }

    get hadEffect() {
        return unsupported("ItemComponentHitEntityEvent.hadEffect");
    }

    get hitEntity() {
        return unsupported("ItemComponentHitEntityEvent.hitEntity");
    }

    get itemStack() {
        return unsupported("ItemComponentHitEntityEvent.itemStack");
    }
}
export class ItemComponentMineBlockEvent {
    get block() {
        return unsupported("ItemComponentMineBlockEvent.block");
    }

    get itemStack() {
        return unsupported("ItemComponentMineBlockEvent.itemStack");
    }

    get minedBlockPermutation() {
        return unsupported("ItemComponentMineBlockEvent.minedBlockPermutation");
    }

    get source() {
        return unsupported("ItemComponentMineBlockEvent.source");
    }
}
export class ItemComponentRegistry {
    registerCustomComponent(name, component) {
        customItemComponents.set(name, component ?? {});
    }
}
export class ItemComponentUseEvent {
    get itemStack() {
        return unsupported("ItemComponentUseEvent.itemStack");
    }

    get source() {
        return unsupported("ItemComponentUseEvent.source");
    }
}
export class ItemComponentUseOnEvent {
    get source() {
        return unsupported("ItemComponentUseOnEvent.source");
    }

    get usedOnBlockPermutation() {
        return unsupported("ItemComponentUseOnEvent.usedOnBlockPermutation");
    }
}
export class ItemCustomComponentInstance extends ItemComponent {
    get customComponentParameters() {
        return unsupported("ItemCustomComponentInstance.customComponentParameters");
    }
}
export class ItemInventoryComponent extends ItemComponent {
    get container() {
        return unsupported("ItemInventoryComponent.container");
    }
}
export class ItemReleaseUseAfterEvent {
    get itemStack() {
        return unsupported("ItemReleaseUseAfterEvent.itemStack");
    }

    get source() {
        return unsupported("ItemReleaseUseAfterEvent.source");
    }

    get useDuration() {
        return unsupported("ItemReleaseUseAfterEvent.useDuration");
    }
}
export class ItemStartUseAfterEvent {
    get itemStack() {
        return unsupported("ItemStartUseAfterEvent.itemStack");
    }

    get source() {
        return unsupported("ItemStartUseAfterEvent.source");
    }

    get useDuration() {
        return unsupported("ItemStartUseAfterEvent.useDuration");
    }
}
export class ItemStartUseOnAfterEvent {
    get block() {
        return unsupported("ItemStartUseOnAfterEvent.block");
    }

    get blockFace() {
        return unsupported("ItemStartUseOnAfterEvent.blockFace");
    }

    get itemStack() {
        return unsupported("ItemStartUseOnAfterEvent.itemStack");
    }

    get source() {
        return unsupported("ItemStartUseOnAfterEvent.source");
    }
}
export class ItemStopUseAfterEvent {
    get itemStack() {
        return unsupported("ItemStopUseAfterEvent.itemStack");
    }

    get source() {
        return unsupported("ItemStopUseAfterEvent.source");
    }

    get useDuration() {
        return unsupported("ItemStopUseAfterEvent.useDuration");
    }
}
export class ItemStopUseOnAfterEvent {
    get block() {
        return unsupported("ItemStopUseOnAfterEvent.block");
    }

    get itemStack() {
        return unsupported("ItemStopUseOnAfterEvent.itemStack");
    }

    get source() {
        return unsupported("ItemStopUseOnAfterEvent.source");
    }
}
export class ItemUseAfterEvent {
    get itemStack() {
        return unsupported("ItemUseAfterEvent.itemStack");
    }

    get source() {
        return unsupported("ItemUseAfterEvent.source");
    }
}
export class ItemUseBeforeEvent {
    get cancel() {
        return unsupported("ItemUseBeforeEvent.cancel");
    }

    get itemStack() {
        return unsupported("ItemUseBeforeEvent.itemStack");
    }

    get source() {
        return unsupported("ItemUseBeforeEvent.source");
    }
}
export class ItemUseOnEvent {
    get block() {
        return unsupported("ItemUseOnEvent.block");
    }

    get blockFace() {
        return unsupported("ItemUseOnEvent.blockFace");
    }

    get faceLocation() {
        return unsupported("ItemUseOnEvent.faceLocation");
    }

    get itemStack() {
        return unsupported("ItemUseOnEvent.itemStack");
    }
}
export class KilledByEntityCondition {
    get entityType() {
        return unsupported("KilledByEntityCondition.entityType");
    }
}
export class KilledByPlayerCondition {}
export class KilledByPlayerOrPetsCondition {}
export class LeverActionAfterEvent extends BlockEvent {
    get isPowered() {
        return unsupported("LeverActionAfterEvent.isPowered");
    }

    get player() {
        return unsupported("LeverActionAfterEvent.player");
    }
}
/** Straight runs from each point to the next, which `Camera.playAnimation` carries a camera along. */
export class LinearSpline {
    controlPoints = [];
}
export class LootItem {
    get conditions() {
        return unsupported("LootItem.conditions");
    }

    get functions() {
        return unsupported("LootItem.functions");
    }

    get name() {
        return unsupported("LootItem.name");
    }
}
export class LootItemCondition {}
export class LootItemFunction {
    get conditions() {
        return unsupported("LootItemFunction.conditions");
    }
}
export class LootPool {
    get bonusRolls() {
        return unsupported("LootPool.bonusRolls");
    }

    get conditions() {
        return unsupported("LootPool.conditions");
    }

    get entries() {
        return unsupported("LootPool.entries");
    }

    get rolls() {
        return unsupported("LootPool.rolls");
    }

    get tiers() {
        return unsupported("LootPool.tiers");
    }
}
export class LootPoolEntry {
    get quality() {
        return unsupported("LootPoolEntry.quality");
    }

    get subTable() {
        return unsupported("LootPoolEntry.subTable");
    }

    get weight() {
        return unsupported("LootPoolEntry.weight");
    }
}
export class LootPoolTiers {
    get bonusChance() {
        return unsupported("LootPoolTiers.bonusChance");
    }

    get bonusRolls() {
        return unsupported("LootPoolTiers.bonusRolls");
    }

    get initialRange() {
        return unsupported("LootPoolTiers.initialRange");
    }
}
export class LootTable {
    get path() {
        return unsupported("LootTable.path");
    }

    get pools() {
        return unsupported("LootTable.pools");
    }
}
export class LootTableEntry extends LootPoolEntry {
    get lootTable() {
        return unsupported("LootTableEntry.lootTable");
    }
}
export class LootTableReference extends LootPoolEntry {
    get path() {
        return unsupported("LootTableReference.path");
    }
}
export class LootingEnchantFunction extends LootItemFunction {
    get count() {
        return unsupported("LootingEnchantFunction.count");
    }
}
export class MatchToolCondition extends LootItemCondition {
    get count() {
        return unsupported("MatchToolCondition.count");
    }

    get durability() {
        return unsupported("MatchToolCondition.durability");
    }

    get enchantments() {
        return unsupported("MatchToolCondition.enchantments");
    }

    get itemName() {
        return unsupported("MatchToolCondition.itemName");
    }

    get itemTagsAll() {
        return unsupported("MatchToolCondition.itemTagsAll");
    }

    get itemTagsAny() {
        return unsupported("MatchToolCondition.itemTagsAny");
    }

    get itemTagsNone() {
        return unsupported("MatchToolCondition.itemTagsNone");
    }
}
export class PassengerOfEntityCondition extends LootItemCondition {
    get entityType() {
        return unsupported("PassengerOfEntityCondition.entityType");
    }
}
export class PistonActivateAfterEvent extends BlockEvent {
    get isExpanding() {
        return unsupported("PistonActivateAfterEvent.isExpanding");
    }

    get piston() {
        return unsupported("PistonActivateAfterEvent.piston");
    }
}
export class PlayerBreakBlockAfterEvent {
    get block() {
        return unsupported("PlayerBreakBlockAfterEvent.block");
    }

    get brokenBlockPermutation() {
        return unsupported("PlayerBreakBlockAfterEvent.brokenBlockPermutation");
    }

    get dimension() {
        return unsupported("PlayerBreakBlockAfterEvent.dimension");
    }

    get itemStackAfterBreak() {
        return unsupported("PlayerBreakBlockAfterEvent.itemStackAfterBreak");
    }

    get itemStackBeforeBreak() {
        return unsupported("PlayerBreakBlockAfterEvent.itemStackBeforeBreak");
    }

    get player() {
        return unsupported("PlayerBreakBlockAfterEvent.player");
    }
}
export class PlayerBreakBlockBeforeEvent {
    get block() {
        return unsupported("PlayerBreakBlockBeforeEvent.block");
    }

    get cancel() {
        return unsupported("PlayerBreakBlockBeforeEvent.cancel");
    }

    get dimension() {
        return unsupported("PlayerBreakBlockBeforeEvent.dimension");
    }

    get itemStack() {
        return unsupported("PlayerBreakBlockBeforeEvent.itemStack");
    }

    get player() {
        return unsupported("PlayerBreakBlockBeforeEvent.player");
    }
}
export class PlayerButtonInputAfterEvent {
    get button() {
        return unsupported("PlayerButtonInputAfterEvent.button");
    }

    get newButtonState() {
        return unsupported("PlayerButtonInputAfterEvent.newButtonState");
    }

    get player() {
        return unsupported("PlayerButtonInputAfterEvent.player");
    }
}
export class PlayerCursorInventoryComponent extends EntityComponent {
    get item() {
        return unsupported("PlayerCursorInventoryComponent.item");
    }

    clear() {
        return unsupported("PlayerCursorInventoryComponent.clear");
    }
}
export class PlayerDimensionChangeAfterEvent {
    get fromDimension() {
        return unsupported("PlayerDimensionChangeAfterEvent.fromDimension");
    }

    get fromLocation() {
        return unsupported("PlayerDimensionChangeAfterEvent.fromLocation");
    }

    get player() {
        return unsupported("PlayerDimensionChangeAfterEvent.player");
    }

    get toDimension() {
        return unsupported("PlayerDimensionChangeAfterEvent.toDimension");
    }

    get toLocation() {
        return unsupported("PlayerDimensionChangeAfterEvent.toLocation");
    }
}
export class PlayerEmoteAfterEvent {
    get personaPieceId() {
        return unsupported("PlayerEmoteAfterEvent.personaPieceId");
    }

    get player() {
        return unsupported("PlayerEmoteAfterEvent.player");
    }
}
export class PlayerEmoteAfterEventSignal extends EventSignal {}
export class PlayerGameModeChangeAfterEvent {
    get fromGameMode() {
        return unsupported("PlayerGameModeChangeAfterEvent.fromGameMode");
    }

    get player() {
        return unsupported("PlayerGameModeChangeAfterEvent.player");
    }

    get toGameMode() {
        return unsupported("PlayerGameModeChangeAfterEvent.toGameMode");
    }
}
export class PlayerGameModeChangeBeforeEvent {
    get cancel() {
        return unsupported("PlayerGameModeChangeBeforeEvent.cancel");
    }

    get fromGameMode() {
        return unsupported("PlayerGameModeChangeBeforeEvent.fromGameMode");
    }

    get player() {
        return unsupported("PlayerGameModeChangeBeforeEvent.player");
    }

    get toGameMode() {
        return unsupported("PlayerGameModeChangeBeforeEvent.toGameMode");
    }
}
export class PlayerHotbarSelectedSlotChangeAfterEvent {
    get itemStack() {
        return unsupported("PlayerHotbarSelectedSlotChangeAfterEvent.itemStack");
    }

    get newSlotSelected() {
        return unsupported("PlayerHotbarSelectedSlotChangeAfterEvent.newSlotSelected");
    }

    get player() {
        return unsupported("PlayerHotbarSelectedSlotChangeAfterEvent.player");
    }

    get previousSlotSelected() {
        return unsupported("PlayerHotbarSelectedSlotChangeAfterEvent.previousSlotSelected");
    }
}
export class PlayerInputModeChangeAfterEvent {
    get newInputModeUsed() {
        return unsupported("PlayerInputModeChangeAfterEvent.newInputModeUsed");
    }

    get player() {
        return unsupported("PlayerInputModeChangeAfterEvent.player");
    }

    get previousInputModeUsed() {
        return unsupported("PlayerInputModeChangeAfterEvent.previousInputModeUsed");
    }
}
export class PlayerInputModeChangeAfterEventSignal extends EventSignal {}
export class PlayerInputPermissionCategoryChangeAfterEvent {
    get category() {
        return unsupported("PlayerInputPermissionCategoryChangeAfterEvent.category");
    }

    get enabled() {
        return unsupported("PlayerInputPermissionCategoryChangeAfterEvent.enabled");
    }

    get player() {
        return unsupported("PlayerInputPermissionCategoryChangeAfterEvent.player");
    }
}
export class PlayerInteractWithBlockAfterEvent {
    get beforeItemStack() {
        return unsupported("PlayerInteractWithBlockAfterEvent.beforeItemStack");
    }

    get block() {
        return unsupported("PlayerInteractWithBlockAfterEvent.block");
    }

    get blockFace() {
        return unsupported("PlayerInteractWithBlockAfterEvent.blockFace");
    }

    get faceLocation() {
        return unsupported("PlayerInteractWithBlockAfterEvent.faceLocation");
    }

    get isFirstEvent() {
        return unsupported("PlayerInteractWithBlockAfterEvent.isFirstEvent");
    }

    get itemStack() {
        return unsupported("PlayerInteractWithBlockAfterEvent.itemStack");
    }

    get player() {
        return unsupported("PlayerInteractWithBlockAfterEvent.player");
    }
}
export class PlayerInteractWithBlockBeforeEvent {
    get block() {
        return unsupported("PlayerInteractWithBlockBeforeEvent.block");
    }

    get blockFace() {
        return unsupported("PlayerInteractWithBlockBeforeEvent.blockFace");
    }

    get cancel() {
        return unsupported("PlayerInteractWithBlockBeforeEvent.cancel");
    }

    get faceLocation() {
        return unsupported("PlayerInteractWithBlockBeforeEvent.faceLocation");
    }

    get isFirstEvent() {
        return unsupported("PlayerInteractWithBlockBeforeEvent.isFirstEvent");
    }

    get itemStack() {
        return unsupported("PlayerInteractWithBlockBeforeEvent.itemStack");
    }

    get player() {
        return unsupported("PlayerInteractWithBlockBeforeEvent.player");
    }
}
export class PlayerInteractWithEntityAfterEvent {
    get beforeItemStack() {
        return unsupported("PlayerInteractWithEntityAfterEvent.beforeItemStack");
    }

    get itemStack() {
        return unsupported("PlayerInteractWithEntityAfterEvent.itemStack");
    }

    get player() {
        return unsupported("PlayerInteractWithEntityAfterEvent.player");
    }

    get target() {
        return unsupported("PlayerInteractWithEntityAfterEvent.target");
    }
}
export class PlayerInteractWithEntityBeforeEvent {
    get cancel() {
        return unsupported("PlayerInteractWithEntityBeforeEvent.cancel");
    }

    get itemStack() {
        return unsupported("PlayerInteractWithEntityBeforeEvent.itemStack");
    }

    get player() {
        return unsupported("PlayerInteractWithEntityBeforeEvent.player");
    }

    get target() {
        return unsupported("PlayerInteractWithEntityBeforeEvent.target");
    }
}
export class PlayerInventoryItemChangeAfterEvent {
    get beforeItemStack() {
        return unsupported("PlayerInventoryItemChangeAfterEvent.beforeItemStack");
    }

    get inventoryType() {
        return unsupported("PlayerInventoryItemChangeAfterEvent.inventoryType");
    }

    get itemStack() {
        return unsupported("PlayerInventoryItemChangeAfterEvent.itemStack");
    }

    get player() {
        return unsupported("PlayerInventoryItemChangeAfterEvent.player");
    }

    get slot() {
        return unsupported("PlayerInventoryItemChangeAfterEvent.slot");
    }
}
export class PlayerJoinAfterEvent {
    get playerId() {
        return unsupported("PlayerJoinAfterEvent.playerId");
    }

    get playerName() {
        return unsupported("PlayerJoinAfterEvent.playerName");
    }
}
export class PlayerLeaveAfterEvent {
    get playerId() {
        return unsupported("PlayerLeaveAfterEvent.playerId");
    }

    get playerName() {
        return unsupported("PlayerLeaveAfterEvent.playerName");
    }
}
export class PlayerLeaveBeforeEvent {
    get player() {
        return unsupported("PlayerLeaveBeforeEvent.player");
    }
}
export class PlayerPlaceBlockAfterEvent {
    get block() {
        return unsupported("PlayerPlaceBlockAfterEvent.block");
    }

    get dimension() {
        return unsupported("PlayerPlaceBlockAfterEvent.dimension");
    }

    get player() {
        return unsupported("PlayerPlaceBlockAfterEvent.player");
    }
}
export class PlayerSpawnAfterEvent {
    get initialSpawn() {
        return unsupported("PlayerSpawnAfterEvent.initialSpawn");
    }

    get player() {
        return unsupported("PlayerSpawnAfterEvent.player");
    }
}
export class PlayerSwingStartAfterEvent {
    get heldItemStack() {
        return unsupported("PlayerSwingStartAfterEvent.heldItemStack");
    }

    get player() {
        return unsupported("PlayerSwingStartAfterEvent.player");
    }

    get swingSource() {
        return unsupported("PlayerSwingStartAfterEvent.swingSource");
    }
}
export class PressurePlatePopAfterEvent extends BlockEvent {
    get previousRedstonePower() {
        return unsupported("PressurePlatePopAfterEvent.previousRedstonePower");
    }

    get redstonePower() {
        return unsupported("PressurePlatePopAfterEvent.redstonePower");
    }
}
export class PressurePlatePushAfterEvent extends BlockEvent {
    get previousRedstonePower() {
        return unsupported("PressurePlatePushAfterEvent.previousRedstonePower");
    }

    get redstonePower() {
        return unsupported("PressurePlatePushAfterEvent.redstonePower");
    }

    get source() {
        return unsupported("PressurePlatePushAfterEvent.source");
    }
}
export class ProjectileHitBlockAfterEvent {
    get dimension() {
        return unsupported("ProjectileHitBlockAfterEvent.dimension");
    }

    get hitVector() {
        return unsupported("ProjectileHitBlockAfterEvent.hitVector");
    }

    get location() {
        return unsupported("ProjectileHitBlockAfterEvent.location");
    }

    get projectile() {
        return unsupported("ProjectileHitBlockAfterEvent.projectile");
    }

    get source() {
        return unsupported("ProjectileHitBlockAfterEvent.source");
    }

    getBlockHit() {
        return unsupported("ProjectileHitBlockAfterEvent.getBlockHit");
    }
}
export class ProjectileHitEntityAfterEvent {
    get dimension() {
        return unsupported("ProjectileHitEntityAfterEvent.dimension");
    }

    get hitVector() {
        return unsupported("ProjectileHitEntityAfterEvent.hitVector");
    }

    get location() {
        return unsupported("ProjectileHitEntityAfterEvent.location");
    }

    get projectile() {
        return unsupported("ProjectileHitEntityAfterEvent.projectile");
    }

    get source() {
        return unsupported("ProjectileHitEntityAfterEvent.source");
    }

    getEntityHit() {
        return unsupported("ProjectileHitEntityAfterEvent.getEntityHit");
    }
}
export class RandomAuxValueFunction extends LootItemFunction {
    get values() {
        return unsupported("RandomAuxValueFunction.values");
    }
}
export class RandomBlockStateFunction extends LootItemFunction {
    get blockState() {
        return unsupported("RandomBlockStateFunction.blockState");
    }

    get values() {
        return unsupported("RandomBlockStateFunction.values");
    }
}
export class RandomChanceCondition extends LootItemCondition {
    get chance() {
        return unsupported("RandomChanceCondition.chance");
    }
}
export class RandomChanceWithLootingCondition extends LootItemCondition {
    get chance() {
        return unsupported("RandomChanceWithLootingCondition.chance");
    }

    get lootingMultiplier() {
        return unsupported("RandomChanceWithLootingCondition.lootingMultiplier");
    }
}
export class RandomDifficultyChanceCondition extends LootItemCondition {
    get chances() {
        return unsupported("RandomDifficultyChanceCondition.chances");
    }
}
export class RandomDyeFunction {}
export class RandomRegionalDifficultyChanceCondition extends LootItemCondition {
    get maxChance() {
        return unsupported("RandomRegionalDifficultyChanceCondition.maxChance");
    }
}
export class ScriptEventCommandMessageAfterEvent {
    get id() {
        return unsupported("ScriptEventCommandMessageAfterEvent.id");
    }

    get initiator() {
        return unsupported("ScriptEventCommandMessageAfterEvent.initiator");
    }

    get message() {
        return unsupported("ScriptEventCommandMessageAfterEvent.message");
    }

    get sourceBlock() {
        return unsupported("ScriptEventCommandMessageAfterEvent.sourceBlock");
    }

    get sourceEntity() {
        return unsupported("ScriptEventCommandMessageAfterEvent.sourceEntity");
    }

    get sourceType() {
        return unsupported("ScriptEventCommandMessageAfterEvent.sourceType");
    }
}
export class SetArmorTrimFunction extends LootItemFunction {
    get material() {
        return unsupported("SetArmorTrimFunction.material");
    }

    get pattern() {
        return unsupported("SetArmorTrimFunction.pattern");
    }
}
export class SetBannerDetailsFunction {}
export class SetBookContentsFunction extends LootItemFunction {
    get author() {
        return unsupported("SetBookContentsFunction.author");
    }

    get pages() {
        return unsupported("SetBookContentsFunction.pages");
    }

    get title() {
        return unsupported("SetBookContentsFunction.title");
    }
}
export class SetDataFromColorIndexFunction {}
export class SetItemCountFunction extends LootItemFunction {
    get count() {
        return unsupported("SetItemCountFunction.count");
    }
}
export class SetItemDamageFunction extends LootItemFunction {
    get damage() {
        return unsupported("SetItemDamageFunction.damage");
    }
}
export class SetItemDataFunction extends LootItemFunction {
    get data() {
        return unsupported("SetItemDataFunction.data");
    }
}
export class SetItemLoreFunction extends LootItemFunction {
    get lore() {
        return unsupported("SetItemLoreFunction.lore");
    }
}
export class SetItemNameFunction extends LootItemFunction {
    get name() {
        return unsupported("SetItemNameFunction.name");
    }
}
export class SetOminousBottleFunction extends LootItemFunction {
    get amplifier() {
        return unsupported("SetOminousBottleFunction.amplifier");
    }
}
export class SetPotionFunction extends LootItemFunction {
    get id() {
        return unsupported("SetPotionFunction.id");
    }
}
export class SetSpawnEggFunction extends LootItemFunction {
    get id() {
        return unsupported("SetSpawnEggFunction.id");
    }
}
export class SetStewEffectFunction extends LootItemFunction {
    get effects() {
        return unsupported("SetStewEffectFunction.effects");
    }
}
export class ShutdownEvent {}
export class SmeltItemFunction {}
export class SpecificEnchantFunction extends LootItemFunction {
    get enchantments() {
        return unsupported("SpecificEnchantFunction.enchantments");
    }
}
export class StartupEvent {
    get blockComponentRegistry() {
        return unsupported("StartupEvent.blockComponentRegistry");
    }

    get customCommandRegistry() {
        return unsupported("StartupEvent.customCommandRegistry");
    }

    get dimensionRegistry() {
        return unsupported("StartupEvent.dimensionRegistry");
    }

    get itemComponentRegistry() {
        return unsupported("StartupEvent.itemComponentRegistry");
    }
}
export class SystemAfterEvents {}
export class SystemBeforeEvents {}
export class TargetBlockHitAfterEvent extends BlockEvent {
    get hitVector() {
        return unsupported("TargetBlockHitAfterEvent.hitVector");
    }

    get previousRedstonePower() {
        return unsupported("TargetBlockHitAfterEvent.previousRedstonePower");
    }

    get redstonePower() {
        return unsupported("TargetBlockHitAfterEvent.redstonePower");
    }

    get source() {
        return unsupported("TargetBlockHitAfterEvent.source");
    }
}
export class TripWireTripAfterEvent extends BlockEvent {
    get isPowered() {
        return unsupported("TripWireTripAfterEvent.isPowered");
    }

    get sources() {
        return unsupported("TripWireTripAfterEvent.sources");
    }
}
export class WeatherChangeAfterEvent {
    get dimension() {
        return unsupported("WeatherChangeAfterEvent.dimension");
    }

    get newWeather() {
        return unsupported("WeatherChangeAfterEvent.newWeather");
    }

    get previousWeather() {
        return unsupported("WeatherChangeAfterEvent.previousWeather");
    }
}
export class WeatherChangeBeforeEvent {
    get cancel() {
        return unsupported("WeatherChangeBeforeEvent.cancel");
    }

    get duration() {
        return unsupported("WeatherChangeBeforeEvent.duration");
    }

    get newWeather() {
        return unsupported("WeatherChangeBeforeEvent.newWeather");
    }

    get previousWeather() {
        return unsupported("WeatherChangeBeforeEvent.previousWeather");
    }
}
export class WorldAfterEvents {}
export class WorldBeforeEvents {}
export class WorldLoadAfterEvent {}

// Names the newest API adds that this mod has nothing behind yet. A module is linked before any of
// it runs, so a pack importing one of these would lose its whole script over a name rather than
// over the one line that used it. Each is here so the import succeeds; reaching for what they would
// do reports through the same path every other gap does.
export class BlockComponentBlockStateChangeEvent extends BlockEvent {
    get previousPermutation() {
        return unsupported("BlockComponentBlockStateChangeEvent.previousPermutation");
    }
}
export class BlockContainerClosedAfterEvent extends BlockEvent {
    get closeSource() {
        return unsupported("BlockContainerClosedAfterEvent.closeSource");
    }
}
export class BlockContainerOpenedAfterEvent extends BlockEvent {
    get openSource() {
        return unsupported("BlockContainerOpenedAfterEvent.openSource");
    }
}
export class DimensionRegistry {
    registerCustomDimension() {
        return unsupported("DimensionRegistry.registerCustomDimension");
    }
}
export class EntityContainerClosedAfterEvent {
    get closeSource() {
        return unsupported("EntityContainerClosedAfterEvent.closeSource");
    }

    get entity() {
        return unsupported("EntityContainerClosedAfterEvent.entity");
    }
}
export class EntityContainerOpenedAfterEvent {
    get entity() {
        return unsupported("EntityContainerOpenedAfterEvent.entity");
    }

    get openSource() {
        return unsupported("EntityContainerOpenedAfterEvent.openSource");
    }
}
export class EntityEnderInventoryComponent extends EntityComponent {
    get container() {
        return unsupported("EntityEnderInventoryComponent.container");
    }
}
export class EntityUpgradeAfterEvent {
    get entity() {
        return unsupported("EntityUpgradeAfterEvent.entity");
    }

    get upgradeId() {
        return unsupported("EntityUpgradeAfterEvent.upgradeId");
    }

    getModifiers() {
        return unsupported("EntityUpgradeAfterEvent.getModifiers");
    }
}
export class EntityUpgradeAfterEventSignal extends EventSignal {}
export class ISerializable {}
export class LocatorBar {
    get count() {
        return unsupported("LocatorBar.count");
    }

    get maxCount() {
        return unsupported("LocatorBar.maxCount");
    }

    addWaypoint() {
        return unsupported("LocatorBar.addWaypoint");
    }

    getAllWaypoints() {
        return unsupported("LocatorBar.getAllWaypoints");
    }

    hasWaypoint() {
        return unsupported("LocatorBar.hasWaypoint");
    }

    removeAllWaypoints() {
        return unsupported("LocatorBar.removeAllWaypoints");
    }

    removeWaypoint() {
        return unsupported("LocatorBar.removeWaypoint");
    }
}
export class PlayerCancelBreakingBlockAfterEvent extends BlockEvent {
    get blockPermutation() {
        return unsupported("PlayerCancelBreakingBlockAfterEvent.blockPermutation");
    }

    get breakProgress() {
        return unsupported("PlayerCancelBreakingBlockAfterEvent.breakProgress");
    }

    get face() {
        return unsupported("PlayerCancelBreakingBlockAfterEvent.face");
    }

    get heldItemStack() {
        return unsupported("PlayerCancelBreakingBlockAfterEvent.heldItemStack");
    }

    get player() {
        return unsupported("PlayerCancelBreakingBlockAfterEvent.player");
    }
}
export class PlayerSplitScreenSlot {}
export class PlayerStartBreakingBlockAfterEvent extends BlockEvent {
    get blockPermutation() {
        return unsupported("PlayerStartBreakingBlockAfterEvent.blockPermutation");
    }

    get face() {
        return unsupported("PlayerStartBreakingBlockAfterEvent.face");
    }

    get heldItemStack() {
        return unsupported("PlayerStartBreakingBlockAfterEvent.heldItemStack");
    }

    get player() {
        return unsupported("PlayerStartBreakingBlockAfterEvent.player");
    }
}
export class PrimitiveShapesManager {
    get maxShapes() {
        return unsupported("PrimitiveShapesManager.maxShapes");
    }

    getShapes() {
        return unsupported("PrimitiveShapesManager.getShapes", []);
    }

    addText() {
        return unsupported("PrimitiveShapesManager.addText");
    }

    removeAll() {
        return unsupported("PrimitiveShapesManager.removeAll");
    }

    removeText() {
        return unsupported("PrimitiveShapesManager.removeText");
    }
}
/**
 * A sound a script played, which is what `playSound` hands back.
 *
 * Bedrock follows a sound's lifetime on the server only when a server sound definition gives it a
 * duration, and nothing here reads one, so no instance carries duration information.
 */
export class SoundInstance {
    #id;
    #soundEventId;
    #recipient;

    constructor(soundEventId, recipient) {
        this.#id = String(++soundsPlayed);
        this.#soundEventId = soundEventId;
        this.#recipient = recipient;
    }

    get id() {
        return this.#id;
    }

    get soundEventId() {
        return this.#soundEventId;
    }

    get recipient() {
        return this.#recipient;
    }

    get durationInfo() {
        return undefined;
    }

    stop() {
        return unsupported("SoundInstance.stop");
    }
}

let soundsPlayed = 0;

/** The sound a pack names, which it may hand over as a definition it looked up or as its id. */
function soundIdOf(sound) {
    return typeof sound === "string" ? sound : sound?.soundEventId;
}

export class SoundCompletedAfterEvent {
    get soundInstanceId() {
        return unsupported("SoundCompletedAfterEvent.soundInstanceId");
    }
}
export class SoundCompletedAfterEventSignal extends EventSignal {}
export class SoundDefinition {
    get durationInfo() {
        return unsupported("SoundDefinition.durationInfo");
    }

    get musicInfo() {
        return unsupported("SoundDefinition.musicInfo");
    }

    get soundEventId() {
        return unsupported("SoundDefinition.soundEventId");
    }

    get tags() {
        return unsupported("SoundDefinition.tags");
    }
}
export class SoundDefinitionRegistry {
    getDefinitions() {
        return unsupported("SoundDefinitionRegistry.getDefinitions", []);
    }
}
export class SoundDurationInfo {
    get duration() {
        return unsupported("SoundDurationInfo.duration");
    }

    get isActive() {
        return unsupported("SoundDurationInfo.isActive");
    }

    getPlaybackPosition() {
        return unsupported("SoundDurationInfo.getPlaybackPosition");
    }
}
export class WaypointTexture {}

// A shape and a waypoint each come in kinds that carry what the base holds, and a class body reads
// the one it extends as it is declared, so each base stands ahead of the kinds built on it.
export class PrimitiveShape {
    get attachedTo() {
        return unsupported("PrimitiveShape.attachedTo");
    }

    get color() {
        return unsupported("PrimitiveShape.color");
    }

    get dimension() {
        return unsupported("PrimitiveShape.dimension");
    }

    get hasDuration() {
        return unsupported("PrimitiveShape.hasDuration");
    }

    get location() {
        return unsupported("PrimitiveShape.location");
    }

    get maximumRenderDistance() {
        return unsupported("PrimitiveShape.maximumRenderDistance");
    }

    get rotation() {
        return unsupported("PrimitiveShape.rotation");
    }

    get scale() {
        return unsupported("PrimitiveShape.scale");
    }

    get timeLeft() {
        return unsupported("PrimitiveShape.timeLeft");
    }

    get totalTimeLeft() {
        return unsupported("PrimitiveShape.totalTimeLeft");
    }

    get visibleTo() {
        return unsupported("PrimitiveShape.visibleTo");
    }

    remove() {
        return unsupported("PrimitiveShape.remove");
    }

    setLocation() {
        return unsupported("PrimitiveShape.setLocation");
    }
}
export class TextPrimitive extends PrimitiveShape {
    get backfaceVisible() {
        return unsupported("TextPrimitive.backfaceVisible");
    }

    get backgroundColorOverride() {
        return unsupported("TextPrimitive.backgroundColorOverride");
    }

    get depthTest() {
        return unsupported("TextPrimitive.depthTest");
    }

    get text() {
        return unsupported("TextPrimitive.text");
    }

    get textBackfaceVisible() {
        return unsupported("TextPrimitive.textBackfaceVisible");
    }

    get useRotation() {
        return unsupported("TextPrimitive.useRotation");
    }

    setText() {
        return unsupported("TextPrimitive.setText");
    }
}
export class Waypoint {
    get color() {
        return unsupported("Waypoint.color");
    }

    get isEnabled() {
        return unsupported("Waypoint.isEnabled");
    }

    get isValid() {
        return unsupported("Waypoint.isValid");
    }

    get textureSelector() {
        return unsupported("Waypoint.textureSelector");
    }

    getDimensionLocation() {
        return unsupported("Waypoint.getDimensionLocation");
    }

    remove() {
        return unsupported("Waypoint.remove");
    }
}
export class EntityWaypoint extends Waypoint {
    get entity() {
        return unsupported("EntityWaypoint.entity");
    }

    get entityRules() {
        return unsupported("EntityWaypoint.entityRules");
    }
}
export class LocationWaypoint extends Waypoint {
    setDimensionLocation() {
        return unsupported("LocationWaypoint.setDimensionLocation");
    }
}
export class PlayerWaypoint extends EntityWaypoint {
    get playerRules() {
        return unsupported("PlayerWaypoint.playerRules");
    }
}

export const AimAssistTargetMode = Object.freeze({ Angle: "Angle", Distance: "Distance" });
export const LocatorBarErrorReason = Object.freeze({
    InvalidPlayer: "InvalidPlayer",
    InvalidWaypoint: "InvalidWaypoint",
});

/** A failure a pack catches by name, which is why each of these is a real error. */
export class BlockCustomComponentAlreadyRegisteredError extends Error {}
export class CustomDimensionAlreadyRegisteredError extends Error {}
export class CustomDimensionInvalidRegistryError extends Error {}
export class CustomDimensionNameError extends Error {}
export class CustomDimensionReloadNewDimensionError extends Error {}
export class InvalidEntityComponentError extends Error {}
export class InvalidWaypointError extends Error {}
export class InvalidWaypointTextureSelectorError extends Error {}
export class LocatorBarError extends Error {
    get reason() {
        return unsupported("LocatorBarError.reason");
    }
}
export class PrimitiveShapeError extends Error {}
export class BlockCustomComponentReloadNewComponentError extends Error {}
export class BlockCustomComponentReloadNewEventError extends Error {}
export class BlockCustomComponentReloadVersionError extends Error {}
export class BookError extends Error {
    get reason() {
        return unsupported("BookError.reason");
    }
}
export class BookPageContentError extends Error {
    constructor(message, pageIndex) {
        super(message);
        this.pageIndex = pageIndex;
    }

    get reason() {
        return unsupported("BookPageContentError.reason");
    }
}
export class CommandError extends Error {}
export class ContainerRulesError extends Error {
    get reason() {
        return unsupported("ContainerRulesError.reason");
    }
}
export class CustomCommandError extends Error {
    get reason() {
        return unsupported("CustomCommandError.reason");
    }
}
export class CustomComponentInvalidRegistryError extends Error {}
export class CustomComponentNameError extends Error {
    get reason() {
        return unsupported("CustomComponentNameError.reason");
    }
}
export class EnchantmentLevelOutOfBoundsError extends Error {}
export class EnchantmentTypeNotCompatibleError extends Error {}
export class EnchantmentTypeUnknownIdError extends Error {}
export class EntitySpawnError extends Error {}
export class InvalidBlockComponentError extends Error {}
export class InvalidContainerError extends Error {}
export class InvalidContainerSlotError extends Error {}
export class InvalidEntityError extends Error {
    get id() {
        return unsupported("InvalidEntityError.id");
    }

    get type() {
        return unsupported("InvalidEntityError.type");
    }
}
export class InvalidItemStackError extends Error {
    get itemType() {
        return unsupported("InvalidItemStackError.itemType");
    }
}
export class InvalidIteratorError extends Error {}
export class InvalidPotionDeliveryTypeError extends Error {}
export class InvalidPotionEffectTypeError extends Error {}
export class InvalidStructureError extends Error {}
export class ItemCustomComponentAlreadyRegisteredError extends Error {}
export class ItemCustomComponentReloadNewComponentError extends Error {}
export class ItemCustomComponentReloadNewEventError extends Error {}
export class ItemCustomComponentReloadVersionError extends Error {}
export class LocationInUnloadedChunkError extends Error {}
export class LocationOutOfWorldBoundariesError extends Error {}
export class NamespaceNameError extends Error {
    get reason() {
        return unsupported("NamespaceNameError.reason");
    }
}
export class PlaceJigsawError extends Error {}
export class RawMessageError extends Error {}
export class TickingAreaError extends Error {
    constructor(message, reason) {
        super(message);
        this.reason = reason;
    }
}
export class UnloadedChunksError extends Error {}
