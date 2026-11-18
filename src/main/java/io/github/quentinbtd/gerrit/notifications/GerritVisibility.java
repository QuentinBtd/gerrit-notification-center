package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.*;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.*;
import com.google.inject.Inject;

public final class GerritVisibility implements NotificationService.Visibility {
  private final PermissionBackend permissions;
  private final ChangeNotes.Factory notes;

  @Inject
  GerritVisibility(PermissionBackend permissions, ChangeNotes.Factory notes) {
    this.permissions = permissions;
    this.notes = notes;
  }

  @Override
  public boolean canSee(int account, Notification n) {
    try {
      var change = notes.createChecked(Project.nameKey(n.project()), Change.id(n.change()));
      return permissions.absentUser(Account.id(account)).change(change).test(ChangePermission.READ);
    } catch (Exception e) {
      return false;
    }
  }
}
