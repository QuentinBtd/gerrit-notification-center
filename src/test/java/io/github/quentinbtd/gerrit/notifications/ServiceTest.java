package io.github.quentinbtd.gerrit.notifications;

import static org.junit.Assert.*;

import org.junit.Test;

public class ServiceTest {
  @Test
  public void hidesRevokedChangesFromListCountAndMutations() throws Exception {
    try (H2Store store = new H2Store("jdbc:h2:mem:permissions")) {
      store.insert(7, "a", "COMMENT", 8, "secret", 1, "/c/secret/+/1", 100);
      store.insert(7, "b", "COMMENT", 8, "public", 2, "/c/public/+/2", 100);
      NotificationService service =
          new NotificationService(store, (account, n) -> account == 7 && n.change() == 2);
      assertEquals(1, service.list(7, "all", null, Long.MAX_VALUE, 50).items().size());
      assertEquals(1, service.count(7));
      assertFalse(service.update(7, 1, "read", 200));
      assertFalse(service.update(8, 2, "read", 200));
      assertTrue(service.update(7, 2, "read", 200));
      assertEquals(0, service.count(7));
      assertNull(store.page(7, "all", null, Long.MAX_VALUE, 100).get(1).readAt());
      service.update(7, 2, "unread", 300);
      service.readAll(7, 400);
      assertEquals(
          Long.valueOf(400), store.page(7, "all", null, Long.MAX_VALUE, 100).get(0).readAt());
      assertNull(store.page(7, "all", null, Long.MAX_VALUE, 100).get(1).readAt());
    }
  }

  @Test
  public void paginationTraversesHiddenRowsWithoutLeakingTheirCursor() throws Exception {
    try (H2Store store = new H2Store("jdbc:h2:mem:pages")) {
      for (int i = 1; i <= 230; i++)
        store.insert(7, "e" + i, "COMMENT", 8, "p", i, "/c/p/+/" + i, 100);
      var service = new NotificationService(store, (a, n) -> n.change() % 100 == 0);
      var first = service.list(7, "all", null, Long.MAX_VALUE, 1);
      assertEquals(200, first.items().get(0).change());
      assertEquals(Long.valueOf(200), first.next());
      var second = service.list(7, "all", null, first.next(), 1);
      assertEquals(100, second.items().get(0).change());
      assertNull(second.next());
      assertEquals(2, service.count(7));
    }
  }
}
