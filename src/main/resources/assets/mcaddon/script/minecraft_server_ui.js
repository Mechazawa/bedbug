// The @minecraft/server-ui surface.
//
// Bedrock's forms are server-authored client screens, which Java has nothing equivalent to, so this
// mod builds one: the server describes the form, the client draws it, and the answer comes back to
// the promise the pack is waiting on.
//
// Two generations of the API are in the wild and both are served. The 1.x classes build a
// description, are shown to a player and read their answer out of `formValues`. The 2.x `CustomForm`
// and `MessageBox` take the player up front and carry their values in observables the answer writes
// back into. Where a call was respelled between the two, the arguments themselves say which one the
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

/**
 * Observables a pack declared the client may not write to.
 *
 * The screen writes every input it drew back into whatever the form bound there, so this is the one
 * case that cannot be served, and binding one of these to an input is reported when it happens.
 */
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

/** A value a pack passed as an observable, read at the moment it is needed. */
function plain(value) {
    return value instanceof Observable ? value.getData() : value;
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

function settle(formId, response) {
    const waiting = pending.get(formId);
    if (waiting === undefined) return;
    pending.delete(formId);
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

/** Called from Java when the player closes the screen. */
globalThis.__mcaddon_forms__ = {
    respond(formId, response) {
        settle(formId, JSON.parse(response));
    },
};

/**
 * Collects what a form shows, then hands it over as one description.
 *
 * Every form class differs in how a pack builds it and in what its answer looks like, and not in
 * what reaches the screen, so one builder collects for all of them and each class shapes the answer
 * its own way in `show`.
 */
class FormBuilder {
    #buttons = [];

    constructor(player, title) {
        this.player = player;
        this.heading = text(title);
        this.elements = [];
        this.bound = [];
        this.clicks = [];
        this.showing = false;
        this.formId = 0;
    }

    #add(type, fields) {
        this.elements.push({ type, ...fields });
        return this;
    }

    /**
     * Reads the options a component carries, and answers whether the component is drawn at all.
     *
     * A hidden component is left out of the description, and its value is left out of the answer
     * with it, which keeps the answer in step with the inputs the screen drew. The rest of the
     * options describe styling this screen has no room for, so each is reported where it is asked
     * for rather than dropped.
     */
    #options(component, options) {
        if (options === undefined || options === null) return true;

        const member = this.constructor.name + "." + component + " ";
        if (options.tooltip !== undefined) unsupported(member + "tooltip");
        if (options.description !== undefined) unsupported(member + "description");
        if (options.disabled !== undefined && options.disabled !== false) unsupported(member + "disabled");
        if (options.visible instanceof Observable) unsupported(member + "visible");
        return plain(options.visible) !== false;
    }

    /** Remembers what an input writes its answer into, in the order the screen reports answers. */
    #bind(component, target, values) {
        if (target !== undefined && readOnly.has(target)) {
            unsupported(this.constructor.name + "." + component + " clientWritable");
        }
        this.bound.push({ target, values });
    }

    title(value) {
        this.heading = text(value);
        return this;
    }

    body(value) {
        return this.#add("label", { text: text(value) });
    }

    header(value, options) {
        return this.#options("header", options) ? this.#add("header", { text: text(value) }) : this;
    }

    label(value, options) {
        return this.#options("label", options) ? this.#add("label", { text: text(value) }) : this;
    }

    divider(options) {
        return this.#options("divider", options) ? this.#add("divider", {}) : this;
    }

    spacer(options) {
        return this.#options("spacer", options) ? this.#add("spacer", {}) : this;
    }

    /**
     * Both generations of a button, told apart by the second argument: a function there is the 2.x
     * handler the press calls, anything else is the 1.x path to the icon beside the label.
     */
    button(label, onClick, options) {
        const handler = typeof onClick === "function" ? onClick : undefined;
        const icon = handler === undefined ? text(onClick) : "";
        if (!this.#options("button", options)) return this;

        if (icon !== "") unsupported(this.constructor.name + ".button icon");
        this.clicks.push(handler);
        return this.#add("button", { text: text(label), icon });
    }

    /** The way out of a form that counts as the player closing it rather than answering it. */
    closeButton() {
        this.clicks.push(undefined);
        return this.#add("button", { text: "Close", icon: "" });
    }

    submitButton(label) {
        this.clicks.push(undefined);
        return this.#add("submit", { text: text(label) });
    }

    button1(label, tooltip) {
        if (tooltip !== undefined) unsupported(this.constructor.name + ".button1 tooltip");
        this.#buttons[0] = text(label);
        return this.#positionButtons();
    }

    button2(label, tooltip) {
        if (tooltip !== undefined) unsupported(this.constructor.name + ".button2 tooltip");
        this.#buttons[1] = text(label);
        return this.#positionButtons();
    }

    /**
     * A two-button question names its buttons by position rather than adding them in turn, so both
     * are laid out again whenever either is named and the order holds whichever was set first.
     */
    #positionButtons() {
        this.elements = this.elements.filter(element => element.type !== "button");
        this.#buttons.forEach(label => this.elements.push({ type: "button", text: label ?? "", icon: "" }));
        return this;
    }

    /**
     * Both generations of a toggle, told apart by the second argument: an observable there is the
     * 2.x state the toggle writes into, a boolean is the 1.x starting state.
     */
    toggle(label, toggled, options) {
        const bound = toggled instanceof Observable ? toggled : undefined;
        const settings = bound === undefined && typeof toggled !== "boolean" ? toggled : options;
        if (!this.#options("toggle", settings)) return this;

        const start = bound === undefined
            ? (typeof toggled === "boolean" ? toggled : plain(settings?.defaultValue)) === true
            : bound.getData() === true;
        this.#bind("toggle", bound);
        return this.#add("toggle", { text: text(label), value: start });
    }

    /**
     * Both generations of a slider, told apart by the second argument: an observable there is the
     * 2.x value the slider writes into, a number is the 1.x minimum and the arguments after it shift
     * along by one.
     */
    slider(label, value, minimum, maximum, options) {
        const bound = value instanceof Observable ? value : undefined;
        const low = plain(bound === undefined ? value : minimum) ?? 0;
        const high = plain(bound === undefined ? minimum : maximum) ?? 1;
        const trailing = bound === undefined ? maximum : options;
        const settings = typeof trailing === "number" ? undefined : trailing;
        if (!this.#options("slider", settings)) return this;

        const step = plain(typeof trailing === "number" ? trailing : settings?.step ?? settings?.valueStep) ?? 1;
        const start = bound === undefined
            ? plain(settings === undefined ? options : settings.defaultValue) ?? low
            : bound.getData();

        // The screen's slider runs the whole range, so a pack asking for coarser stops gets finer ones.
        if (step !== 1) unsupported(this.constructor.name + ".slider step");

        this.#bind("slider", bound);
        return this.#add("slider", { text: text(label), min: low, max: high, step, value: start });
    }

    /**
     * Both generations of a dropdown, told apart by the second argument: an observable there is the
     * 2.x value the selection writes into, an array is the 1.x list of items.
     */
    dropdown(label, value, items, options) {
        const older = Array.isArray(value);
        const bound = value instanceof Observable ? value : undefined;
        const source = older ? value : items;
        const choices = Array.isArray(source) ? source : [];
        const trailing = older ? items : options;
        const settings = typeof trailing === "number" ? undefined : trailing;
        if (!this.#options("dropdown", settings)) return this;

        if (choices.some(item => item?.description !== undefined)) {
            unsupported(this.constructor.name + ".dropdown item description");
        }

        // A 2.x item carries the number the selection stands for, so the answer is looked up rather
        // than taken as the index a 1.x item list answers with.
        const answers = bound === undefined ? undefined : choices.map(item => plain(item?.value));
        const labels = choices.map(item => text(item?.label === undefined ? item : item.label));
        const start = bound === undefined
            ? plain(typeof trailing === "number" ? trailing : settings?.defaultValueIndex) ?? 0
            : Math.max(0, answers.indexOf(bound.getData()));

        this.#bind("dropdown", bound, answers);
        return this.#add("dropdown", { text: text(label), options: labels, value: start });
    }

    /**
     * Both generations of a text field, told apart by the second argument: an observable string
     * there is the 2.x text the field writes into, anything else is the 1.x placeholder.
     */
    textField(label, value, options) {
        const bound = value instanceof Observable ? value : undefined;
        const settings = isText(options) ? undefined : options;
        if (!this.#options("textField", settings)) return this;

        const start = bound === undefined ? text(settings === undefined ? options : settings.defaultValue)
            : text(bound.getData());
        this.#bind("textField", bound);
        return this.#add("textField", {
            text: text(label), placeholder: bound === undefined ? text(value) : "", value: start,
        });
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
     * Shows the form and waits for the answer.
     *
     * The values come back in the order the inputs were added, which is the order Bedrock's
     * `formValues` uses, so each is written into whatever the form bound at that position and the
     * handler of the button that was pressed is called.
     */
    show(player) {
        if (this.showing) {
            throw new FormVisibilityError(String(this.formId), FormVisibilityErrorReason.AlreadyShowing);
        }

        if (player !== undefined) this.player = player;
        const id = nextFormId++;
        this.formId = id;

        const description = JSON.stringify({ title: this.heading, elements: this.elements });
        if (!bridge.showForm(this.player?.id ?? "", id, description)) {
            return Promise.resolve({
                canceled: true,
                cancelationReason: FormCancelationReason.UserBusy,
                closeReason: DataDrivenScreenClosedReason.UserBusy,
            });
        }

        this.showing = true;
        return new Promise(resolve => pending.set(id, { resolve, form: this })).then(response => {
            try {
                if (response.canceled) return response;

                const answers = response.formValues ?? [];
                this.bound.forEach((binding, index) => {
                    if (index < answers.length) binding.target?.setData(binding.values?.[answers[index]] ?? answers[index]);
                });
                this.clicks[response.selection]?.();
                return response;
            } finally {
                // A button's handler runs while the form still counts as open, because closing the
                // form it was pressed on is the ordinary first thing such a handler does.
                this.showing = false;
            }
        });
    }
}

/** What a pack gets back from a form, named so it can be tested with `instanceof`. */
export class FormResponse {
}

export class ActionFormResponse extends FormResponse {
}

export class MessageFormResponse extends FormResponse {
}

export class ModalFormResponse extends FormResponse {
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
