CREATE TABLE push_subscriptions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint VARCHAR(2048) NOT NULL UNIQUE,
    p256dh VARCHAR(100) NOT NULL,
    auth VARCHAR(100) NOT NULL,
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_success_at TIMESTAMPTZ
);
CREATE INDEX ON push_subscriptions(user_id);

CREATE TABLE notification_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    new_notice_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    schedule_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- sent_at records the dispatch attempt, including failures: prefer at-most-once delivery.
CREATE TABLE notification_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(30) NOT NULL CHECK(kind IN ('NEW_NOTICE', 'SCHEDULE_REMINDER')),
    ref_key BIGINT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, kind, ref_key)
);

-- Withdrawal must clear subscriptions even when push jobs/keys are disabled.
CREATE FUNCTION push_cleanup_withdrawn() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'WITHDRAWN' THEN
        DELETE FROM push_subscriptions WHERE user_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER push_cleanup_withdrawn AFTER UPDATE OF status ON users
    FOR EACH ROW EXECUTE FUNCTION push_cleanup_withdrawn();
