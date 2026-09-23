package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 接口端到端：成功路径、幂等创建、命名冲突、配额、归属、已释放/不存在的区分错误码。
 */
@SpringBootTest(properties = {
        "ephemeral-env.max-active-leases=2",
        "ephemeral-env.default-ttl-millis=60000",
        "ephemeral-env.scheduler-enabled=false"
})
@AutoConfigureMockMvc
class WebApiTests {

    @BeforeAll
    static void isolateDataFile() throws Exception {
        Path dir = Files.createTempDirectory("ephemeral-web-");
        System.setProperty("ephemeral-env.data-file", dir.resolve("leases.json").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void fullApiFlow() throws Exception {
        // 创建：201
        mvc.perform(post("/api/envs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"web-a","owner":"team-1","ttlMillis":60000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.reason").value("CREATED"))
                .andExpect(jsonPath("$.lease.status").value("ACTIVE"));

        // 重复创建：200 幂等
        mvc.perform(post("/api/envs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"web-a","owner":"team-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.reason").value("IDEMPOTENT_EXISTING"));

        // 命名冲突：409 NAME_CONFLICT
        mvc.perform(post("/api/envs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"web-a","owner":"team-2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NAME_CONFLICT"));

        mvc.perform(post("/api/envs").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"web-b","owner":"team-1"}
                        """)).andExpect(status().isCreated());

        // 配额：409 QUOTA_EXCEEDED
        mvc.perform(post("/api/envs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"web-c","owner":"team-1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("QUOTA_EXCEEDED"));

        // 非归属方续租：409 NOT_OWNER
        mvc.perform(post("/api/envs/web-a/renew").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"team-9"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NOT_OWNER"));

        // 不存在：404 NOT_FOUND；参数非法：400
        mvc.perform(get("/api/envs/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"));
        mvc.perform(post("/api/envs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","owner":"team-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));

        // 正常续租
        mvc.perform(post("/api/envs/web-a/renew").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"team-1","extendMillis":5000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewed").value(true));

        // 释放：成功后再次释放 410 ALREADY_RELEASED
        mvc.perform(post("/api/envs/web-a/release").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"owner":"team-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));
        mvc.perform(get("/api/envs/web-a"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value("ALREADY_RELEASED"));
    }
}
