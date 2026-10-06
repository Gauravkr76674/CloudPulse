-- ---------------------------------------------------------------------------
-- Optional demo data. The application also seeds users and mock resources on
-- startup (see com.cloudops.agent.config.DataSeeder), so this file is only for
-- inspecting the schema from a SQL client without running the app.
--
--   mysql -u root -p cloudops < db/seed.sql
--
-- The password_hash values below are real BCrypt ($2a$, cost 10) hashes of
-- "Admin@12345", "Operator@12345" and "Viewer@12345" respectively, verified
-- against BCryptPasswordEncoder's algorithm. They are demo credentials only:
-- change them before any deployment that is reachable by anyone else.
--
-- Note: these INSERTs are IGNORE-d if the application has already created the
-- accounts on startup, in which case BOOTSTRAP_ADMIN_PASSWORD is the password
-- for all three and the hashes below are never used.
-- ---------------------------------------------------------------------------

USE cloudops;

INSERT IGNORE INTO users (username, email, password_hash, full_name, role, enabled, created_at)
VALUES
 ('admin', 'admin@cloudops.local',
  '$2a$10$YaudhDQspm14dgQmJQkbweS02/g6AOR0K2uM5bPf52926u6WttBia',
  'Platform Admin', 'ADMIN', b'1', NOW(6)),
 ('operator', 'operator@cloudops.local',
  '$2a$10$Lm7yqbaRRIzMbxVMx83C3O6nTmOOi.HbUWgbHQVS8DwE.6V7TgkHi',
  'Ops Engineer', 'OPERATOR', b'1', NOW(6)),
 ('viewer', 'viewer@cloudops.local',
  '$2a$10$6pc0sAwV743C8PSgKZed9eE.jDQNfu6fBb3ur/8WJyLxbs9TjuRuy',
  'Read Only', 'VIEWER', b'1', NOW(6));

INSERT IGNORE INTO cloud_resources
 (resource_id, name, type, region, availability_zone, instance_type, status,
  monitored, source, tags_json, private_ip, cpu_threshold_percent, created_by, created_at)
VALUES
 ('i-0demoweb0000000a1', 'demo-web-01', 'EC2_INSTANCE', 'ap-south-1', 'ap-south-1a',
  't3.micro', 'RUNNING', b'1', 'MOCK', '{"Name":"demo-web-01","Env":"dev"}',
  '10.0.1.21', 90, 'system', NOW(6)),
 ('i-0demoapi0000000b2', 'demo-api-02', 'EC2_INSTANCE', 'ap-south-1', 'ap-south-1b',
  't3.micro', 'RUNNING', b'1', 'MOCK', '{"Name":"demo-api-02","Env":"dev"}',
  '10.0.2.34', 85, 'system', NOW(6));
