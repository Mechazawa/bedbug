package at.ioexception.mcaddon.state;

/** A dynamic property that holds a Vector3. */
public record PropertyVector(double x, double y, double z) {
    public double[] triple() {
        return new double[] {x, y, z};
    }
}
