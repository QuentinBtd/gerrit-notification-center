package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Address;
import com.google.gerrit.entities.EmailHeader;
import com.google.gerrit.extensions.client.GeneralPreferencesInfo.EmailStrategy;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.validators.OutgoingEmailValidationListener;
import com.google.gerrit.server.validators.ValidationException;
import com.google.inject.*;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.eclipse.jgit.lib.PersonIdent;

/**
 * Emails newly stored notifications through the plugin's own SMTP settings, so Gerrit's own email
 * ({@code [sendemail]}) can be switched off. Site outgoing-email validators still apply.
 */
@Singleton
public final class Mailer implements EventRouter.Delivered, LifecycleListener {
  private static final Logger LOG = Logger.getLogger(Mailer.class.getName());
  static final int QUEUE_CAPACITY = 1000;
  static final String MESSAGE_CLASS = "notification-center";
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
  private final MailTransport transport;
  private final DynamicSet<OutgoingEmailValidationListener> validators;
  private final Provider<PersonIdent> server;
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
      MailTransport transport,
      DynamicSet<OutgoingEmailValidationListener> validators,
      @GerritPersonIdent Provider<PersonIdent> server,
      @CanonicalWebUrl Provider<String> webUrl) {
    this.settings = settings;
    this.store = store;
    this.visibility = visibility;
    this.accounts = accounts;
    this.transport = transport;
    this.validators = validators;
    this.server = server;
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

  /** Unique per event (same digest as the inbox row key) and recipient. */
  static String messageId(int account, DeliveryEvent e) {
    return "notification-center-" + EventRouter.digest(e.type() + ":" + e.key()) + "-" + account;
  }

  /** Headers plus a base64 UTF-8 text body: no dot-stuffing or 8-bit transport concerns. */
  static String compose(Map<String, EmailHeader> headers, String body) throws IOException {
    var out = new StringWriter();
    for (var h : headers.entrySet()) {
      out.write(h.getKey() + ": ");
      h.getValue().write(out);
      out.write("\r\n");
    }
    out.write(
        "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
            + "Content-Transfer-Encoding: base64\r\n\r\n");
    out.write(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
    out.write("\r\n");
    return out.toString();
  }

  @Override
  public void delivered(int account, String type, DeliveryEvent e) {
    if (!settings.email || !transport.configured()) return;
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

  private void send(int account, String type, DeliveryEvent e) {
    try {
      // Re-checked here: the user may have opted out while this email waited in the queue.
      if (!store.preferences(account).email) return;
      var state = accounts.get(Account.id(account)).orElse(null);
      // Gerrit's "Disabled" email strategy still means no email at all.
      if (state == null
          || !state.account().isActive() // deactivated accounts get no email, like Gerrit's
          || state.account().preferredEmail() == null
          || state.generalPreferences().getEmailStrategy() == EmailStrategy.DISABLED) return;
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
      String base = Objects.requireNonNullElse(webUrl.get(), "/").replaceAll("/$", "");
      String what = e.project() + " #" + e.change();
      var ident = server.get();
      Address from =
          settings.from != null
              ? Address.parse(settings.from)
              : Address.create(ident.getName(), ident.getEmailAddress());
      Address to = Address.create(state.account().fullName(), state.account().preferredEmail());
      String host = base.startsWith("http") ? URI.create(base).getHost() : "gerrit";
      Map<String, EmailHeader> headers = new LinkedHashMap<>();
      headers.put("Date", new EmailHeader.Date(Instant.now()));
      headers.put("From", new EmailHeader.AddressList(from));
      headers.put("To", new EmailHeader.AddressList(to));
      headers.put("Subject", new EmailHeader.StringEmailHeader("[Gerrit] " + reason + ": " + what));
      headers.put(
          "Message-ID",
          new EmailHeader.StringEmailHeader("<" + messageId(account, e) + "@" + host + ">"));
      headers.put("Auto-Submitted", new EmailHeader.StringEmailHeader("auto-generated"));
      headers.put("X-Gerrit-MessageType", new EmailHeader.StringEmailHeader(MESSAGE_CLASS));
      String body =
          reason
              + " by "
              + actor
              + " on "
              + what
              + "\n\n"
              + base
              + e.url()
              + "\n\n"
              + "Manage these emails in Notifications → Settings.\n";
      // Site outgoing-email validators keep applying to plugin email.
      var args = new OutgoingEmailValidationListener.Args();
      args.messageClass = MESSAGE_CLASS;
      args.smtpFromAddress = from;
      args.smtpRcptTo = new HashSet<>(Set.of(to));
      args.headers = headers;
      args.body = body;
      for (var v : validators) v.validateOutgoingEmail(args);
      // Validators may rewrite sender, recipients, headers and body: send what they left.
      if (args.smtpRcptTo == null || args.smtpRcptTo.isEmpty()) return;
      transport.send(args.smtpFromAddress, args.smtpRcptTo, compose(args.headers, args.body));
    } catch (ValidationException ex) {
      // Rejected by a site validator: not sent, by design.
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
