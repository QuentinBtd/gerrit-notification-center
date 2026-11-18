package io.github.quentinbtd.gerrit.notifications;

import java.sql.SQLException;
import java.util.*;

public interface NotificationStore extends AutoCloseable {
  /** Returns true when a new row was stored (false for a duplicate event). */
  boolean insert(
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

  Preferences preferences(int account) throws SQLException;

  void savePreferences(int account, Preferences preferences) throws SQLException;

  /** Accounts with email on and replaceGerritEmails set. */
  Set<Integer> replacingAccounts() throws SQLException;

  /** Every account's subscriptions, keyed by account ID. */
  Map<Integer, List<Preferences.Subscription>> subscriptions() throws SQLException;

  List<Notification> page(int account, String state, String type, long before, int limit)
      throws SQLException;
}
