-- ============================================================================
-- Chorus — MySQL 测试数据 (JPA 建表后由 sql.init 加载)
-- 测试账号密码: test123
-- ============================================================================

INSERT INTO users (id, username, discriminator, email, password_hash, verified, locale, flags, premium_type, public_flags, mfa_enabled, created_at, last_seen) VALUES
(1000000000000001, 'Alice',   '0001', 'alice@test.com',
 '$2a$10$obTvB.hhHFATH5COug0qaegxB4bpl9/txJ5p30Xb5oGalXYp1pwcK', TRUE, 'zh-CN', 0, 0, 0, FALSE, NOW(), NOW())
ON DUPLICATE KEY UPDATE username = VALUES(username);

INSERT INTO users (id, username, discriminator, email, password_hash, verified, locale, flags, premium_type, public_flags, mfa_enabled, created_at, last_seen) VALUES
(1000000000000002, 'Bob',     '0002', 'bob@test.com',
 '$2a$10$obTvB.hhHFATH5COug0qaegxB4bpl9/txJ5p30Xb5oGalXYp1pwcK', TRUE, 'zh-CN', 0, 0, 0, FALSE, NOW(), NOW())
ON DUPLICATE KEY UPDATE username = VALUES(username);

INSERT INTO users (id, username, discriminator, email, password_hash, verified, locale, flags, premium_type, public_flags, mfa_enabled, created_at, last_seen) VALUES
(1000000000000003, 'Charlie', '0003', 'charlie@test.com',
 '$2a$10$obTvB.hhHFATH5COug0qaegxB4bpl9/txJ5p30Xb5oGalXYp1pwcK', TRUE, 'en-US', 0, 0, 0, FALSE, NOW(), NOW())
ON DUPLICATE KEY UPDATE username = VALUES(username);
