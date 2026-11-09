-- Additive, explicit owner migration. Old 0.3.x runtimes reject schema 2.
SELECT pg_advisory_xact_lock(1347765327);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pudding_config WHERE singleton AND schema_version=1 AND rules_version='research-rules-v0') THEN
  RAISE EXCEPTION 'expected pudding schema 1';
 END IF;
END $$;
ALTER TABLE pudding_agents ADD COLUMN enforcement_revision integer NOT NULL DEFAULT 0 CHECK(enforcement_revision>=0);
CREATE TABLE pudding_accounts (
 tenant_id varchar(80) PRIMARY KEY, human_id varchar(80) NOT NULL UNIQUE,
 last_mutation_at timestamptz NOT NULL DEFAULT '-infinity'
);
CREATE TABLE pudding_bindings (
 agent_id varchar(80) PRIMARY KEY REFERENCES pudding_agents(id),
 tenant_id varchar(80) NOT NULL REFERENCES pudding_accounts(tenant_id),
 external_subject varchar(256) NOT NULL UNIQUE,
 credential_id char(64) NOT NULL UNIQUE,
 epoch integer NOT NULL CHECK(epoch > 0), active boolean NOT NULL
);
CREATE TABLE pudding_products (
 id varchar(80) NOT NULL, version varchar(80) NOT NULL,
 snapshot jsonb NOT NULL CHECK(octet_length(snapshot::text) <= 32768),
 PRIMARY KEY(id,version)
);
CREATE TABLE pudding_orders (
 id uuid PRIMARY KEY, tenant_id varchar(80) NOT NULL REFERENCES pudding_accounts(tenant_id),
 revision integer NOT NULL CHECK(revision >= 0),
 data jsonb NOT NULL CHECK(octet_length(data::text) <= 32768)
);
CREATE INDEX pudding_orders_tenant ON pudding_orders(tenant_id,id);
-- This is an isolated mock provider authority, never a real payment gateway.
CREATE TABLE pudding_mock_payments (
 order_id uuid PRIMARY KEY REFERENCES pudding_orders(id), revision integer NOT NULL,
 data jsonb NOT NULL CHECK(octet_length(data::text) <= 32768)
);
CREATE TABLE pudding_assets (
 id uuid PRIMARY KEY, tenant_id varchar(80) NOT NULL REFERENCES pudding_accounts(tenant_id),
 order_id uuid NOT NULL UNIQUE REFERENCES pudding_orders(id),
 agent_id varchar(80) REFERENCES pudding_bindings(agent_id),
 session_id uuid UNIQUE REFERENCES pudding_sessions(id),
 revision integer NOT NULL CHECK(revision >= 0),
 dispose_after timestamptz NOT NULL,
 data jsonb NOT NULL CHECK(octet_length(data::text) <= 32768)
);
CREATE INDEX pudding_assets_agent ON pudding_assets(agent_id,id);
CREATE INDEX pudding_assets_due ON pudding_assets(dispose_after,id);
CREATE TABLE pudding_grants (
 id uuid PRIMARY KEY, asset_id uuid NOT NULL REFERENCES pudding_assets(id),
 agent_id varchar(80) NOT NULL REFERENCES pudding_bindings(agent_id),
 epoch integer NOT NULL, expires_at timestamptz NOT NULL,
 active boolean NOT NULL, intent_digest char(64) NOT NULL,
 asset_revision integer NOT NULL
);
CREATE UNIQUE INDEX pudding_one_grant ON pudding_grants(asset_id) WHERE active;
CREATE TABLE pudding_api_requests (
 actor varchar(170) NOT NULL, request_id uuid NOT NULL,
 command jsonb NOT NULL, response jsonb NOT NULL,
 PRIMARY KEY(actor,request_id),
 CHECK(octet_length(command::text) <= 32768),
 CHECK(octet_length(response::text) <= 65536)
);
CREATE TABLE pudding_commerce_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(80), actor varchar(170) NOT NULL,
 event_key varchar(200) NOT NULL, occurred_at timestamptz NOT NULL,
 data jsonb NOT NULL CHECK(octet_length(data::text) <= 65536),
 UNIQUE(actor,event_key)
);
CREATE TABLE pudding_candidates (
 id varchar(160) PRIMARY KEY, asset_id uuid NOT NULL REFERENCES pudding_assets(id),
 event_kind varchar(80) NOT NULL, data jsonb NOT NULL,
 UNIQUE(asset_id,event_kind), CHECK(octet_length(data::text) <= 32768)
);
CREATE TABLE pudding_experiences (
 session_id uuid PRIMARY KEY REFERENCES pudding_sessions(id),
 data jsonb NOT NULL CHECK(octet_length(data::text) <= 32768)
);
CREATE TABLE pudding_cleanup_checks (
 session_id uuid PRIMARY KEY REFERENCES pudding_sessions(id),
 agent_id varchar(80) NOT NULL REFERENCES pudding_agents(id), due_at timestamptz NOT NULL,
 resolved boolean NOT NULL DEFAULT false, checked boolean NOT NULL DEFAULT false
);
CREATE TABLE pudding_policy_reviews (
 agent_id varchar(80) NOT NULL REFERENCES pudding_agents(id), obligation_id varchar(200) NOT NULL,
 human_id varchar(80) NOT NULL, reason varchar(512) NOT NULL, reviewed_at timestamptz NOT NULL,
 PRIMARY KEY(agent_id,obligation_id)
);
CREATE TABLE pudding_maintenance (
 singleton boolean PRIMARY KEY CHECK(singleton),
 heartbeat timestamptz, cleanup_hold boolean NOT NULL DEFAULT true,
 last_daily_date date
);
INSERT INTO pudding_maintenance(singleton) VALUES(true);
UPDATE pudding_config SET schema_version=2 WHERE singleton;
