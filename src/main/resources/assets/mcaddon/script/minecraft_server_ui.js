// The @minecraft/server-ui surface.
//
// Bedrock's forms are server-authored client screens, which Java has nothing equivalent to, so this
// mod builds one: the server describes the form, the client draws it, and the answer comes back to
// the promise the pack is waiting on.

const bridge = globalThis.__mcaddon_bridge__;

export const FormCancelationReason = Object.freeze({
    UserBusy: "UserBusy", UserClosed: "UserClosed",
});

export const DataDrivenScreenClosedReason = Object.freeze({
    ServerClosed: "ServerClosed", UserBusy: "UserBusy", UserClosed: "UserClosed",
});

class Observable {
    constructor(value) {
        this.value = value;
        this.#listeners = [];
    }

    #listeners;

    subscribe(callback) {
        this.#listeners.push(callback);
        return callback;
    }

    unsubscribe(callback) {
        const index = this.#listeners.indexOf(callback);
        if (index >= 0) this.#listeners.splice(index, 1);
    }

    set(value) {
        this.value = value;
        for (const listener of this.#listeners.slice()) listener(value);
    }
}

export class ObservableBoolean extends Observable {
}

export class ObservableNumber extends Observable {
}

export class ObservableString extends Observable {
}

export class ObservableUIRawMessage extends Observable {
}

/** A value a pack passed as an observable, read at the moment the form is shown. */
function plain(value) {
    return value instanceof Observable ? value.value : value;
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

const pending = new Map();
let nextFormId = 1;

/** Called from Java when the player closes the screen. */
globalThis.__mcaddon_forms__ = {
    respond(formId, response) {
        const resolve = pending.get(formId);
        if (resolve === undefined) return;
        pending.delete(formId);
        resolve(JSON.parse(response));
    },
};

/**
 * Collects what a form shows, then hands it over as one description.
 *
 * Bedrock's three older form classes and its newer CustomForm differ in how a pack builds them and
 * not in what reaches the screen, so one builder serves all four.
 */
class FormBuilder {
    constructor(kind) {
        this.kind = kind;
        this.elements = [];
        this.heading = "";
        this.bound = [];
    }

    #add(type, fields) {
        this.elements.push({ type, ...fields });
        return this;
    }

    title(value) {
        this.heading = text(value);
        return this;
    }

    body(value) {
        return this.#add("label", { text: text(value) });
    }

    Header(value) {
        return this.#add("header", { text: text(value) });
    }

    header(value) {
        return this.Header(value);
    }

    Label(value) {
        return this.#add("label", { text: text(value) });
    }

    label(value) {
        return this.Label(value);
    }

    Divider() {
        return this.#add("divider", {});
    }

    divider() {
        return this.Divider();
    }

    Spacer() {
        return this.#add("spacer", {});
    }

    spacer() {
        return this.Spacer();
    }

    Button(label, icon) {
        return this.#add("button", { text: text(label), icon: plain(icon) ?? "" });
    }

    button(label, icon) {
        return this.Button(label, icon);
    }

    Toggle(label, value) {
        this.bound.push(value);
        return this.#add("toggle", { text: text(label), value: plain(value) === true });
    }

    toggle(label, value) {
        return this.Toggle(label, value);
    }

    Slider(label, minimum, maximum, step, value) {
        this.bound.push(value);
        return this.#add("slider", {
            text: text(label), min: plain(minimum) ?? 0, max: plain(maximum) ?? 1,
            step: plain(step) ?? 1, value: plain(value) ?? plain(minimum) ?? 0,
        });
    }

    slider(label, minimum, maximum, step, value) {
        return this.Slider(label, minimum, maximum, step, value);
    }

    Dropdown(label, options, value) {
        this.bound.push(value);
        return this.#add("dropdown", {
            text: text(label),
            options: (plain(options) ?? []).map(text),
            value: plain(value) ?? 0,
        });
    }

    dropdown(label, options, value) {
        return this.Dropdown(label, options, value);
    }

    TextField(label, placeholder, value) {
        this.bound.push(value);
        return this.#add("textField", {
            text: text(label), placeholder: text(placeholder), value: text(value),
        });
    }

    textField(label, placeholder, value) {
        return this.TextField(label, placeholder, value);
    }

    submitButton(label) {
        return this.#add("submit", { text: text(label) });
    }

    state() {
        return this;
    }

    close() {
        bridge.closeForm(this.shownTo ?? "");
    }

    /**
     * Shows the form and waits for the answer.
     *
     * The values come back in the order the inputs were added, which is the order Bedrock's
     * `formValues` uses, and anything the pack passed as an observable is updated with its answer.
     */
    show(player) {
        const id = nextFormId++;
        this.shownTo = player?.id ?? "";

        const description = JSON.stringify({ title: this.heading, elements: this.elements });
        if (!bridge.showForm(this.shownTo, id, description)) {
            return Promise.resolve({
                canceled: true, cancelationReason: FormCancelationReason.UserBusy,
            });
        }

        return new Promise(resolve => pending.set(id, resolve)).then(response => {
            if (!response.canceled) {
                const values = response.formValues ?? [];
                this.bound.forEach((target, index) => {
                    if (target instanceof Observable && index < values.length) target.set(values[index]);
                });
            }
            return response;
        });
    }
}

export class CustomForm extends FormBuilder {
    constructor() {
        super("CustomForm");
    }
}

export class ActionFormData extends FormBuilder {
    constructor() {
        super("ActionFormData");
    }
}

export class ModalFormData extends FormBuilder {
    constructor() {
        super("ModalFormData");
    }
}

/**
 * A two-button question, which names its buttons by position rather than by adding them in turn.
 *
 * The answer still comes back as the index of the button pressed, so a message box is a form with
 * exactly two buttons and the numbered names put them in the right order whichever is set first.
 */
export class MessageFormData extends FormBuilder {
    #buttons = [];

    constructor() {
        super("MessageFormData");
    }

    button1(label) {
        this.#buttons[0] = text(label);
        return this.#rebuild();
    }

    button2(label) {
        this.#buttons[1] = text(label);
        return this.#rebuild();
    }

    #rebuild() {
        this.elements = this.elements.filter(element => element.type !== "button");
        this.#buttons.forEach(label => this.elements.push({ type: "button", text: label ?? "", icon: "" }));
        return this;
    }
}

/** The same two-button question under the name a pack written against the older API imports. */
export class MessageBox extends MessageFormData {
}

/** Every form open on a player, so a pack can shut them without knowing which is showing. */
export const uiManager = Object.freeze({
    closeAllForms(player) {
        bridge.closeForm(player?.id ?? "");
    },
});

export class UIManager {
    closeAllForms(player) {
        uiManager.closeAllForms(player);
    }
}

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

/** What a pack gets back from a form, named so it can be tested with `instanceof`. */
export class FormResponse {
}

export class ActionFormResponse extends FormResponse {
}

export class MessageFormResponse extends FormResponse {
}

export class ModalFormResponse extends FormResponse {
}

export class FormRejectError extends Error {
}

export class FormVisibilityError extends Error {
}

export class InvalidFormError extends Error {
}

export class InvalidFormModificationError extends Error {
}

export class InvalidObservableError extends Error {
}

export class PlayerLeftError extends Error {
}
