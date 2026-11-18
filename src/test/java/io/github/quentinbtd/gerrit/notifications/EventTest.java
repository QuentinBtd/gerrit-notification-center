package io.github.quentinbtd.gerrit.notifications;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class EventTest {
  @Test
  public void mentionsOverrideCommentsExcludeActorAndDeduplicate() throws Exception {
    try (var store = new H2Store("jdbc:h2:mem:events")) {
      var router =
          new EventRouter(
              store,
              Set.of("COMMENT", "MENTION", "REVIEWER_ADDED", "PATCHSET", "MERGED", "ABANDONED"));
      var e =
          new DeliveryEvent(
              "COMMENT",
              "stable-action",
              8,
              7,
              "p",
              12,
              "/c/p/+/12",
              100,
              Set.of(8, 9),
              Set.of(),
              Set.of(9, 10));
      router.receive(e);
      router.receive(e);
      assertEquals("COMMENT", store.page(7, "all", null, Long.MAX_VALUE, 50).get(0).type());
      assertEquals("MENTION", store.page(9, "all", null, Long.MAX_VALUE, 50).get(0).type());
      assertEquals(1, store.page(9, "all", null, Long.MAX_VALUE, 50).size());
      assertEquals("MENTION", store.page(10, "all", null, Long.MAX_VALUE, 50).get(0).type());
      assertTrue(store.page(8, "all", null, Long.MAX_VALUE, 50).isEmpty());
      router.receive(
          new DeliveryEvent(
              "REVIEWER_ADDED",
              "reviewer",
              8,
              7,
              "p",
              12,
              "/c/p/+/12",
              101,
              Set.of(9),
              Set.of(10),
              Set.of()));
      assertEquals(2, store.page(10, "all", null, Long.MAX_VALUE, 50).size());
      assertEquals(1, store.page(7, "all", null, Long.MAX_VALUE, 50).size());
      for (String type : List.of("PATCHSET", "MERGED", "ABANDONED"))
        router.receive(
            new DeliveryEvent(
                type, type, 8, 7, "p", 12, "/c/p/+/12", 102, Set.of(9), Set.of(), Set.of()));
      assertEquals(4, store.page(7, "all", null, Long.MAX_VALUE, 50).size());
      router.receive(null);
    }
  }

  @Test
  public void disabledAndIncompleteEventsAreIgnored() throws Exception {
    try (var store = new H2Store("jdbc:h2:mem:disabled")) {
      var router = new EventRouter(store, Set.of());
      router.receive(
          new DeliveryEvent(
              "MERGED", "x", 8, 7, "p", 12, "/c/p/+/12", 100, Set.of(), Set.of(), Set.of()));
      assertTrue(store.page(7, "all", null, Long.MAX_VALUE, 50).isEmpty());
    }
  }

  @Test
  public void resolvesOnlyExplicitUnambiguousMentions() throws Exception {
    var resolver =
        new RecipientResolver(email -> email.equals("a@example.org") ? Set.of(7) : Set.of(8, 9));
    assertEquals(
        Set.of(7, 12),
        resolver.mentions(
            "Hi @a@example.org @ambiguous@example.org <GERRIT_ACCOUNT_12>"
                + " <GERRIT_ACCOUNT_99999999999999999999>"));
    assertTrue(resolver.mentions(null).isEmpty());
    assertTrue(resolver.mentions("ordinary a@example.org @unknown").isEmpty());
  }
}
