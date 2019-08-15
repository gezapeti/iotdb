package org.apache.iotdb.db.exception.auth;

public class DeleteDefaultAdminException extends AuthException {

  public DeleteDefaultAdminException() {
    super("Default administrator cannot be deleted");
  }
}
