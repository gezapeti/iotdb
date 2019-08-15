package org.apache.iotdb.db.exception.auth;

public class UserNotHasRoleException extends AuthException {

  public UserNotHasRoleException(String username, String roleName) {
    super(String.format("User %s does not have role %s", username, roleName));
  }
}
