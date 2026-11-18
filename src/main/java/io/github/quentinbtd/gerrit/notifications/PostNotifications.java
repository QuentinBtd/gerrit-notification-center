package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.*;

public final class PostNotifications
    implements RestModifyView<AccountResource, PostNotifications.Input> {
  public static final class Input {
    public String action;
    public long id;
  }

  private final Provider<CurrentUser> user;
  private final NotificationService service;
  private final Settings settings;

  @Inject
  PostNotifications(Provider<CurrentUser> user, NotificationService service, Settings settings) {
    this.user = user;
    this.service = service;
    this.settings = settings;
  }

  @Override
  public Response<?> apply(AccountResource r, Input in) throws Exception {
    int account = GetNotifications.account(user.get(), r);
    if (!settings.enabled) throw new ResourceConflictException("notification-center disabled");
    if (in == null || in.action == null) throw new BadRequestException("action required");
    long now = System.currentTimeMillis();
    try {
      if (in.action.equals("read-all")) service.readAll(account, now);
      else if (!service.update(account, in.id, in.action, now))
        throw new ResourceNotFoundException();
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return Response.none();
  }
}
