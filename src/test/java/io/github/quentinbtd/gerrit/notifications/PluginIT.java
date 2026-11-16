package io.github.quentinbtd.gerrit.notifications;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gson.JsonParser;
import org.junit.Test;

@TestPlugin(
    name = "notification-center",
    sysModule = "io.github.quentinbtd.gerrit.notifications.Module")
public class PluginIT extends LightweightPluginDaemonTest {
  private static final String PATH = "/accounts/self/notification-center~notifications";

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
