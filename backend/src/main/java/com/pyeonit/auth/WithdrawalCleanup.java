package com.pyeonit.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class WithdrawalCleanup {
  private final JdbcTemplate db;

  WithdrawalCleanup(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void deletePersonalSettings(long userId) {
    db.update("delete from favorite_universities where user_id=?", userId);
    db.update("delete from notification_settings where user_id=?", userId);
    db.update("delete from notification_log where user_id=?", userId);
  }
}
