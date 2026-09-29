-- ============================================================================
-- Chorus — Complete Database Schema
-- ============================================================================

-- Snowflake ID 生成函数 (snowflake 64-bit ID)
CREATE SEQUENCE IF NOT EXISTS snowflake_seq START 1;

CREATE OR REPLACE FUNCTION generate_snowflake(worker_id INTEGER DEFAULT 1)
RETURNS BIGINT AS $$
DECLARE
    epoch BIGINT := 1700000000000;  -- 自定义 epoch (2023-11-15)
    now_ms BIGINT := EXTRACT(EPOCH FROM clock_timestamp()) * 1000;
    seq INTEGER;
BEGIN
    seq := (nextval('snowflake_seq') - 1) % 4096;
    RETURN ((now_ms - epoch) << 22) |
           ((worker_id & 1023) << 12) |
           (seq & 4095);
END;
$$ LANGUAGE plpgsql;

-- ==================== 用户系统 ====================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    discriminator VARCHAR(4) NOT NULL DEFAULT '0000',
    global_name VARCHAR(32),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    avatar VARCHAR(255),
    banner VARCHAR(255),
    accent_color INTEGER,
    about_me TEXT DEFAULT '',
    locale VARCHAR(10) DEFAULT 'zh-CN',
    mfa_enabled BOOLEAN DEFAULT FALSE,
    verified BOOLEAN DEFAULT FALSE,
    flags INTEGER DEFAULT 0,
    premium_type SMALLINT DEFAULT 0,
    public_flags INTEGER DEFAULT 0,
    last_seen TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 服务器 (Guild) ====================
CREATE TABLE IF NOT EXISTS guilds (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    icon VARCHAR(255),
    splash VARCHAR(255),
    banner VARCHAR(255),
    owner_id BIGINT NOT NULL REFERENCES users(id),
    afk_channel_id BIGINT,
    afk_timeout INTEGER DEFAULT 300,
    verification_level SMALLINT DEFAULT 0,
    default_message_notifications SMALLINT DEFAULT 0,
    explicit_content_filter SMALLINT DEFAULT 0,
    mfa_level SMALLINT DEFAULT 0,
    system_channel_id BIGINT,
    rules_channel_id BIGINT,
    public_updates_channel_id BIGINT,
    preferred_locale VARCHAR(10) DEFAULT 'zh-CN',
    premium_tier SMALLINT DEFAULT 0,
    member_count INTEGER DEFAULT 0,
    max_members INTEGER DEFAULT 100000,
    max_presences INTEGER DEFAULT 50000,
    max_video_channel_users INTEGER DEFAULT 25,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 公会成员
CREATE TABLE IF NOT EXISTS guild_members (
    guild_id BIGINT NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nickname VARCHAR(32),
    avatar VARCHAR(255),
    joined_at TIMESTAMPTZ DEFAULT NOW(),
    deaf BOOLEAN DEFAULT FALSE,
    mute BOOLEAN DEFAULT FALSE,
    pending BOOLEAN DEFAULT FALSE,
    PRIMARY KEY (guild_id, user_id)
);

-- ==================== 角色系统 ====================
CREATE TABLE IF NOT EXISTS roles (
    id BIGINT PRIMARY KEY,
    guild_id BIGINT NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    color INTEGER DEFAULT 0,
    hoist BOOLEAN DEFAULT FALSE,
    position INTEGER DEFAULT 0,
    permissions BIGINT NOT NULL DEFAULT '0',
    managed BOOLEAN DEFAULT FALSE,
    mentionable BOOLEAN DEFAULT FALSE,
    icon VARCHAR(255),
    unicode_emoji VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS member_roles (
    guild_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (guild_id, user_id, role_id),
    FOREIGN KEY (guild_id, user_id) REFERENCES guild_members(guild_id, user_id) ON DELETE CASCADE
);

-- ==================== 频道系统 ====================
CREATE TABLE IF NOT EXISTS channels (
    id BIGINT PRIMARY KEY,
    guild_id BIGINT,
    name VARCHAR(100),
    topic TEXT DEFAULT '',
    type SMALLINT NOT NULL DEFAULT 0,
    position INTEGER DEFAULT 0,
    parent_id BIGINT,
    nsfw BOOLEAN DEFAULT FALSE,
    bitrate INTEGER DEFAULT 64000,
    user_limit INTEGER DEFAULT 0,
    rtc_region VARCHAR(50),
    video_quality_mode SMALLINT DEFAULT 1,
    rate_limit_per_user INTEGER DEFAULT 0,
    last_message_id BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- DM 频道
CREATE TABLE IF NOT EXISTS dm_channels (
    id BIGINT PRIMARY KEY,
    type SMALLINT DEFAULT 1,
    name VARCHAR(100),
    icon VARCHAR(255),
    owner_id BIGINT,
    last_message_id BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS dm_channel_members (
    channel_id BIGINT NOT NULL REFERENCES dm_channels(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (channel_id, user_id)
);

-- ==================== 消息系统 (分区表) ====================
CREATE TABLE IF NOT EXISTS messages (
    id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    guild_id BIGINT,
    author_id BIGINT NOT NULL,
    content TEXT DEFAULT '',
    embeds JSONB DEFAULT '[]',
    attachments JSONB DEFAULT '[]',
    stickers JSONB DEFAULT '[]',
    reactions JSONB DEFAULT '{}',
    mentions JSONB DEFAULT '{}',
    type SMALLINT DEFAULT 0,
    flags INTEGER DEFAULT 0,
    pinned BOOLEAN DEFAULT FALSE,
    mention_everyone BOOLEAN DEFAULT FALSE,
    message_reference JSONB,
    nonce VARCHAR(100),
    edited_timestamp TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (channel_id, id)
) PARTITION BY HASH (channel_id);

-- 创建 8 个分区
CREATE TABLE IF NOT EXISTS messages_0 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE IF NOT EXISTS messages_1 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE IF NOT EXISTS messages_2 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE IF NOT EXISTS messages_3 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE IF NOT EXISTS messages_4 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE IF NOT EXISTS messages_5 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE IF NOT EXISTS messages_6 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE IF NOT EXISTS messages_7 PARTITION OF messages FOR VALUES WITH (MODULUS 8, REMAINDER 7);

-- 消息索引
CREATE INDEX IF NOT EXISTS idx_messages_channel_created ON messages(channel_id, created_at DESC);

-- ==================== 频道权限覆盖 ====================
CREATE TABLE IF NOT EXISTS channel_overwrites (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    type SMALLINT NOT NULL,
    target_id BIGINT NOT NULL,
    allow BIGINT DEFAULT 0,
    deny BIGINT DEFAULT 0,
    UNIQUE (channel_id, type, target_id)
);

-- ==================== 好友关系 ====================
CREATE TABLE IF NOT EXISTS relationships (
    id BIGSERIAL PRIMARY KEY,
    from_id BIGINT NOT NULL REFERENCES users(id),
    to_id BIGINT NOT NULL REFERENCES users(id),
    type SMALLINT NOT NULL DEFAULT 0,
    since TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (from_id, to_id)
);

-- ==================== 语音系统 ====================
-- 语音状态跟踪
CREATE TABLE IF NOT EXISTS voice_states (
    guild_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL,
    self_mute BOOLEAN DEFAULT FALSE,
    self_deaf BOOLEAN DEFAULT FALSE,
    mute BOOLEAN DEFAULT FALSE,
    deaf BOOLEAN DEFAULT FALSE,
    suppress BOOLEAN DEFAULT FALSE,
    request_to_speak_timestamp TIMESTAMPTZ,
    joined_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (guild_id, user_id)
);

-- Voice Server 注册表
CREATE TABLE IF NOT EXISTS voice_servers (
    id VARCHAR(50) PRIMARY KEY,
    ip VARCHAR(45) NOT NULL,
    port INTEGER NOT NULL,
    region VARCHAR(20),
    current_load REAL DEFAULT 0,
    max_sessions INTEGER DEFAULT 500,
    current_sessions INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'online',
    last_heartbeat TIMESTAMPTZ DEFAULT NOW()
);

-- Voice Server 分配
CREATE TABLE IF NOT EXISTS voice_allocations (
    id BIGSERIAL PRIMARY KEY,
    guild_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    server_id VARCHAR(50) NOT NULL,
    token VARCHAR(255) NOT NULL,
    ssrc INTEGER NOT NULL,
    session_id VARCHAR(255),
    allocated_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ
);

-- ==================== 附件/文件系统 ====================
CREATE TABLE IF NOT EXISTS attachments (
    id BIGINT PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    size INTEGER NOT NULL,
    url VARCHAR(512) NOT NULL,
    proxy_url VARCHAR(512),
    width SMALLINT,
    height SMALLINT,
    content_type VARCHAR(100),
    spoiler BOOLEAN DEFAULT FALSE,
    uploaded_by BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    message_id BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 表情系统 ====================
CREATE TABLE IF NOT EXISTS emoji (
    id BIGINT PRIMARY KEY,
    guild_id BIGINT NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,
    name VARCHAR(32) NOT NULL,
    animated BOOLEAN DEFAULT FALSE,
    image_hash VARCHAR(255) NOT NULL,
    roles JSONB DEFAULT '[]',
    uploaded_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 邀请系统 ====================
CREATE TABLE IF NOT EXISTS invites (
    code VARCHAR(8) PRIMARY KEY,
    guild_id BIGINT NOT NULL REFERENCES guilds(id),
    channel_id BIGINT NOT NULL REFERENCES channels(id),
    inviter_id BIGINT NOT NULL REFERENCES users(id),
    max_uses INTEGER DEFAULT 0,
    max_age INTEGER DEFAULT 86400,
    temporary BOOLEAN DEFAULT FALSE,
    uses INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ
);

-- ==================== 审核日志 ====================
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY,
    guild_id BIGINT NOT NULL REFERENCES guilds(id),
    actor_id BIGINT NOT NULL REFERENCES users(id),
    target_id BIGINT,
    action_type SMALLINT NOT NULL,
    changes JSONB,
    options JSONB,
    reason TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 用户会话 (用于 Gateway Resume) ====================
CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(255) UNIQUE NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'online',
    client_info JSONB DEFAULT '{}',
    activities JSONB DEFAULT '[]',
    last_seq INTEGER DEFAULT 0,
    last_activity TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 索引 ====================
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_guilds_owner ON guilds(owner_id);
CREATE INDEX IF NOT EXISTS idx_guild_members_user ON guild_members(user_id);
CREATE INDEX IF NOT EXISTS idx_roles_guild ON roles(guild_id);
CREATE INDEX IF NOT EXISTS idx_channels_guild ON channels(guild_id);
CREATE INDEX IF NOT EXISTS idx_relationships_from ON relationships(from_id);
CREATE INDEX IF NOT EXISTS idx_relationships_to ON relationships(to_id);
CREATE INDEX IF NOT EXISTS idx_dm_members_user ON dm_channel_members(user_id);
CREATE INDEX IF NOT EXISTS idx_voice_states_channel ON voice_states(channel_id);
CREATE INDEX IF NOT EXISTS idx_invites_guild ON invites(guild_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_guild ON audit_log(guild_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON user_sessions(user_id);
