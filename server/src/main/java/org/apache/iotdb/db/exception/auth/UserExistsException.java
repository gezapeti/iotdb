package org.apache.iotdb.db.exception.auth;

public class UserExistsException extends AuthException {

  public UserExistsException(String username) {
    super(String.format("User %s already exists", username));
  }
}
