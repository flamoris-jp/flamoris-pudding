CREATE TABLE pudding_config (
    singleton boolean PRIMARY KEY CHECK (singleton),
    schema_version integer NOT NULL,
    rules_version text NOT NULL,
    threshold integer NOT NULL CHECK (threshold BETWEEN 1 AND 100)
);
INSERT INTO pudding_config VALUES (true, 1, 'research-rules-v0', 3);
CREATE TABLE pudding_agents (
    id varchar(80) PRIMARY KEY,
    policy bytea NOT NULL CHECK (octet_length(policy) <= 65536)
);
CREATE TABLE pudding_sessions (
    id uuid PRIMARY KEY,
    agent_id varchar(80) NOT NULL REFERENCES pudding_agents(id),
    revision integer NOT NULL CHECK (revision >= 0),
    closed boolean NOT NULL,
    state bytea NOT NULL CHECK (octet_length(state) <= 65536)
);
CREATE UNIQUE INDEX pudding_one_unresolved ON pudding_sessions(agent_id) WHERE NOT closed;
CREATE TABLE pudding_requests (
    agent_id varchar(80) NOT NULL REFERENCES pudding_agents(id),
    request_id uuid NOT NULL,
    command bytea NOT NULL CHECK (octet_length(command) <= 65536),
    response bytea NOT NULL CHECK (octet_length(response) <= 65536),
    PRIMARY KEY(agent_id,request_id)
);
CREATE TABLE pudding_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id varchar(80) NOT NULL REFERENCES pudding_agents(id),
    event_key text NOT NULL,
    occurred_at timestamptz NOT NULL,
    response bytea NOT NULL CHECK (octet_length(response) <= 65536),
    UNIQUE(agent_id,event_key)
);
