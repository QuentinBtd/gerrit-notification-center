package io.github.quentinbtd.gerrit.notifications;

import java.util.*;
import java.util.regex.Pattern;

public final class RecipientResolver {
  @FunctionalInterface
  public interface Emails {
    Set<Integer> lookup(String email) throws Exception;
  }

  private final Emails emails;
  private static final Pattern EMAIL =
      Pattern.compile("(?:^|\\s)@([a-zA-Z0-9.!#$%&'*+=?^_`{|}~-]+@[a-zA-Z0-9.-]+)(?=\\s|$)");
  private static final Pattern ACCOUNT = Pattern.compile("<GERRIT_ACCOUNT_([0-9]+)>");

  public RecipientResolver(Emails emails) {
    this.emails = emails;
  }

  public Set<Integer> mentions(String text) throws Exception {
    Set<Integer> result = new HashSet<>();
    if (text == null) return result;
    var m = EMAIL.matcher(text);
    while (m.find()) {
      var ids = emails.lookup(m.group(1));
      if (ids.size() == 1) result.addAll(ids);
    }
    m = ACCOUNT.matcher(text);
    while (m.find()) {
      try {
        int id = Integer.parseInt(m.group(1));
        if (id > 0) result.add(id);
      } catch (NumberFormatException ignored) {
      }
    }
    return result;
  }
}
