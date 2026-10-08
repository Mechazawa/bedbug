package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.registry.AddonEntities;

/**
 * Where what became of a command a pack ran is counted.
 *
 * The first two are gaps in this mod. The third is the game answering a question it understood,
 * such as a selector that matched nothing, and is kept apart so it never reads as one.
 */
public interface CommandReport {
    /** A part of the line this mod has no Java form for, named the way a function file's is. */
    void untranslatable(String what);

    /** A translated line Java's parser would not read. */
    void unreadable(String command, String reason);

    /** A line the game read and then declined. */
    void declined(String command, String reason);

    /** The commands a pack's data runs: its entity events, animations, dialogue scenes and blocks. */
    CommandReport DATA = new CommandReport() {
        @Override
        public void untranslatable(String what) {
            AddonEntities.unsupported(what);
        }

        @Override
        public void unreadable(String command, String reason) {
            AddonEntities.unsupported("command " + command.split(" ")[0] + " that did not parse: " + reason);
        }

        @Override
        public void declined(String command, String reason) {
            AddonEntities.declined(command, reason);
        }
    };
}
