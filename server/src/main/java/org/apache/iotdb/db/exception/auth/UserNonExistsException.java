package org.apache.iotdb.db.exception.auth;

public class UserNonExistsException extends AuthException {

  public UserNonExistsException(String username) {
    super(String.format("User %s does not exist", username));
  }
}
