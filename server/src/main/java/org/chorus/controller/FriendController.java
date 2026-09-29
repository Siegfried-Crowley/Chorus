package org.chorus.controller;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.DmChannel;
import org.chorus.gateway.GatewayWebSocketHandler;
import org.chorus.service.FriendService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {
    private final FriendService friendService;
    private final GatewayWebSocketHandler gatewayHandler;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getFriends(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(friendService.getRelationships(userId));
    }

    @PostMapping("/requests")
    public ResponseEntity<Void> sendFriendRequest(@RequestBody Map<String, String> body,
                                                   Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long targetId = Long.parseLong(body.get("user_id"));
        friendService.sendFriendRequest(userId, targetId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/requests/{userId}/accept")
    public ResponseEntity<Void> acceptRequest(@PathVariable Long userId, Authentication auth) {
        Long currentUserId = (Long) auth.getPrincipal();
        DmChannel dm = friendService.acceptFriendRequest(currentUserId, userId);
        // 实时通知双方：关系已变 + 已建 DM 频道
        gatewayHandler.dispatchRelationshipUpdate(currentUserId, userId, dm);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/requests/{userId}/reject")
    public ResponseEntity<Void> rejectRequest(@PathVariable Long userId, Authentication auth) {
        Long currentUserId = (Long) auth.getPrincipal();
        friendService.rejectFriendRequest(currentUserId, userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{friendId}")
    public ResponseEntity<Void> removeFriend(@PathVariable Long friendId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        friendService.removeFriend(userId, friendId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/blocks")
    public ResponseEntity<Void> blockUser(@RequestBody Map<String, String> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long blockedId = Long.parseLong(body.get("user_id"));
        friendService.blockUser(userId, blockedId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/blocks/{blockedId}")
    public ResponseEntity<Void> unblockUser(@PathVariable Long blockedId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        friendService.unblockUser(userId, blockedId);
        return ResponseEntity.ok().build();
    }
}
