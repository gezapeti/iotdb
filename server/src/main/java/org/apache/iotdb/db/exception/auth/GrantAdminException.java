package org.apache.iotdb.db.exception.auth;

public class GrantAdminException extends AuthException {

  public GrantAdminException() {
    super("Invalid operation, administrator already has all privileges");
  }
}
