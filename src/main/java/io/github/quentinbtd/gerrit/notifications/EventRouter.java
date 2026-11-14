package io.github.quentinbtd.gerrit.notifications;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.util.*;

public final class EventRouter {
  public static final Set<String> TYPES =
      Set.of("REVIEWER_ADDED", "COMMENT", "MENTION", "PATCHSET", "MERGED", "ABANDONED");
  private final NotificationStore store;
  private final Set<String> enabled;

  public EventRouter(NotificationStore store, Set<String> enabled) {
    this.store = store;
    this.enabled = Set.copyOf(enabled);
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
    recipients.remove(e.actor());
    for (var r : recipients.entrySet()) {
      if (r.getKey() > 0 && enabled.contains(r.getValue()))
        store.insert(
            r.getKey(),
            digest(e.type() + ":" + e.key()),
            r.getValue(),
            e.actor(),
            e.project(),
            e.change(),
            e.url(),
            e.when());
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
