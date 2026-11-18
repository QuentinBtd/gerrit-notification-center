package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.*;
import java.util.Map;
import org.kohsuke.args4j.Option;

public final class GetNotifications implements RestReadView<AccountResource> {
  private final Provider<CurrentUser> user;
  private final NotificationService service;
  private final Settings settings;

  @Option(name = "--state")
  String state = "all";

  @Option(name = "--type")
  String type;

  @Option(name = "--before")
  long before = Long.MAX_VALUE;

  @Option(name = "--limit")
  int limit = 50;

  @Option(name = "--count")
  boolean count;

  @Inject
  GetNotifications(Provider<CurrentUser> user, NotificationService service, Settings settings) {
    this.user = user;
    this.service = service;
    this.settings = settings;
  }

  static int account(CurrentUser user, AccountResource resource) throws AuthException {
    if (!user.isIdentifiedUser() || !user.getAccountId().equals(resource.getUser().getAccountId()))
      throw new AuthException("own account required");
    return user.getAccountId().get();
  }

  @Override
  public Response<?> apply(AccountResource r) throws Exception {
    int account = account(user.get(), r);
    if (!settings.enabled) throw new ResourceConflictException("notification-center disabled");
    if (type != null && !EventRouter.TYPES.contains(type))
      throw new BadRequestException("invalid type");
    try {
      return Response.ok(
          count
              ? Map.of(
                  "unread",
                  service.count(account),
                  "latest",
                  service.latest(account),
                  "pollSeconds",
                  settings.pollSeconds,
                  "browser",
                  service.preferences(account).browser)
              : service.list(account, state, type, before, limit));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
  }
}
