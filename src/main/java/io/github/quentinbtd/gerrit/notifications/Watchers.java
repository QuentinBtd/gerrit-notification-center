package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.*;
import com.google.gerrit.entities.NotifyConfig.NotifyType;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.mail.send.EmailArguments;
import com.google.gerrit.server.mail.send.ProjectWatch;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeQueryBuilder;
import com.google.inject.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Accounts following a Change: native Gerrit project watches (Settings → Notifications, evaluated
 * by Gerrit's own ProjectWatch) plus plugin subscriptions written as change search queries.
 */
@Singleton
public final class Watchers {
  private static final Logger LOG = Logger.getLogger(Watchers.class.getName());
  private static final Map<String, NotifyType> NATIVE =
      Map.of(
          "NEW_CHANGE", NotifyType.NEW_CHANGES,
          "PATCHSET", NotifyType.NEW_PATCHSETS,
          "COMMENT", NotifyType.ALL_COMMENTS,
          "MERGED", NotifyType.SUBMITTED_CHANGES,
          "ABANDONED", NotifyType.ABANDONED_CHANGES);
  private final EmailArguments emailArgs;
  private final ProjectCache projects;
  private final ChangeData.Factory changes;
  private final Provider<ChangeQueryBuilder> queries;
  private final IdentifiedUser.GenericFactory users;
  private final NotificationStore store;

  @Inject
  Watchers(
      EmailArguments emailArgs,
      ProjectCache projects,
      ChangeData.Factory changes,
      Provider<ChangeQueryBuilder> queries,
      IdentifiedUser.GenericFactory users,
      NotificationStore store) {
    this.emailArgs = emailArgs;
    this.projects = projects;
    this.changes = changes;
    this.queries = queries;
    this.users = users;
    this.store = store;
  }

  /** Throws when the query is not valid change search syntax for this account. */
  public void validate(int account, String query) throws QueryParseException {
    var unused = queries.get().asUser(users.create(Account.id(account))).parse(query);
  }

  public Set<Integer> of(String type, ChangeNotes notes) {
    Set<Integer> result = new HashSet<>();
    NotifyType nativeType = NATIVE.get(type);
    if (nativeType == null) return result;
    ChangeData cd = changes.create(notes);
    try {
      var project = projects.get(notes.getProjectName()).orElseThrow();
      for (var id :
          new ProjectWatch(emailArgs, notes.getProjectName(), project, cd)
              .getWatchers(nativeType, false)
              .all()
              .accounts) result.add(id.get());
    } catch (Exception e) {
      LOG.warning("notification-center native watch lookup failed; inspect Gerrit logs");
    }
    try {
      // shortcut: scans every subscription per event; index subscriptions by project if it grows.
      for (var entry : store.subscriptions().entrySet())
        for (var sub : entry.getValue())
          if (sub.types.contains(type) && matches(entry.getKey(), sub.query, cd)) {
            result.add(entry.getKey());
            break;
          }
    } catch (Exception e) {
      LOG.warning("notification-center subscription lookup failed; inspect storage health");
    }
    return result;
  }

  private boolean matches(int account, String query, ChangeData cd) {
    try {
      return queries
          .get()
          .asUser(users.create(Account.id(account)))
          .parse(query)
          .asMatchable()
          .match(cd);
    } catch (Exception e) {
      // Invalid or index-only (non-matchable) query: no match, same as Gerrit's watch filters.
      return false;
    }
  }
}
