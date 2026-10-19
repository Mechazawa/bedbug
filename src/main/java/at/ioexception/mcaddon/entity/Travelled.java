package at.ioexception.mcaddon.entity;

/**
 * How far an entity has gone across the ground since it was loaded, counted from the steps it took
 * between ticks however it took them, in the unit of Java's `moveDist`. Every entity answers it.
 */
public interface Travelled {
    float mcaddon$travelled();
}
