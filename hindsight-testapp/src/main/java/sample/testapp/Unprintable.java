package sample.testapp;

/**
 * An object that refuses to describe itself.
 *
 * <p>Real applications contain these: entities whose {@code toString} touches a lazily-initialised
 * association, builders that are illegal to read before they are complete, objects that throw
 * because somebody wrote the method that way. A recorder that cannot survive one of these being
 * passed as an argument is a recorder that cannot be attached to anything real.
 */
final class Unprintable {

    @Override
    public String toString() {
        throw new IllegalStateException("this object refuses to be printed");
    }
}
