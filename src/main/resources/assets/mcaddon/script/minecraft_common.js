// The @minecraft/common surface.
//
// The error types the other modules throw and the two shapes they pass around. Bedrock's own
// modules import from here rather than each declaring its own, which is why a pack that never
// mentions this module by name still needs it to resolve.

export const InvalidArgumentErrorType = Object.freeze({
    Duplicate: "Duplicate",
    Empty: "Empty",
    InvalidType: "InvalidType",
    Unknown: "Unknown",
    Unspecified: "Unspecified",
    UnsupportedValue: "UnsupportedValue",
});

/** A `min` and a `max`, which is how a component states the range a value may take. */
export class NumberRange {
    constructor(min, max) {
        this.min = min;
        this.max = max;
    }
}

/** A pair of block positions, inclusive of both, as `@minecraft/server` describes a region. */
export class BlockBoundingBox {
    constructor(min, max) {
        this.min = min;
        this.max = max;
    }
}

export class ArgumentOutOfBoundsError extends Error {
    constructor(value, minValue, maxValue, index) {
        super("A value of " + value + " is outside " + minValue + " to " + maxValue + ".");
        this.value = value;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.index = index;
    }
}

export class ContainerSizeOutOfBoundsError extends Error {
    constructor(maxValue) {
        super("A container holds at most " + maxValue + " slots.");
        this.maxValue = maxValue;
    }
}

export class EngineError extends Error {
}

export class InvalidArgumentError extends Error {
    constructor(functionName, argumentTypeName, errorType, argumentIndex) {
        super(functionName + " was given a " + argumentTypeName + " it cannot use: " + errorType);
        this.type = errorType;
        this.index = argumentIndex;
    }
}

export class PropertyOutOfBoundsError extends Error {
    constructor(value, minValue, maxValue) {
        super("A property value of " + value + " is outside " + minValue + " to " + maxValue + ".");
        this.value = value;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }
}

export class RuntimeConditionError extends Error {
}

export class UnsupportedFunctionalityError extends Error {
}
