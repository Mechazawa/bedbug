// The @minecraft/server-gametest surface.
//
// Bedrock's own test framework, which a pack imports to ship tests of its content. Java Edition has
// a framework of the same name that shares none of its vocabulary: a Bedrock test names a structure
// to build the world from and asserts against Bedrock components, neither of which Java's has a
// form of, so nothing here runs a pack's tests.
//
// What it does instead is take the registration and say so. A pack registers its tests while its
// script is being loaded, and chains the builder's methods onto the call, so refusing the import or
// the chain would cost the pack the rest of its script over tests that were never going to run.

import { Player } from "@minecraft/server";

const bridge = globalThis.__mcaddon_bridge__;

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

/**
 * What a pack chains onto a registration to describe how its test would be run.
 *
 * Every method answers with the builder, so a chain of any length and order ends where it started
 * and the pack's script carries on.
 */
export class RegistrationBuilder {
    batch() {
        return this;
    }

    maxAttempts() {
        return this;
    }

    maxTicks() {
        return this;
    }

    padding() {
        return this;
    }

    required() {
        return this;
    }

    requiredSuccessfulAttempts() {
        return this;
    }

    rotateTest() {
        return this;
    }

    setupTicks() {
        return this;
    }

    structureLocation() {
        return this;
    }

    structureName() {
        return this;
    }

    tag() {
        return this;
    }
}

export function register(testClassName, testName, testFunction) {
    bridge.unsupported("GameTest.register");
    return new RegistrationBuilder();
}

export function registerAsync(testClassName, testName, testFunction) {
    bridge.unsupported("GameTest.registerAsync");
    return new RegistrationBuilder();
}

// Named so a pack can import one and write `instanceof` against it. Only a running test is handed
// one of these, and no test runs, so nothing here is ever built.
export class Test {}
export class GameTestSequence {}
export class FenceConnectivity {}
export class NavigationResult {}
export class SculkSpreader {}

export class SimulatedPlayer extends Player {}

export class GameTestError extends Error {}
