package io.github.quentinbtd.gerrit.notifications;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.util.*;

public final class EventRouter {
  public static final Set<String> TYPES =
      Set.of(
          "REVIEWER_ADDED", "COMMENT", "MENTION", "NEW_CHANGE", "PATCHSET", "MERGED", "ABANDONED");

  /** Called once per newly stored notification (not for duplicates). */
  @FunctionalInterface
  public interface Delivered {
    void delivered(int account, String type, DeliveryEvent event);
  }

  private final NotificationStore store;
  private final Set<String> enabled;
  private final Delivered delivered;

  public EventRouter(NotificationStore store, Set<String> enabled, Delivered delivered) {
    this.store = store;
    this.enabled = Set.copyOf(enabled);
    this.delivered = delivered;
  }

  public void receive(DeliveryEvent e) throws SQLException {
    if (e == null || e.project() == null || e.key() == null || e.change() < 1 || e.actor() < 1)
      return;
    Map<Integer, String> recipients = new HashMap<>();
    if (e.type().equals("REVIEWER_ADDED")) for (int id : e.added()) recipients.put(id, e.type());
    else {
      if (e.owner() > 0) recipients.put(e.owner(), e.type());
      for (int id : e.reviewers()) recipients.put(id, e.type());
      if (e.type().equals("COMMENT")) for (int id : e.mentions()) recipients.put(id, "MENTION");
    }
    // Watchers/subscribers never override a more specific reason such as MENTION.
    for (int id : e.watchers()) recipients.putIfAbsent(id, e.type());
    recipients.remove(e.actor());
    for (var r : recipients.entrySet()) {
      if (r.getKey() > 0
          && enabled.contains(r.getValue())
          && store.insert(
              r.getKey(),
              digest(e.type() + ":" + e.key()),
              r.getValue(),
              e.actor(),
              e.project(),
              e.change(),
              e.url(),
              e.when())) {
        try {
          delivered.delivered(r.getKey(), r.getValue(), e);
        } catch (RuntimeException ex) {
          // An extra channel (email) must never stop inbox delivery to remaining recipients.
          java.util.logging.Logger.getLogger(EventRouter.class.getName())
              .warning("notification-center channel delivery failed (" + r.getValue() + ")");
        }
      }
    }
  }

  static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
