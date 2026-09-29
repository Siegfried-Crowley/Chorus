package org.chorus.controller;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.Channel;
import org.chorus.entity.Guild;
import org.chorus.entity.Invite;
import org.chorus.gateway.GatewayWebSocketHandler;
import org.chorus.service.GuildService;
import org.chorus.service.InviteService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InviteController {
    private final InviteService inviteService;
    private final GuildService guildService;
    private final GatewayWebSocketHandler gatewayHandler;
    private final org.chorus.repository.UserRepository userRepository;

    @PostMapping("/guilds/{guildId}/invites")
    public ResponseEntity<Map<String, Object>> createInvite(@PathVariable Long guildId,
                                                            @RequestBody Map<String, Object> body,
                                                            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long channelId = body.get("channel_id") != null
                ? Long.parseLong(body.get("channel_id").toString()) : null;
        Integer maxUses = body.get("max_uses") != null
                ? ((Number) body.get("max_uses")).intValue() : null;
        Integer maxAge = body.get("max_age") != null
                ? ((Number) body.get("max_age")).intValue() : null;
        Invite invite = inviteService.createInvite(guildId, userId, channelId, maxUses, maxAge);
        return ResponseEntity.ok(toJson(invite));
    }

    @GetMapping("/guilds/{guildId}/invites")
    public ResponseEntity<List<Map<String, Object>>> listInvites(@PathVariable Long guildId,
                                                                 Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<Map<String, Object>> result = inviteService.getInvites(guildId, userId).stream()
                .map(this::toJson).toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/invites/{code}")
    public ResponseEntity<Void> deleteInvite(@PathVariable String code, Authentication auth) {
        inviteService.deleteInvite(code, (Long) auth.getPrincipal());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/invites/{code}")
    public ResponseEntity<Map<String, Object>> previewInvite(@PathVariable String code) {
        return ResponseEntity.ok(inviteService.getInvitePreview(code));
    }

    @PostMapping("/invites/{code}/join")
    public ResponseEntity<Map<String, Object>> joinInvite(@PathVariable String code,
                                                          Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Invite invite = inviteService.joinInvite(code, userId);

        // 加入成功后刷新该用户在线会话缓存的公会集合,否则新公会收不到后续广播
        gatewayHandler.refreshUserGuilds(userId);

        // 广播 GUILD_MEMBER_ADD
        Map<String, Object> addData = new HashMap<>();
        addData.put("guild_id", invite.getGuildId().toString());
        addData.put("user_id", userId.toString());
        org.chorus.entity.GuildMember joined = guildService.getMember(invite.getGuildId(), userId);
        addData.put("joined_at", joined != null && joined.getJoinedAt() != null
                ? joined.getJoinedAt().toString() : java.time.Instant.now().toString());
        addData.put("roles", java.util.List.of());
        // 附上用户资料,避免成员列表在收到广播时只能显示雪花 ID
        org.chorus.entity.User joinedUser = userRepository.findById(userId).orElse(null);
        if (joinedUser != null) {
            addData.put("username", joinedUser.getUsername());
            addData.put("global_name", joinedUser.getGlobalName());
            addData.put("avatar", joinedUser.getAvatar());
        }
        gatewayHandler.dispatchToGuild(invite.getGuildId(), "GUILD_MEMBER_ADD", addData, null);

        // 返回公会 + 频道列表
        Guild guild = guildService.getGuild(invite.getGuildId());
        Map<String, Object> result = new HashMap<>();
        result.put("guild_id", guild.getId().toString());
        result.put("name", guild.getName());
        result.put("icon", guild.getIcon());
        result.put("owner_id", guild.getOwnerId().toString());
        result.put("channels", guildService.getGuildChannels(invite.getGuildId()).stream()
                .map(c -> {
                    Map<String, Object> ch = new HashMap<>();
                    ch.put("id", c.getId().toString());
                    ch.put("name", c.getName());
                    ch.put("type", (int) c.getType());
                    ch.put("parent_id", c.getParentId() != null ? c.getParentId().toString() : null);
                    return ch;
                }).toList());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toJson(Invite invite) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", invite.getCode());
        m.put("guild_id", invite.getGuildId().toString());
        m.put("channel_id", invite.getChannelId().toString());
        m.put("inviter_id", invite.getInviterId().toString());
        m.put("max_uses", invite.getMaxUses());
        m.put("max_age", invite.getMaxAge());
        m.put("uses", invite.getUses());
        m.put("created_at", invite.getCreatedAt() != null ? invite.getCreatedAt().toString() : null);
        m.put("expires_at", invite.getExpiresAt() != null ? invite.getExpiresAt().toString() : null);
        return m;
    }
}
