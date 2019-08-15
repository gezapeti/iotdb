package org.apache.iotdb.db.exception.auth;

import org.apache.iotdb.db.auth.entity.PrivilegeType;

public class PrivilegeNonExistsException extends AuthException {

  public PrivilegeNonExistsException(String name, int privilegeId, String path) {
    super(String.format(
        "%s does not have %s on %s", name, PrivilegeType.values()[privilegeId], path));
  }
}
