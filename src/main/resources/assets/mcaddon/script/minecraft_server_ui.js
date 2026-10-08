// The @minecraft/server-ui surface.
//
// Bedrock's forms are server-authored client screens, which Java has nothing equivalent to, so this
// mod builds one: the server describes the form, the client draws it, and the answer comes back to
// the promise the pack is waiting on.
//
// Two generations of the API are in the wild and both are served. The 1.x classes build a
// description, are shown to a player and read their answer out of `formValues`. The 2.x `CustomForm`
// and `MessageBox` take the player up front and carry their values in observables the screen writes
// back into. A 2.x form follows every observable it reads and is described to the screen again when
// one changes, and a `CustomForm` button runs its handler with the form left open. Where a call was respelled between the two, the arguments themselves say which one the
// pack meant, so no pack has to declare which generation it was written against.

const bridge = globalThis.__mcaddon_bridge__;

function unsupported(member, answer) {
    bridge.unsupported(member);
    return answer;
}

export const FormCancelationReason = Object.freeze({
    UserBusy: "UserBusy", UserClosed: "UserClosed",
});

export const DataDrivenScreenClosedReason = Object.freeze({
    ClientClosed: "ClientClosed", ServerClosed: "ServerClosed", UserBusy: "UserBusy",
});

export const FormRejectReason = Object.freeze({
    MalformedResponse: "MalformedResponse",
    PlayerQuit: "PlayerQuit",
    ServerShutdown: "ServerShutdown",
});

export const FormVisibilityErrorReason = Object.freeze({
    AlreadyShowing: "AlreadyShowing",
    NotShowing: "NotShowing",
});

export const TextFilteringError = Object.freeze({
    DisabledByPlayer: "DisabledByPlayer",
    TextProcessorServiceUnreachable: "TextProcessorServiceUnreachable",
    Unknown: "Unknown",
});

/** Observables a pack declared the client may not write to, whose inputs are drawn disabled. */
const readOnly = new WeakSet();

class Observable {
    #data;
    #listeners = [];

    constructor(data, options) {
        this.#data = data;
        if (options?.clientWritable === false) readOnly.add(this);
    }

    getData() {
        return this.#data;
    }

    setData(data) {
        if (data === this.#data) return;
        this.#data = data;
        this.#listeners.slice().forEach(listener => listener(data));
    }

    subscribe(callback) {
        this.#listeners.push(callback);
        return callback;
    }

    unsubscribe(callback) {
        const index = this.#listeners.indexOf(callback);
        if (index < 0) return false;
        this.#listeners.splice(index, 1);
        return true;
    }
}

export class ObservableBoolean extends Observable {
}

export class ObservableNumber extends Observable {
}

export class ObservableString extends Observable {
    /** This mod runs no text filtering service, so the text comes back as the player typed it. */
    getFilteredText(player) {
        return Promise.resolve(unsupported("ObservableString.getFilteredText", this.getData()));
    }
}

export class ObservableUIRawMessage extends Observable {
}

/**
 * The observables the description being built has read so far, so a showing form can follow every
 * one of them. Undefined outside a build.
 */
let reading;

/** A value a pack passed as an observable, read at the moment it is needed. */
function plain(value) {
    if (!(value instanceof Observable)) return value;
    reading?.add(value);
    return value.getData();
}

function text(value) {
    const resolved = plain(value);
    if (typeof resolved === "string") return resolved;
    if (Array.isArray(resolved)) return resolved.map(text).join("");
    if (resolved?.text !== undefined) return resolved.text;
    if (resolved?.translate !== undefined) return bridge.translate(resolved.translate);
    if (resolved?.rawtext !== undefined) return text(resolved.rawtext);
    return resolved === undefined ? "" : String(resolved);
}

/**
 * Whether an argument is something to read as text.
 *
 * A 1.x default value and a 2.x options bag arrive in the same position, and this is what tells them
 * apart: a default value is a string, a RawMessage or an observable holding one.
 */
function isText(value) {
    return typeof value === "string" || Array.isArray(value) || value instanceof Observable
        || value?.text !== undefined || value?.translate !== undefined || value?.rawtext !== undefined;
}

const pending = new Map();
let nextFormId = 1;

/** The member a press or a change on a form that stays open is handed to. */
const answerOpen = Symbol("answerOpen");

function settle(formId, response) {
    const waiting = pending.get(formId);
    if (waiting === undefined) return;
    pending.delete(formId);
    bridge.formSettled(formId);
    waiting.resolve(response);
}

/** The answer for a form the server took away rather than the player. */
function serverClosed() {
    return {
        canceled: true,
        cancelationReason: FormCancelationReason.UserClosed,
        closeReason: DataDrivenScreenClosedReason.ServerClosed,
    };
}

/**
 * Called from Java with what the client sent. An answer marked `open` is a press or a change on a
 * form that stays on the screen; anything else is the form going away.
 */
globalThis.__mcaddon_forms__ = {
    respond(formId, response) {
        const parsed = JSON.parse(response);
        if (parsed.open === true) {
            pending.get(formId)?.form[answerOpen](parsed);
        } else {
            settle(formId, parsed);
        }
    },
};

/**
 * Collects what a form shows, and describes it to the screen again whenever it changes.
 *
 * Every form class differs in how a pack builds it and in what its answer looks like, and not in
 * what reaches the screen, so one builder collects for all of them and each class shapes the answer
 * its own way in `show`.
 *
 * A component is kept as a way of describing it rather than as its description, because any part of
 * it may be an observable. Each one's place in `components` is its id, which the screen answers
 * with, so a press is matched to the button that was drawn even after the layout around it moved.
 */
class FormBuilder {
    #buttons = [];
    #followed = new Set();
    #sent = undefined;
    #scheduled = false;

    constructor(player, title) {
        this.player = player;
        this.heading = title;
        this.components = [];
        this.showing = false;
        this.formId = 0;
    }

    #add(type, describe, fields = {}) {
        this.components.push({ type, describe, ...fields });
        return this;
    }

    /**
     * Reports the options a component carries that the screen has no room for. Visibility and the
     * disabled state are read each time the form is described, because either may be an observable.
     */
    #options(component, options) {
        if (options === undefined || options === null) return undefined;

        const member = this.constructor.name + "." + component + " ";
        if (options.tooltip !== undefined) unsupported(member + "tooltip");
        if (options.description !== undefined) unsupported(member + "description");
        return options;
    }

    /**
     * Describes every component that is visible, in the order they were added, and collects every
     * observable the description read on the way.
     */
    #describe() {
        reading = new Set();
        try {
            const elements = this.components.flatMap((component, id) => {
                if (plain(component.options?.visible) === false) return [];

                const element = { type: component.type, id, ...component.describe() };
                const readOnlyTarget = component.target !== undefined && readOnly.has(component.target);
                if (plain(component.options?.disabled) === true || readOnlyTarget) element.disabled = true;
                if (component.target !== undefined) element.bound = true;
                if (component.click !== undefined) element.stay = true;
                return [element];
            });
            return { description: { title: text(this.heading), elements }, observed: reading };
        } finally {
            reading = undefined;
        }
    }

    /**
     * Sends the description, and follows every observable it read so a change reaches the screen.
     * An update the screen already shows is not sent, which is what an input's own value written
     * back into its observable comes to.
     */
    #publish(update) {
        const { description, observed } = this.#describe();
        observed.forEach(observable => {
            if (this.#followed.has(observable)) return;
            this.#followed.add(observable);
            observable.subscribe(this.#changed);
        });

        const encoded = JSON.stringify(description);
        if (update && encoded === this.#sent) return true;
        this.#sent = encoded;
        return bridge.showForm(this.player?.id ?? "", this.formId,
            update ? JSON.stringify({ ...description, update: true }) : encoded);
    }

    /** One update per burst of changes, because a handler usually sets several observables at once. */
    #changed = () => {
        if (this.#scheduled) return;
        this.#scheduled = true;
        Promise.resolve().then(() => {
            this.#scheduled = false;
            if (pending.has(this.formId)) this.#publish(true);
        });
    };

    #unfollow() {
        this.#followed.forEach(observable => observable.unsubscribe(this.#changed));
        this.#followed.clear();
    }

    /** Writes what the screen's inputs hold into whatever the form bound to them. */
    #write(values) {
        Object.entries(values ?? {}).forEach(([id, value]) => {
            const component = this.components[Number(id)];
            if (component?.target === undefined || readOnly.has(component.target)) return;
            component.target.setData(component.values?.[value] ?? value);
        });
    }

    [answerOpen](response) {
        this.#write(response.values);
        this.components[response.button]?.click?.();
    }

    title(value) {
        this.heading = value;
        return this;
    }

    body(value) {
        return this.#add("label", () => ({ text: text(value) }));
    }

    header(value, options) {
        return this.#add("header", () => ({ text: text(value) }), { options: this.#options("header", options) });
    }

    label(value, options) {
        return this.#add("label", () => ({ text: text(value) }), { options: this.#options("label", options) });
    }

    divider(options) {
        return this.#add("divider", () => ({}), { options: this.#options("divider", options) });
    }

    spacer(options) {
        return this.#add("spacer", () => ({}), { options: this.#options("spacer", options) });
    }

    /**
     * Both generations of a button, told apart by the second argument: a function there is the 2.x
     * handler the press calls with the form left open, anything else is the 1.x path to the icon
     * beside the label, whose press answers the form.
     */
    button(label, onClick, options) {
        const click = typeof onClick === "function" ? onClick : undefined;
        const icon = click === undefined ? text(onClick) : "";
        if (icon !== "") unsupported(this.constructor.name + ".button icon");
        return this.#add("button", () => ({ text: text(label), icon }),
            { options: this.#options("button", options), click });
    }

    /** The way out of a form that counts as the player closing it rather than answering it. */
    closeButton() {
        return this.#add("button", () => ({ text: "Close", icon: "" }));
    }

    submitButton(label) {
        return this.#add("submit", () => ({ text: text(label) }));
    }

    button1(label, tooltip) {
        if (tooltip !== undefined) unsupported(this.constructor.name + ".button1 tooltip");
        this.#buttons[0] = label;
        return this.#positionButtons();
    }

    button2(label, tooltip) {
        if (tooltip !== undefined) unsupported(this.constructor.name + ".button2 tooltip");
        this.#buttons[1] = label;
        return this.#positionButtons();
    }

    /**
     * A two-button question names its buttons by position rather than adding them in turn, so both
     * are laid out again whenever either is named and the order holds whichever was set first.
     */
    #positionButtons() {
        this.components = this.components.filter(component => component.type !== "button");
        this.#buttons.forEach(label => this.#add("button", () => ({ text: text(label ?? ""), icon: "" })));
        return this;
    }

    /**
     * Both generations of a toggle, told apart by the second argument: an observable there is the
     * 2.x state the toggle writes into, a boolean is the 1.x starting state.
     */
    toggle(label, toggled, options) {
        const target = toggled instanceof Observable ? toggled : undefined;
        const settings = target === undefined && typeof toggled !== "boolean" ? toggled : options;
        const start = () => (target === undefined
            ? (typeof toggled === "boolean" ? toggled : plain(settings?.defaultValue))
            : plain(target)) === true;
        return this.#add("toggle", () => ({ text: text(label), value: start() }),
            { options: this.#options("toggle", settings), target });
    }

    /**
     * Both generations of a slider, told apart by the second argument: an observable there is the
     * 2.x value the slider writes into, a number is the 1.x minimum and the arguments after it shift
     * along by one.
     */
    slider(label, value, minimum, maximum, options) {
        const target = value instanceof Observable ? value : undefined;
        const trailing = target === undefined ? maximum : options;
        const settings = typeof trailing === "number" ? undefined : trailing;
        const step = plain(typeof trailing === "number" ? trailing : settings?.step ?? settings?.valueStep) ?? 1;

        // The screen's slider runs the whole range, so a pack asking for coarser stops gets finer ones.
        if (step !== 1) unsupported(this.constructor.name + ".slider step");

        return this.#add("slider", () => {
            const low = plain(target === undefined ? value : minimum) ?? 0;
            const high = plain(target === undefined ? minimum : maximum) ?? 1;
            const start = target === undefined
                ? plain(settings === undefined ? options : settings.defaultValue) ?? low
                : plain(target);
            return { text: text(label), min: low, max: high, step, value: start };
        }, { options: this.#options("slider", settings), target });
    }

    /**
     * Both generations of a dropdown, told apart by the second argument: an observable there is the
     * 2.x value the selection writes into, an array is the 1.x list of items.
     */
    dropdown(label, value, items, options) {
        const older = Array.isArray(value);
        const target = value instanceof Observable ? value : undefined;
        const source = older ? value : items;
        const choices = Array.isArray(source) ? source : [];
        const trailing = older ? items : options;
        const settings = typeof trailing === "number" ? undefined : trailing;

        if (choices.some(item => item?.description !== undefined)) {
            unsupported(this.constructor.name + ".dropdown item description");
        }

        // A 2.x item carries the number the selection stands for, so the answer is looked up rather
        // than taken as the index a 1.x item list answers with.
        const answers = target === undefined ? undefined : choices.map(item => plain(item?.value));
        return this.#add("dropdown", () => ({
            text: text(label),
            options: choices.map(item => text(item?.label === undefined ? item : item.label)),
            value: target === undefined
                ? plain(typeof trailing === "number" ? trailing : settings?.defaultValueIndex) ?? 0
                : Math.max(0, answers.indexOf(plain(target))),
        }), { options: this.#options("dropdown", settings), target, values: answers });
    }

    /**
     * Both generations of a text field, told apart by the second argument: an observable string
     * there is the 2.x text the field writes into, anything else is the 1.x placeholder.
     */
    textField(label, value, options) {
        const target = value instanceof Observable ? value : undefined;
        const settings = isText(options) ? undefined : options;
        return this.#add("textField", () => ({
            text: text(label),
            placeholder: target === undefined ? text(value) : "",
            value: target === undefined ? text(settings === undefined ? options : settings.defaultValue)
                : text(target),
        }), { options: this.#options("textField", settings), target });
    }

    image(source, pack, options) {
        return unsupported(this.constructor.name + ".image", this);
    }

    multiButtonRow(buttons, options) {
        return unsupported(this.constructor.name + ".multiButtonRow", this);
    }

    isShowing() {
        return this.showing;
    }

    /** Takes the form away from the player, which resolves the promise the pack is waiting on. */
    close() {
        if (!this.showing) {
            throw new FormVisibilityError(String(this.formId), FormVisibilityErrorReason.NotShowing);
        }

        bridge.closeForm(this.player?.id ?? "");
        settle(this.formId, serverClosed());
    }

    /**
     * Shows the form and waits for it to go away.
     *
     * What the inputs held is written into whatever the form bound to them, and the handler of the
     * button that answered the form is called. A 1.x pack reads its answer out of `selection` and
     * `formValues`, which the screen counts among what it drew, the order Bedrock uses.
     */
    show(player) {
        if (this.showing) {
            throw new FormVisibilityError(String(this.formId), FormVisibilityErrorReason.AlreadyShowing);
        }

        if (player !== undefined) this.player = player;
        const id = nextFormId++;
        this.formId = id;

        if (!this.#publish(false)) {
            this.#unfollow();
            return Promise.resolve({
                canceled: true,
                cancelationReason: FormCancelationReason.UserBusy,
                closeReason: DataDrivenScreenClosedReason.UserBusy,
            });
        }

        this.showing = true;
        return new Promise(resolve => pending.set(id, { resolve, form: this })).then(response => {
            try {
                this.#unfollow();
                this.#write(response.values);
                if (!response.canceled) this.components[response.button]?.click?.();
                return response;
            } finally {
                // A button's handler runs while the form still counts as open, because closing the
                // form it was pressed on is the ordinary first thing such a handler does.
                this.showing = false;
            }
        });
    }
}

/** What every form answers with: whether the player closed it, and why if they did. */
export class FormResponse {
    canceled = false;
    cancelationReason = undefined;
}

export class ActionFormResponse extends FormResponse {
    selection = undefined;
}

export class MessageFormResponse extends FormResponse {
    selection = undefined;
}

export class ModalFormResponse extends FormResponse {
    formValues = undefined;
}

function responseOf(kind, fields) {
    const response = new kind();
    response.canceled = fields.canceled === true;
    if (fields.cancelationReason !== undefined) response.cancelationReason = fields.cancelationReason;
    if (fields.selection !== undefined) response.selection = fields.selection;
    if (fields.formValues !== undefined) response.formValues = fields.formValues;
    return response;
}

export class ActionFormData extends FormBuilder {
    show(player) {
        return super.show(player).then(response => responseOf(ActionFormResponse, response));
    }
}

export class MessageFormData extends FormBuilder {
    show(player) {
        return super.show(player).then(response => responseOf(MessageFormResponse, response));
    }
}

export class ModalFormData extends FormBuilder {
    show(player) {
        return super.show(player).then(response => responseOf(ModalFormResponse, response));
    }
}

/**
 * A form whose values live in the observables a pack bound to its inputs.
 *
 * It takes its player when it is built rather than when it is shown, and its answer is only how the
 * screen went away: what the player chose is in the observables by the time the promise resolves.
 */
export class CustomForm extends FormBuilder {
    show(player) {
        return super.show(player)
            .then(response => response.closeReason ?? DataDrivenScreenClosedReason.ClientClosed);
    }
}

/** A two-button question, built and answered the way `CustomForm` is. */
export class MessageBox extends FormBuilder {
    show(player) {
        return super.show(player).then(response => ({
            closeReason: response.closeReason ?? DataDrivenScreenClosedReason.ClientClosed,
            selection: response.selection,
        }));
    }
}

/** Every form open on a player, so a pack can shut them without knowing which is showing. */
export class UIManager {
    closeAllForms(player) {
        const id = player?.id ?? "";
        bridge.closeForm(id);
        Array.from(pending.entries())
            .filter(([, waiting]) => (waiting.form.player?.id ?? "") === id)
            .forEach(([formId]) => settle(formId, serverClosed()));
    }
}

export const uiManager = new UIManager();

export class FormRejectError extends Error {
    constructor(reason) {
        super("The form was rejected: " + reason);
        this.reason = reason;
    }
}

export class FormVisibilityError extends Error {
    constructor(formId, reason) {
        super("Form " + formId + " is " + reason);
        this.formId = formId;
        this.reason = reason;
    }
}

export class InvalidFormError extends Error {
    constructor(formId) {
        super("There is no form " + formId);
        this.formId = formId;
    }
}

export class InvalidFormModificationError extends Error {
    constructor(formId) {
        super("Form " + formId + " is showing and cannot be changed");
        this.formId = formId;
    }
}

export class InvalidObservableError extends Error {
}

export class PlayerLeftError extends Error {
    constructor(formId) {
        super("The player showing form " + formId + " left the game");
        this.formId = formId;
    }
}
