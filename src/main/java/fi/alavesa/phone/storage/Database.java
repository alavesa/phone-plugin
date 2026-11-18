package fi.alavesa.phone.storage;

import fi.alavesa.phone.messaging.ContactRow;
import fi.alavesa.phone.messaging.ConversationPage;
import fi.alavesa.phone.messaging.Message;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage (plugins/Phone/phone.db) for messages, known player names, block lists, mutes, per-player settings
 * and the photo index. SQLite because conversations are queried by pair, by unread state and by page, and must
 * survive a crash mid-write; the driver ships with Paper, so nothing is shaded.
 *
 * <p>Threading: one connection, used only on the single "phone-db" thread. Every public method returns at once with
 * a future; callers hop back to the main thread themselves before touching Bukkit.
 */
public final class Database {

    private final Logger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "phone-db");
        t.setDaemon(true);
        return t;
    });
    private Connection db;

    public Database(Logger log) {
        this.log = log;
    }

    /** Opens the file and creates the tables. Blocks: called once from onEnable. */
    public void open(File file) throws Exception {
        executor.submit(() -> {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, "
                        + "recipient TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL, read INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE INDEX IF NOT EXISTS messages_unread ON messages (recipient, read)");
                st.execute("CREATE INDEX IF NOT EXISTS messages_pair ON messages (sender, recipient, id)");
                st.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, fov REAL)");
                st.execute("CREATE INDEX IF NOT EXISTS players_name ON players (name COLLATE NOCASE)");
                st.execute("CREATE TABLE IF NOT EXISTS blocks (owner TEXT NOT NULL, blocked TEXT NOT NULL, PRIMARY KEY (owner, blocked))");
                st.execute("CREATE TABLE IF NOT EXISTS mutes (uuid TEXT PRIMARY KEY, until INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS photos (map_id INTEGER PRIMARY KEY, photographer TEXT NOT NULL, "
                        + "photographer_name TEXT NOT NULL, taken_at INTEGER NOT NULL, world TEXT, x INTEGER, y INTEGER, z INTEGER)");
                // columns added after 1.0.0; SQLite has no "ADD COLUMN IF NOT EXISTS"
                addColumn(st, "messages", "edited INTEGER NOT NULL DEFAULT 0");
                addColumn(st, "players", "ui TEXT");
                addColumn(st, "players", "pass_hash TEXT");
                addColumn(st, "players", "pass_salt TEXT");
                addColumn(st, "photos", "frames INTEGER NOT NULL DEFAULT 0");
            }
            return null;
        }).get();
    }

    private static void addColumn(Statement st, String table, String definition) throws SQLException {
        try {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("duplicate column")) throw e;
        }
    }

    /** Finishes queued writes (up to 10 s) and closes the file. Blocks: called from onDisable. */
    public void close() {
        executor.execute(() -> {
            try {
                if (db != null) db.close();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Closing the database failed", e);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) log.warning("Database writes did not finish in 10 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T> CompletableFuture<T> async(String what, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.call());
                } catch (Exception e) {
                    log.log(Level.SEVERE, "Database error (" + what + ")", e);
                    future.completeExceptionally(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            future.completeExceptionally(e);    // shutting down
        }
        return future;
    }

    // ---- messages ---------------------------------------------------------------------------------------------------

    public CompletableFuture<Long> insertMessage(UUID sender, UUID recipient, String body, long sentAt) {
        return async("insert message", () -> {
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO messages (sender, recipient, body, sent_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, sender.toString());
                ps.setString(2, recipient.toString());
                ps.setString(3, body);
                ps.setLong(4, sentAt);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }

    private static Message message(ResultSet rs) throws SQLException {
        return new Message(rs.getLong("id"), UUID.fromString(rs.getString("sender")), UUID.fromString(rs.getString("recipient")),
                rs.getString("body"), rs.getLong("sent_at"), rs.getInt("read") != 0, rs.getInt("edited") != 0);
    }

    private static final String PAIR = "((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?))";

    private static void pair(PreparedStatement ps, int at, UUID a, UUID b) throws SQLException {
        ps.setString(at, a.toString());
        ps.setString(at + 1, b.toString());
        ps.setString(at + 2, b.toString());
        ps.setString(at + 3, a.toString());
    }

    private boolean exists(UUID a, UUID b, String condition, long id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM messages WHERE " + PAIR + " AND " + condition + " LIMIT 1")) {
            pair(ps, 1, a, b);
            ps.setLong(5, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * A window of the conversation between {@code viewer} and {@code partner}, oldest message first, and marks
     * everything the partner sent as read.
     *
     * @param skip       how many of the newest messages to leave out (0 = the end of the conversation)
     * @param maxCount   at most this many messages
     * @param lineBudget at most this many text lines, measured with {@code lines} (always at least one message)
     */
    public CompletableFuture<ConversationPage> loadPage(UUID viewer, UUID partner, int skip, int maxCount, int lineBudget,
                                                        ToIntFunction<String> lines) {
        return async("load conversation", () -> {
            List<Message> picked = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement("SELECT * FROM messages WHERE " + PAIR + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                pair(ps, 1, viewer, partner);
                ps.setInt(5, maxCount);
                ps.setInt(6, Math.max(0, skip));
                int used = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    // newest first: keep taking older ones until the window is full
                    while (rs.next()) {
                        Message m = message(rs);
                        int need = lines.applyAsInt(m.body());
                        if (!picked.isEmpty() && used + need > lineBudget) break;
                        picked.add(m);
                        used += need;
                    }
                }
            }
            Collections.reverse(picked);
            boolean older = !picked.isEmpty() && exists(viewer, partner, "id < ?", picked.getFirst().id());
            boolean newer = !picked.isEmpty() && exists(viewer, partner, "id > ?", picked.getLast().id());
            int marked;
            try (PreparedStatement ps = db.prepareStatement("UPDATE messages SET read = 1 WHERE recipient = ? AND sender = ? AND read = 0")) {
                ps.setString(1, viewer.toString());
                ps.setString(2, partner.toString());
                marked = ps.executeUpdate();
            }
            return new ConversationPage(List.copyOf(picked), older, newer, marked, unread(viewer));
        });
    }

    /** The newest messages {@code sender} wrote to {@code recipient}, newest first. */
    public CompletableFuture<List<Message>> ownMessages(UUID sender, UUID recipient, int limit) {
        return async("load own messages", () -> {
            List<Message> out = new ArrayList<>();
            try (PreparedStatement ps = db.prepareStatement("SELECT * FROM messages WHERE sender = ? AND recipient = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, sender.toString());
                ps.setString(2, recipient.toString());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(message(rs));
                }
            }
            return out;
        });
    }

    /** One message, or null. */
    public CompletableFuture<Message> message(long id) {
        return async("load message", () -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT * FROM messages WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? message(rs) : null;
                }
            }
        });
    }

    /** Replaces the text of a message its sender wrote. True when a row changed. */
    public CompletableFuture<Boolean> editMessage(long id, UUID sender, String body) {
        return async("edit message", () -> {
            try (PreparedStatement ps = db.prepareStatement("UPDATE messages SET body = ?, edited = 1 WHERE id = ? AND sender = ?")) {
                ps.setString(1, body);
                ps.setLong(2, id);
                ps.setString(3, sender.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Deletes a message its sender wrote, for both sides. True when a row was removed. */
    public CompletableFuture<Boolean> deleteMessage(long id, UUID sender) {
        return async("delete message", () -> {
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM messages WHERE id = ? AND sender = ?")) {
                ps.setLong(1, id);
                ps.setString(2, sender.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    private int unread(UUID player) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM messages WHERE recipient = ? AND read = 0")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public CompletableFuture<Integer> unreadCount(UUID player) {
        return async("count unread", () -> unread(player));
    }

    /** Everyone {@code player} has exchanged messages with, newest conversation first. */
    public CompletableFuture<List<ContactRow>> contacts(UUID player) {
        return async("load contacts", () -> {
            String me = player.toString();
            Map<String, Integer> unread = new HashMap<>();
            try (PreparedStatement ps = db.prepareStatement("SELECT sender, COUNT(*) FROM messages WHERE recipient = ? AND read = 0 GROUP BY sender")) {
                ps.setString(1, me);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) unread.put(rs.getString(1), rs.getInt(2));
                }
            }
            List<ContactRow> out = new ArrayList<>();
            String sql = "SELECT m.*, p.name AS partner_name, t.partner AS partner FROM "
                    + "(SELECT CASE WHEN sender = ? THEN recipient ELSE sender END AS partner, MAX(id) AS last_id "
                    + " FROM messages WHERE sender = ? OR recipient = ? GROUP BY partner) t "
                    + "JOIN messages m ON m.id = t.last_id LEFT JOIN players p ON p.uuid = t.partner ORDER BY m.id DESC";
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                ps.setString(1, me);
                ps.setString(2, me);
                ps.setString(3, me);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String partner = rs.getString("partner");
                        out.add(new ContactRow(UUID.fromString(partner), rs.getString("partner_name"), rs.getString("body"),
                                rs.getLong("sent_at"), me.equals(rs.getString("sender")), unread.getOrDefault(partner, 0)));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Integer> purgeOlderThan(long time) {
        return async("purge messages", () -> {
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM messages WHERE sent_at < ?")) {
                ps.setLong(1, time);
                return ps.executeUpdate();
            }
        });
    }

    // ---- players ----------------------------------------------------------------------------------------------------

    public CompletableFuture<Void> rememberPlayer(UUID id, String name) {
        return async("remember player", () -> {
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO players (uuid, name) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name")) {
                ps.setString(1, id.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** A player who has joined before, by name (case-insensitive); null when unknown. */
    public CompletableFuture<KnownPlayer> findPlayer(String name) {
        return async("find player", () -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT uuid, name FROM players WHERE name = ? COLLATE NOCASE LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new KnownPlayer(UUID.fromString(rs.getString(1)), rs.getString(2)) : null;
                }
            }
        });
    }

    public CompletableFuture<String> playerName(UUID id) {
        return async("player name", () -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT name FROM players WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Everything stored about one player besides their messages; all fields null for a player without settings. */
    public record PlayerData(Double fov, String ui, String passHash, String passSalt) {
        public static final PlayerData EMPTY = new PlayerData(null, null, null, null);
    }

    public CompletableFuture<PlayerData> playerData(UUID id) {
        return async("load player data", () -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT fov, ui, pass_hash, pass_salt FROM players WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return PlayerData.EMPTY;
                    double fov = rs.getDouble(1);
                    return new PlayerData(rs.wasNull() ? null : fov, rs.getString(2), rs.getString(3), rs.getString(4));
                }
            }
        });
    }

    private CompletableFuture<Void> setPlayerColumns(String what, String sql, UUID id, Object... values) {
        return async(what, () -> {
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
                ps.setString(values.length + 1, id.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setFov(UUID id, Double fov) {
        return setPlayerColumns("save fov", "UPDATE players SET fov = ? WHERE uuid = ?", id, fov);
    }

    /** @param ui "fancy", "plain" or null for automatic */
    public CompletableFuture<Void> setUi(UUID id, String ui) {
        return setPlayerColumns("save ui mode", "UPDATE players SET ui = ? WHERE uuid = ?", id, ui);
    }

    /** Stores a salted passcode hash; both null removes the passcode. */
    public CompletableFuture<Void> setPasscode(UUID id, String hash, String salt) {
        return setPlayerColumns("save passcode", "UPDATE players SET pass_hash = ?, pass_salt = ? WHERE uuid = ?", id, hash, salt);
    }

    // ---- blocks and mutes -------------------------------------------------------------------------------------------

    /** The players {@code owner} has blocked. */
    public CompletableFuture<Set<UUID>> blocks(UUID owner) {
        return async("load blocks", () -> {
            Set<UUID> out = new HashSet<>();
            try (PreparedStatement ps = db.prepareStatement("SELECT blocked FROM blocks WHERE owner = ?")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
                }
            }
            return out;
        });
    }

    /** True when {@code owner} has blocked {@code other} (for recipients who are offline). */
    public CompletableFuture<Boolean> isBlocked(UUID owner, UUID other) {
        return async("check block", () -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM blocks WHERE owner = ? AND blocked = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, other.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public CompletableFuture<Void> setBlocked(UUID owner, UUID other, boolean blocked) {
        return async("save block", () -> {
            try (PreparedStatement ps = db.prepareStatement(blocked ? "INSERT OR IGNORE INTO blocks (owner, blocked) VALUES (?, ?)"
                    : "DELETE FROM blocks WHERE owner = ? AND blocked = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, other.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** uuid -> epoch millis the mute ends ({@link Long#MAX_VALUE} = permanent). Blocks: called once from onEnable. */
    public Map<UUID, Long> loadMutes() throws Exception {
        return async("load mutes", () -> {
            Map<UUID, Long> out = new HashMap<>();
            try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, until FROM mutes")) {
                while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
            }
            return out;
        }).get();
    }

    public CompletableFuture<Void> setMute(UUID id, Long until) {
        return async("save mute", () -> {
            if (until == null) {
                try (PreparedStatement ps = db.prepareStatement("DELETE FROM mutes WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO mutes (uuid, until) VALUES (?, ?)")) {
                    ps.setString(1, id.toString());
                    ps.setLong(2, until);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ---- photos -----------------------------------------------------------------------------------------------------

    /** @param frames 0 for a photo, the number of frames for a video */
    public CompletableFuture<Void> insertPhoto(int mapId, UUID photographer, String name, long takenAt, String world, int x, int y, int z,
                                               int frames) {
        return async("save photo", () -> {
            try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO photos (map_id, photographer, photographer_name, "
                    + "taken_at, world, x, y, z, frames) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setInt(9, frames);
                ps.setInt(1, mapId);
                ps.setString(2, photographer.toString());
                ps.setString(3, name);
                ps.setLong(4, takenAt);
                ps.setString(5, world);
                ps.setInt(6, x);
                ps.setInt(7, y);
                ps.setInt(8, z);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Map id -> frame count (0 = photo) of everything printed. Blocks: called once from onEnable. */
    public Map<Integer, Integer> loadPhotoIds() throws Exception {
        return async("load photos", () -> {
            Map<Integer, Integer> out = new HashMap<>();
            try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT map_id, frames FROM photos")) {
                while (rs.next()) out.put(rs.getInt(1), rs.getInt(2));
            }
            return out;
        }).get();
    }

    public record KnownPlayer(UUID id, String name) {}
}
