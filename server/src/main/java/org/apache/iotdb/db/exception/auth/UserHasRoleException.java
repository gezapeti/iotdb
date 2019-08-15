package org.apache.iotdb.db.exception.auth;

public class UserHasRoleException extends AuthException {

  public UserHasRoleException(String username, String roleName) {
    super(String.format("User %s already has role %s", username, roleName));
  }
}
