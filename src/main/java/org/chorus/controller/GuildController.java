package org.chorus.controller;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.*;
import org.chorus.gateway.GatewayWebSocketHandler;
import org.chorus.service.ChannelAccessService;
import org.chorus.service.GuildService;
import org.chorus.service.ChannelService;
import org.chorus.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/guilds")
@RequiredArgsConstructor
public class GuildController {
    private final GuildService guildService;
    private final ChannelService channelService;
    private final ChannelAccessService channelAccess;
    private final GatewayWebSocketHandler gatewayHandler;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> createGuild(@RequestBody Map<String, String> body,
                                                            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new org.chorus.exception.BadRequestException("Guild name required");
        }
        Guild guild = guildService.createGuild(name, userId);
        Map<String, Object> res = new HashMap<>();
        res.put("id", guild.getId().toString());
        res.put("name", guild.getName());
        res.put("owner_id", guild.getOwnerId().toString());
        res.put("member_count", guild.getMemberCount());
        return ResponseEntity.ok(res);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMyGuilds(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<Guild> guilds = guildService.getUserGuilds(userId);
        List<Map<String, Object>> result = guilds.stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", g.getId().toString());
            m.put("name", g.getName());
            m.put("icon", g.getIcon());
            m.put("owner_id", g.getOwnerId().toString());
            m.put("member_count", g.getMemberCount());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{guildId}")
    public ResponseEntity<Map<String, Object>> getGuild(@PathVariable Long guildId,
                                                        Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        channelAccess.requireGuildMember(guildId, userId);
        Guild guild = guildService.getGuild(guildId);
        Map<String, Object> res = new HashMap<>();
        res.put("id", guild.getId().toString());
        res.put("name", guild.getName());
        res.put("icon", guild.getIcon());
        res.put("owner_id", guild.getOwnerId().toString());
        res.put("member_count", guild.getMemberCount());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/{guildId}/channels")
    public ResponseEntity<List<Map<String, Object>>> getChannels(@PathVariable Long guildId,
                                                                   Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        // 获取用户有权限查看的频道
        List<Channel> channels = channelService.getAccessibleChannels(guildId, userId);
        List<Map<String, Object>> result = channels.stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId().toString());
            m.put("guild_id", c.getGuildId().toString());
            m.put("name", c.getName());
            m.put("type", (int) c.getType());
            m.put("position", c.getPosition());
            m.put("parent_id", c.getParentId() != null ? c.getParentId().toString() : null);
            m.put("bitrate", c.getBitrate());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{guildId}/members")
    public ResponseEntity<List<Map<String, Object>>> getMembers(@PathVariable Long guildId,
                                                                Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        channelAccess.requireGuildMember(guildId, userId);
        List<GuildMember> members = guildService.getGuildMembers(guildId);
        // 批量取用户资料:成员列表需要展示用户名,否则无昵称成员只能显示雪花 ID 数字串
        List<Long> userIds = members.stream().map(GuildMember::getUserId).toList();
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(u -> userMap.put(u.getId(), u));
        }
        List<Map<String, Object>> result = members.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("user_id", m.getUserId().toString());
            map.put("nickname", m.getNickname());
            map.put("joined_at", m.getJoinedAt() != null ? m.getJoinedAt().toString() : null);
            User u = userMap.get(m.getUserId());
            if (u != null) {
                map.put("username", u.getUsername());
                map.put("discriminator", u.getDiscriminator());
                map.put("global_name", u.getGlobalName());
                map.put("avatar", u.getAvatar());
            }
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{guildId}/roles")
    public ResponseEntity<List<Map<String, Object>>> getRoles(@PathVariable Long guildId,
                                                              Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        channelAccess.requireGuildMember(guildId, userId);
        List<Role> roles = guildService.getGuildRoles(guildId);
        List<Map<String, Object>> result = roles.stream().map(this::roleToJson).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{guildId}/audit-log")
    public ResponseEntity<List<Map<String, Object>>> getAuditLog(@PathVariable Long guildId,
                                                                  Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(guildService.getAuditLog(guildId, actorId));
    }

    @PatchMapping("/{guildId}")
    public ResponseEntity<Map<String, Object>> updateGuild(@PathVariable Long guildId,
                                                           @RequestBody Map<String, Object> body,
                                                           Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        Guild guild = guildService.updateGuild(guildId, actorId,
                (String) body.get("name"), (String) body.get("icon"));
        gatewayHandler.dispatchToGuild(guildId, "GUILD_UPDATE", guildToJson(guild), null);
        return ResponseEntity.ok(guildToJson(guild));
    }

    @DeleteMapping("/{guildId}")
    public ResponseEntity<Void> deleteGuild(@PathVariable Long guildId, Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        guildService.deleteGuild(guildId, actorId);
        gatewayHandler.dispatchToGuild(guildId, "GUILD_DELETE",
                Map.<String, Object>of("id", guildId.toString()), null);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{guildId}/members/me")
    public ResponseEntity<Void> leaveGuild(@PathVariable Long guildId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        guildService.leaveGuild(guildId, userId);
        gatewayHandler.refreshUserGuilds(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("guild_id", guildId.toString());
        data.put("user_id", userId.toString());
        gatewayHandler.dispatchToGuild(guildId, "GUILD_MEMBER_REMOVE", data, null);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{guildId}/members/me")
    public ResponseEntity<Map<String, Object>> updateMyNickname(@PathVariable Long guildId,
                                                                @RequestBody Map<String, Object> body,
                                                                Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        GuildMember member = guildService.updateNickname(guildId, userId, userId,
                (String) body.get("nickname"));
        Map<String, Object> data = new HashMap<>();
        data.put("guild_id", guildId.toString());
        data.put("user_id", userId.toString());
        data.put("nickname", member.getNickname());
        gatewayHandler.dispatchToGuild(guildId, "GUILD_MEMBER_UPDATE", data, null);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/{guildId}/members/{userId}/roles")
    public ResponseEntity<List<Map<String, Object>>> getMemberRoles(@PathVariable Long guildId,
                                                                    @PathVariable Long userId,
                                                                    Authentication auth) {
        Long viewerId = (Long) auth.getPrincipal();
        channelAccess.requireGuildMember(guildId, viewerId);
        List<Map<String, Object>> result = guildService.getMemberRoles(guildId, userId).stream()
                .map(this::roleToJson).toList();
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{guildId}/members/{userId}")
    public ResponseEntity<Map<String, Object>> updateMember(@PathVariable Long guildId,
                                                            @PathVariable Long userId,
                                                            @RequestBody Map<String, Object> body,
                                                            Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        GuildMember member = guildService.updateNickname(guildId, userId, actorId,
                (String) body.get("nickname"));
        Map<String, Object> data = new HashMap<>();
        data.put("guild_id", guildId.toString());
        data.put("user_id", userId.toString());
        data.put("nickname", member.getNickname());
        gatewayHandler.dispatchToGuild(guildId, "GUILD_MEMBER_UPDATE", data, null);
        return ResponseEntity.ok(data);
    }

    @PutMapping("/{guildId}/members/{userId}/roles")
    public ResponseEntity<Void> assignRoles(@PathVariable Long guildId,
                                            @PathVariable Long userId,
                                            @RequestBody Map<String, Object> body,
                                            Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        List<Long> roleIds = new ArrayList<>();
        if (body.get("role_ids") != null) {
            for (Object o : (List<?>) body.get("role_ids")) {
                roleIds.add(Long.parseLong(o.toString()));
            }
        }
        guildService.assignRoles(guildId, userId, actorId, roleIds);
        Map<String, Object> data = new HashMap<>();
        data.put("guild_id", guildId.toString());
        data.put("user_id", userId.toString());
        data.put("roles", roleIds.stream().map(String::valueOf).toList());
        gatewayHandler.dispatchToGuild(guildId, "GUILD_MEMBER_UPDATE", data, null);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{guildId}/members/{userId}/kick")
    public ResponseEntity<Void> kickMember(@PathVariable Long guildId,
                                           @PathVariable Long userId,
                                           Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        guildService.kickMember(guildId, userId, actorId);
        gatewayHandler.refreshUserGuilds(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("guild_id", guildId.toString());
        data.put("user_id", userId.toString());
        gatewayHandler.dispatchToGuild(guildId, "GUILD_MEMBER_REMOVE", data, null);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{guildId}/members/{userId}/ban")
    public ResponseEntity<Map<String, Object>> banMember(@PathVariable Long guildId,
                                                         @PathVariable Long userId,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        String reason = body != null ? (String) body.get("reason") : null;
        boolean wasMember = guildService.getMember(guildId, userId) != null;
        GuildBan ban = guildService.banMember(guildId, userId, actorId, reason);
        gatewayHandler.refreshUserGuilds(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("guild_id", guildId.toString());
        data.put("user_id", userId.toString());
        data.put("reason", ban.getReason());
        gatewayHandler.dispatchToGuild(guildId, "GUILD_BAN_ADD", data, null);
        // 仅当被禁者当时在公会内才广播成员移除(否则前端会误减 memberCount)
        if (wasMember) {
            gatewayHandler.dispatchToGuild(guildId, "GUILD_MEMBER_REMOVE", data, null);
        }
        return ResponseEntity.ok(data);
    }

    @GetMapping("/{guildId}/bans")
    public ResponseEntity<List<Map<String, Object>>> getBans(@PathVariable Long guildId,
                                                             Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        List<Map<String, Object>> result = guildService.getBans(guildId, actorId).stream()
                .map(b -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("guild_id", b.getGuildId().toString());
                    m.put("user_id", b.getUserId().toString());
                    m.put("reason", b.getReason());
                    m.put("created_at", b.getCreatedAt() != null ? b.getCreatedAt().toString() : null);
                    return m;
                }).toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{guildId}/bans/{userId}")
    public ResponseEntity<Void> unbanMember(@PathVariable Long guildId,
                                            @PathVariable Long userId,
                                            Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        guildService.unbanMember(guildId, userId, actorId);
        Map<String, Object> data = new HashMap<>();
        data.put("guild_id", guildId.toString());
        data.put("user_id", userId.toString());
        gatewayHandler.dispatchToGuild(guildId, "GUILD_BAN_REMOVE", data, null);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{guildId}/roles")
    public ResponseEntity<Map<String, Object>> createRole(@PathVariable Long guildId,
                                                          @RequestBody Map<String, Object> body,
                                                          Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        Role role = guildService.createRole(guildId, actorId,
                (String) body.get("name"),
                body.get("color") != null ? ((Number) body.get("color")).intValue() : null,
                body.get("hoist") != null && (Boolean) body.get("hoist"),
                body.get("permissions") != null ? Long.parseLong(body.get("permissions").toString()) : null,
                body.get("mentionable") != null && (Boolean) body.get("mentionable"));
        Map<String, Object> json = roleToJson(role);
        gatewayHandler.dispatchToGuild(guildId, "ROLE_CREATE", json, null);
        return ResponseEntity.ok(json);
    }

    @PatchMapping("/{guildId}/roles/{roleId}")
    public ResponseEntity<Map<String, Object>> updateRole(@PathVariable Long guildId,
                                                          @PathVariable Long roleId,
                                                          @RequestBody Map<String, Object> body,
                                                          Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        Role role = guildService.updateRole(guildId, roleId, actorId,
                (String) body.get("name"),
                body.get("color") != null ? ((Number) body.get("color")).intValue() : null,
                body.get("hoist") != null ? (Boolean) body.get("hoist") : null,
                body.get("permissions") != null ? Long.parseLong(body.get("permissions").toString()) : null,
                body.get("mentionable") != null ? (Boolean) body.get("mentionable") : null);
        Map<String, Object> json = roleToJson(role);
        gatewayHandler.dispatchToGuild(guildId, "ROLE_UPDATE", json, null);
        return ResponseEntity.ok(json);
    }

    @DeleteMapping("/{guildId}/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(@PathVariable Long guildId,
                                           @PathVariable Long roleId,
                                           Authentication auth) {
        Long actorId = (Long) auth.getPrincipal();
        guildService.deleteRole(guildId, roleId, actorId);
        gatewayHandler.dispatchToGuild(guildId, "ROLE_DELETE",
                Map.<String, Object>of("id", roleId.toString(), "guild_id", guildId.toString()), null);
        return ResponseEntity.ok().build();
    }

    private Map<String, Object> guildToJson(Guild g) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", g.getId().toString());
        m.put("name", g.getName());
        m.put("icon", g.getIcon());
        m.put("owner_id", g.getOwnerId().toString());
        m.put("member_count", g.getMemberCount());
        return m;
    }

    private Map<String, Object> roleToJson(Role r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId().toString());
        m.put("guild_id", r.getGuildId().toString());
        m.put("name", r.getName());
        m.put("color", r.getColor());
        m.put("hoist", r.isHoist());
        m.put("position", r.getPosition());
        m.put("permissions", r.getPermissions() != null ? r.getPermissions().toString() : "0");
        m.put("mentionable", r.isMentionable());
        return m;
    }
}
