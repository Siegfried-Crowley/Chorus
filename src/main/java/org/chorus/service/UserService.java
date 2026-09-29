package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.User;
import org.chorus.exception.NotFoundException;
import org.chorus.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @Transactional
    public User updateProfile(Long userId, String username, String globalName, String aboutMe) {
        User user = getUser(userId);
        if (username != null && !username.isBlank()) user.setUsername(username);
        if (globalName != null) user.setGlobalName(globalName.isBlank() ? null : globalName);
        if (aboutMe != null) user.setAboutMe(aboutMe.isBlank() ? null : aboutMe);
        return userRepository.save(user);
    }

    @Transactional
    public User updateAvatar(Long userId, String avatarUrl) {
        User user = getUser(userId);
        user.setAvatar(avatarUrl);
        return userRepository.save(user);
    }

    /** 公开资料(不含邮箱/密码等敏感字段) */
    public Map<String, Object> toPublicJson(User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", user.getId().toString());
        m.put("username", user.getUsername());
        m.put("discriminator", user.getDiscriminator());
        m.put("global_name", user.getGlobalName());
        m.put("avatar", user.getAvatar());
        m.put("banner", user.getBanner());
        m.put("accent_color", user.getAccentColor());
        m.put("about_me", user.getAboutMe());
        return m;
    }
}
