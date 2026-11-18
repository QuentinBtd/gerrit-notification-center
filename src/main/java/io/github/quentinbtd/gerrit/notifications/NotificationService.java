package io.github.quentinbtd.gerrit.notifications;

import java.sql.SQLException;
import java.util.*;

public final class NotificationService {
  @FunctionalInterface
  public interface Visibility {
    boolean canSee(int account, Notification row);
  }

  public record Page(List<Notification> items, Long next) {}

  private final NotificationStore store;
  private final Visibility visibility;

  public NotificationService(NotificationStore store, Visibility visibility) {
    this.store = store;
    this.visibility = visibility;
  }

  public Page list(int account, String state, String type, long before, int limit)
      throws SQLException {
    if (limit < 1 || limit > 100 || before < 1)
      throw new IllegalArgumentException("invalid pagination");
    List<Notification> visible = new ArrayList<>();
    long cursor = before;
    while (true) {
      var batch = store.page(account, state, type, cursor, 100);
      for (var n : batch) {
        if (visibility.canSee(account, n)) {
          if (visible.size() == limit)
            return new Page(List.copyOf(visible), visible.getLast().id());
          visible.add(n);
        }
        cursor = n.id();
      }
      if (batch.size() < 100) return new Page(List.copyOf(visible), null);
    }
  }

  public long count(int account) throws SQLException {
    long count = 0, cursor = Long.MAX_VALUE;
    while (true) {
      Page p = list(account, "unread", null, cursor, 100);
      count += p.items().size();
      if (p.next() == null) return count;
      cursor = p.next();
    }
  }

  /** ID of the newest visible unread notification, or 0. Lets the UI detect new arrivals. */
  public long latest(int account) throws SQLException {
    var p = list(account, "unread", null, Long.MAX_VALUE, 1);
    return p.items().isEmpty() ? 0 : p.items().get(0).id();
  }

  public Preferences preferences(int account) throws SQLException {
    return store.preferences(account);
  }

  public boolean update(int account, long id, String action, long now) throws SQLException {
    if (!Set.of("read", "unread", "archive", "unarchive", "save", "unsave").contains(action))
      throw new IllegalArgumentException("invalid action");
    Notification row = store.find(account, id);
    return row != null && visibility.canSee(account, row) && store.update(account, id, action, now);
  }

  public void readAll(int account, long now) throws SQLException {
    long cursor = Long.MAX_VALUE;
    while (true) {
      Page p = list(account, "unread", null, cursor, 100);
      for (var n : p.items()) update(account, n.id(), "read", now);
      if (p.next() == null) return;
      cursor = p.next();
    }
  }
}
