package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.client.GeneralPreferencesInfo;
import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.mail.EmailSettings;
import com.google.inject.*;
import java.util.*;

/** GET/PUT /accounts/self/notification-center~preferences (own account only). */
public final class PreferencesRest {
  static final int MAX_SUBSCRIPTIONS = 50, MAX_QUERY = 2048;

  private PreferencesRest() {}

  public static final class Get implements RestReadView<AccountResource> {
    private final Provider<CurrentUser> user;
    private final NotificationStore store;
    private final Settings settings;

    @Inject
    Get(Provider<CurrentUser> user, NotificationStore store, Settings settings) {
      this.user = user;
      this.store = store;
      this.settings = settings;
    }

    @Override
    public Response<?> apply(AccountResource r) throws Exception {
      int account = GetNotifications.account(user.get(), r);
      if (!settings.enabled) throw new ResourceConflictException("notification-center disabled");
      return Response.ok(store.preferences(account));
    }
  }

  public static final class Put implements RestModifyView<AccountResource, Preferences> {
    private final Provider<CurrentUser> user;
    private final NotificationStore store;
    private final Settings settings;
    private final Watchers watchers;
    private final AccountCache accounts;
    private final EmailSettings email;

    @Inject
    Put(
        Provider<CurrentUser> user,
        NotificationStore store,
        Settings settings,
        Watchers watchers,
        AccountCache accounts,
        EmailSettings email) {
      this.accounts = accounts;
      this.email = email;
      this.user = user;
      this.store = store;
      this.settings = settings;
      this.watchers = watchers;
    }

    @Override
    public Response<?> apply(AccountResource r, Preferences in) throws Exception {
      int account = GetNotifications.account(user.get(), r);
      if (!settings.enabled) throw new ResourceConflictException("notification-center disabled");
      if (in == null) throw new BadRequestException("preferences required");
      if (in.subscriptions == null) in.subscriptions = new ArrayList<>();
      if (in.replaceGerritEmails && !in.email)
        throw new BadRequestException("replacing Gerrit emails requires email delivery");
      // Gerrit's plain-text-only pass cannot be filtered: the user would get both emails.
      if (in.replaceGerritEmails
          && email.html
          && accounts
                  .get(Account.id(account))
                  .map(s -> s.generalPreferences().getEmailFormat())
                  .orElse(null)
              == GeneralPreferencesInfo.EmailFormat.PLAINTEXT)
        throw new BadRequestException(
            "set your Gerrit email format to \"HTML and plaintext\" to replace Gerrit emails");
      if (in.subscriptions.size() > MAX_SUBSCRIPTIONS)
        throw new BadRequestException("at most " + MAX_SUBSCRIPTIONS + " subscriptions");
      for (var sub : in.subscriptions) {
        if (sub == null || sub.query == null || sub.query.isBlank())
          throw new BadRequestException("subscription query required");
        sub.query = sub.query.trim();
        if (sub.query.length() > MAX_QUERY) throw new BadRequestException("query too long");
        if (sub.types == null
            || sub.types.isEmpty()
            || sub.types.contains(null) // before containsAll: Set.of() throws on null
            || !Preferences.SUBSCRIBABLE.containsAll(sub.types))
          throw new BadRequestException("invalid subscription types");
        sub.types = List.copyOf(new TreeSet<>(sub.types));
        try {
          watchers.validate(account, sub.query);
        } catch (QueryParseException e) {
          throw new BadRequestException("invalid query \"" + sub.query + "\": " + e.getMessage());
        }
      }
      store.savePreferences(account, in);
      return Response.ok(store.preferences(account));
    }
  }
}
