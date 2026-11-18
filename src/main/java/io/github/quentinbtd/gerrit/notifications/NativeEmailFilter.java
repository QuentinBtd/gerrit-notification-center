package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.Account;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.mail.EmailSettings;
import com.google.gerrit.server.validators.OutgoingEmailValidationListener;
import com.google.gerrit.server.validators.ValidationException;
import com.google.inject.*;
import java.util.*;

/**
 * Removes accounts that chose "replace Gerrit's emails" from Gerrit's own emails for the events the
 * plugin also emails. Other Gerrit emails (attention set, restore, revert, account/security mail)
 * are never touched. Fails open: on any error Gerrit's email is sent unchanged.
 */
@Singleton
public final class NativeEmailFilter implements OutgoingEmailValidationListener {
  /**
   * Gerrit message class -> plugin event types that must all be enabled to replace it. Not
   * "comment": Gerrit also uses it for ready-for-review and vote-only reviews, which the plugin
   * does not deliver.
   */
  static final Map<String, Set<String>> COVERED =
      Map.of(
          "newchange", Set.of("REVIEWER_ADDED", "NEW_CHANGE"),
          "newpatchset", Set.of("PATCHSET"),
          "merged", Set.of("MERGED"),
          "abandon", Set.of("ABANDONED"));

  private final Settings settings;
  private final NotificationStore store;
  private final AccountCache accounts;
  private final EmailSettings email;

  @Inject
  NativeEmailFilter(
      Settings settings, NotificationStore store, AccountCache accounts, EmailSettings email) {
    this.email = email;
    this.settings = settings;
    this.store = store;
    this.accounts = accounts;
  }

  @Override
  public void validateOutgoingEmail(Args args) throws ValidationException {
    var types = COVERED.get(args.messageClass);
    if (!settings.email || types == null || !settings.events.containsAll(types)) return;
    // Gerrit's second, plain-text-only pass sends to a list validators cannot edit; leave it.
    if (args.smtpRcptTo == null || args.smtpRcptTo.isEmpty() || messageId(args).contains("-PLAIN@"))
      return;
    Set<String> replaced = new HashSet<>();
    try {
      for (int id : store.replacingAccounts())
        accounts
            .get(Account.id(id))
            .map(s -> s.account().preferredEmail())
            .ifPresent(e -> replaced.add(e.toLowerCase(Locale.ROOT)));
    } catch (Exception e) {
      return;
    }
    if (replaced.isEmpty()) return;
    if (args.smtpRcptTo.stream()
        .allMatch(a -> replaced.contains(a.email().toLowerCase(Locale.ROOT)))) {
      // Neither rejecting nor emptying is safe with HTML email: either ends the whole send, which
      // includes Gerrit's separate plain-text pass to other users. Keep Gerrit's email then.
      if (email.html) return;
      throw new ValidationException("all recipients receive this via notification-center");
    }
    // In place: Gerrit sends to this same set after validation.
    args.smtpRcptTo.removeIf(a -> replaced.contains(a.email().toLowerCase(Locale.ROOT)));
  }

  private static String messageId(Args args) {
    var h = args.headers == null ? null : args.headers.get("Message-ID");
    return h instanceof com.google.gerrit.entities.EmailHeader.StringEmailHeader s
        ? s.getString()
        : "";
  }
}
