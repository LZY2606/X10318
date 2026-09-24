package gsb.fea.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite connection holder with schema versioning and migration chain. */
public final class Db implements AutoCloseable {
    public static final int SCHEMA_VERSION = 3;

    private final Connection connection;

    private Db(Connection connection) {
        this.connection = connection;
    }

    public static Db open(String path) {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            Db db = new Db(connection);
            db.execute("PRAGMA journal_mode=WAL");
            db.execute("PRAGMA foreign_keys=ON");
            db.execute("PRAGMA busy_timeout=5000");
            db.migrate();
            db.repairTornWrites();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public Connection connection() {
        return connection;
    }

    private void migrate() throws SQLException {
        int version = userVersion();
        if (version < 1) {
            applyV1();
        }
        if (version < 2) {
            applyV2();
        }
        if (version < 3) {
            applyV3();
        }
        setUserVersion(SCHEMA_VERSION);
    }

    private int userVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setUserVersion(int version) throws SQLException {
        execute("PRAGMA user_version=" + version);
    }

    private void applyV1() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
        execute("CREATE TABLE IF NOT EXISTS versions ("
                + "id TEXT PRIMARY KEY, parent_id TEXT, label TEXT, author TEXT,"
                + "created_at INTEGER, edit_summary TEXT, status TEXT NOT NULL DEFAULT 'WRITING')");
        execute("CREATE TABLE IF NOT EXISTS nodes ("
                + "version_id TEXT NOT NULL, uid TEXT NOT NULL, original_number INTEGER,"
                + "json TEXT NOT NULL, PRIMARY KEY (version_id, uid))");
        execute("CREATE TABLE IF NOT EXISTS elements ("
                + "version_id TEXT NOT NULL, uid TEXT NOT NULL, original_number INTEGER,"
                + "json TEXT NOT NULL, PRIMARY KEY (version_id, uid))");
        execute("CREATE TABLE IF NOT EXISTS matrices ("
                + "version_id TEXT NOT NULL, uid TEXT NOT NULL, element_id TEXT, revision TEXT,"
                + "json TEXT NOT NULL, PRIMARY KEY (version_id, uid))");
        execute("CREATE TABLE IF NOT EXISTS mappings ("
                + "version_id TEXT NOT NULL, revision TEXT NOT NULL,"
                + "json TEXT NOT NULL, PRIMARY KEY (version_id, revision))");
        execute("CREATE TABLE IF NOT EXISTS constraint_sets ("
                + "version_id TEXT NOT NULL, revision TEXT NOT NULL,"
                + "json TEXT NOT NULL, PRIMARY KEY (version_id, revision))");
        execute("CREATE TABLE IF NOT EXISTS renumbers ("
                + "version_id TEXT NOT NULL, revision TEXT NOT NULL,"
                + "json TEXT NOT NULL, PRIMARY KEY (version_id, revision))");
        execute("CREATE TABLE IF NOT EXISTS selections ("
                + "version_id TEXT PRIMARY KEY, mapping_revision TEXT,"
                + "constraint_revision TEXT, renumber_revision TEXT)");
        execute("CREATE TABLE IF NOT EXISTS derivations ("
                + "id TEXT PRIMARY KEY, version_id TEXT NOT NULL, kind TEXT NOT NULL,"
                + "config_signature TEXT, input_hashes TEXT, result_json TEXT,"
                + "created_at INTEGER, event_id TEXT)");
        execute("CREATE TABLE IF NOT EXISTS events ("
                + "id TEXT PRIMARY KEY, seq INTEGER NOT NULL, version_id TEXT, type TEXT NOT NULL,"
                + "actor TEXT, rationale TEXT, payload_json TEXT, created_at INTEGER)");
    }

    private void applyV2() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS decisions ("
                + "id TEXT PRIMARY KEY, event_id TEXT, version_id TEXT, decision_type TEXT,"
                + "actor TEXT, chosen TEXT, rejected_json TEXT, rationale TEXT, created_at INTEGER)");
    }

    private void applyV3() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS conflicts ("
                + "id TEXT PRIMARY KEY, base_version_id TEXT, version_a TEXT, version_b TEXT,"
                + "actor_a TEXT, actor_b TEXT, state TEXT NOT NULL DEFAULT 'OPEN',"
                + "resolved_version_id TEXT, resolved_by TEXT,"
                + "created_at INTEGER, resolved_at INTEGER)");
    }

    /** Remove versions whose write never completed (crash between begin and commit). */
    private void repairTornWrites() throws SQLException {
        execute("DELETE FROM nodes WHERE version_id IN (SELECT id FROM versions WHERE status='WRITING')");
        execute("DELETE FROM elements WHERE version_id IN (SELECT id FROM versions WHERE status='WRITING')");
        execute("DELETE FROM matrices WHERE version_id IN (SELECT id FROM versions WHERE status='WRITING')");
        execute("DELETE FROM mappings WHERE version_id IN (SELECT id FROM versions WHERE status='WRITING')");
        execute("DELETE FROM constraint_sets WHERE version_id IN (SELECT id FROM versions WHERE status='WRITING')");
        execute("DELETE FROM renumbers WHERE version_id IN (SELECT id FROM versions WHERE status='WRITING')");
        execute("DELETE FROM selections WHERE version_id IN (SELECT id FROM versions WHERE status='WRITING')");
        execute("DELETE FROM versions WHERE status='WRITING'");
    }

    public void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public void update(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update failed: " + sql, e);
        }
    }

    public List<Row> query(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Row> rows = new ArrayList<>();
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Row row = new Row();
                    for (int i = 1; i <= columns; i++) {
                        row.values.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    public String scalar(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        if (rows.isEmpty() || rows.get(0).values.isEmpty()) {
            return null;
        }
        Object value = rows.get(0).values.get(0);
        return value == null ? null : value.toString();
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object param = params[i];
            if (param == null) {
                ps.setObject(i + 1, null);
            } else if (param instanceof Long longValue) {
                ps.setLong(i + 1, longValue);
            } else if (param instanceof Integer intValue) {
                ps.setInt(i + 1, intValue);
            } else {
                ps.setString(i + 1, param.toString());
            }
        }
    }

    public void begin() {
        executeQuietly("BEGIN IMMEDIATE");
    }

    public void commit() {
        executeQuietly("COMMIT");
    }

    public void rollback() {
        executeQuietly("ROLLBACK");
    }

    private void executeQuietly(String sql) {
        try {
            execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("tx failed: " + sql, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Row {
        public final List<Object> values = new ArrayList<>();

        public String text(int index) {
            Object value = values.get(index);
            return value == null ? null : value.toString();
        }

        public long number(int index) {
            Object value = values.get(index);
            return value == null ? 0L : ((Number) value).longValue();
        }
    }
}
