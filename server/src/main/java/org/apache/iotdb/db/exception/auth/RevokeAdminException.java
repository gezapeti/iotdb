package org.apache.iotdb.db.exception.auth;

public class RevokeAdminException extends AuthException {

  public RevokeAdminException() {
    super("Invalid operation, administrator must have all privileges");
  }
}
