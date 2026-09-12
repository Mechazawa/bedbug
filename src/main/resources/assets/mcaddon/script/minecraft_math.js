// The @minecraft/math surface.
//
// Arithmetic over {x, y, z} objects, so the whole module runs in the guest and asks the game for
// nothing. The one thing it needs from `@minecraft/server` is the BlockVolume an AABB is rounded
// out to.

import { BlockVolume } from "@minecraft/server";

export function clampNumber(value, minimum, maximum) {
    return Math.min(Math.max(value, minimum), maximum);
}

const LOWEST = Number.MIN_SAFE_INTEGER;
const HIGHEST = Number.MAX_SAFE_INTEGER;

/**
 * Every operation that reads the same on any set of axes.
 *
 * Vector3, Vector2 and VectorXZ share all of their arithmetic and differ only in which axes they
 * carry, so each exported table names its axes and takes the operations from here.
 */
function utilsFor(axes) {
    const build = produce => Object.fromEntries(axes.map(axis => [axis, produce(axis)]));
    const add = (first, second) => build(axis => first[axis] + (second[axis] ?? 0));
    const subtract = (first, second) => build(axis => first[axis] - (second[axis] ?? 0));
    const scale = (vector, factor) => build(axis => vector[axis] * factor);
    const dot = (first, second) => axes.reduce((total, axis) => total + first[axis] * second[axis], 0);
    const magnitude = vector => Math.sqrt(axes.reduce((total, axis) => total + vector[axis] ** 2, 0));

    return {
        add, subtract, scale, dot, magnitude,
        equals: (first, second) => axes.every(axis => first[axis] === second[axis]),
        multiply: (first, second) => build(axis => first[axis] * second[axis]),
        min: (first, second) => build(axis => Math.min(first[axis], second[axis])),
        max: (first, second) => build(axis => Math.max(first[axis], second[axis])),
        floor: vector => build(axis => Math.floor(vector[axis])),
        ceil: vector => build(axis => Math.ceil(vector[axis])),
        distance: (first, second) => magnitude(subtract(first, second)),
        normalize: vector => {
            const length = magnitude(vector);
            return build(axis => vector[axis] / length);
        },
        lerp: (first, second, fraction) =>
            build(axis => first[axis] + (second[axis] - first[axis]) * fraction),

        // Along the arc between the two rather than the line, which is undefined for a pair that
        // points the same way: the sine of a zero angle divides.
        slerp: (first, second, fraction) => {
            const theta = Math.acos(dot(first, second));
            const sine = Math.sin(theta);
            return add(scale(first, Math.sin((1 - fraction) * theta) / sine),
                scale(second, Math.sin(fraction * theta) / sine));
        },

        clamp: (vector, limits) => build(axis => clampNumber(
            vector[axis], limits?.min?.[axis] ?? LOWEST, limits?.max?.[axis] ?? HIGHEST)),

        toString: (vector, options) => axes
            .map(axis => vector[axis].toFixed(options?.decimals ?? 2))
            .join(options?.delimiter ?? ", "),

        fromString: (text, delimiter = ",") => {
            const parts = text.split(delimiter).map(part => parseFloat(part));
            if (parts.length !== axes.length || parts.some(Number.isNaN)) return undefined;
            return build(axis => parts[axes.indexOf(axis)]);
        },
    };
}

const AXES3 = ["x", "y", "z"];
const AXES2 = ["x", "y"];
const AXESXZ = ["x", "z"];

export const Vector3Utils = Object.freeze({
    ...utilsFor(AXES3),

    cross: (first, second) => ({
        x: first.y * second.z - first.z * second.y,
        y: first.z * second.x - first.x * second.z,
        z: first.x * second.y - first.y * second.x,
    }),

    rotateX(vector, angle) {
        const cosine = Math.cos(angle);
        const sine = Math.sin(angle);
        return {
            x: vector.x,
            y: vector.y * cosine - vector.z * sine,
            z: vector.z * cosine + vector.y * sine,
        };
    },

    rotateY(vector, angle) {
        const cosine = Math.cos(angle);
        const sine = Math.sin(angle);
        return {
            x: vector.x * cosine + vector.z * sine,
            y: vector.y,
            z: vector.z * cosine - vector.x * sine,
        };
    },

    rotateZ(vector, angle) {
        const cosine = Math.cos(angle);
        const sine = Math.sin(angle);
        return {
            x: vector.x * cosine - vector.y * sine,
            y: vector.y * cosine + vector.x * sine,
            z: vector.z,
        };
    },
});

export const Vector2Utils = Object.freeze(utilsFor(AXES2));

export const VectorXZUtils = Object.freeze(utilsFor(AXESXZ));

/**
 * A vector that carries its own operations and changes in place.
 *
 * Each method assigns the answer back over the builder and returns it, so a pack chains them and
 * ends up with one object rather than a new one per step.
 */
function builderFor(axes, utils) {
    const zero = Object.fromEntries(axes.map(axis => [axis, 0]));

    return class {
        constructor(first, second, third) {
            const numbers = [first, second, third];
            if (typeof first === "string") {
                this.assign(utils.fromString(first, second ?? ",") ?? zero);
            } else if (typeof first === "object" && first !== null) {
                this.assign(first);
            } else {
                this.assign(Object.fromEntries(
                    axes.map((axis, index) => [axis, numbers[index] ?? 0])));
            }
        }

        assign(vector) {
            for (const axis of axes) this[axis] = vector[axis];
            return this;
        }

        equals(other) {
            return utils.equals(this, other);
        }

        dot(other) {
            return utils.dot(this, other);
        }

        magnitude() {
            return utils.magnitude(this);
        }

        distance(other) {
            return utils.distance(this, other);
        }

        toString(options) {
            return utils.toString(this, options);
        }

        add(other) {
            return this.assign(utils.add(this, other));
        }

        subtract(other) {
            return this.assign(utils.subtract(this, other));
        }

        scale(factor) {
            return this.assign(utils.scale(this, factor));
        }

        multiply(other) {
            return this.assign(utils.multiply(this, other));
        }

        normalize() {
            return this.assign(utils.normalize(this));
        }

        floor() {
            return this.assign(utils.floor(this));
        }

        clamp(limits) {
            return this.assign(utils.clamp(this, limits));
        }

        lerp(other, fraction) {
            return this.assign(utils.lerp(this, other, fraction));
        }

        slerp(other, fraction) {
            return this.assign(utils.slerp(this, other, fraction));
        }
    };
}

export class Vector3Builder extends builderFor(AXES3, Vector3Utils) {
    cross(other) {
        return this.assign(Vector3Utils.cross(this, other));
    }

    ceil() {
        return this.assign(Vector3Utils.ceil(this));
    }

    min(other) {
        return this.assign(Vector3Utils.min(this, other));
    }

    max(other) {
        return this.assign(Vector3Utils.max(this, other));
    }

    rotateX(angle) {
        return this.assign(Vector3Utils.rotateX(this, angle));
    }

    rotateY(angle) {
        return this.assign(Vector3Utils.rotateY(this, angle));
    }

    rotateZ(angle) {
        return this.assign(Vector3Utils.rotateZ(this, angle));
    }
}

export class Vector2Builder extends builderFor(AXES2, Vector2Utils) {
}

export class VectorXZBuilder extends builderFor(AXESXZ, VectorXZUtils) {
}

export const VECTOR3_UP = Object.freeze({ x: 0, y: 1, z: 0 });
export const VECTOR3_DOWN = Object.freeze({ x: 0, y: -1, z: 0 });
export const VECTOR3_LEFT = Object.freeze({ x: -1, y: 0, z: 0 });
export const VECTOR3_RIGHT = Object.freeze({ x: 1, y: 0, z: 0 });
export const VECTOR3_FORWARD = Object.freeze({ x: 0, y: 0, z: 1 });
export const VECTOR3_BACK = Object.freeze({ x: 0, y: 0, z: -1 });
export const VECTOR3_ONE = Object.freeze({ x: 1, y: 1, z: 1 });
export const VECTOR3_ZERO = Object.freeze({ x: 0, y: 0, z: 0 });
export const VECTOR3_WEST = Object.freeze({ x: -1, y: 0, z: 0 });
export const VECTOR3_EAST = Object.freeze({ x: 1, y: 0, z: 0 });
export const VECTOR3_NORTH = Object.freeze({ x: 0, y: 0, z: 1 });
export const VECTOR3_SOUTH = Object.freeze({ x: 0, y: 0, z: -1 });
export const VECTOR3_HALF = Object.freeze({ x: 0.5, y: 0.5, z: 0.5 });
export const VECTOR3_NEGATIVE_ONE = Object.freeze({ x: -1, y: -1, z: -1 });
export const VECTOR2_ZERO = Object.freeze({ x: 0, y: 0 });
export const VECTORXZ_ZERO = Object.freeze({ x: 0, z: 0 });

/** Minecraft's dye colours, as the 0 to 1 channels a pack passes to anything that takes an RGBA. */
function rgb(red, green, blue) {
    return Object.freeze({ red: red / 255, green: green / 255, blue: blue / 255, alpha: 1 });
}

export const Colors = Object.freeze({
    Black: rgb(29, 29, 33),
    Blue: rgb(60, 68, 170),
    Brown: rgb(131, 84, 50),
    Cyan: rgb(22, 156, 156),
    Gray: rgb(71, 79, 82),
    Green: rgb(94, 124, 22),
    LightBlue: rgb(58, 179, 218),
    Lime: rgb(128, 199, 31),
    Magenta: rgb(199, 78, 189),
    Orange: rgb(249, 128, 29),
    Pink: rgb(243, 139, 170),
    Purple: rgb(137, 50, 184),
    Red: rgb(176, 46, 38),
    Silver: rgb(157, 157, 151),
    White: rgb(240, 240, 240),
    Yellow: rgb(254, 216, 61),
    PureWhite: rgb(255, 255, 255),
    PureBlack: rgb(0, 0, 0),
    PureRed: rgb(255, 0, 0),
    PureGreen: rgb(0, 255, 0),
    PureBlue: rgb(0, 0, 255),
    Transparent: Object.freeze({ red: 0, green: 0, blue: 0, alpha: 0 }),
});

export class AABBInvalidExtentError extends Error {
    constructor(extent) {
        super("Invalid AABB extent of '" + Vector3Utils.toString(extent) + "'");
    }
}

/**
 * A box described by its centre and its half size, which is what `@minecraft/server` hands back.
 *
 * An extent below the epsilon is refused rather than worked with: a box with no thickness on an
 * axis answers every containment question the same way and a pack that built one meant something
 * else.
 */
export const AABBUtils = Object.freeze({
    EPSILON: 0.00001,

    isValid: box => AXES3.every(axis => box.extent[axis] >= AABBUtils.EPSILON),

    throwErrorIfInvalid(box) {
        if (!AABBUtils.isValid(box)) throw new AABBInvalidExtentError(box.extent);
    },

    createFromCornerPoints(first, second) {
        const minimum = Vector3Utils.min(first, second);
        const extent = Vector3Utils.scale(Vector3Utils.subtract(Vector3Utils.max(first, second), minimum), 0.5);
        const box = { center: Vector3Utils.add(minimum, extent), extent };
        AABBUtils.throwErrorIfInvalid(box);
        return box;
    },

    equals(box, other) {
        AABBUtils.throwErrorIfInvalid(box);
        AABBUtils.throwErrorIfInvalid(other);
        return Vector3Utils.equals(box.center, other.center)
            && Vector3Utils.equals(box.extent, other.extent);
    },

    getMin(box) {
        AABBUtils.throwErrorIfInvalid(box);
        return Vector3Utils.subtract(box.center, box.extent);
    },

    getMax(box) {
        AABBUtils.throwErrorIfInvalid(box);
        return Vector3Utils.add(box.center, box.extent);
    },

    getSpan(box) {
        AABBUtils.throwErrorIfInvalid(box);
        return Vector3Utils.scale(box.extent, 2);
    },

    // Pulled in by an epsilon on each side first, so a face that sits exactly on a block boundary
    // does not take in the row of blocks beyond it.
    getBlockVolume(box) {
        const epsilon = { x: AABBUtils.EPSILON, y: AABBUtils.EPSILON, z: AABBUtils.EPSILON };
        return new BlockVolume(
            Vector3Utils.floor(Vector3Utils.add(AABBUtils.getMin(box), epsilon)),
            Vector3Utils.ceil(Vector3Utils.subtract(AABBUtils.getMax(box), epsilon)));
    },

    translate(box, delta) {
        AABBUtils.throwErrorIfInvalid(box);
        return { center: Vector3Utils.add(box.center, delta), extent: box.extent };
    },

    dilate(box, size) {
        AABBUtils.throwErrorIfInvalid(box);
        const smallest = { x: AABBUtils.EPSILON, y: AABBUtils.EPSILON, z: AABBUtils.EPSILON };
        return {
            center: box.center,
            extent: Vector3Utils.clamp(Vector3Utils.add(box.extent, size), { min: smallest }),
        };
    },

    expand(box, other) {
        return AABBUtils.createFromCornerPoints(
            Vector3Utils.min(AABBUtils.getMin(box), AABBUtils.getMin(other)),
            Vector3Utils.max(AABBUtils.getMax(box), AABBUtils.getMax(other)));
    },

    getIntersection(box, other) {
        if (!AABBUtils.intersects(box, other)) return undefined;
        return AABBUtils.createFromCornerPoints(
            Vector3Utils.max(AABBUtils.getMin(box), AABBUtils.getMin(other)),
            Vector3Utils.min(AABBUtils.getMax(box), AABBUtils.getMax(other)));
    },

    intersects(box, other) {
        const boxMin = AABBUtils.getMin(box);
        const boxMax = AABBUtils.getMax(box);
        const otherMin = AABBUtils.getMin(other);
        const otherMax = AABBUtils.getMax(other);
        return AXES3.every(axis => otherMax[axis] >= boxMin[axis] && otherMin[axis] <= boxMax[axis]);
    },

    isInside(box, position) {
        const minimum = AABBUtils.getMin(box);
        const maximum = AABBUtils.getMax(box);
        return AXES3.every(axis => position[axis] >= minimum[axis] && position[axis] <= maximum[axis]);
    },
});
