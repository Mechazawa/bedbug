package at.ioexception.mcaddon.bedrock.molang;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state an expression is evaluated against: the entity's queries, the variables animations
 * assign, and the `this` value a keyframe is being applied to.
 */
public class MolangEnvironment {
    /** Answers `q.name(args)`. Unknown queries read as zero, which is what Bedrock does. */
    public interface Queries {
        MolangValue query(String name, List<MolangValue> arguments);
    }

    private final Map<String, MolangValue> variables = new HashMap<>();
    private Map<String, MolangValue> temporaries = new HashMap<>();
    private Map<String, List<MolangValue>> arrays = Map.of();

    private final Map<String, MolangValue> contexts = new HashMap<>();

    private Queries queries = (name, arguments) -> MolangValue.ZERO;
    private MolangValue self = MolangValue.ZERO;

    private float animTime;
    private float lifeTime;
    private float deltaTime;
    private float stateTime;
    private float keyFrameLerpTime;

    public void setQueries(Queries queries) {
        this.queries = queries;
    }

    public MolangValue query(String name, List<MolangValue> arguments) {
        return queries.query(name, arguments);
    }

    public MolangValue variable(String name) {
        return variables.getOrDefault(name, MolangValue.ZERO);
    }

    public void setVariable(String name, MolangValue value) {
        variables.put(name, value);
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }

    /**
     * `c.name`: what the game set for the place the expression runs in, else the query of that name.
     *
     * An attachable is handed whoever carries it as `c.owning_entity`.
     */
    public MolangValue context(String name, List<MolangValue> arguments) {
        MolangValue set = contexts.get(name);
        return set != null ? set : query(name, arguments);
    }

    public void setContext(String name, MolangValue value) {
        contexts.put(name, value);
    }

    /**
     * This environment as another entity may read it: the named variables, and the queries.
     *
     * Another entity reaches it through `->`, and Bedrock answers zero for a variable this entity's
     * `scripts.variables` does not name `public`. A variable written through `->` is written here.
     */
    public MolangEnvironment publishedAs(Set<String> published) {
        MolangEnvironment owner = this;
        return new MolangEnvironment() {
            @Override
            public MolangValue variable(String name) {
                return published.contains(name) ? owner.variable(name) : MolangValue.ZERO;
            }

            @Override
            public boolean hasVariable(String name) {
                return published.contains(name) && owner.hasVariable(name);
            }

            @Override
            public void setVariable(String name, MolangValue value) {
                owner.setVariable(name, value);
            }

            @Override
            public MolangValue query(String name, List<MolangValue> arguments) {
                return owner.query(name, arguments);
            }
        };
    }

    public boolean hasTemporary(String name) {
        return temporaries.containsKey(name);
    }

    public MolangValue temporary(String name) {
        return temporaries.getOrDefault(name, MolangValue.ZERO);
    }

    public void setTemporary(String name, MolangValue value) {
        temporaries.put(name, value);
    }

    /**
     * Keeps this environment's `temp.` values in a store other environments write to as well.
     *
     * Bedrock clears no temporary between one expression and the next, so a value one entity's
     * expression leaves behind is still there when another entity's expression reads it. A pack
     * passes numbers between two entities that way, keyed by where they stand.
     */
    public void shareTemporaries(Map<String, MolangValue> shared) {
        temporaries = shared;
    }

    /** The value a keyframe channel is interpolating from, which `this` refers to. */
    public MolangValue self() {
        return self;
    }

    public void setSelf(double value) {
        self = MolangValue.of(value);
    }

    /** Render controllers index named arrays of texture and geometry keys. */
    public void setArrays(Map<String, List<MolangValue>> arrays) {
        this.arrays = arrays;
    }

    public MolangValue element(String array, int index) {
        List<MolangValue> values = arrays.get(array);
        if (values == null || values.isEmpty()) return MolangValue.ZERO;
        // Bedrock clamps an out of range index rather than failing to draw.
        return values.get(Math.min(Math.max(index, 0), values.size() - 1));
    }

    public void clearTemporaries() {
        temporaries.clear();
    }

    /** Where the clip being evaluated currently is, in seconds. */
    public float animTime() {
        return animTime;
    }

    public void setAnimTime(float animTime) {
        this.animTime = animTime;
    }

    /** How long the entity has existed, in seconds, which ambient motion is keyed off. */
    public float lifeTime() {
        return lifeTime;
    }

    public void setLifeTime(float lifeTime) {
        this.lifeTime = lifeTime;
    }

    public float deltaTime() {
        return deltaTime;
    }

    public void setDeltaTime(float deltaTime) {
        this.deltaTime = deltaTime;
    }

    /** How long the animation controller has been in its current state, in seconds. */
    public float stateTime() {
        return stateTime;
    }

    public void setStateTime(float stateTime) {
        this.stateTime = stateTime;
    }

    /**
     * How far the channel being evaluated stands between the keyframe behind it and the one ahead.
     *
     * A keyframe's own expression may read this, so the channel sets it before evaluating either
     * end of the segment it is crossing. Zero everywhere else, which is where a keyframe sits.
     */
    public float keyFrameLerpTime() {
        return keyFrameLerpTime;
    }

    public void setKeyFrameLerpTime(float keyFrameLerpTime) {
        this.keyFrameLerpTime = keyFrameLerpTime;
    }
}
