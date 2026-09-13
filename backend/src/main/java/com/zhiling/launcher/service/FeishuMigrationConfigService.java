package com.zhiling.launcher.service;

import com.zhiling.launcher.api.FeishuMigrationConfigRequest;
import com.zhiling.launcher.api.FeishuMigrationConfigView;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/** 管理租户级飞书迁移配置，并在保存时校验知识库节点归属的空间。 */
@Service
public class FeishuMigrationConfigService {

    private static final Pattern WIKI_PATH = Pattern.compile("/wiki/([^/]+)", Pattern.CASE_INSENSITIVE);
    private final Map<String, StoredConfig> configs = new ConcurrentHashMap<>();
    private final RestClient restClient;

    public FeishuMigrationConfigService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl("https://open.feishu.cn")
                .requestFactory(factory)
                .build();
    }

    public FeishuMigrationConfigView get(String tenantName) {
        String key = normalizeTenant(tenantName);
        StoredConfig config = configs.get(key);
        return config == null ? emptyView(key) : config.view();
    }

    public FeishuMigrationConfigView save(FeishuMigrationConfigRequest request) {
        requireAdmin(request.role());
        String tenant = normalizeTenant(request.tenantName());
        String appId = request.appId().trim();
        String appSecret = request.appSecret().trim();
        String url = request.knowledgeBaseUrl().trim();
        String parentToken = parseWikiToken(url);

        Verification verification = verifyNode(appId, appSecret, parentToken);
        StoredConfig config = new StoredConfig(
                tenant,
                appId,
                appSecret,
                url,
                parentToken,
                verification.spaceId(),
                verification.status(),
                verification.message(),
                Instant.now()
        );
        configs.put(tenant, config);
        return config.view();
    }

    public void requireAdmin(String role) {
        if (role == null || !"ADMIN".equalsIgnoreCase(role.trim())) {
            throw new ResponseStatusException(FORBIDDEN, "仅企业管理员可以配置飞书文档迁移参数");
        }
    }

    /** 从飞书 wiki URL 中提取节点 token，允许用户粘贴带查询参数的完整链接。 */
    static String parseWikiToken(String knowledgeBaseUrl) {
        final URI uri;
        try {
            uri = new URI(knowledgeBaseUrl.trim());
        } catch (URISyntaxException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "企业知识库地址格式不正确");
        }
        Matcher matcher = WIKI_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!matcher.find() || matcher.group(1).isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "企业知识库地址必须是 /wiki/{token} 格式");
        }
        return matcher.group(1);
    }

    private Verification verifyNode(String appId, String appSecret, String parentToken) {
        try {
            Map<?, ?> tokenBody = restClient.post()
                    .uri("/open-apis/auth/v3/tenant_access_token/internal")
                    .body(Map.of("app_id", appId, "app_secret", appSecret))
                    .retrieve()
                    .body(Map.class);
            String tenantToken = stringValue(tokenBody, "tenant_access_token");
            if (tenantToken == null || tenantToken.isBlank()) {
                return new Verification(null, "INVALID_CREDENTIALS", "无法获取 tenant_access_token，请检查 app_id/app_secret 或应用配置");
            }
            Map<?, ?> nodeBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/open-apis/wiki/v2/spaces/get_node")
                            .queryParam("token", parentToken)
                            .queryParam("obj_type", "wiki")
                            .build())
                    .header("Authorization", "Bearer " + tenantToken)
                    .retrieve()
                    .body(Map.class);
            String spaceId = nestedString(nodeBody, "data", "node", "space_id");
            if (spaceId == null || spaceId.isBlank()) {
                return new Verification(null, "NO_ACCESS", "已解析节点 token，但应用无法读取该知识库，请将应用加入知识库并授予读取权限");
            }
            return new Verification(spaceId, "VERIFIED", "知识库地址和应用权限校验成功");
        } catch (Exception ex) {
            return new Verification(null, "VERIFY_PENDING", "配置已保存，暂未完成飞书校验：请确认应用权限和网络连通性");
        }
    }

    private static String normalizeTenant(String tenantName) {
        if (tenantName == null || tenantName.trim().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "租户名称不能为空");
        }
        return tenantName.trim();
    }

    private static String stringValue(Map<?, ?> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String nestedString(Map<?, ?> root, String first, String second, String third) {
        Object levelOne = root == null ? null : root.get(first);
        if (!(levelOne instanceof Map<?, ?> mapOne)) return null;
        Object levelTwo = mapOne.get(second);
        if (!(levelTwo instanceof Map<?, ?> mapTwo)) return null;
        return stringValue(mapTwo, third);
    }

    private static FeishuMigrationConfigView emptyView(String tenant) {
        return new FeishuMigrationConfigView(tenant, "", "", "", "", "NOT_CONFIGURED", "尚未配置飞书文档迁移参数", false, null);
    }

    private record Verification(String spaceId, String status, String message) {
    }

    private record StoredConfig(
            String tenantName,
            String appId,
            String appSecret,
            String knowledgeBaseUrl,
            String parentWikiToken,
            String spaceId,
            String verificationStatus,
            String verificationMessage,
            Instant updatedAt
    ) {
        FeishuMigrationConfigView view() {
            return new FeishuMigrationConfigView(tenantName, appId, knowledgeBaseUrl, parentWikiToken, spaceId,
                    verificationStatus, verificationMessage, true, updatedAt);
        }
    }
}
