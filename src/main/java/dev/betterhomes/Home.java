package dev.betterhomes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Comparator;

/** Immutable home snapshot. {@code createdAt} is epoch milliseconds. */
public record Home(String name, String world, double x, double y, double z,
                   float yaw, float pitch, long createdAt) {

    public static final Comparator<Home> ORDER =
            Comparator.comparingInt(Home::numericName).thenComparing(Home::name);

    public static Home at(String name, Location loc) {
        return new Home(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(), System.currentTimeMillis());
    }

    /** Returns the destination, or {@code null} if the world is not loaded. */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public static final int COLOR_COUNT = 16;

    /** Stable colour slot (0..15) derived from the home name, so colours don't shift on deletes. */
    public int colorIndex() {
        int n = numericName();
        return Math.floorMod(n == Integer.MAX_VALUE ? name.hashCode() : n - 1, COLOR_COUNT);
    }

    public int numericName() {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException _) {
            return Integer.MAX_VALUE;
        }
    }
}
