package org.chorus.config;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.User;
import org.chorus.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 启动时播种演示账号（alice/bob/charlie，密码 test123）。
 *
 * <p>替代 data.sql 方案：schema.sql 中的 $$ 函数体无法通过 Spring 的简易脚本解析器执行
 * （按分号切分会拆坏函数体），导致 sql.init 一旦启用即启动失败，data.sql 在 H2 文件库
 * 形态下从未真正生效。本 Runner 以代码方式实现同样的幂等语义——仅当用户表为空时插入。
 */
@Component
@RequiredArgsConstructor
public class SeedUsersRunner implements ApplicationRunner {

    /** BCrypt("test123")，与原 data.sql 中的种子哈希一致 */
    private static final String TEST_PASSWORD_HASH =
            "$2a$10$obTvB.hhHFATH5COug0qaegxB4bpl9/txJ5p30Xb5oGalXYp1pwcK";

    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        long count = userRepository.count();
        if (count > 0) {
            return;
        }
        seed(1000000000000001L, "Alice", "0001", "alice@test.com");
        seed(1000000000000002L, "Bob", "0002", "bob@test.com");
        seed(1000000000000003L, "Charlie", "0003", "charlie@test.com");
        System.out.println("[SeedUsersRunner] seeded 3 demo users (was " + count + ")");
    }

    private void seed(long id, String username, String discriminator, String email) {
        User user = User.builder()
                .id(id)
                .username(username)
                .discriminator(discriminator)
                .email(email)
                .passwordHash(TEST_PASSWORD_HASH)
                .verified(true)
                .locale("zh-CN")
                .createdAt(Instant.now())
                .lastSeen(Instant.now())
                .build();
        userRepository.save(user);
    }
}
