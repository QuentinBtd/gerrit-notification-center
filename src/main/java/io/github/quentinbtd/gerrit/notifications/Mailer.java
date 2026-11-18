package io.github.quentinbtd.gerrit.notifications;

import static com.google.common.html.HtmlEscapers.htmlEscaper;

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.change.NotifyResolver;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.mail.send.EmailArguments;
import com.google.gerrit.server.mail.send.MessageIdGenerator;
import com.google.gerrit.server.mail.send.OutgoingEmail;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.*;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Emails a newly stored notification to accounts that opted in, through Gerrit's own OutgoingEmail:
 * {@code [sendemail]} SMTP and {@code from}, outgoing-email validators and each account's email
 * strategy all apply exactly as for Gerrit's emails.
 */
@Singleton
public final class Mailer implements EventRouter.Delivered, LifecycleListener {
  private static final Logger LOG = Logger.getLogger(Mailer.class.getName());
  static final int QUEUE_CAPACITY = 1000;
  static final Map<String, String> REASONS =
      Map.of(
          "REVIEWER_ADDED", "Review requested",
          "MENTION", "Mentioned",
          "COMMENT", "Comment",
          "NEW_CHANGE", "New change",
          "PATCHSET", "New patchset",
          "MERGED", "Merged",
          "ABANDONED", "Abandoned");
  private final Settings settings;
  private final NotificationStore store;
  private final GerritVisibility visibility;
  private final AccountCache accounts;
  private final EmailArguments emailArgs;
  private final OneOffRequestContext requestContext;
  private final Provider<String> webUrl;
  private final AtomicLong dropped = new AtomicLong();
  private final ThreadPoolExecutor executor =
      queue(
          QUEUE_CAPACITY,
          () -> {
            long n = dropped.incrementAndGet();
            if (n % 100 == 1)
              LOG.warning(
                  "notification-center email queue full (SMTP slow?); " + n + " emails dropped");
          });

  @Inject
  Mailer(
      Settings settings,
      NotificationStore store,
      GerritVisibility visibility,
      AccountCache accounts,
      EmailArguments emailArgs,
      OneOffRequestContext requestContext,
      @CanonicalWebUrl Provider<String> webUrl) {
    this.settings = settings;
    this.store = store;
    this.visibility = visibility;
    this.accounts = accounts;
    this.emailArgs = emailArgs;
    this.requestContext = requestContext;
    this.webUrl = webUrl;
  }

  /**
   * One worker over a bounded queue. On overflow the task is dropped and {@code onOverflow} runs;
   * nothing is thrown back to the caller, so inbox delivery to other recipients continues.
   */
  static ThreadPoolExecutor queue(int capacity, Runnable onOverflow) {
    return new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(capacity),
        r -> {
          var t = new Thread(r, "notification-center-mail");
          t.setDaemon(true);
          return t;
        },
        (task, pool) -> onOverflow.run());
  }

  @Override
  public void delivered(int account, String type, DeliveryEvent e) {
    if (!settings.email || !emailArgs.emailSender.isEnabled()) return;
    try {
      // Opted-out accounts never take a queue slot.
      if (!store.preferences(account).email) return;
    } catch (Exception ex) {
      LOG.warning("notification-center email preference lookup failed; inspect storage health");
      return;
    }
    // Off the Gerrit request thread: SMTP latency must not slow the originating action.
    executor.execute(() -> send(account, type, e));
  }

  /** Unique per event (same digest as the inbox row key) and recipient; Gerrit appends the host. */
  static String messageId(int account, DeliveryEvent e) {
    return "notification-center-" + EventRouter.digest(e.type() + ":" + e.key()) + "-" + account;
  }

  private void send(int account, String type, DeliveryEvent e) {
    // Background thread: OutgoingEmail needs a request context; act as Gerrit's internal user.
    try (var ctx = requestContext.open()) {
      // Re-checked here: the user may have opted out while this email waited in the queue.
      if (!store.preferences(account).email) return;
      var row =
          new Notification(
              0,
              account,
              type,
              e.actor(),
              e.project(),
              e.change(),
              e.url(),
              e.when(),
              null,
              null,
              null);
      // Same live READ check as the inbox: never mail a Change the recipient cannot see.
      if (!visibility.canSee(account, row)) return;
      String actor =
          accounts.get(Account.id(e.actor())).map(s -> s.account().getName()).orElse("Someone");
      String reason = REASONS.getOrDefault(type, type);
      String link = Objects.requireNonNullElse(webUrl.get(), "/").replaceAll("/$", "") + e.url();
      String what = e.project() + " #" + e.change();
      var email =
          new OutgoingEmail(
              emailArgs,
              "notification-center",
              new OutgoingEmail.EmailDecorator() {
                private OutgoingEmail out;

                @Override
                public void init(OutgoingEmail out) {
                  this.out = out;
                  out.setHeader("Subject", "[Gerrit] " + reason + ": " + what);
                  out.addByAccountId(RecipientType.TO, Account.id(account));
                }

                @Override
                public void populateEmailContent() {
                  out.appendText(
                      reason
                          + " by "
                          + actor
                          + " on "
                          + what
                          + "\n\n"
                          + link
                          + "\n\n"
                          + "Manage these emails in Notifications → Settings.\n");
                  if (out.useHtml())
                    out.appendHtml(
                        UnsafeSanitizedContentOrdainer.ordainAsSafe(
                            "<p>"
                                + htmlEscaper().escape(reason + " by " + actor + " on ")
                                + "<a href=\""
                                + htmlEscaper().escape(link)
                                + "\">"
                                + htmlEscaper().escape(what)
                                + "</a></p><p>Manage these emails in Notifications →"
                                + " Settings.</p>",
                            ContentKind.HTML));
                }
              });
      email.setNotify(NotifyResolver.Result.all());
      email.setMessageId(MessageIdGenerator.MessageId.create(messageId(account, e)));
      email.send();
    } catch (Exception ex) {
      // No address, project or exception payload in production logs.
      LOG.warning("notification-center email delivery failed (" + type + ")");
    }
  }

  @Override
  public void start() {}

  @Override
  public void stop() {
    executor.shutdownNow();
  }
}
