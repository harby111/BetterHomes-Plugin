package dev.betterhomes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite access with a small connection pool. Every query runs on a virtual thread;
 * callers receive CompletableFutures and must hop back to the main thread themselves.
 */
public final class DatabaseManager implements AutoCloseable {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS homes (
                uuid VARCHAR(36), name VARCHAR(32), world VARCHAR(64),
                x DOUBLE, y DOUBLE, z DOUBLE, yaw FLOAT, pitch FLOAT, created_at TIMESTAMP
            )""";
    private static final String CREATE_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_homes_uuid_name ON homes (uuid, name)";

    private final BetterHomes plugin;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private BlockingQueue<Connection> pool;
    private final List<Connection> all = new ArrayList<>();

    public DatabaseManager(BetterHomes plugin) {
        this.plugin = plugin;
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run(Connection connection) throws SQLException;
    }

    public void init() throws SQLException, IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Path file = plugin.getDataFolder().toPath()
                .resolve(plugin.getConfig().getString("database.file", "homes.db"));
        String url = "jdbc:sqlite:" + file.toAbsolutePath();

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found on the server classpath", e);
        }

        int size = Math.max(1, plugin.getConfig().getInt("database.pool-size", 4));
        pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            all.add(c);
            pool.add(c);
        }

        try (Statement st = all.getFirst().createStatement()) {
            st.execute(CREATE_TABLE);
            st.execute(CREATE_INDEX);
        }
    }

    private <T> CompletableFuture<T> async(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            Connection c = null;
            try {
                c = pool.take();
                return task.run(c);
            } catch (SQLException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } finally {
                if (c != null) pool.offer(c);
            }
        }, executor);
    }

    public CompletableFuture<List<Home>> loadHomes(UUID owner) {
        return async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, world, x, y, z, yaw, pitch, created_at FROM homes WHERE uuid = ?")) {
                ps.setString(1, owner.toString());
                List<Home> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Home(rs.getString(1), rs.getString(2),
                                rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                                rs.getFloat(6), rs.getFloat(7), rs.getLong(8)));
                    }
                }
                return result;
            }
        });
    }

    public CompletableFuture<Void> insertHome(UUID owner, Home h) {
        return async(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT OR REPLACE INTO homes
                    (uuid, name, world, x, y, z, yaw, pitch, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, owner.toString());
                ps.setString(2, h.name());
                ps.setString(3, h.world());
                ps.setDouble(4, h.x());
                ps.setDouble(5, h.y());
                ps.setDouble(6, h.z());
                ps.setFloat(7, h.yaw());
                ps.setFloat(8, h.pitch());
                ps.setLong(9, h.createdAt());
                ps.executeUpdate();
                return null;
            }
        });
    }

    public CompletableFuture<Void> deleteHome(UUID owner, String name) {
        return async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM homes WHERE uuid = ? AND name = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown(); // lets queued writes finish
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (Connection c : all) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
