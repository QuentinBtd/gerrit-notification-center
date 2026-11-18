package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.*;
import java.util.List;

public final class PostNotifications
    implements RestModifyView<AccountResource, PostNotifications.Input> {
  public static final class Input {
    public String action;
    public long id;

    /** Bulk form: apply the action to each of these (1..100) instead of {@link #id}. */
    public List<Long> ids;
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
      else if (in.ids != null) {
        if (in.ids.isEmpty() || in.ids.size() > 100 || in.ids.contains(null))
          throw new BadRequestException("ids must hold 1..100 notification IDs");
        int updated = 0;
        // Best effort per row, like read-all: hidden or unknown IDs are skipped.
        for (long id : in.ids) if (service.update(account, id, in.action, now)) updated++;
        if (updated == 0) throw new ResourceNotFoundException();
      } else if (!service.update(account, in.id, in.action, now))
        throw new ResourceNotFoundException();
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return Response.none();
  }
}
