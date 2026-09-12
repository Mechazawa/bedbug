// The @minecraft/server-gametest surface.
//
// Bedrock's own test framework, which a pack imports to ship tests of its content. A pack registers
// its tests while its script loads; `/gametest run`, `runset` and `runall` build an area, hand the
// pack's function a Test object bound to it, and answer whoever typed the command.
//
// A test runs on the server tick. The body may finish in one tick or arrange its ending over many
// through a sequence, a delayed callback or a promise, so a run stays alive until it succeeds,
// fails, or spends its tick budget.

import { Player, world } from "@minecraft/server";

const bridge = globalThis.__mcaddon_bridge__;

// A Java array reaches the guest as a host object with no JavaScript methods on it, so every list
// the bridge returns is copied into a real array before anything here calls `map` or `includes`.
function list(hostArray) {
    return hostArray === null || hostArray === undefined ? [] : Array.from(hostArray);
}

/** Bedrock's own ceiling on how long one test may take before it counts as failed. */
const DEFAULT_MAX_TICKS = 200;

export const GameTestErrorType = Object.freeze({
    Assert: "Assert",
    AssertAtPosition: "AssertAtPosition",
    ExecutionTimeout: "ExecutionTimeout",
    ExhaustedAttempts: "ExhaustedAttempts",
    FailConditionsMet: "FailConditionsMet",
    LevelStateModificationFailed: "LevelStateModificationFailed",
    MethodNotImplemented: "MethodNotImplemented",
    SimulatedPlayerOutOfBounds: "SimulatedPlayerOutOfBounds",
    Unknown: "Unknown",
    Waiting: "Waiting",
});

export const LookDuration = Object.freeze({
    Continuous: "Continuous",
    Instant: "Instant",
    UntilMove: "UntilMove",
});

/** The suites a test declares itself part of, which a pack reads while it registers. */
export class Tags {
    static suiteAll = "suite:all";
    static suiteDebug = "suite:debug";
    static suiteDefault = "suite:default";
    static suiteDisabled = "suite:disabled";
    static suiteNextUpdate = "suite:nextupdate";
}

export class GameTestError extends Error {
    constructor(message, type) {
        super(message);
        this.name = "GameTestError";
        this.type = type ?? GameTestErrorType.Assert;
        this.messageParameters = [];
    }
}

function unsupported(member, answer) {
    bridge.unsupported(member);
    return answer;
}

function fail(message, type) {
    throw new GameTestError(message, type);
}

function blockPosition(location) {
    return { x: Math.floor(location.x), y: Math.floor(location.y), z: Math.floor(location.z) };
}

function typeIdOf(value) {
    return typeof value === "string" ? value : value?.id ?? String(value);
}

function describe(location) {
    return "(" + location.x + ", " + location.y + ", " + location.z + ")";
}

/** Everything one registration holds, which is the test itself and how Bedrock would run it. */
class Registration {
    constructor(className, testName, testFunction, awaited) {
        this.className = className;
        this.testName = testName;
        this.testFunction = testFunction;
        this.awaited = awaited;
        this.structureName = undefined;
        this.structureLocation = undefined;
        this.maxTicks = DEFAULT_MAX_TICKS;
        this.setupTicks = 0;
        this.padding = 0;
        this.batch = "default";
        this.tags = [];
        this.required = true;
        this.rotate = false;
        this.maxAttempts = 1;
        this.requiredSuccesses = 1;
    }

    get name() {
        return this.className + ":" + this.testName;
    }
}

const registered = [];

/**
 * What a pack chains onto a registration to describe how its test is run.
 *
 * Every method answers with the builder, so a chain of any length and order ends where it started.
 * The two that describe running a test more than once are kept and reported: a run here places one
 * structure and calls the function once.
 */
export class RegistrationBuilder {
    #entry;

    constructor(entry) {
        this.#entry = entry;
    }

    batch(batchName) {
        this.#entry.batch = batchName;
        return this;
    }

    maxAttempts(attemptCount) {
        this.#entry.maxAttempts = attemptCount;
        if (attemptCount > 1) unsupported("GameTest.maxAttempts");
        return this;
    }

    maxTicks(tickCount) {
        this.#entry.maxTicks = tickCount;
        return this;
    }

    padding(paddingBlocks) {
        this.#entry.padding = paddingBlocks;
        return this;
    }

    required(isRequired) {
        this.#entry.required = isRequired !== false;
        return this;
    }

    requiredSuccessfulAttempts(attemptCount) {
        this.#entry.requiredSuccesses = attemptCount;
        if (attemptCount > 1) unsupported("GameTest.requiredSuccessfulAttempts");
        return this;
    }

    /** The spelling an older pack chained, which names the same count. */
    requiredSuccesses(attemptCount) {
        return this.requiredSuccessfulAttempts(attemptCount);
    }

    rotateTest(rotate) {
        this.#entry.rotate = rotate === true;
        if (rotate === true) unsupported("GameTest.rotateTest");
        return this;
    }

    setupTicks(tickCount) {
        this.#entry.setupTicks = tickCount;
        return this;
    }

    /** Kept and reported: a run puts its structure beside whoever asked for it. */
    structureLocation(location) {
        this.#entry.structureLocation = location;
        unsupported("GameTest.structureLocation");
        return this;
    }

    structureName(structureName) {
        this.#entry.structureName = structureName;
        return this;
    }

    tag(tag) {
        this.#entry.tags.push(tag);
        return this;
    }
}

export function register(testClassName, testName, testFunction) {
    const entry = new Registration(testClassName, testName, testFunction, false);
    registered.push(entry);
    return new RegistrationBuilder(entry);
}

export function registerAsync(testClassName, testName, testFunction) {
    const entry = new Registration(testClassName, testName, testFunction, true);
    registered.push(entry);
    return new RegistrationBuilder(entry);
}

/**
 * A chain of steps a test advances one tick at a time.
 *
 * A step either passes control on within the same tick, as executing a callback does, or holds the
 * chain where it is until a delay runs out or a check stops throwing. A throw anywhere but in a
 * waiting step ends the chain and fails the test, which is what Bedrock does with one.
 */
export class GameTestSequence {
    #run;
    #steps = [];
    #index = 0;
    #since = 0;
    #stopped = false;

    constructor(run) {
        this.#run = run;
    }

    thenIdle(delayTicks) {
        return this.#add({ kind: "idle", ticks: delayTicks });
    }

    thenExecute(callback) {
        return this.#add({ kind: "execute", callback });
    }

    thenExecuteAfter(delayTicks, callback) {
        this.#add({ kind: "idle", ticks: delayTicks });
        return this.#add({ kind: "execute", callback });
    }

    thenExecuteFor(tickCount, callback) {
        return this.#add({ kind: "repeat", ticks: tickCount, callback });
    }

    thenWait(callback) {
        return this.#add({ kind: "wait", callback });
    }

    thenWaitAfter(delayTicks, callback) {
        this.#add({ kind: "idle", ticks: delayTicks });
        return this.#add({ kind: "wait", callback });
    }

    thenSucceed() {
        this.#add({ kind: "succeed" });
    }

    thenFail(errorMessage) {
        this.#add({ kind: "fail", message: errorMessage });
    }

    #add(step) {
        this.#steps.push(step);
        return this;
    }

    /** Advances as far as this tick allows, and answers nothing; the run reads its own state. */
    advance(elapsed) {
        while (!this.#stopped && this.#index < this.#steps.length) {
            if (!this.#step(this.#steps[this.#index], elapsed - this.#since)) return;
            this.#index++;
            this.#since = elapsed;
        }
    }

    /** True once the step is done with and the chain may move on to the next one. */
    #step(step, waited) {
        switch (step.kind) {
            case "idle":
                return waited >= (step.ticks ?? 0);
            case "execute":
                this.#stopped = !this.#run.call(step.callback);
                return !this.#stopped;
            case "repeat":
                this.#stopped = !this.#run.call(step.callback);
                return !this.#stopped && waited + 1 >= (step.ticks ?? 0);
            case "wait":
                return this.#run.attempt(step.callback);
            case "succeed":
                this.#stopped = true;
                this.#run.succeed();
                return false;
            case "fail":
                this.#stopped = true;
                this.#run.failWith(new GameTestError(step.message, GameTestErrorType.FailConditionsMet));
                return false;
            default:
                return false;
        }
    }
}

/** One test being run, which owns the area it was given and the verdict it reaches. */
class TestRun {
    #settled = false;

    constructor(entry, placement) {
        this.entry = entry;
        this.runId = placement.runId;
        this.dimensionId = placement.dimension;
        this.origin = { x: placement.x, y: placement.y, z: placement.z };
        this.size = { x: placement.width, y: placement.height, z: placement.depth };
        this.elapsed = -Math.max(0, entry.setupTicks ?? 0);
        this.sequences = [];
        this.delayed = [];
        this.started = false;
        this.test = new Test(this);
    }

    get settled() {
        return this.#settled;
    }

    /** The test's own tick count, which starts at zero once the setup ticks are spent. */
    pump() {
        if (this.#settled) return;

        if (!this.started) {
            if (this.elapsed < 0) {
                this.elapsed++;
                return;
            }
            this.started = true;
            this.#begin();
            if (this.#settled) return;
        }

        this.#runDelayed();
        for (const sequence of this.sequences.slice()) {
            if (this.#settled) return;
            sequence.advance(this.elapsed);
        }

        if (!this.#settled && this.elapsed >= (this.entry.maxTicks ?? DEFAULT_MAX_TICKS)) {
            this.failWith(new GameTestError("Timed out after " + this.elapsed + " ticks",
                GameTestErrorType.ExecutionTimeout));
        }
        this.elapsed++;
    }

    #begin() {
        let answer;
        try {
            answer = this.entry.testFunction(this.test);
        } catch (error) {
            this.failWith(error);
            return;
        }

        if (this.entry.awaited) {
            Promise.resolve(answer).then(() => this.succeed(), error => this.failWith(error));
        }
    }

    #runDelayed() {
        const due = this.delayed.filter(entry => entry.at <= this.elapsed);
        this.delayed = this.delayed.filter(entry => entry.at > this.elapsed);
        for (const entry of due) {
            if (this.#settled) return;
            this.call(entry.callback);
        }
    }

    after(delayTicks, callback) {
        this.delayed.push({ at: this.elapsed + Math.max(0, delayTicks ?? 0), callback });
    }

    at(tick, callback) {
        this.delayed.push({ at: tick, callback });
    }

    startSequence() {
        const sequence = new GameTestSequence(this);
        this.sequences.push(sequence);
        return sequence;
    }

    /** Runs a callback, failing the test on a throw. True when it came back without one. */
    call(callback) {
        try {
            callback();
            return true;
        } catch (error) {
            this.failWith(error);
            return false;
        }
    }

    /**
     * Runs a check whose failed assertion only means "not yet", which is how a step waits.
     *
     * A test calling `fail` says so outright rather than describing a condition, so that one ends
     * the run instead of being tried again next tick.
     */
    attempt(callback) {
        try {
            callback();
            return true;
        } catch (error) {
            if (error instanceof GameTestError && error.type !== GameTestErrorType.FailConditionsMet) {
                return false;
            }
            this.failWith(error);
            return false;
        }
    }

    succeed() {
        this.#settle(true, "");
    }

    failWith(error) {
        this.#settle(false, error?.message ?? String(error));
    }

    abandon() {
        this.#settled = true;
    }

    #settle(passed, message) {
        if (this.#settled) return;
        this.#settled = true;
        bridge.gametests.report(this.runId, passed, this.entry.name, message);
    }
}

/**
 * What a running test is handed.
 *
 * Every position a pack gives is relative to the structure's lower north-west corner, so each one is
 * moved into the world before it reaches the game, and everything the game answers with is moved
 * back before the pack sees it. Every assertion throws, which is how a Bedrock test fails.
 */
export class Test {
    #run;

    constructor(run) {
        this.#run = run;
    }

    // Placing and reading

    getDimension() {
        return world.getDimension(this.#run.dimensionId);
    }

    worldLocation(relativeLocation) {
        return {
            x: this.#run.origin.x + relativeLocation.x,
            y: this.#run.origin.y + relativeLocation.y,
            z: this.#run.origin.z + relativeLocation.z,
        };
    }

    worldBlockLocation(relativeBlockLocation) {
        return blockPosition(this.worldLocation(relativeBlockLocation));
    }

    relativeLocation(worldLocation) {
        return {
            x: worldLocation.x - this.#run.origin.x,
            y: worldLocation.y - this.#run.origin.y,
            z: worldLocation.z - this.#run.origin.z,
        };
    }

    relativeBlockLocation(worldBlockLocation) {
        return blockPosition(this.relativeLocation(worldBlockLocation));
    }

    getBlock(blockLocation) {
        return this.getDimension().getBlock(this.worldBlockLocation(blockLocation));
    }

    setBlockType(blockType, blockLocation) {
        this.getDimension().setBlockType(this.worldBlockLocation(blockLocation), typeIdOf(blockType));
    }

    /** The spelling an older pack calls, which places the same block. */
    setBlock(blockType, blockLocation) {
        this.setBlockType(blockType, blockLocation);
    }

    setBlockPermutation(blockData, blockLocation) {
        this.getDimension().setBlockPermutation(this.worldBlockLocation(blockLocation), blockData);
    }

    destroyBlock(blockLocation, dropResources) {
        const at = this.worldBlockLocation(blockLocation);
        bridge.gametests.destroyBlock(this.#run.dimensionId, at.x, at.y, at.z, dropResources === true);
    }

    getFenceConnectivity(blockLocation) {
        const at = this.worldBlockLocation(blockLocation);
        const sides = JSON.parse(bridge.gametests.fenceConnectivity(this.#run.dimensionId, at.x, at.y, at.z));
        return new FenceConnectivity(sides);
    }

    getTestDirection() {
        return "south";
    }

    // A run places one structure the way the pack saved it, so neither of these turns anything.

    rotateDirection(direction) {
        return direction;
    }

    rotateVector(vector) {
        return vector;
    }

    // Assertions

    assert(condition, message) {
        if (!condition) fail(message);
    }

    assertBlockPresent(blockType, blockLocation, isPresent) {
        const wanted = this.getDimension().getBlock(this.worldBlockLocation(blockLocation));
        const present = wanted !== undefined
            && wanted.typeId === (bridge.blocks.canonicalId(typeIdOf(blockType)) ?? typeIdOf(blockType));
        this.#expect(present, isPresent,
            typeIdOf(blockType) + " at " + describe(blockLocation), GameTestErrorType.AssertAtPosition);
    }

    assertBlockState(blockLocation, callback) {
        const block = this.getDimension().getBlock(this.worldBlockLocation(blockLocation));
        if (block === undefined || callback(block) !== true) {
            fail("Block at " + describe(blockLocation) + " is not in the expected state",
                GameTestErrorType.AssertAtPosition);
        }
    }

    assertIsWaterlogged(blockLocation, isWaterlogged) {
        const at = this.worldBlockLocation(blockLocation);
        this.#expect(bridge.blocks.isWaterlogged(this.#run.dimensionId, at.x, at.y, at.z), isWaterlogged,
            "waterlogging at " + describe(blockLocation), GameTestErrorType.AssertAtPosition);
    }

    assertRedstonePower(blockLocation, power) {
        const at = this.worldBlockLocation(blockLocation);
        const found = bridge.blocks.redstonePower(this.#run.dimensionId, at.x, at.y, at.z);
        if (found !== power) {
            fail("Redstone power at " + describe(blockLocation) + " is " + found + ", expected " + power,
                GameTestErrorType.AssertAtPosition);
        }
    }

    assertEntityPresent(entityTypeIdentifier, blockLocation, searchDistance, isPresent) {
        const found = this.#near(entityTypeIdentifier, blockLocation, searchDistance ?? 0).length > 0;
        this.#expect(found, isPresent, entityTypeIdentifier + " at " + describe(blockLocation),
            GameTestErrorType.AssertAtPosition);
    }

    /** The spelling an older pack calls for the absence of one. */
    assertEntityNotPresent(entityTypeIdentifier, blockLocation, searchDistance) {
        this.assertEntityPresent(entityTypeIdentifier, blockLocation, searchDistance, false);
    }

    assertEntityPresentInArea(entityTypeIdentifier, isPresent) {
        this.#expect(this.#inArea(entityTypeIdentifier).length > 0, isPresent,
            entityTypeIdentifier + " in the test area");
    }

    assertEntityNotPresentInArea(entityTypeIdentifier) {
        this.assertEntityPresentInArea(entityTypeIdentifier, false);
    }

    assertEntityInstancePresent(entity, blockLocation, isPresent) {
        const found = this.#near(entity.typeId, blockLocation, 0).includes(entity.id);
        this.#expect(found, isPresent, "that " + entity.typeId + " at " + describe(blockLocation),
            GameTestErrorType.AssertAtPosition);
    }

    assertEntityInstancePresentInArea(entity, isPresent) {
        this.#expect(this.#inArea(undefined).includes(entity.id), isPresent,
            "that " + entity.typeId + " in the test area");
    }

    assertEntityState(blockLocation, entityTypeIdentifier, callback) {
        const found = this.#near(entityTypeIdentifier, blockLocation, 0)
            .map(id => world.getEntity(id))
            .filter(entity => entity !== undefined);
        if (found.length === 0) {
            fail("No " + entityTypeIdentifier + " at " + describe(blockLocation),
                GameTestErrorType.AssertAtPosition);
        }
        if (!found.some(entity => callback(entity) === true)) {
            fail("No " + entityTypeIdentifier + " at " + describe(blockLocation) + " is in that state",
                GameTestErrorType.AssertAtPosition);
        }
    }

    assertEntityHasComponent(entityTypeIdentifier, componentIdentifier, blockLocation, hasComponent) {
        const found = this.#near(entityTypeIdentifier, blockLocation, 0)
            .some(id => world.getEntity(id)?.hasComponent(componentIdentifier) === true);
        this.#expect(found, hasComponent,
            entityTypeIdentifier + " with " + componentIdentifier + " at " + describe(blockLocation),
            GameTestErrorType.AssertAtPosition);
    }

    assertEntityTouching(entityTypeIdentifier, location, isTouching) {
        const at = this.worldLocation(location);
        const found = this.#inArea(entityTypeIdentifier)
            .some(id => bridge.gametests.touches(id, at.x, at.y, at.z));
        this.#expect(found, isTouching,
            entityTypeIdentifier + " touching " + describe(location), GameTestErrorType.AssertAtPosition);
    }

    assertContainerContains(itemStack, blockLocation) {
        const container = this.#container(blockLocation);
        const slots = [...Array(container.size).keys()]
            .map(slot => container.getItem(slot))
            .filter(item => item !== undefined);
        if (!slots.some(item => item.typeId === itemStack.typeId && item.amount >= itemStack.amount)) {
            fail("The container at " + describe(blockLocation) + " holds no " + itemStack.typeId,
                GameTestErrorType.AssertAtPosition);
        }
    }

    assertContainerEmpty(blockLocation) {
        const container = this.#container(blockLocation);
        if (container.emptySlotsCount !== container.size) {
            fail("The container at " + describe(blockLocation) + " is not empty",
                GameTestErrorType.AssertAtPosition);
        }
    }

    assertItemEntityPresent(itemType, blockLocation, searchDistance, isPresent) {
        const found = this.#items(itemType, blockLocation, searchDistance ?? 0).length > 0;
        this.#expect(found, isPresent, typeIdOf(itemType) + " dropped at " + describe(blockLocation),
            GameTestErrorType.AssertAtPosition);
    }

    assertItemEntityCountIs(itemType, blockLocation, searchDistance, count) {
        const found = this.#items(itemType, blockLocation, searchDistance)
            .reduce((total, dropped) => total + dropped.amount, 0);
        if (found !== count) {
            fail("Found " + found + " " + typeIdOf(itemType) + " at " + describe(blockLocation)
                + ", expected " + count, GameTestErrorType.AssertAtPosition);
        }
    }

    fail(errorMessage) {
        fail(errorMessage, GameTestErrorType.FailConditionsMet);
    }

    /** Fails once the callback stops throwing, which is the inverse of waiting for one to pass. */
    failIf(callback) {
        this.#run.startSequence().thenWait(callback).thenFail("Fail conditions met");
    }

    // Entities

    spawn(entityTypeIdentifier, blockLocation) {
        return this.spawnAtLocation(entityTypeIdentifier, centre(blockLocation));
    }

    spawnAtLocation(entityTypeIdentifier, location) {
        return this.getDimension().spawnEntity(entityTypeIdentifier, this.worldLocation(location));
    }

    spawnWithoutBehaviors(entityTypeIdentifier, blockLocation) {
        return this.spawnWithoutBehaviorsAtLocation(entityTypeIdentifier, centre(blockLocation));
    }

    spawnWithoutBehaviorsAtLocation(entityTypeIdentifier, location) {
        const at = this.worldLocation(location);
        const id = bridge.gametests.spawnWithoutAi(this.#run.dimensionId, entityTypeIdentifier,
            at.x, at.y, at.z);
        if (id === null) fail("Could not spawn " + entityTypeIdentifier);
        return world.getEntity(id);
    }

    spawnItem(itemStack, location) {
        return this.getDimension().spawnItem(itemStack, this.worldLocation(location));
    }

    killAllEntities() {
        const from = this.#run.origin;
        const to = this.#run.size;
        bridge.gametests.killEntitiesIn(this.#run.dimensionId, from.x, from.y, from.z,
            from.x + to.x, from.y + to.y, from.z + to.z);
    }

    // Flow

    succeed() {
        this.#run.succeed();
    }

    /** Checked where it is written, so a check that is not met yet is the test failing. */
    succeedIf(callback) {
        if (this.#run.call(callback)) this.#run.succeed();
    }

    succeedWhen(callback) {
        this.#run.startSequence().thenWait(callback).thenSucceed();
    }

    succeedOnTick(tick) {
        this.#run.at(tick, () => this.#run.succeed());
    }

    succeedOnTickWhen(tick, callback) {
        this.#run.at(tick, () => {
            callback();
            this.#run.succeed();
        });
    }

    succeedWhenBlockPresent(blockType, blockLocation, isPresent) {
        this.succeedWhen(() => this.assertBlockPresent(blockType, blockLocation, isPresent));
    }

    succeedWhenEntityPresent(entityTypeIdentifier, blockLocation, isPresent) {
        this.succeedWhen(() => this.assertEntityPresent(entityTypeIdentifier, blockLocation, 0, isPresent));
    }

    succeedWhenEntityHasComponent(entityTypeIdentifier, componentIdentifier, blockLocation, hasComponent) {
        this.succeedWhen(() => this.assertEntityHasComponent(entityTypeIdentifier, componentIdentifier,
            blockLocation, hasComponent));
    }

    runAtTickTime(tick, callback) {
        this.#run.at(tick, callback);
    }

    runAfterDelay(delayTicks, callback) {
        this.#run.after(delayTicks, callback);
    }

    startSequence() {
        return this.#run.startSequence();
    }

    idle(tickDelay) {
        return new Promise(resolve => this.#run.after(tickDelay, resolve));
    }

    until(callback) {
        return new Promise(resolve => this.#run.startSequence().thenWait(callback).thenExecute(resolve));
    }

    print(text) {
        bridge.gametests.print(this.#run.runId, text);
    }

    // The world around the test

    setTntFuse(entity, fuseLength) {
        if (!bridge.gametests.setTntFuse(entity.id, fuseLength)) {
            fail("That entity is not primed TNT");
        }
    }

    pressButton(blockLocation) {
        const at = this.worldBlockLocation(blockLocation);
        bridge.gametests.pressButton(this.#run.dimensionId, at.x, at.y, at.z);
    }

    pullLever(blockLocation) {
        const at = this.worldBlockLocation(blockLocation);
        bridge.gametests.pullLever(this.#run.dimensionId, at.x, at.y, at.z);
    }

    /** A block of redstone for the given span, which is how Bedrock powers one position briefly. */
    pulseRedstone(blockLocation, duration) {
        this.setBlockType("minecraft:redstone_block", blockLocation);
        this.#run.after(duration, () => this.setBlockType("minecraft:air", blockLocation));
    }

    setFluidContainer(location, type) {
        const at = this.worldBlockLocation(location);
        bridge.gametests.setFluidContainer(this.#run.dimensionId, at.x, at.y, at.z, type);
    }

    triggerInternalBlockEvent(blockLocation, event, eventParameters) {
        const at = this.worldBlockLocation(blockLocation);
        bridge.gametests.triggerBlockEvent(this.#run.dimensionId, at.x, at.y, at.z,
            Number(event), Number(eventParameters?.[0] ?? 0));
    }

    // What this mod has no counterpart for. Each reports itself so a pack is told rather than left
    // watching a test that never moves.

    spawnSimulatedPlayer(blockLocation, name, gameMode) {
        return unsupported("Test.spawnSimulatedPlayer", new SimulatedPlayer(""));
    }

    removeSimulatedPlayer(simulatedPlayer) {
        unsupported("Test.removeSimulatedPlayer");
    }

    assertCanReachLocation(mob, blockLocation, canReach) {
        unsupported("Test.assertCanReachLocation");
    }

    assertEntityHasArmor(entityTypeIdentifier, armorSlot, armorName, armorData, blockLocation, hasArmor) {
        unsupported("Test.assertEntityHasArmor");
    }

    getSculkSpreader(blockLocation) {
        return unsupported("Test.getSculkSpreader", undefined);
    }

    spreadFromFaceTowardDirection(blockLocation, fromFace, direction) {
        unsupported("Test.spreadFromFaceTowardDirection");
    }

    onPlayerJump(mob, jumpAmount) {
        unsupported("Test.onPlayerJump");
    }

    walkTo(mob, blockLocation, speedModifier) {
        unsupported("Test.walkTo");
    }

    walkToLocation(mob, location, speedModifier) {
        unsupported("Test.walkToLocation");
    }

    // Shared shapes

    /** Raises the failure an assertion's expectation and its answer disagree on. */
    #expect(found, wanted, what, type) {
        if (found === (wanted !== false)) return;
        fail((wanted === false ? "Found " : "Did not find ") + what, type);
    }

    #container(blockLocation) {
        const block = this.getBlock(blockLocation);
        const container = block?.getComponent("minecraft:inventory")?.container;
        if (container === undefined) {
            fail("There is no container at " + describe(blockLocation), GameTestErrorType.AssertAtPosition);
        }
        return container;
    }

    /** The ids of one type of entity within a radius of a position the pack gave in test space. */
    #near(entityTypeIdentifier, blockLocation, searchDistance) {
        const at = this.worldBlockLocation(blockLocation);
        const reach = Math.max(searchDistance ?? 0, 0);
        return list(bridge.gametests.entitiesIn(this.#run.dimensionId,
            at.x - reach, at.y - reach, at.z - reach,
            at.x + reach + 1, at.y + reach + 1, at.z + reach + 1,
            entityTypeIdentifier ?? null));
    }

    #inArea(entityTypeIdentifier) {
        const from = this.#run.origin;
        const to = this.#run.size;
        return list(bridge.gametests.entitiesIn(this.#run.dimensionId, from.x, from.y, from.z,
            from.x + to.x, from.y + to.y, from.z + to.z, entityTypeIdentifier ?? null));
    }

    /** The dropped stacks of one kind, each as the id and the count the pack asserts against. */
    #items(itemType, blockLocation, searchDistance) {
        const at = this.worldBlockLocation(blockLocation);
        const reach = Math.max(searchDistance ?? 0, 0);
        return JSON.parse(bridge.gametests.itemEntitiesIn(this.#run.dimensionId,
            at.x - reach, at.y - reach, at.z - reach,
            at.x + reach + 1, at.y + reach + 1, at.z + reach + 1, typeIdOf(itemType)));
    }
}

function centre(blockLocation) {
    return { x: blockLocation.x + 0.5, y: blockLocation.y, z: blockLocation.z + 0.5 };
}

export class FenceConnectivity {
    constructor(sides) {
        this.north = sides.north === true;
        this.east = sides.east === true;
        this.south = sides.south === true;
        this.west = sides.west === true;
    }
}

export class NavigationResult {
    constructor() {
        this.isFullPath = false;
    }

    getPath() {
        return unsupported("NavigationResult.getPath", []);
    }
}

export class SculkSpreader {
    get maxCharge() {
        return unsupported("SculkSpreader.maxCharge", 0);
    }

    addCursorsWithOffset() {
        unsupported("SculkSpreader.addCursorsWithOffset");
    }

    getCursorPosition() {
        return unsupported("SculkSpreader.getCursorPosition", undefined);
    }

    getNumberOfCursors() {
        return unsupported("SculkSpreader.getNumberOfCursors", 0);
    }

    getTotalCharge() {
        return unsupported("SculkSpreader.getTotalCharge", 0);
    }
}

/**
 * A player the game drives for a test, which this mod has no entity for.
 *
 * Java's own fake player exists only inside its test framework and carries none of Bedrock's
 * vocabulary, so every member here reports itself and leaves the world alone. A pack calling one is
 * told through `/mcaddon scripts` rather than watching a test that never moves.
 */
export class SimulatedPlayer extends Player {
    get headRotation() {
        return unsupported("SimulatedPlayer.headRotation", { x: 0, y: 0 });
    }

    get isSprinting() {
        return unsupported("SimulatedPlayer.isSprinting", false);
    }

    set isSprinting(sprinting) {
        unsupported("SimulatedPlayer.isSprinting");
    }
}

// Every remaining member of a simulated player, named so that calling one reports it. Written as a
// list because each answers the same way and forty near-identical bodies would say nothing more.
const SIMULATED_PLAYER_MEMBERS = Object.freeze({
    attack: false, attackEntity: false, breakBlock: false, chat: undefined, disconnect: undefined,
    dropSelectedItem: false, fly: undefined, giveItem: false, glide: false, interact: false,
    interactWithBlock: false, interactWithEntity: false, jump: false, lookAtBlock: undefined,
    lookAtEntity: undefined, lookAtLocation: undefined, move: undefined, moveRelative: undefined,
    moveToBlock: undefined, moveToLocation: undefined, respawn: false, rotateBody: undefined,
    setBodyRotation: undefined, setItem: false, startBuild: undefined, stopBreakingBlock: undefined,
    stopBuild: undefined, stopFlying: undefined, stopGliding: undefined, stopInteracting: undefined,
    stopMoving: undefined, stopSwimming: undefined, stopUsingItem: undefined, swim: undefined,
    useItem: false, useItemInSlot: false, useItemInSlotOnBlock: false, useItemOnBlock: false,
});

for (const [member, answer] of Object.entries(SIMULATED_PLAYER_MEMBERS)) {
    SimulatedPlayer.prototype[member] = () => unsupported("SimulatedPlayer." + member, answer);
}

for (const member of ["navigateToBlock", "navigateToEntity", "navigateToLocation"]) {
    SimulatedPlayer.prototype[member] = () => unsupported("SimulatedPlayer." + member, new NavigationResult());
}

SimulatedPlayer.prototype.navigateToLocations = () => unsupported("SimulatedPlayer.navigateToLocations");

/**
 * What Java calls into. Kept off the module exports so a pack cannot reach it through an import.
 *
 * The hook is installed when the module is evaluated, which only happens once a pack imports it, so
 * its absence is how the game knows no pack ships tests.
 */
const running = new Map();

globalThis.__mcaddon_gametests__ = {
    list() {
        return JSON.stringify(registered.map(entry => ({
            name: entry.name,
            className: entry.className,
            testName: entry.testName,
            structureName: entry.structureName ?? "",
            padding: entry.padding ?? 0,
            batch: entry.batch,
            required: entry.required,
            tags: entry.tags,
        })));
    },

    start(index, placementJson) {
        const entry = registered[index];
        if (entry === undefined) return;

        const placement = JSON.parse(placementJson);
        running.set(placement.runId, new TestRun(entry, placement));
    },

    stop() {
        running.forEach(run => run.abandon());
        running.clear();
    },

    pump() {
        for (const [runId, run] of [...running]) {
            run.pump();
            if (run.settled) running.delete(runId);
            if (bridge.outOfBudget()) return;
        }
    },
};
