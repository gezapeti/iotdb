package org.apache.iotdb.db.exception.auth;

import org.apache.iotdb.db.auth.entity.PrivilegeType;

public class PrivilegeExistsException extends AuthException {

  public PrivilegeExistsException(String name, int privilegeId, String path) {
    super(String.format(
        "%s already has %s on %s", name, PrivilegeType.values()[privilegeId], path));
  }
}
