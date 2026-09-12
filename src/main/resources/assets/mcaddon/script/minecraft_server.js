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
function blockType(id) {
    let type = blockTypeCache.get(id);
    if (type === undefined) {
        type = Object.freeze({ id, localizationKey: bridge.blocks.localizationKey(id) ?? id });
        blockTypeCache.set(id, type);
    }
    return type;
}

function typeIdOf(value) {
    if (typeof value === "string") return value;
    return value?.type?.id ?? value?.id;
}

// A block name without a namespace is a vanilla one, which is how the registry reads it too. Going
// through the bridge for that would cost a host call per comparison, so the default is applied here.
function blockNameOf(value) {
    const id = typeIdOf(value);
    return typeof id === "string" && !id.includes(":") ? "minecraft:" + id : id;
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

export const BlockTypes = Object.freeze({
    get(typeName) {
        const id = bridge.blocks.canonicalId(typeName);
        return id === null ? undefined : blockType(id);
    },
    getAll() {
        return list(bridge.blocks.typeIds()).map(id => blockType(id));
    },
});

// The catalogue is the same for as long as the game runs, and building it reads every block there
// is, so it is read once and handed out as a copy.
let blockStateTypes;

export const BlockStates = Object.freeze({
    get(stateName) {
        return BlockStates.getAll().find(state => state.id === stateName);
    },
    getAll() {
        if (blockStateTypes === undefined) blockStateTypes = JSON.parse(bridge.blocks.stateTypes());
        return blockStateTypes.slice();
    },
});

// A kind of item is the same for as long as the game runs, so what the game says about one is read
// once and handed back frozen, which also keeps one ItemType object per id for a pack to compare.
const itemTypeCache = new Map();

/** An id with no namespace names a vanilla thing, wherever Bedrock takes one. */
function namespaced(name) {
    const id = typeof name === "string" ? name : name?.id;
    return id !== undefined && id !== null && !id.includes(":") ? "minecraft:" + id : id;
}

/** What the game knows about a kind of item, with no fields at all for an id nothing registers. */
function itemInfo(typeId) {
    let info = itemTypeCache.get(typeId);
    if (info === undefined) {
        info = JSON.parse(bridge.items.describe(typeId ?? ""));
        info.type = info.id === undefined ? undefined
            : Object.freeze({ id: info.id, localizationKey: info.localizationKey });
        Object.freeze(info);
        itemTypeCache.set(typeId, info);
    }
    return info;
}

export const ItemTypes = Object.freeze({
    get(itemId) {
        return itemInfo(namespaced(itemId)).type;
    },
    getAll() {
        return list(bridge.items.all()).map(id => itemInfo(id).type);
    },
});

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
const itemComponents = Object.freeze([
    ItemComponentTypes.Durability, ItemComponentTypes.Food, ItemComponentTypes.Cooldown,
    ItemComponentTypes.Enchantable, ItemComponentTypes.Dyeable, ItemComponentTypes.Potion,
    ItemComponentTypes.Compostable,
]);

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
        this.typeId = namespaced(itemType);
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
        return copy;
    }

    /** States belong to blocks, so a stack of a block item answers with the ones it would place. */
    matches(itemName, states) {
        if (namespaced(itemName) !== this.typeId) return false;

        const wanted = Object.entries(states ?? {});
        if (wanted.length === 0) return true;

        const resolved = bridge.blocks.resolve(this.typeId, "{}");
        if (resolved === null) return false;

        const placed = JSON.parse(resolved);
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
        const info = itemInfo(this.typeId);
        switch (name) {
            case ItemComponentTypes.Durability:
                return this.#durability(name);
            case ItemComponentTypes.Food:
                return info.food === undefined ? undefined : { typeId: name, ...info.food };
            case ItemComponentTypes.Compostable:
                return info.compostable === undefined ? undefined : { typeId: name, ...info.compostable };
            case ItemComponentTypes.Cooldown:
                return this.#cooldown(name, info.cooldown);
            case ItemComponentTypes.Enchantable:
                return this.#enchantable(name, info.enchantable);
            case ItemComponentTypes.Dyeable:
                return this.#dyeable(name, info.dyeable);
            case ItemComponentTypes.Potion:
                return this.#potion(name, info.potion);
            default:
                return unsupported("ItemStack.getComponent(" + name + ")");
        }
    }

    #durability(name) {
        const max = this.maxDurability ?? bridge.itemMaxDurability(this.typeId);
        if (max <= 0) return undefined;

        const stack = this;
        return {
            typeId: name,
            maxDurability: max,
            get damage() {
                return stack.damage ?? 0;
            },
            set damage(value) {
                stack.damage = value;
            },
            get unbreakable() {
                return stack.unbreakable ?? false;
            },
            set unbreakable(value) {
                stack.unbreakable = value;
                if (value) unsupported("ItemDurabilityComponent.unbreakable");
            },
            getDamageChance: () => 100,
            getDamageChanceRange: () => ({ min: 100, max: 100 }),
        };
    }

    /** A cooldown is read from a player, because it is the player and not the item that holds one. */
    #cooldown(name, cooldown) {
        if (cooldown === undefined) return undefined;

        const typeId = this.typeId;
        return {
            typeId: name,
            cooldownCategory: cooldown.cooldownCategory,
            cooldownTicks: cooldown.cooldownTicks,
            isCooldownCategory: category => category === cooldown.cooldownCategory,
            getCooldownTicksRemaining: player => bridge.items.cooldownRemaining(player.id, typeId),
            startCooldown: player => {
                bridge.items.startCooldown(player.id, typeId, cooldown.cooldownTicks);
            },
        };
    }

    /**
     * An enchantment a pack adds is kept on the stack and answered back. Nothing carries it onto the
     * item the world holds, so adding one is reported.
     */
    #enchantable(name, enchantable) {
        if (enchantable === undefined) return undefined;

        const stack = this;
        const held = () => stack.enchantments ?? [];
        const add = enchantment => {
            const added = enchantmentOf(enchantment);
            stack.enchantments = [...held().filter(on => on.type.id !== added.type.id), added];
            unsupported("ItemEnchantableComponent.addEnchantment");
        };
        return {
            typeId: name,
            slots: [...enchantable.slots],
            getEnchantments: () => held().slice(),
            getEnchantment: type => held().find(on => on.type.id === namespaced(type)),
            hasEnchantment: type => held().some(on => on.type.id === namespaced(type)),
            canAddEnchantment: enchantment => {
                const asked = enchantmentOf(enchantment);
                return !held().some(on => on.type.id === asked.type.id);
            },
            addEnchantment: add,
            addEnchantments: enchantments => (enchantments ?? []).forEach(add),
            removeEnchantment: type => {
                stack.enchantments = held().filter(on => on.type.id !== namespaced(type));
            },
            removeAllEnchantments: () => {
                stack.enchantments = [];
            },
        };
    }

    /** A colour a pack dyes the stack is answered back, and nothing carries it into the world. */
    #dyeable(name, dyeable) {
        if (dyeable === undefined) return undefined;

        const stack = this;
        return {
            typeId: name,
            defaultColor: { ...dyeable.defaultColor },
            get color() {
                return stack.dyeColor ?? { ...dyeable.defaultColor };
            },
            set color(value) {
                stack.dyeColor = value;
                unsupported("ItemDyeableComponent.color");
            },
        };
    }

    /** Which effect the potion carries is written on the stack in the world, not on its type. */
    #potion(name, potion) {
        if (potion === undefined) return undefined;

        return {
            typeId: name,
            potionDeliveryType: Object.freeze({ id: potion.potionDeliveryType }),
            get potionEffectType() {
                return unsupported("ItemPotionComponent.potionEffectType");
            },
        };
    }

    getComponents() {
        return itemComponents
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
        const build = BLOCK_COMPONENTS[name];
        return build === undefined ? unsupported("Block.getComponent(" + name + ")") : build(this);
    }

    getComponents() {
        return Object.values(BLOCK_COMPONENTS)
            .map(build => build(this))
            .filter(component => component !== undefined);
    }

    hasComponent(component) {
        const name = normaliseComponent(component);
        const build = BLOCK_COMPONENTS[name];
        if (build === undefined) {
            unsupported("Block.hasComponent(" + name + ")");
            return false;
        }
        return build(this) !== undefined;
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

function movableComponent(block) {
    const movement = blockComponent(bridge.blocks.movement(...blockAt(block)));
    return { typeId: "minecraft:movable", block, ...movement };
}

/**
 * The sign a pack writes to, reading the block again for every answer.
 *
 * A pack holds a component and expects it to follow the sign, so nothing here is a copy taken when
 * the component was handed out. A side is the front unless the back was asked for by name.
 */
function signComponent(block) {
    const read = () => blockComponent(bridge.blocks.sign(...blockAt(block)));
    if (read() === undefined) return undefined;

    const isFront = side => side !== SignSide.Back;
    const text = side => read()?.[isFront(side) ? "front" : "back"];
    return {
        typeId: "minecraft:sign",
        block,
        get isWaxed() {
            return read()?.isWaxed === true;
        },
        getText: side => text(side)?.text,
        getRawText: side => ({ rawtext: [{ text: text(side)?.text ?? "" }] }),
        getTextDyeColor: side => text(side)?.color,
        setText: (message, side) =>
            bridge.blocks.setSignText(...blockAt(block), isFront(side), rawText(message)),
        setTextDyeColor: (color, side) =>
            bridge.blocks.setSignDye(...blockAt(block), isFront(side), color ?? null),
        setWaxed: waxed => bridge.blocks.setSignWaxed(...blockAt(block), waxed === true),
    };
}

/** The container a chest, a hopper or a furnace holds, read again on every call like the rest. */
function inventoryComponent(block) {
    const read = () => containerAt(...blockAt(block));
    if (read() === undefined) return undefined;

    return {
        typeId: "minecraft:inventory",
        block,
        get container() {
            return read();
        },
    };
}

function pistonComponent(block) {
    const read = () => blockComponent(bridge.blocks.piston(...blockAt(block)));
    if (read() === undefined) return undefined;

    const attached = () => (read()?.attached ?? []).map(vector);
    return {
        typeId: "minecraft:piston",
        block,
        get isMoving() {
            return read()?.isMoving === true;
        },
        get state() {
            return read()?.state;
        },
        getAttachedBlocksLocations: attached,
        getAttachedBlocks: () => attached().map(location => block.dimension.getBlock(location))
            .filter(found => found !== undefined),
    };
}

/** Java gives a block one map colour, so the tinted colour is that same colour untouched. */
function mapColorComponent(block) {
    const colour = blockComponent(bridge.blocks.mapColour(...blockAt(block)));
    if (colour === undefined) return undefined;

    return {
        typeId: "minecraft:map_color",
        block,
        color: colour,
        tintedColor: colour,
        tintMethod: TintMethod.None,
    };
}

function redstoneProducerComponent(block) {
    const read = () => blockComponent(bridge.blocks.redstoneProducer(...blockAt(block)));
    if (read() === undefined) return undefined;

    return {
        typeId: "minecraft:redstone_producer",
        block,
        get power() {
            return read()?.power ?? 0;
        },
        getConnectedFaces: () => read()?.connectedFaces ?? [],
        getStronglyPoweredFace: () => read()?.stronglyPoweredFace ?? undefined,
    };
}

// Every component this mod answers for a block, by the name a pack asks for it under. A builder
// hands back undefined for a block that does not carry its component, which is what Bedrock does.
const BLOCK_COMPONENTS = {
    "minecraft:inventory": inventoryComponent,
    "minecraft:movable": movableComponent,
    "minecraft:sign": signComponent,
    "minecraft:piston": pistonComponent,
    "minecraft:map_color": mapColorComponent,
    "minecraft:redstone_producer": redstoneProducerComponent,
};

// Component classes. A pack imports these to name a type, and the objects `getComponent` hands back
// are plain objects, so these exist to satisfy the import and any `instanceof` written against them.
export class EntityComponent {
}

export class EntityHealthComponent extends EntityComponent {
}

export class EntityEquippableComponent extends EntityComponent {
}

export class EntityInventoryComponent extends EntityComponent {
    static componentId = "minecraft:inventory";
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

export class Effect {
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

export const EffectTypes = Object.freeze({
    get(identifier) {
        return bridge.registries.hasEffectType(identifier)
            ? new EffectType(namespaced(identifier)) : undefined;
    },

    getAll() {
        return list(bridge.registries.effectTypes()).map(id => new EffectType(id));
    },
});

export class EntityType {
    constructor(fields) {
        this.id = fields.id;
        this.localizationKey = fields.localizationKey;
    }
}

export const EntityTypes = Object.freeze({
    get(identifier) {
        const found = bridge.registries.entityType(identifier);
        return found === null ? undefined : new EntityType(JSON.parse(found));
    },

    getAll() {
        return JSON.parse(bridge.registries.entityTypes()).map(fields => new EntityType(fields));
    },
});

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

export const EnchantmentTypes = Object.freeze({
    get(enchantmentId) {
        return bridge.registries.enchantment(enchantmentId) === null
            ? undefined : new EnchantmentType(enchantmentId);
    },

    getAll() {
        return JSON.parse(bridge.registries.enchantments()).map(fields => new EnchantmentType(fields.id));
    },
});

/** An instant effect has no duration, so `durationTicks` is absent on one instead of zero. */
export class PotionEffectType {
    constructor(fields) {
        this.id = fields.id;
        this.durationTicks = fields.durationTicks;
    }
}

export const Potions = Object.freeze({
    getEffectType(potionEffectId) {
        const found = bridge.registries.potionEffectType(potionEffectId);
        return found === null ? undefined : new PotionEffectType(JSON.parse(found));
    },

    getAllEffectTypes() {
        return JSON.parse(bridge.registries.potionEffectTypes()).map(fields => new PotionEffectType(fields));
    },

    // Java has no registry of potion delivery shapes: a drunk, splash or lingering potion is three
    // items. That leaves nothing to answer a delivery type with, and nothing for `resolve` to build.
    getDeliveryType() {
        return unsupported("Potions.getDeliveryType");
    },

    getAllDeliveryTypes() {
        return unsupported("Potions.getAllDeliveryTypes");
    },

    resolve() {
        return unsupported("Potions.resolve");
    },
});

export class BiomeType {
    constructor(id) {
        this.id = id;
    }
}

export const BiomeTypes = Object.freeze({
    get(typeName) {
        return bridge.registries.hasBiomeType(typeName) ? new BiomeType(namespaced(typeName)) : undefined;
    },

    getAll() {
        return list(bridge.registries.biomeTypes()).map(id => new BiomeType(id));
    },
});

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

    resetProperty(name) {
        return bridge.entityMembers.resetProperty(this.id, name);
    }

    getDynamicProperty(name) {
        return bridge.entityDynamicProperty(this.id, name);
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
        const names = list(bridge.entityComponents(this.id));
        if (hasComponent(this, "minecraft:inventory")) names.push("minecraft:inventory");
        return names.map(name => componentFor(this, name));
    }

    getEffects() {
        return JSON.parse(bridge.entityEffects(this.id)).map(effect => ({ ...effect, isValid: true }));
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

    // What follows describes the Bedrock client itself: the camera it draws through, the device it
    // runs on, the buttons held on it and the parts of its heads-up display. Java Edition's server
    // is told none of it, so each of these reports itself rather than answering something made up.
    //
    // The three that Bedrock hands back as an object are answered with one whose every member
    // reports, because a pack reaching through `player.camera.setCamera(...)` for a feature Java has
    // none of should cost that call and not the tick a TypeError would end.

    get camera() {
        return {
            isValid: true,
            attachToEntity() {
                unsupported("Camera.attachToEntity");
            },
            clear() {
                unsupported("Camera.clear");
            },
            fade() {
                unsupported("Camera.fade");
            },
            playAnimation() {
                unsupported("Camera.playAnimation");
            },
            setCamera() {
                unsupported("Camera.setCamera");
            },
            setDefaultCamera() {
                unsupported("Camera.setDefaultCamera");
            },
            setFov() {
                unsupported("Camera.setFov");
            },
        };
    }

    get clientSystemInfo() {
        return {
            get maxRenderDistance() {
                return unsupported("ClientSystemInfo.maxRenderDistance");
            },
            get platformType() {
                return unsupported("ClientSystemInfo.platformType");
            },
        };
    }

    get graphicsMode() {
        return unsupported("Player.graphicsMode");
    }

    get inputInfo() {
        return {
            get lastInputModeUsed() {
                return unsupported("InputInfo.lastInputModeUsed");
            },
            get touchOnlyAffectsHotbar() {
                return unsupported("InputInfo.touchOnlyAffectsHotbar");
            },
            getButtonState() {
                return unsupported("InputInfo.getButtonState");
            },
            getMovementVector() {
                unsupported("InputInfo.getMovementVector");
                return { x: 0, y: 0 };
            },
        };
    }

    get inputPermissions() {
        return {
            isPermissionCategoryEnabled() {
                unsupported("PlayerInputPermissions.isPermissionCategoryEnabled");
                return true;
            },
            setPermissionCategory() {
                unsupported("PlayerInputPermissions.setPermissionCategory");
            },
        };
    }

    get isEmoting() {
        return unsupported("Player.isEmoting");
    }

    getControlScheme() {
        return unsupported("Player.getControlScheme");
    }

    setControlScheme() {
        unsupported("Player.setControlScheme");
    }

    // An override shows one entity differently to one player, which needs a per-player entity state
    // Java has no packet for.

    setPropertyOverrideForEntity() {
        unsupported("Player.setPropertyOverrideForEntity");
    }

    removePropertyOverrideForEntity() {
        unsupported("Player.removePropertyOverrideForEntity");
    }

    clearPropertyOverridesForEntity() {
        unsupported("Player.clearPropertyOverridesForEntity");
    }

    get onScreenDisplay() {
        const id = this.id;
        return {
            isValid: true,
            setActionBar(text) {
                bridge.players.setActionBar(id, JSON.stringify(text));
            },
            setTitle(text, options) {
                bridge.players.setTitle(id, JSON.stringify(text), JSON.stringify(options ?? null));
            },
            updateSubtitle(text) {
                bridge.players.updateSubtitle(id, JSON.stringify(text));
            },
            getHiddenHudElements() {
                unsupported("ScreenDisplay.getHiddenHudElements");
                return [];
            },
            hideAllExcept() {
                unsupported("ScreenDisplay.hideAllExcept");
            },
            isForcedHidden() {
                unsupported("ScreenDisplay.isForcedHidden");
                return false;
            },
            resetHudElementsVisibility() {
                unsupported("ScreenDisplay.resetHudElementsVisibility");
            },
            setHudVisibility() {
                unsupported("ScreenDisplay.setHudVisibility");
            },
        };
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
        bridge.playSoundTo(this.id, soundId, options?.volume ?? 1, options?.pitch ?? 1);
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

function normaliseComponent(component) {
    const name = typeof component === "string" ? component : String(component);
    return name.includes(":") ? name : "minecraft:" + name;
}

/**
 * Whether the entity carries a component.
 *
 * The inventory is answered by whether a container could be found for the entity, because a player
 * and a chest minecart hold their items in objects the entity component list knows nothing about.
 */
function hasComponent(entity, name) {
    return name === "minecraft:inventory"
        ? bridge.containers.ofEntity(entity.id) !== null
        : bridge.entityHasComponent(entity.id, name);
}

/** Only the components real packs read are modelled; the rest report themselves as unsupported. */
function componentFor(entity, name) {
    switch (name) {
        case "minecraft:inventory":
            return {
                typeId: name, entity,
                get containerType() {
                    // The pack says what kind of container its entity carries; Java has one kind of
                    // inventory, so the name comes back from the pack rather than from the game.
                    const declared = bridge.entityMembers.packComponent(entity.id, "minecraft:inventory");
                    return declared === null
                        ? "inventory" : (JSON.parse(declared).container_type ?? "inventory");
                },
                additionalSlotsPerStrength: 0,
                canBeSiphonedFrom: false,
                private: false,
                restrictToOwner: false,
                get container() {
                    const handle = bridge.containers.ofEntity(entity.id);
                    return handle === null ? undefined : new Container(handle);
                },
                get inventorySize() {
                    return this.container?.size ?? 0;
                },
            };
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
    stack.damage = handle.damage ?? 0;
    stack.maxDurability = handle.maxDurability ?? 0;
    stack.lore = handle.lore ?? [];
    stack.properties = handle.properties ?? {};
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
    });
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
        const ready = this.shape ? this.shape(shapeFields(event)) : shapeFields(event);
        for (const listener of this.#listeners.slice()) {
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
    brokenBlockPermutation: permutationFrom,
    dimension: dimensionOf,
    fromDimension: dimensionOf,
    itemStack: itemFrom,
    itemStackAfterBreak: itemFrom,
    itemStackBeforeBreak: itemFrom,
    toDimension: dimensionOf,
};

function shapeFields(event) {
    for (const name of Object.keys(FIELD_SHAPES)) {
        if (event[name] !== undefined && event[name] !== null) event[name] = FIELD_SHAPES[name](event[name]);
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
        // Nothing tells the mod which component groups an event changed, so a pack that asks is
        // told none rather than being handed a method that is not there.
        event.getModifiers = () => [];
        return event;
    },

    entityItemDrop(event) {
        // Bedrock hands over a list, and one throw drops one stack.
        event.items = [event.item];
        delete event.item;
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
function eventGroup(raised) {
    return new Proxy({}, {
        get(target, name) {
            if (typeof name !== "string") return undefined;
            if (target[name] === undefined) {
                if (!raised.includes(name)) bridge.unsupported(name + " (event)");
                target[name] = new EventSignal();
                target[name].shape = EVENT_SHAPES[name];
            }
            return target[name];
        },
    });
}

/** Every event name this mod actually raises, by the group it belongs to. */
const RAISED = {
    worldAfter: [
        "dataDrivenEntityTrigger", "entityDie", "entityHitEntity", "entityHurt", "entityItemDrop",
        "entityLoad", "entityRemove", "entitySpawn", "itemCompleteUse", "itemReleaseUse",
        "itemStartUse", "itemStartUseOn", "itemStopUse", "itemUse", "playerBreakBlock",
        "playerDimensionChange", "playerGameModeChange", "playerInteractWithBlock",
        "playerInteractWithEntity", "playerInventoryItemChange", "playerJoin", "playerLeave",
        "playerPlaceBlock", "playerSpawn", "worldLoad", "effectAdd", "entityItemPickup",
        "buttonPush", "projectileHitEntity", "projectileHitBlock",
    ],
    worldBefore: [
        "entityHurt", "entityRemove", "itemUse", "playerBreakBlock", "playerInteractWithBlock",
        "playerInteractWithEntity", "playerLeave", "effectAdd", "entityItemPickup",
    ],
    systemAfter: ["scriptEventReceive"],
    systemBefore: ["startup", "shutdown"],
};

export class World {
    #afterEvents = eventGroup(RAISED.worldAfter);
    #beforeEvents = eventGroup(RAISED.worldBefore);

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

    get seed() {
        return bridge.worlds.seed();
    }

    get tickingAreaManager() {
        return unsupported("World.tickingAreaManager");
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
        return options === undefined
            ? this.getAllPlayers()
            : list(bridge.matchingPlayers(JSON.stringify(options))).map(entityFor);
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
        return unsupported("World.getLootTableManager");
    }

    getDynamicProperty(name) {
        return bridge.worldDynamicProperty(name);
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
        return {
            get(id) {
                const size = bridge.structureSize(id);
                return size[0] === 0 && size[1] === 0 && size[2] === 0
                    ? undefined
                    : { id, size: { x: size[0], y: size[1], z: size[2] } };
            },

            place(structure, dimension, location, options) {
                const id = typeof structure === "string" ? structure : structure?.id;
                return bridge.placeStructure(dimension.id, id,
                    Math.floor(location.x), Math.floor(location.y), Math.floor(location.z),
                    options?.rotation ?? "None", options?.mirror ?? "None");
            },

            createFromWorld(id, dimension, from, to, options) {
                const low = {
                    x: Math.min(Math.floor(from.x), Math.floor(to.x)),
                    y: Math.min(Math.floor(from.y), Math.floor(to.y)),
                    z: Math.min(Math.floor(from.z), Math.floor(to.z)),
                };
                const size = {
                    x: Math.abs(Math.floor(to.x) - Math.floor(from.x)) + 1,
                    y: Math.abs(Math.floor(to.y) - Math.floor(from.y)) + 1,
                    z: Math.abs(Math.floor(to.z) - Math.floor(from.z)) + 1,
                };
                bridge.saveStructure(dimension.id, id, low.x, low.y, low.z, size.x, size.y, size.z);
                return { id, size };
            },

            delete(structure) {
                return bridge.deleteStructure(typeof structure === "string" ? structure : structure?.id);
            },
        };
    }
}

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
        return this.getParticipants().map(participant => ({
            participant, score: this.getScore(participant) ?? 0,
        }));
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

const scoreboard = {
    getObjective(id) {
        return bridge.scoreboardHasObjective(id) ? new ScoreboardObjective(id) : undefined;
    },

    getObjectives() {
        return list(bridge.scoreboardObjectives()).map(id => new ScoreboardObjective(id));
    },

    addObjective(id, displayName) {
        bridge.addObjective(id, displayName ?? id);
        return new ScoreboardObjective(id);
    },

    removeObjective(objective) {
        bridge.removeObjective(typeof objective === "string" ? objective : objective.id);
        return true;
    },

    getParticipants() {
        return list(bridge.scoreboardParticipants()).map(identityFor);
    },

    getObjectiveAtDisplaySlot() {
        return unsupported("Scoreboard.getObjectiveAtDisplaySlot");
    },

    setObjectiveAtDisplaySlot(slot, options) {
        const objective = options?.objective;
        bridge.setDisplaySlot(slot, typeof objective === "string" ? objective : objective?.id);
        return objective === undefined ? undefined : scoreboard.getObjective(
            typeof objective === "string" ? objective : objective.id);
    },

    clearObjectiveAtDisplaySlot(slot) {
        bridge.setDisplaySlot(slot, "");
        return undefined;
    },
};

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
    #afterEvents = eventGroup(RAISED.systemAfter);
    #beforeEvents = eventGroup(RAISED.systemBefore);

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
export class BlockComponent {}
export class BlockComponentBlockBreakEvent {}
export class BlockComponentEntityEvent {}
export class BlockComponentEntityFallOnEvent {}
export class BlockComponentOnPlaceEvent {}
export class BlockComponentPlayerBreakEvent {}
export class BlockComponentPlayerInteractEvent {}
export class BlockComponentPlayerPlaceBeforeEvent {}
export class BlockComponentRandomTickEvent {}
export class BlockComponentRedstoneUpdateEvent {}
export class BlockComponentRegistry {}
export class BlockComponentStepOffEvent {}
export class BlockComponentStepOnEvent {}
export class BlockComponentTickEvent {}
export class BlockCustomComponentInstance {}
export class BlockEvent {}
export class BlockExplodeAfterEvent {}
export class BlockExplodeAfterEventSignal {}
export class BlockFluidContainerComponent { static componentId = "minecraft:fluid_container"; }
export class BlockInventoryComponent { static componentId = "minecraft:inventory"; }
export class BlockMapColorComponent { static componentId = "minecraft:map_color"; }
export class BlockMovableComponent { static componentId = "minecraft:movable"; }
export class BlockPistonComponent { static componentId = "minecraft:piston"; }
export class BlockPrecipitationInteractionsComponent { static componentId = "minecraft:precipitation_interactions"; }
export class BlockRecordPlayerComponent { static componentId = "minecraft:record_player"; }
export class BlockRedstoneProducerComponent { static componentId = "minecraft:redstone_producer"; }
export class BlockSignComponent { static componentId = "minecraft:sign"; }
export class BlockStateType {}
export class BlockType {}
export class ButtonPushAfterEvent {}
export class ButtonPushAfterEventSignal {}
export class Camera {}
export class CatmullRomSpline {}
export class ClientSystemInfo {}
export class CommandResult {}
export class Component {}
export class CustomCommandOrigin {}
export class CustomCommandRegistry {}
export class CustomComponentParameters {}
export class DamagedByEntityCondition {}
export class DataDrivenEntityTriggerAfterEvent {}
export class DataDrivenEntityTriggerAfterEventSignal {}
export class DimensionType {}
export class EffectAddAfterEvent {}
export class EffectAddAfterEventSignal {}
export class EffectAddBeforeEvent {}
export class EffectAddBeforeEventSignal {}
export class EmptyLootItem {}
export class EnchantInfo {}
export class EnchantRandomEquipmentFunction {}
export class EnchantRandomlyFunction {}
export class EnchantWithLevelsFunction {}
export class EntityAddRiderComponent {}
export class EntityAgeableComponent {}
export class EntityAttributeComponent {}
export class EntityBaseMovementComponent {}
export class EntityBreathableComponent {}
export class EntityCanClimbComponent {}
export class EntityCanFlyComponent {}
export class EntityCanPowerJumpComponent {}
export class EntityColor2Component {}
export class EntityColorComponent {}
export class EntityDefinitionFeedItem {}
export class EntityDieAfterEvent {}
export class EntityDieAfterEventSignal {}
export class EntityExhaustionComponent {}
export class EntityFireImmuneComponent {}
export class EntityFloatsInLiquidComponent {}
export class EntityFlyingSpeedComponent {}
export class EntityFrictionModifierComponent {}
export class EntityHasMarkVariantCondition {}
export class EntityHasVariantCondition {}
export class EntityHealAfterEvent {}
export class EntityHealAfterEventSignal {}
export class EntityHealBeforeEvent {}
export class EntityHealBeforeEventSignal {}
export class EntityHealSource {}
export class EntityHealableComponent {}
export class EntityHealthChangedAfterEvent {}
export class EntityHealthChangedAfterEventSignal {}
export class EntityHitBlockAfterEvent {}
export class EntityHitBlockAfterEventSignal {}
export class EntityHitEntityAfterEvent {}
export class EntityHitEntityAfterEventSignal {}
export class EntityHungerComponent {}
export class EntityHurtAfterEvent {}
export class EntityHurtAfterEventSignal {}
export class EntityHurtBeforeEvent {}
export class EntityHurtBeforeEventSignal {}
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
export class EntityItemDropAfterEvent {}
export class EntityItemDropAfterEventSignal {}
export class EntityItemPickupAfterEvent {}
export class EntityItemPickupAfterEventSignal {}
export class EntityItemPickupBeforeEvent {}
export class EntityItemPickupBeforeEventSignal {}
export class EntityKilledCondition {}
export class EntityLavaMovementComponent {}
export class EntityLoadAfterEvent {}
export class EntityLoadAfterEventSignal {}
export class EntityMovementAmphibiousComponent {}
export class EntityMovementBasicComponent {}
export class EntityMovementComponent {}
export class EntityMovementFlyComponent {}
export class EntityMovementGenericComponent {}
export class EntityMovementGlideComponent {}
export class EntityMovementHoverComponent {}
export class EntityMovementJumpComponent {}
export class EntityMovementSkipComponent {}
export class EntityMovementSwayComponent {}
export class EntityNavigationClimbComponent {}
export class EntityNavigationComponent {}
export class EntityNavigationFloatComponent {}
export class EntityNavigationFlyComponent {}
export class EntityNavigationGenericComponent {}
export class EntityNavigationHoverComponent {}
export class EntityNavigationWalkComponent {}
export class EntityOnFireComponent {}
export class EntityPushThroughComponent {}
export class EntityRemoveAfterEvent {}
export class EntityRemoveAfterEventSignal {}
export class EntityRemoveBeforeEvent {}
export class EntityRemoveBeforeEventSignal {}
export class EntitySaturationComponent {}
export class EntityScaleComponent {}
export class EntitySpawnAfterEvent {}
export class EntitySpawnAfterEventSignal {}
export class EntityStrengthComponent {}
export class EntityTameMountComponent {}
export class EntityTameableComponent {}
export class EntityUnderwaterMovementComponent {}
export class EntityWantsJockeyComponent {}
export class ExplorationMapFunction {}
export class ExplosionAfterEvent {}
export class ExplosionAfterEventSignal {}
export class ExplosionBeforeEvent {}
export class ExplosionBeforeEventSignal {}
export class ExplosionDecayFunction {}
export class FeedItem {}
export class FeedItemEffect {}
export class FillContainerFunction {}
export class FluidContainer {}
export class GameRuleChangeAfterEvent {}
export class GameRuleChangeAfterEventSignal {}
export class GameRules {}
export class InputInfo {}
export class IsBabyCondition {}
export class ItemBookComponent {}
export class ItemCompleteUseAfterEvent {}
export class ItemCompleteUseAfterEventSignal {}
export class ItemCompleteUseEvent {}
export class ItemComponentBeforeDurabilityDamageEvent {}
export class ItemComponentCompleteUseEvent {}
export class ItemComponentConsumeEvent {}
export class ItemComponentHitEntityEvent {}
export class ItemComponentMineBlockEvent {}
export class ItemComponentRegistry {}
export class ItemComponentUseEvent {}
export class ItemComponentUseOnEvent {}
export class ItemCompostableComponent {}
export class ItemCustomComponentInstance {}
export class ItemDyeableComponent {}
export class ItemInventoryComponent {}
export class ItemPotionComponent {}
export class ItemReleaseUseAfterEvent {}
export class ItemReleaseUseAfterEventSignal {}
export class ItemStartUseAfterEvent {}
export class ItemStartUseAfterEventSignal {}
export class ItemStartUseOnAfterEvent {}
export class ItemStartUseOnAfterEventSignal {}
export class ItemStopUseAfterEvent {}
export class ItemStopUseAfterEventSignal {}
export class ItemStopUseOnAfterEvent {}
export class ItemStopUseOnAfterEventSignal {}
export class ItemType {}
export class ItemUseAfterEvent {}
export class ItemUseAfterEventSignal {}
export class ItemUseBeforeEvent {}
export class ItemUseBeforeEventSignal {}
export class ItemUseOnEvent {}
export class KilledByEntityCondition {}
export class KilledByPlayerCondition {}
export class KilledByPlayerOrPetsCondition {}
export class LeverActionAfterEvent {}
export class LeverActionAfterEventSignal {}
export class LinearSpline {}
export class LootItem {}
export class LootItemCondition {}
export class LootItemFunction {}
export class LootPool {}
export class LootPoolEntry {}
export class LootPoolTiers {}
export class LootTable {}
export class LootTableEntry {}
export class LootTableManager {}
export class LootTableReference {}
export class LootingEnchantFunction {}
export class MatchToolCondition {}
export class MolangVariableMap {}
export class PassengerOfEntityCondition {}
export class PistonActivateAfterEvent {}
export class PistonActivateAfterEventSignal {}
export class PlayerBreakBlockAfterEvent {}
export class PlayerBreakBlockAfterEventSignal {}
export class PlayerBreakBlockBeforeEvent {}
export class PlayerBreakBlockBeforeEventSignal {}
export class PlayerButtonInputAfterEvent {}
export class PlayerButtonInputAfterEventSignal {}
export class PlayerCursorInventoryComponent {}
export class PlayerDimensionChangeAfterEvent {}
export class PlayerDimensionChangeAfterEventSignal {}
export class PlayerEmoteAfterEvent {}
export class PlayerEmoteAfterEventSignal {}
export class PlayerGameModeChangeAfterEvent {}
export class PlayerGameModeChangeAfterEventSignal {}
export class PlayerGameModeChangeBeforeEvent {}
export class PlayerGameModeChangeBeforeEventSignal {}
export class PlayerHotbarSelectedSlotChangeAfterEvent {}
export class PlayerHotbarSelectedSlotChangeAfterEventSignal {}
export class PlayerInputModeChangeAfterEvent {}
export class PlayerInputModeChangeAfterEventSignal {}
export class PlayerInputPermissionCategoryChangeAfterEvent {}
export class PlayerInputPermissionCategoryChangeAfterEventSignal {}
export class PlayerInputPermissions {}
export class PlayerInteractWithBlockAfterEvent {}
export class PlayerInteractWithBlockAfterEventSignal {}
export class PlayerInteractWithBlockBeforeEvent {}
export class PlayerInteractWithBlockBeforeEventSignal {}
export class PlayerInteractWithEntityAfterEvent {}
export class PlayerInteractWithEntityAfterEventSignal {}
export class PlayerInteractWithEntityBeforeEvent {}
export class PlayerInteractWithEntityBeforeEventSignal {}
export class PlayerInventoryItemChangeAfterEvent {}
export class PlayerInventoryItemChangeAfterEventSignal {}
export class PlayerJoinAfterEvent {}
export class PlayerJoinAfterEventSignal {}
export class PlayerLeaveAfterEvent {}
export class PlayerLeaveAfterEventSignal {}
export class PlayerLeaveBeforeEvent {}
export class PlayerLeaveBeforeEventSignal {}
export class PlayerPlaceBlockAfterEvent {}
export class PlayerPlaceBlockAfterEventSignal {}
export class PlayerSpawnAfterEvent {}
export class PlayerSpawnAfterEventSignal {}
export class PlayerSwingStartAfterEvent {}
export class PlayerSwingStartAfterEventSignal {}
export class PotionDeliveryType {}
export class PressurePlatePopAfterEvent {}
export class PressurePlatePopAfterEventSignal {}
export class PressurePlatePushAfterEvent {}
export class PressurePlatePushAfterEventSignal {}
export class ProjectileHitBlockAfterEvent {}
export class ProjectileHitBlockAfterEventSignal {}
export class ProjectileHitEntityAfterEvent {}
export class ProjectileHitEntityAfterEventSignal {}
export class RandomAuxValueFunction {}
export class RandomBlockStateFunction {}
export class RandomChanceCondition {}
export class RandomChanceWithLootingCondition {}
export class RandomDifficultyChanceCondition {}
export class RandomDyeFunction {}
export class RandomRegionalDifficultyChanceCondition {}
export class Scoreboard {}
export class ScoreboardScoreInfo {}
export class ScreenDisplay {}
export class ScriptEventCommandMessageAfterEvent {}
export class ScriptEventCommandMessageAfterEventSignal {}
export class Seat {}
export class SetArmorTrimFunction {}
export class SetBannerDetailsFunction {}
export class SetBookContentsFunction {}
export class SetDataFromColorIndexFunction {}
export class SetItemCountFunction {}
export class SetItemDamageFunction {}
export class SetItemDataFunction {}
export class SetItemLoreFunction {}
export class SetItemNameFunction {}
export class SetOminousBottleFunction {}
export class SetPotionFunction {}
export class SetSpawnEggFunction {}
export class SetStewEffectFunction {}
export class ShutdownBeforeEventSignal {}
export class ShutdownEvent {}
export class SmeltItemFunction {}
export class SpecificEnchantFunction {}
export class StartupBeforeEventSignal {}
export class StartupEvent {}
export class Structure {}
export class StructureManager {}
export class System {}
export class SystemAfterEvents {}
export class SystemBeforeEvents {}
export class SystemInfo {}
export class TargetBlockHitAfterEvent {}
export class TargetBlockHitAfterEventSignal {}
export class TickingAreaManager {}
export class Trigger {}
export class TripWireTripAfterEvent {}
export class TripWireTripAfterEventSignal {}
export class WeatherChangeAfterEvent {}
export class WeatherChangeAfterEventSignal {}
export class WeatherChangeBeforeEvent {}
export class WeatherChangeBeforeEventSignal {}
export class WorldAfterEvents {}
export class WorldBeforeEvents {}
export class WorldLoadAfterEvent {}
export class WorldLoadAfterEventSignal {}

/** A failure a pack catches by name, which is why each of these is a real error. */
export class BlockCustomComponentAlreadyRegisteredError extends Error {}
export class BlockCustomComponentReloadNewComponentError extends Error {}
export class BlockCustomComponentReloadNewEventError extends Error {}
export class BlockCustomComponentReloadVersionError extends Error {}
export class BookError extends Error {}
export class BookPageContentError extends Error {}
export class CommandError extends Error {}
export class ContainerRulesError extends Error {}
export class CustomCommandError extends Error {}
export class CustomComponentInvalidRegistryError extends Error {}
export class CustomComponentNameError extends Error {}
export class EnchantmentLevelOutOfBoundsError extends Error {}
export class EnchantmentTypeNotCompatibleError extends Error {}
export class EnchantmentTypeUnknownIdError extends Error {}
export class EntitySpawnError extends Error {}
export class InvalidBlockComponentError extends Error {}
export class InvalidContainerError extends Error {}
export class InvalidContainerSlotError extends Error {}
export class InvalidEntityError extends Error {}
export class InvalidItemStackError extends Error {}
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
export class NamespaceNameError extends Error {}
export class PlaceJigsawError extends Error {}
export class RawMessageError extends Error {}
export class TickingAreaError extends Error {}
export class UnloadedChunksError extends Error {}
