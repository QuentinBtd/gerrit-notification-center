package io.github.quentinbtd.gerrit.notifications;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.Address;
import com.google.gerrit.entities.EmailHeader;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.client.GeneralPreferencesInfo;
import com.google.gerrit.extensions.client.ProjectWatchInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.validators.OutgoingEmailValidationListener;
import com.google.gerrit.server.validators.ValidationException;
import com.google.gson.JsonParser;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.util.Modules;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

@TestPlugin(
    name = "notification-center",
    sysModule = "io.github.quentinbtd.gerrit.notifications.PluginIT$TestModule")
public class PluginIT extends LightweightPluginDaemonTest {
  /**
   * The production module plus a site outgoing-email validator the tests drive. For plugin emails
   * whose Subject contains {@code target}, it signals {@code seen}, optionally blocks the mail
   * worker on {@code hold}, and rejects when {@code reject} was set before the message arrived.
   */
  public static final class TestModule extends AbstractModule {
    /** Captures plugin email instead of talking SMTP. */
    static final class FakeTransport implements MailTransport {
      record Sent(Address from, Address to, String raw) {
        String body() {
          return new String(
              Base64.getMimeDecoder().decode(raw.split("\r\n\r\n", 2)[1].trim()),
              StandardCharsets.UTF_8);
        }
      }

      final List<Sent> sent = new CopyOnWriteArrayList<>();

      @Override
      public boolean configured() {
        return true;
      }

      @Override
      public void send(Address from, Collection<Address> to, String message) {
        for (var rcpt : to) sent.add(new Sent(from, rcpt, message));
      }
    }

    static final FakeTransport MAIL = new FakeTransport();
    static volatile boolean reject;

    /** When set, a matching plugin email gets a rewritten header map and this recipient. */
    static volatile String redirectTo;

    static volatile String target;
    static volatile CountDownLatch seen = new CountDownLatch(1), hold;

    @Override
    protected void configure() {
      install(
          Modules.override(new Module()).with(b -> b.bind(MailTransport.class).toInstance(MAIL)));
      DynamicSet.bind(binder(), OutgoingEmailValidationListener.class)
          .toInstance(
              args -> {
                boolean rejectThis = reject;
                String t = target;
                if (!"notification-center".equals(args.messageClass)
                    || t == null
                    || !(args.headers.get("Subject") instanceof EmailHeader.StringEmailHeader h)
                    || !h.getString().contains(t)) return;
                String redirect = redirectTo;
                if (redirect != null) {
                  var rewritten = new LinkedHashMap<>(args.headers);
                  rewritten.put("X-Policy", new EmailHeader.StringEmailHeader("rewritten"));
                  args.headers = rewritten;
                  args.smtpRcptTo = new HashSet<>(Set.of(Address.parse(redirect)));
                }
                seen.countDown();
                var waitFor = hold;
                try {
                  if (waitFor != null) waitFor.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                if (rejectThis) throw new ValidationException("site policy rejects this email");
              });
    }
  }

  private static void resetHook() {
    TestModule.reject = false;
    TestModule.target = null;
    TestModule.redirectTo = null;
    var h = TestModule.hold;
    TestModule.hold = null;
    if (h != null) h.countDown();
  }

  private static final String PATH = "/accounts/self/notification-center~notifications";
  private static final String PREFS = "/accounts/self/notification-center~preferences";
  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject private ProjectOperations projectOperations;

  private int count(String query) throws Exception {
    var response = userRestSession.get(PATH + query);
    response.assertOK();
    return JsonParser.parseString(response.getEntityContent())
        .getAsJsonObject()
        .getAsJsonArray("items")
        .size();
  }

  private List<TestModule.FakeTransport.Sent> mails(String email, String reason) {
    return TestModule.MAIL.sent.stream()
        .filter(
            m ->
                m.to().email().equals(email)
                    && m.body().startsWith(reason + " by ")
                    && m.body().contains(" on " + project.get() + " #"))
        .toList();
  }

  private List<TestModule.FakeTransport.Sent> pluginMails(String email) {
    return mails(email, "New change");
  }

  /**
   * Mail is sent on one FIFO worker thread: waiting for mail N implies earlier ones were handled.
   */
  private void awaitPluginMails(String email, int expected) throws Exception {
    for (int i = 0; i < 400 && pluginMails(email).size() < expected; i++) Thread.sleep(50);
    assertThat(pluginMails(email).size()).isEqualTo(expected);
  }

  private static void await(CountDownLatch latch) throws Exception {
    assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
  }

  private Preferences subscribedToNewChanges(boolean email) {
    var prefs = new Preferences();
    prefs.email = email;
    prefs.subscriptions =
        List.of(new Preferences.Subscription("project:" + project.get(), List.of("NEW_CHANGE")));
    return prefs;
  }

  @Test
  public void queuedEmailIsDroppedAfterTheRecipientOptsOut() throws Exception {
    TestModule.target = "New change: " + project.get() + " #";
    TestModule.seen = new CountDownLatch(1);
    TestModule.hold = new CountDownLatch(1);
    userRestSession.put(PREFS, subscribedToNewChanges(true)).assertOK();
    try {
      TestModule.MAIL.sent.clear();
      createChange(); // A: the worker blocks in the validator while sending it
      await(TestModule.seen);
      createChange(); // B: queued behind A while the user still has email enabled
      userRestSession.put(PREFS, subscribedToNewChanges(false)).assertOK();
      adminRestSession.put(PREFS, subscribedToNewChanges(true)).assertOK();
      TestModule.hold.countDown();
      // C, by user, emails only admin: once it arrives, the FIFO worker has handled B.
      requestScopeOperations.setApiUser(user.id());
      var unused = gApi.changes().create(new ChangeInput(project.get(), "master", "C")).get();
      requestScopeOperations.setApiUser(admin.id());
      awaitPluginMails(admin.email(), 1);
      assertThat(pluginMails(user.email()).size()).isEqualTo(1); // A only, B was dropped
    } finally {
      resetHook();
      requestScopeOperations.setApiUser(admin.id());
      userRestSession.put(PREFS, new Preferences()).assertOK();
      adminRestSession.put(PREFS, new Preferences()).assertOK();
      projectOperations
          .project(project)
          .forUpdate()
          .add(block(Permission.READ).ref("refs/*").group(SystemGroupBackend.REGISTERED_USERS))
          .update();
    }
  }

  @Test
  @GerritConfig(
      name = "plugin.notification-center.from",
      value = "Notifications <notify@example.com>")
  public void emailUsesConfiguredSenderAndHonorsOutgoingValidators() throws Exception {
    userRestSession.put(PREFS, subscribedToNewChanges(true)).assertOK();
    try {
      TestModule.MAIL.sent.clear();
      createChange();
      awaitPluginMails(user.email(), 1);
      var mail = pluginMails(user.email()).get(0);
      assertThat(mail.from().email()).isEqualTo("notify@example.com");
      assertThat(mail.raw()).contains("X-Gerrit-MessageType: notification-center");
      assertThat(mail.body()).contains("/c/" + project.get() + "/+/");
      TestModule.seen = new CountDownLatch(1);
      TestModule.reject = true;
      TestModule.target = "New change: " + project.get() + " #";
      createChange();
      await(TestModule.seen); // the validator has seen (and rejected) this plugin email
      TestModule.reject = false;
      createChange();
      awaitPluginMails(user.email(), 2); // FIFO: the rejected one was handled before this one
      assertThat(pluginMails(user.email()).size()).isEqualTo(2);
    } finally {
      resetHook();
      userRestSession.put(PREFS, new Preferences()).assertOK();
      projectOperations
          .project(project)
          .forUpdate()
          .add(block(Permission.READ).ref("refs/*").group(SystemGroupBackend.REGISTERED_USERS))
          .update();
    }
  }

  @Test
  public void frontendScriptUrlCarriesItsContentHash() throws Exception {
    String version = Module.frontendVersion();
    assertThat(version).matches("[0-9a-f]{12}");
    var info = adminRestSession.get("/config/server/info");
    info.assertOK();
    String path = "plugins/notification-center/static/notification-center.js?v=" + version;
    var paths =
        JsonParser.parseString(info.getEntityContent().substring(")]}'".length()))
            .getAsJsonObject()
            .getAsJsonObject("plugin")
            .getAsJsonArray("js_resource_paths");
    assertThat(paths.size()).isEqualTo(1);
    assertThat(paths.get(0).getAsString()).isEqualTo(path);
    // The in-process test plugin has no content scanner, so the file itself is not served here;
    // real Gerrit resolves it from the path info, which excludes the query string.
  }

  private void addReviewers(int number, String... emails) throws Exception {
    var review = new ReviewInput();
    for (String e : emails) review = review.reviewer(e);
    gApi.changes().id(project.get(), number).current().review(review);
  }

  private void awaitMails(String email, String reason, int expected) throws Exception {
    for (int i = 0; i < 400 && mails(email, reason).size() < expected; i++) Thread.sleep(50);
    assertThat(mails(email, reason).size()).isEqualTo(expected);
  }

  @Test
  public void validatorRewrittenHeadersAndRecipientsAreWhatGetsSent() throws Exception {
    userRestSession.put(PREFS, subscribedToNewChanges(true)).assertOK();
    TestModule.target = "New change: " + project.get() + " #";
    TestModule.redirectTo = "policy@example.com";
    try {
      TestModule.MAIL.sent.clear();
      createChange();
      awaitPluginMails("policy@example.com", 1);
      assertThat(pluginMails("policy@example.com").get(0).raw()).contains("X-Policy: rewritten");
      assertThat(pluginMails(user.email())).isEmpty();
    } finally {
      resetHook();
      userRestSession.put(PREFS, new Preferences()).assertOK();
      projectOperations
          .project(project)
          .forUpdate()
          .add(block(Permission.READ).ref("refs/*").group(SystemGroupBackend.REGISTERED_USERS))
          .update();
    }
  }

  @Test
  public void deactivatedReviewerGetsNoEmail() throws Exception {
    var gone = accountCreator.create("nc-gone", "nc-gone@example.com", "Gone", null);
    userRestSession.put(PREFS, new Preferences()).assertOK();
    try {
      int number = createChange().getChange().getId().get();
      addReviewers(number, gone.email(), user.email());
      awaitMails(gone.email(), "Review requested", 1); // active: emailed
      gApi.accounts().id(gone.id().get()).setActive(false);
      TestModule.MAIL.sent.clear();
      gApi.changes().id(project.get(), number).abandon(); // owner + both reviewers
      awaitMails(user.email(), "Abandoned", 1);
      // FIFO worker: once user's next email arrives, gone's abandon email was already handled.
      addReviewers(createChange().getChange().getId().get(), user.email());
      awaitMails(user.email(), "Review requested", 1);
      assertThat(mails(gone.email(), "Abandoned")).isEmpty();
    } finally {
      gApi.accounts().id(gone.id().get()).setActive(true);
      projectOperations
          .project(project)
          .forUpdate()
          .add(block(Permission.READ).ref("refs/*").group(SystemGroupBackend.REGISTERED_USERS))
          .update();
    }
  }

  @Test
  public void emailIsOnByDefaultExceptForGerritDisabledEmailStrategy() throws Exception {
    var quiet = accountCreator.create("nc-quiet", "nc-quiet@example.com", "Quiet", null);
    requestScopeOperations.setApiUser(quiet.id());
    var gp = gApi.accounts().self().getPreferences();
    gp.emailStrategy = GeneralPreferencesInfo.EmailStrategy.DISABLED;
    gApi.accounts().self().setPreferences(gp);
    requestScopeOperations.setApiUser(admin.id());
    userRestSession.put(PREFS, new Preferences()).assertOK(); // defaults: email on
    try {
      TestModule.MAIL.sent.clear();
      addReviewers(createChange().getChange().getId().get(), quiet.email(), user.email());
      awaitMails(user.email(), "Review requested", 1);
      // FIFO worker: once user's second email arrives, quiet's would have been sent already.
      addReviewers(createChange().getChange().getId().get(), user.email());
      awaitMails(user.email(), "Review requested", 2);
      assertThat(mails(quiet.email(), "Review requested")).isEmpty();
    } finally {
      projectOperations
          .project(project)
          .forUpdate()
          .add(block(Permission.READ).ref("refs/*").group(SystemGroupBackend.REGISTERED_USERS))
          .update();
    }
  }

  @Test
  public void nativeWatchesAndQuerySubscriptionsNotifyNonReviewers() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    var watch = new ProjectWatchInfo();
    watch.project = project.get();
    watch.notifyAbandonedChanges = true;
    gApi.accounts().self().setWatchedProjects(List.of(watch));
    requestScopeOperations.setApiUser(admin.id());
    try {
      var prefs = new Preferences();
      prefs.email = true;
      prefs.subscriptions =
          List.of(new Preferences.Subscription("bogus-operator:x", List.of("NEW_CHANGE")));
      userRestSession.put(PREFS, prefs).assertBadRequest();
      prefs.subscriptions =
          List.of(
              new Preferences.Subscription(
                  "project:" + project.get(),
                  new java.util.ArrayList<>(Arrays.asList("MERGED", null))));
      var nullType = userRestSession.put(PREFS, prefs);
      nullType.assertBadRequest();
      assertThat(nullType.getEntityContent()).contains("invalid subscription types");
      prefs.subscriptions =
          List.of(
              new Preferences.Subscription(
                  "project:^" + project.get().substring(0, 5) + ".* status:open",
                  List.of("NEW_CHANGE")));
      userRestSession.put(PREFS, prefs).assertOK();
      var stored = userRestSession.get(PREFS);
      stored.assertOK();
      assertThat(stored.getEntityContent()).contains("status:open");
      int newChanges = count("?type=NEW_CHANGE"), abandoned = count("?type=ABANDONED");
      var change = createChange();
      assertThat(count("?type=NEW_CHANGE")).isEqualTo(newChanges + 1);
      gApi.changes().id(project.get(), change.getChange().getId().get()).abandon();
      assertThat(count("?type=ABANDONED")).isEqualTo(abandoned + 1);
    } finally {
      // The lightweight daemon shares the plugin inbox and preferences across methods.
      userRestSession.put(PREFS, new Preferences()).assertOK();
      projectOperations
          .project(project)
          .forUpdate()
          .add(block(Permission.READ).ref("refs/*").group(SystemGroupBackend.REGISTERED_USERS))
          .update();
    }
  }

  @Test
  public void summaryMentionDoesNotLinkToUnrelatedInline() throws Exception {
    var result = createChange("summary mention", "a.txt", "one\n");
    int number = result.getChange().getId().get();
    var review = new ReviewInput().message("Summary @" + user.email());
    var comment = new ReviewInput.CommentInput();
    comment.line = 1;
    comment.message = "An unrelated inline without a mention";
    review.comments = java.util.Map.of("a.txt", java.util.List.of(comment));
    gApi.changes().id(project.get(), number).current().review(review);
    var response = userRestSession.get(PATH + "?type=MENTION");
    response.assertOK();
    var items =
        JsonParser.parseString(response.getEntityContent())
            .getAsJsonObject()
            .getAsJsonArray("items");
    try {
      assertThat(items.size()).isEqualTo(1);
      assertThat(items.get(0).getAsJsonObject().get("url").getAsString())
          .isEqualTo("/c/" + project.get() + "/+/" + number);
    } finally {
      // The lightweight daemon shares the plugin inbox across methods.
      gApi.changes().id(project.get(), number).setPrivate(true);
    }
  }

  @Test
  public void inlineMentionLinkUsesSmallestMentionUuidNotUnrelatedInline() throws Exception {
    var result = createChange("mixed inlines", "a.txt", "one\n");
    int number = result.getChange().getId().get();
    var review = new ReviewInput();
    var comments = new java.util.ArrayList<ReviewInput.CommentInput>();
    for (String message :
        java.util.List.of(
            "First @" + user.email(), "Second @" + user.email(), "Unrelated inline")) {
      var comment = new ReviewInput.CommentInput();
      comment.line = 1;
      comment.message = message;
      comments.add(comment);
    }
    review.comments = java.util.Map.of("a.txt", comments);
    gApi.changes().id(project.get(), number).current().review(review);
    try {
      var published = gApi.changes().id(project.get(), number).current().comments().get("a.txt");
      assertThat(published.size()).isEqualTo(3);
      var mentionIds =
          published.stream()
              .filter(c -> c.message.contains("@" + user.email()))
              .map(c -> c.id)
              .sorted()
              .toList();
      assertThat(mentionIds.size()).isEqualTo(2);
      var response = userRestSession.get(PATH + "?type=MENTION");
      response.assertOK();
      var items =
          JsonParser.parseString(response.getEntityContent())
              .getAsJsonObject()
              .getAsJsonArray("items");
      assertThat(items.size()).isEqualTo(1);
      assertThat(items.get(0).getAsJsonObject().get("url").getAsString())
          .isEqualTo("/c/" + project.get() + "/+/" + number + "/comment/" + mentionIds.get(0));
    } finally {
      gApi.changes().id(project.get(), number).setPrivate(true);
    }
  }

  @Test
  public void nativePatchsetMergeAbandonAndInlineMention() throws Exception {
    var result = createChange("a change", "a.txt", "one\n");
    int number = result.getChange().getId().get();
    gApi.changes().id(project.get(), number).addReviewer(user.email());
    amendChange(result.getChangeId());
    var patches = userRestSession.get(PATH + "?type=PATCHSET");
    patches.assertOK();
    assertThat(
            JsonParser.parseString(patches.getEntityContent())
                .getAsJsonObject()
                .getAsJsonArray("items")
                .size())
        .isEqualTo(1);
    var review = new ReviewInput();
    var comment = new ReviewInput.CommentInput();
    comment.line = 1;
    comment.message = "Inline @" + user.email();
    review.comments = java.util.Map.of("a.txt", java.util.List.of(comment));
    gApi.changes().id(project.get(), number).current().review(review);
    var mentions = userRestSession.get(PATH + "?type=MENTION");
    mentions.assertOK();
    var items =
        JsonParser.parseString(mentions.getEntityContent())
            .getAsJsonObject()
            .getAsJsonArray("items");
    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).getAsJsonObject().get("url").getAsString()).contains("/comment/");
    gApi.changes().id(project.get(), number).current().review(ReviewInput.approve());
    gApi.changes().id(project.get(), number).current().submit();
    var merged = userRestSession.get(PATH + "?type=MERGED");
    merged.assertOK();
    assertThat(
            JsonParser.parseString(merged.getEntityContent())
                .getAsJsonObject()
                .getAsJsonArray("items")
                .size())
        .isEqualTo(1);
    var second = createChange();
    int other = second.getChange().getId().get();
    gApi.changes().id(project.get(), other).addReviewer(user.email());
    gApi.changes().id(project.get(), other).abandon();
    var abandoned = userRestSession.get(PATH + "?type=ABANDONED");
    abandoned.assertOK();
    var abandonedItems =
        JsonParser.parseString(abandoned.getEntityContent())
            .getAsJsonObject()
            .getAsJsonArray("items");
    assertThat(abandonedItems.size()).isEqualTo(1);
    var mutation = new PostNotifications.Input();
    mutation.action = "archive";
    mutation.id = abandonedItems.get(0).getAsJsonObject().get("id").getAsLong();
    userRestSession.post(PATH, mutation).assertNoContent();
    var archived = userRestSession.get(PATH + "?state=archived");
    archived.assertOK();
    assertThat(
            JsonParser.parseString(archived.getEntityContent())
                .getAsJsonObject()
                .getAsJsonArray("items")
                .size())
        .isEqualTo(1);
    var invalid = new PostNotifications.Input();
    invalid.action = "sql-injection";
    userRestSession.post(PATH, invalid).assertBadRequest();
    userRestSession.get(PATH + "?state=unknown").assertBadRequest();
    userRestSession.get(PATH + "?type=unknown").assertBadRequest();
    anonymousRestSession.get(PATH).assertForbidden();
  }

  @Test
  public void realEventsRestIdentityAndPermissionRevocation() throws Exception {
    var change = createChange();
    int number = change.getChange().getId().get();
    gApi.changes().id(project.get(), number).addReviewer(user.email());
    var listed = userRestSession.get(PATH);
    listed.assertOK();
    var json = JsonParser.parseString(listed.getEntityContent()).getAsJsonObject();
    assertThat(json.getAsJsonArray("items").size()).isEqualTo(1);
    var notification = json.getAsJsonArray("items").get(0).getAsJsonObject();
    assertThat(notification.get("type").getAsString()).isEqualTo("REVIEWER_ADDED");
    long id = notification.get("id").getAsLong();
    var mutation = new PostNotifications.Input();
    mutation.action = "read";
    mutation.id = id;
    userRestSession.post(PATH, mutation).assertNoContent();
    var count = userRestSession.get(PATH + "?count=true");
    count.assertOK();
    assertThat(
            JsonParser.parseString(count.getEntityContent())
                .getAsJsonObject()
                .get("unread")
                .getAsLong())
        .isEqualTo(0);
    userRestSession
        .get("/accounts/" + admin.id().get() + "/notification-center~notifications")
        .assertForbidden();
    gApi.changes()
        .id(project.get(), number)
        .current()
        .review(new ReviewInput().message("Please see @" + user.email()));
    var mentioned = userRestSession.get(PATH + "?type=MENTION");
    mentioned.assertOK();
    assertThat(
            JsonParser.parseString(mentioned.getEntityContent())
                .getAsJsonObject()
                .getAsJsonArray("items")
                .size())
        .isEqualTo(1);
    gApi.changes().id(project.get(), number).reviewer(user.email()).remove();
    gApi.changes().id(project.get(), number).setPrivate(true);
    var hidden = userRestSession.get(PATH);
    hidden.assertOK();
    assertThat(
            JsonParser.parseString(hidden.getEntityContent())
                .getAsJsonObject()
                .getAsJsonArray("items")
                .size())
        .isEqualTo(0);
    mutation.action = "unread";
    userRestSession.post(PATH, mutation).assertNotFound();
  }
}
