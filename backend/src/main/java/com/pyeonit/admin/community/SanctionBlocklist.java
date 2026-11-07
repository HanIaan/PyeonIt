package com.pyeonit.admin.community;

/** Auth depends only on this port; implementations must join the caller's transaction. */
public interface SanctionBlocklist {
  void recordOnWithdraw(long userId);

  boolean isBlocked(String provider, String providerUserId);
}
