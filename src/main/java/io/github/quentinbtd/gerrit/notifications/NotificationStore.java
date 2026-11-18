package io.github.quentinbtd.gerrit.notifications;

import java.sql.SQLException;
import java.util.List;

public interface NotificationStore extends AutoCloseable {
  void insert(
      int account,
      String event,
      String type,
      int actor,
      String project,
      int change,
      String url,
      long created)
      throws SQLException;

  boolean update(int account, long id, String action, long now) throws SQLException;

  boolean claimComment(String project, int change, String id, String event, long when)
      throws SQLException;

  Notification find(int account, long id) throws SQLException;

  int prune(long before) throws SQLException;

  List<Notification> page(int account, String state, String type, long before, int limit)
      throws SQLException;
}
