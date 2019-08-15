package org.apache.iotdb.db.exception.auth;

public class IllegalPasswordException extends AuthException {

  public IllegalPasswordException(String newPassword) {
    super("password " + newPassword + " is illegal");
  }
}
