package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.McAddonPaths;
import at.ioexception.mcaddon.pack.PackOrder;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.pack.ReceivedArchives;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonRegistry;
import at.ioexception.mcaddon.resource.AddonPacks;
import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.sync.AddonManifest;
import at.ioexception.mcaddon.sync.Incoming;
import at.ioexception.mcaddon.sync.Transfer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * This client's visit to a server: the server's add-ons made the active set for the length of the
 * visit, and the installed set made active again once it ends.
 *
 * Every change to the registries and the active set is chained on one future that also waits for
 * the resource reload the change asked for, so no change overlaps a reload still reading the
 * registries, and a visit that ended before its turn came is dropped. The registries only grow: an
 * installed add-on the server lacks goes dormant for the visit and comes back when it ends.
 */
public final class AddonSession {
    private static CompletableFuture<Void> settled = CompletableFuture.completedFuture(null);

    /** The connection a transfer is under way on, and the transfer, or null between them. */
    private static volatile Visit current;
    private static volatile Incoming incoming;

    /** Whether the active set is other than the installed one, which is what a leave puts back. */
    private static boolean visiting;

    private AddonSession() {
    }

    /**
     * One connection's side of a transfer: where the client's answers go, and whether the
     * connection has ended.
     */
    private static final class Visit {
        private final java.util.function.Consumer<Transfer.Ready> answer;
        private volatile boolean closed;

        Visit(java.util.function.Consumer<Transfer.Ready> answer) {
            this.answer = answer;
        }

        void close() {
            closed = true;
        }

        void ready(boolean ok, String reason) {
            if (!closed) answer.accept(new Transfer.Ready(ok, reason));
        }
    }

    public static void register() {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
            at.ioexception.mcaddon.network.SyncChannels.Manifest.TYPE, (payload, context) -> {
                net.fabricmc.fabric.api.networking.v1.PacketSender sender = context.responseSender();
                Visit visit = new Visit(ready ->
                    sender.sendPacket(new at.ioexception.mcaddon.network.SyncChannels.Ready(ready)));
                current = visit;
                incoming = offered(payload.json(), visit, new Incoming.Link() {
                    @Override
                    public void want(List<Transfer.Wanted> wanted) {
                        sender.sendPacket(new at.ioexception.mcaddon.network.SyncChannels.Request(wanted));
                    }

                    @Override
                    public void acknowledge(Transfer.Wanted held) {
                        sender.sendPacket(new at.ioexception.mcaddon.network.SyncChannels.Acknowledged(held));
                    }

                    @Override
                    public void ready(Transfer.Ready ready) {
                        visit.ready(ready.ok(), ready.reason());
                    }
                });
            });
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(
            at.ioexception.mcaddon.network.SyncChannels.Piece.TYPE, (payload, context) -> {
                Incoming receiving = incoming;
                if (receiving != null) receiving.accept(payload.piece());
            });
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.DISCONNECT
            .register((handler, client) -> ended());
        //?}
        // Raised on the network thread when a remote connection drops, so the leave is handed over.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
            .register((handler, client) -> ended());
    }

    /** The connection a transfer belonged to has ended, in whatever phase it was. */
    private static void ended() {
        Visit visit = current;
        if (visit != null) visit.close();
        current = null;
        incoming = null;
        Platform.schedule(AddonSession::leave);
    }

    /**
     * Takes a server's manifest, fetches what it lacks, and once every archive is held makes the
     * server's add-ons active on the client thread.
     */
    private static Incoming offered(String json, Visit visit, Incoming.Link link) {
        AddonManifest manifest;
        try {
            manifest = AddonManifest.decode(json);
        } catch (IllegalArgumentException unreadable) {
            visit.ready(false, "This server's add-on list could not be read: " + unreadable.getMessage());
            return null;
        }
        Incoming incoming = new Incoming(manifest, ReceivedArchives.inCache(), link,
            whole -> Platform.schedule(() -> enter(whole, visit)));
        incoming.start(installedArchives(), freeSpace());
        return incoming;
    }

    /** Puts the installed add-ons back, once whatever change is under way has finished. */
    private static void leave() {
        settled = settled.thenCompose(ignored -> {
            if (!visiting) return CompletableFuture.<Void>completedFuture(null);
            visiting = false;
            ActiveAddons.activate(ActiveAddons.installed());
            McAddon.LOGGER.info("Restored the installed add-on set.");
            return Minecraft.getInstance().reloadResourcePacks();
        }).exceptionally(AddonSession::failed);
    }

    private static void enter(AddonManifest manifest, Visit visit) {
        settled = settled.thenCompose(ignored -> activate(manifest, visit)).exceptionally(failure -> {
            visit.ready(false, "The server's add-ons could not be loaded: " + failure.getMessage());
            return failed(failure);
        });
    }

    private static CompletableFuture<Void> activate(AddonManifest manifest, Visit visit) {
        CompletableFuture<Void> nothing = CompletableFuture.completedFuture(null);
        if (visit.closed) return nothing;
        // A world opened to LAN shares its add-ons with the server inside it, which the host never
        // changes; its own player is not sent a manifest, and this keeps it that way.
        if (Minecraft.getInstance().hasSingleplayerServer()) {
            visit.ready(true, "");
            return nothing;
        }
        Set<String> wanted = manifest.entries().stream().filter(AddonManifest.Entry::active)
            .map(AddonManifest.Entry::sha256).collect(Collectors.toSet());
        List<BedrockPack> current = ActiveAddons.active().packs().packs();
        if (manifest.entries().stream().allMatch(AddonManifest.Entry::active)
            && current.stream().allMatch(pack -> pack.archive() != null)
            && wanted.equals(current.stream().map(BedrockPack::archive).collect(Collectors.toSet()))) {
            visit.ready(true, "");
            return nothing;
        }

        PackSet active = packsOf(manifest, true);
        PackSet dormant = packsOf(manifest, false);
        List<BedrockPack> every = new ArrayList<>(active.packs());
        every.addAll(dormant.packs());
        String clash = AddonRegistry.conflict(new PackSet(every, List.of())).orElse(null);
        if (clash != null) {
            visit.ready(false, "The server's copy of " + clash + " cannot replace yours without a restart. "
                + "Remove or replace it in addons/ and start the game again.");
            return nothing;
        }

        visiting = true;
        if (!dormant.packs().isEmpty()) AddonRegistry.ensureRegistered(AddonPacks.convert(dormant));
        AddonSet set = AddonPacks.convert(active);
        ActiveAddons.activate(set);
        McAddon.LOGGER.info("Activated the server's add-on set of {} add-ons.", wanted.size());
        return Minecraft.getInstance().reloadResourcePacks().thenRun(() -> visit.ready(true, ""));
    }

    /** The packs inside the manifest's archives of one kind, from the installed copy where there is one. */
    private static PackSet packsOf(AddonManifest manifest, boolean active) {
        List<BedrockPack> packs = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (AddonManifest.Entry entry : manifest.entries()) {
            if (entry.active() != active) continue;
            List<BedrockPack> installed = ActiveAddons.installed().packs().packs().stream()
                .filter(pack -> entry.sha256().equals(pack.archive()))
                .toList();
            try {
                packs.addAll(installed.isEmpty()
                    ? ReceivedArchives.inCache().read(entry.sha256(), entry.name(), problems)
                    : installed);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        problems.forEach(problem -> McAddon.LOGGER.warn("The server's add-ons: {}", problem));
        return PackOrder.sort(packs, problems);
    }

    private static Set<String> installedArchives() {
        return ActiveAddons.installed().packs().packs().stream().map(BedrockPack::archive)
            .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static long freeSpace() {
        try {
            Files.createDirectories(McAddonPaths.cache());
            return Files.getFileStore(McAddonPaths.cache()).getUsableSpace();
        } catch (IOException unreadable) {
            return Long.MAX_VALUE;
        }
    }

    private static Void failed(Throwable failure) {
        McAddon.LOGGER.error("Could not change the active add-ons.", failure);
        return null;
    }
}
