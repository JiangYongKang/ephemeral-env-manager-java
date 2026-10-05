package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层集成测试：验证 HTTP 状态码与 reason 字段对各类拒绝原因可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ephemeral.reclaim-interval-millis=3600000",
        "ephemeral.snapshot-path=target/test-snapshots/api-test-snapshot.json"
})
class LeaseApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullApiFlow_withDistinguishableErrors() throws Exception {
        // 创建成功
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"web-env","owner":"alice","ttlMillis":60000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.owner").value("alice"));
        System.out.println("[input] POST /api/leases web-env/alice -> [judge] 200 ACTIVE");

        // 同名不同归属方 -> 409 NAME_CONFLICT
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"web-env","owner":"bob","ttlMillis":60000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NAME_CONFLICT"));
        System.out.println("[input] 同名不同归属方创建 -> [judge] 409 NAME_CONFLICT");

        // 重复同名同归属方 -> 200 幂等
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"web-env","owner":"alice","ttlMillis":60000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
        System.out.println("[input] 同名同归属方重复创建 -> [judge] 200 幂等");

        // 非归属方续租 -> 403 NOT_OWNER
        mvc.perform(post("/api/leases/web-env/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"bob","ttlMillis":60000}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("NOT_OWNER"));
        System.out.println("[input] bob 续租 alice 的环境 -> [judge] 403 NOT_OWNER");

        // 归属方续租成功
        mvc.perform(post("/api/leases/web-env/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"alice","ttlMillis":60000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseVersion").value(2));
        System.out.println("[input] alice 续租 -> [judge] 200 version=2");

        // 不存在 -> 404 NOT_FOUND
        mvc.perform(get("/api/leases/no-such-env"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"));
        System.out.println("[input] GET 不存在的环境 -> [judge] 404 NOT_FOUND");

        // TTL 非法 -> 400 INVALID_TTL
        mvc.perform(post("/api/leases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"bad-ttl","owner":"alice","ttlMillis":999999999}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_TTL"));
        System.out.println("[input] ttlMillis 超过上限 -> [judge] 400 INVALID_TTL");

        // 释放
        mvc.perform(post("/api/leases/web-env/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASED"));
        System.out.println("[input] alice 释放 web-env -> [judge] 200 RELEASED");

        // 释放后续租 -> 409 ALREADY_RELEASED
        mvc.perform(post("/api/leases/web-env/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"alice","ttlMillis":60000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ALREADY_RELEASED"));
        System.out.println("[input] 释放后续租 -> [judge] 409 ALREADY_RELEASED");

        // 重复释放幂等
        mvc.perform(post("/api/leases/web-env/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASED"));
        System.out.println("[input] 重复释放 -> [judge] 200 幂等 RELEASED");

        // 回收进度查询（只读）：当前无未回收干净的环境
        mvc.perform(get("/api/leases/reclaim-pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        System.out.println("[input] GET /api/leases/reclaim-pending -> [judge] 200 空列表");

        // 手动重试：已释放环境 -> ALREADY_RELEASED；不存在 -> NOT_FOUND
        mvc.perform(post("/api/leases/web-env/reclaim-retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_RELEASED"));
        System.out.println("[input] 已释放环境手动重试 -> [judge] 200 ALREADY_RELEASED");
        mvc.perform(post("/api/leases/ghost/reclaim-retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NOT_FOUND"));
        System.out.println("[input] 不存在环境手动重试 -> [judge] 200 NOT_FOUND");
    }
}
