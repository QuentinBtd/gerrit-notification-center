package io.github.quentinbtd.gerrit.notifications;

import java.util.*;

/** Per-account delivery channels and change-query subscriptions. Also the REST body. */
public final class Preferences {
  /** Event types a subscription (or a native Gerrit watch) can follow. */
  public static final Set<String> SUBSCRIBABLE =
      Set.of("NEW_CHANGE", "PATCHSET", "COMMENT", "MERGED", "ABANDONED");

  public static final class Subscription {
    public String query;
    public List<String> types = new ArrayList<>();

    public Subscription() {}

    public Subscription(String query, List<String> types) {
      this.query = query;
      this.types = types;
    }
  }

  public boolean email;
  public boolean browser;

  /** With {@link #email}: drop this account from Gerrit's own emails the plugin also covers. */
  public boolean replaceGerritEmails;

  public List<Subscription> subscriptions = new ArrayList<>();
}
