// The @minecraft/server-ui surface.
//
// Bedrock's forms are server-authored client screens. Java has nothing equivalent, so the builders
// and the observables are real here and `show` reports itself as unsupported and resolves cancelled.
// A pack that offers a settings form therefore loads and runs; the form does not open.

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

/** Collects what a form would show, so the pack's builder chain runs to completion. */
class FormBuilder {
    constructor(kind) {
        this.kind = kind;
        this.elements = [];
    }

    #add(type, args) {
        this.elements.push({ type, args });
        return this;
    }

    title(...args) {
        return this.#add("title", args);
    }

    body(...args) {
        return this.#add("body", args);
    }

    Header(...args) {
        return this.#add("header", args);
    }

    Label(...args) {
        return this.#add("label", args);
    }

    Divider(...args) {
        return this.#add("divider", args);
    }

    Spacer(...args) {
        return this.#add("spacer", args);
    }

    Button(...args) {
        return this.#add("button", args);
    }

    Toggle(...args) {
        return this.#add("toggle", args);
    }

    Slider(...args) {
        return this.#add("slider", args);
    }

    Dropdown(...args) {
        return this.#add("dropdown", args);
    }

    TextField(...args) {
        return this.#add("textField", args);
    }

    submitButton(...args) {
        return this.#add("submitButton", args);
    }

    state() {
        return this;
    }

    close() {
    }

    show() {
        bridge.unsupported(this.kind + ".show");
        return Promise.resolve({ canceled: true, cancelationReason: FormCancelationReason.UserClosed });
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

export class MessageFormData extends FormBuilder {
    constructor() {
        super("MessageFormData");
    }
}
