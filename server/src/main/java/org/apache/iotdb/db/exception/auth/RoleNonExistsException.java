package org.apache.iotdb.db.exception.auth;

public class RoleNonExistsException extends AuthException {

  public RoleNonExistsException(String roleName) {
    super(String.format("Role %s does not exist", roleName));
  }
}
