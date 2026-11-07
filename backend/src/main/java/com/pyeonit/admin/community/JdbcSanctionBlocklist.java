package com.pyeonit.admin.community;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcSanctionBlocklist implements SanctionBlocklist {
  private final JdbcTemplate db;
  private final BlocklistHasher hasher;
  private final Clock clock;

  JdbcSanctionBlocklist(JdbcTemplate db, BlocklistHasher hasher, Clock clock) {
    this.db = db;
    this.hasher = hasher;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordOnWithdraw(long userId) {
    // Serialize with sanction creation/release and the expiration worker.
    db.queryForList("select id from users where id=? for update", userId);
    OffsetDateTime now = OffsetDateTime.now(clock);
    var ends =
        db.query(
            """
            select ends_at from sanctions where user_id=? and type in ('SUSPEND','BAN')
            and starts_at<=? and (ends_at is null or ends_at>?)
            """,
            (rs, i) -> rs.getObject(1, OffsetDateTime.class),
            userId,
            now,
            now);
    if (ends.isEmpty()) return;
    OffsetDateTime until =
        ends.stream()
            .map(e -> e == null ? now.plusYears(1) : e)
            .max(OffsetDateTime::compareTo)
            .orElseThrow();
    db
        .query(
            "select provider,provider_user_id from social_accounts where user_id=? order by id",
            (rs, i) -> hasher.hash(rs.getString(1), rs.getString(2)),
            userId)
        .stream()
        .sorted()
        .forEach(
            hash -> {
              lockIdentity(hash);
              db.update(
                  """
                  insert into sanction_blocklist(identity_hash,expires_at) values (?,?)
                  on conflict(identity_hash) do update
                  set expires_at=greatest(sanction_blocklist.expires_at,excluded.expires_at)
                  """,
                  hash,
                  until);
            });
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isBlocked(String provider, String providerUserId) {
    String hash = hasher.hash(provider, providerUserId);
    lockIdentity(hash);
    return Boolean.TRUE.equals(
        db.queryForObject(
            """
            select exists(select 1 from sanction_blocklist where identity_hash=? and expires_at>?)
            """,
            Boolean.class,
            hash,
            OffsetDateTime.now(clock)));
  }

  private void lockIdentity(String hash) {
    // Only a digest-derived number crosses the SQL boundary, never the original identity.
    db.queryForList(
        "select pg_advisory_xact_lock(?)", Long.parseUnsignedLong(hash.substring(0, 16), 16));
  }

  @Transactional
  public void deleteExpired() {
    db.update("delete from sanction_blocklist where expires_at<=?", OffsetDateTime.now(clock));
  }
}
