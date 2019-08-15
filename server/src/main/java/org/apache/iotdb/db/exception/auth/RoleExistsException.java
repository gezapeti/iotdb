package org.apache.iotdb.db.exception.auth;

public class RoleExistsException extends AuthException {

  public RoleExistsException(String roleName) {
    super(String.format("Role %s already exists", roleName));
  }
}
