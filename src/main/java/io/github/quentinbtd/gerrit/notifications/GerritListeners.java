package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.*;
import com.google.gerrit.extensions.events.*;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.notedb.*;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Singleton
public final class GerritListeners
    implements CommentAddedListener,
        ReviewerAddedListener,
        RevisionCreatedListener,
        ChangeMergedListener,
        ChangeAbandonedListener {
  private static final Logger LOG = Logger.getLogger(GerritListeners.class.getName());
  private final ChangeNotes.Factory notes;
  private final EventRouter router;
  private final NotificationStore store;
  private final RecipientResolver resolver;

  @Inject
  GerritListeners(
      ChangeNotes.Factory notes, EventRouter router, Emails emails, NotificationStore store) {
    this.notes = notes;
    this.router = router;
    this.store = store;
    resolver =
        new RecipientResolver(
            email ->
                emails.getAccountFor(email).stream()
                    .map(Account.Id::get)
                    .collect(Collectors.toSet()));
  }

  @Override
  public void onCommentAdded(CommentAddedListener.Event e) {
    accept("COMMENT", e);
  }

  @Override
  public void onReviewersAdded(ReviewerAddedListener.Event e) {
    accept("REVIEWER_ADDED", e);
  }

  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event e) {
    accept("PATCHSET", e);
  }

  @Override
  public void onChangeMerged(ChangeMergedListener.Event e) {
    accept("MERGED", e);
  }

  @Override
  public void onChangeAbandoned(ChangeAbandonedListener.Event e) {
    accept("ABANDONED", e);
  }

  private void accept(String type, ChangeEvent e) {
    if (e == null
        || e.getChange() == null
        || e.getChange().project == null
        || e.getChange()._number == null
        || e.getWho() == null
        || e.getWho()._accountId == null
        || e.getWhen() == null) return;
    try {
      var c = e.getChange();
      int actor = e.getWho()._accountId;
      var n = notes.createChecked(Project.nameKey(c.project), Change.id(c._number));
      Set<Integer> reviewers =
          n.getReviewers().byState(ReviewerStateInternal.REVIEWER).stream()
              .map(Account.Id::get)
              .collect(Collectors.toSet());
      Set<Integer> added = new TreeSet<>(), mentions = new HashSet<>();
      String url =
          "/c/"
              + URLEncoder.encode(c.project, StandardCharsets.UTF_8).replace("+", "%20")
              + "/+/"
              + c._number;
      String discriminator = "";
      if (e instanceof ReviewerAddedListener.Event r && r.getReviewers() != null) {
        for (var a : r.getReviewers()) if (a._accountId != null) added.add(a._accountId);
        discriminator = added.toString();
      }
      if (e instanceof RevisionEvent r && r.getRevision() != null)
        discriminator += r.getRevision()._number;
      if (e instanceof CommentAddedListener.Event comment) {
        String text = comment.getComment();
        mentions.addAll(resolver.mentions(text));
        discriminator += String.valueOf(text);
        String commentEvent =
            EventRouter.digest(
                c.project
                    + ":"
                    + c._number
                    + ":"
                    + actor
                    + ":"
                    + e.getWhen()
                    + ":"
                    + discriminator);
        for (var inline : n.getHumanComments().values()) {
          if (inline.author.getId().get() == actor
              && inline.writtenOn.toInstant().getEpochSecond() == e.getWhen().getEpochSecond()
              && store.claimComment(
                  c.project,
                  c._number,
                  inline.key.uuid,
                  commentEvent,
                  e.getWhen().toEpochMilli())) {
            mentions.addAll(resolver.mentions(inline.message));
            if (!mentions.isEmpty())
              url =
                  "/c/"
                      + URLEncoder.encode(c.project, StandardCharsets.UTF_8).replace("+", "%20")
                      + "/+/"
                      + c._number
                      + "/comment/"
                      + URLEncoder.encode(inline.key.uuid, StandardCharsets.UTF_8);
          }
        }
        // Gerrit also emits vote-only comment-added events. Do not notify on those.
        if ((text == null || text.isBlank()) && mentions.isEmpty()) return;
      }
      router.receive(
          new DeliveryEvent(
              type,
              c.project + ":" + c._number + ":" + actor + ":" + e.getWhen() + ":" + discriminator,
              actor,
              n.getChange().getOwner().get(),
              c.project,
              c._number,
              url,
              e.getWhen().toEpochMilli(),
              reviewers,
              added,
              mentions));
    } catch (Exception ex) {
      // No project, subject, comment, email or exception payload in production logs.
      LOG.warning(
          "notification-center event delivery failed (" + type + "); inspect storage health");
    }
  }
}
