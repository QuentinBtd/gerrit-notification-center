package io.github.quentinbtd.gerrit.notifications;

import java.sql.*;
import java.util.*;

/** One file, one owning server. No AUTO_SERVER or TCP listener. */
public final class H2Store implements NotificationStore {
  private final Connection connection;

  public H2Store(String url) throws SQLException {
    connection = DriverManager.getConnection(url, "sa", "");
    try (Statement s = connection.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY)");
      try (ResultSet r = s.executeQuery("SELECT MAX(version) FROM schema_version")) {
        if (r.next() && r.getInt(1) > 3) throw new SQLException("unsupported schema version");
      }
      s.execute(
          "CREATE TABLE IF NOT EXISTS notifications (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY"
              + " KEY, account_id INT NOT NULL, event_key VARCHAR(128) NOT NULL, event_type"
              + " VARCHAR(32) NOT NULL, actor INT NOT NULL, project VARCHAR(1024) NOT NULL,"
              + " change_id INT NOT NULL, url VARCHAR(4096) NOT NULL, created_at BIGINT NOT NULL,"
              + " read_at BIGINT, archived_at BIGINT, UNIQUE(account_id,event_key))");
      s.execute(
          "CREATE INDEX IF NOT EXISTS inbox_account_created ON"
              + " notifications(account_id,created_at)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS inbox_account_read ON notifications(account_id,read_at,id)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS inbox_account_archive ON"
              + " notifications(account_id,archived_at,id)");
      s.execute("MERGE INTO schema_version KEY(version) VALUES (1)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS comment_events (project VARCHAR(1024) NOT NULL, change_id INT"
              + " NOT NULL, comment_id VARCHAR(1024) NOT NULL, event_key VARCHAR(128) NOT NULL,"
              + " created_at BIGINT NOT NULL, PRIMARY KEY(project,change_id,comment_id))");
      s.execute(
          "CREATE INDEX IF NOT EXISTS comment_events_retention ON comment_events(created_at)");
      s.execute("MERGE INTO schema_version KEY(version) VALUES (2)");
      s.execute("ALTER TABLE notifications ADD COLUMN IF NOT EXISTS saved_at BIGINT");
      s.execute(
          "CREATE INDEX IF NOT EXISTS inbox_account_saved ON"
              + " notifications(account_id,saved_at,id)");
      s.execute("MERGE INTO schema_version KEY(version) VALUES (3)");
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
  }

  @Override
  public synchronized void insert(
      int account,
      String event,
      String type,
      int actor,
      String project,
      int change,
      String url,
      long created)
      throws SQLException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO"
                + " notifications(account_id,event_key,event_type,actor,project,change_id,url,created_at)"
                + " VALUES (?,?,?,?,?,?,?,?)")) {
      s.setInt(1, account);
      s.setString(2, event);
      s.setString(3, type);
      s.setInt(4, actor);
      s.setString(5, project);
      s.setInt(6, change);
      s.setString(7, url);
      s.setLong(8, created);
      s.executeUpdate();
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) throw e;
    }
  }

  @Override
  public synchronized List<Notification> page(
      int account, String state, String type, long before, int limit) throws SQLException {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
    String filter =
        switch (state) {
          case "all" -> "archived_at IS NULL";
          case "unread" -> "archived_at IS NULL AND read_at IS NULL";
          case "archived" -> "archived_at IS NOT NULL";
          case "saved" -> "saved_at IS NOT NULL";
          default -> throw new IllegalArgumentException("invalid state");
        };
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT * FROM notifications WHERE account_id=? AND id<? AND "
                + filter
                + (type == null ? "" : " AND event_type=?")
                + " ORDER BY id DESC LIMIT ?")) {
      s.setInt(1, account);
      s.setLong(2, before);
      int i = 3;
      if (type != null) s.setString(i++, type);
      s.setInt(i, limit);
      List<Notification> result = new ArrayList<>();
      try (ResultSet r = s.executeQuery()) {
        while (r.next())
          result.add(
              new Notification(
                  r.getLong("id"),
                  r.getInt("account_id"),
                  r.getString("event_type"),
                  r.getInt("actor"),
                  r.getString("project"),
                  r.getInt("change_id"),
                  r.getString("url"),
                  r.getLong("created_at"),
                  (Long) r.getObject("read_at"),
                  (Long) r.getObject("archived_at"),
                  (Long) r.getObject("saved_at")));
      }
      return result;
    }
  }

  @Override
  public synchronized boolean update(int account, long id, String action, long now)
      throws SQLException {
    String assignment =
        switch (action) {
          case "read" -> "read_at=?";
          case "unread" -> "read_at=NULL";
          case "archive" -> "archived_at=?";
          case "unarchive" -> "archived_at=NULL";
          case "save" -> "saved_at=?";
          case "unsave" -> "saved_at=NULL";
          default -> throw new IllegalArgumentException("invalid action");
        };
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE notifications SET " + assignment + " WHERE account_id=? AND id=?")) {
      int i = 1;
      if (!assignment.endsWith("NULL")) s.setLong(i++, now);
      s.setInt(i++, account);
      s.setLong(i, id);
      return s.executeUpdate() == 1;
    }
  }

  @Override
  public synchronized boolean claimComment(
      String project, int change, String id, String event, long when) throws SQLException {
    try (var s =
        connection.prepareStatement(
            "INSERT INTO comment_events(project,change_id,comment_id,event_key,created_at) VALUES"
                + " (?,?,?,?,?)")) {
      s.setString(1, project);
      s.setInt(2, change);
      s.setString(3, id);
      s.setString(4, event);
      s.setLong(5, when);
      s.executeUpdate();
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) throw e;
    }
    try (var s =
        connection.prepareStatement(
            "SELECT event_key FROM comment_events WHERE project=? AND change_id=? AND"
                + " comment_id=?")) {
      s.setString(1, project);
      s.setInt(2, change);
      s.setString(3, id);
      try (var r = s.executeQuery()) {
        return r.next() && event.equals(r.getString(1));
      }
    }
  }

  @Override
  public synchronized Notification find(int account, long id) throws SQLException {
    for (String state : List.of("all", "archived")) {
      var rows = page(account, state, null, id == Long.MAX_VALUE ? id : id + 1, 1);
      if (!rows.isEmpty() && rows.get(0).id() == id) return rows.get(0);
    }
    return null;
  }

  @Override
  public synchronized int prune(long before) throws SQLException {
    connection.setAutoCommit(false);
    try (PreparedStatement s =
            connection.prepareStatement(
                "DELETE FROM notifications WHERE created_at<? AND saved_at IS NULL");
        PreparedStatement comments =
            connection.prepareStatement("DELETE FROM comment_events WHERE created_at<?")) {
      s.setLong(1, before);
      int count = s.executeUpdate();
      comments.setLong(1, before);
      comments.executeUpdate();
      connection.commit();
      return count;
    } catch (SQLException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    connection.close();
  }
}
