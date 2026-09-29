package org.chorus;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Map;

/**
 * 集成测试基类：
 * - RANDOM_PORT 启动真实 Tomcat（满足 WebSocket ServletContainer 依赖）
 * - 用 JdkClientHttpRequestFactory（Java HttpClient），避免 HttpURLConnection 对
 *   401/403 响应抛 HttpRetryException 的怪癖，从而能正常断言错误状态码与 JSON body
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    @LocalServerPort
    protected int port;

    protected final RestTemplate rest = new RestTemplate(new JdkClientHttpRequestFactory());

    {
        // 4xx/5xx 不抛异常，便于断言错误状态码与 JSON body
        rest.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) { return false; }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {}
        });
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected String login(String email, String password) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rest.postForObject(
                url("/api/auth/login"),
                Map.of("email", email, "password", password),
                Map.class);
        return body != null && body.get("token") != null ? body.get("token").toString() : null;
    }
}
