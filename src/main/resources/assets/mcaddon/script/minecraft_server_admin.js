// The @minecraft/server-admin surface.
//
// A dedicated server's `config/` folder hands each pack's script variables and secrets:
// `config/default/` for every script module and `config/<script module uuid>/` for one, the module's
// keys over the default's. They are read as the scripts start and again on `/reloadconfig` or
// `dedicatedServer.reloadScriptingConfig()`. A secret's value never enters the script: `secrets.get`
// hands back a `SecretString` that only names it.
//
// Every pack's script imports this one module, so the pack asking is read off the calling frame,
// whose file sits under the pack's header uuid in the guest's file system.
//
// `dedicatedServer` exists on a dedicated server and is undefined in a world a player hosts, the
// way Bedrock leaves it undefined outside its dedicated server.

import { EngineError, InvalidArgumentError } from "@minecraft/common";

const bridge = globalThis.__mcaddon_bridge__;

function unsupported(member, answer) {
    bridge.unsupported(member);
    return answer;
}

const PACK_FOLDER = /\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\//i;

/** The header uuid of the pack whose script called, or null when no frame names one. */
function callingPack() {
    return PACK_FOLDER.exec(new Error().stack ?? "")?.[1] ?? null;
}

export class SecretString {
    // Nothing a script can read: Bedrock resolves a secret only where it is used, inside the engine.
    constructor(value) {
    }
}

export class ServerVariables {
    get names() {
        return Object.keys(JSON.parse(bridge.admin.variables(callingPack())));
    }

    get(name) {
        return JSON.parse(bridge.admin.variables(callingPack()))[name];
    }
}

export class ServerSecrets {
    get names() {
        return Array.from(bridge.admin.secretNames(callingPack()));
    }

    get(name) {
        return Array.from(bridge.admin.secretNames(callingPack())).includes(name) ? new SecretString("") : undefined;
    }
}

export class AllowList {
    get enabled() { return unsupported("AllowList.enabled", false); }
    set enabled(value) { unsupported("AllowList.enabled (write)"); }
    get entries() { return unsupported("AllowList.entries", []); }
    add(playerDetails, ignorePlayerLimit) { unsupported("AllowList.add"); }
    clear() { unsupported("AllowList.clear"); }
    contains(playerDetails) { return unsupported("AllowList.contains", false); }
    reloadFile() { unsupported("AllowList.reloadFile"); }
    remove(playerDetails) { unsupported("AllowList.remove"); }
}

export class LevelStorageQuerySnapshotFile {
    fileName = "";
    fileSize = 0;
}

export class LevelStorage {
    saveHold() { return Promise.resolve(unsupported("LevelStorage.saveHold")); }
    saveQuery() { return Promise.resolve(unsupported("LevelStorage.saveQuery", [])); }
    saveResume() { return Promise.resolve(unsupported("LevelStorage.saveResume")); }
}

export class DedicatedServerUtils {
    allowList = new AllowList();
    levelStorage = new LevelStorage();

    reloadCDNConfig() {
        unsupported("DedicatedServerUtils.reloadCDNConfig");
    }

    reloadPermissions() {
        if (bridge.admin.reloadPermissions() !== null) throw new EngineError("The operator list could not be read.");
    }

    reloadScriptingConfig() {
        bridge.admin.reloadScriptingConfig();
    }

    stopServer() {
        unsupported("DedicatedServerUtils.stopServer");
    }
}

export class AsyncPlayerJoinBeforeEvent {
    name = "";
    persistentId = "";
    splitScreenSlot = undefined;
    allowJoin() { unsupported("AsyncPlayerJoinBeforeEvent.allowJoin"); }
    disallowJoin(reason) { unsupported("AsyncPlayerJoinBeforeEvent.disallowJoin"); }
    disconnect(reason) { unsupported("AsyncPlayerJoinBeforeEvent.disconnect"); }
    isValid() { return false; }
}

export class AsyncPlayerJoinBeforeEventSignal {
    subscribe(callback) {
        unsupported("AsyncPlayerJoinBeforeEventSignal.subscribe");
        return callback;
    }

    unsubscribe(callback) {
        return unsupported("AsyncPlayerJoinBeforeEventSignal.unsubscribe", false);
    }
}

export class AdminBeforeEvents {
    asyncPlayerJoin = new AsyncPlayerJoinBeforeEventSignal();
}

export class AllowListFileReloadError extends Error {
}

export class AllowListModificationError extends Error {
}

export class CannotDeopPlayerError extends Error {
}

export class CannotKickPlayerError extends Error {
}

export class DisconnectedError extends Error {
    constructor(id) {
        super("The player " + id + " has disconnected.");
        this.id = id;
    }
}

export class LevelStorageSaveStateChangeError extends Error {
}

export class PlayerAlreadyOpError extends Error {
}

const OPERATOR_ERRORS = {
    InvalidArgumentError: () => new InvalidArgumentError("player", "Player", "not on this server", 0),
    PlayerAlreadyOpError: () => new PlayerAlreadyOpError("The player is already an operator."),
    CannotDeopPlayerError: () => new CannotDeopPlayerError("The player is not an operator."),
};

function setOperator(player, operator) {
    const refused = bridge.admin.setOperator(player?.id ?? "", operator);
    if (refused !== null) throw OPERATOR_ERRORS[refused]();
}

export function opPlayer(player) {
    setOperator(player, true);
}

export function deopPlayer(player) {
    setOperator(player, false);
}

export function kickPlayer(player, reason) {
    unsupported("kickPlayer");
}

export function transferPlayer(player, options) {
    unsupported("transferPlayer");
}

export const beforeEvents = new AdminBeforeEvents();
export const dedicatedServer = bridge.admin.dedicated() ? new DedicatedServerUtils() : undefined;
export const secrets = new ServerSecrets();
export const variables = new ServerVariables();
