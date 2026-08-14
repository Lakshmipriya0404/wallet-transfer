package com.paytm.wallet.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "JWT_SECRET=supersecretkeythatisatleast32byteslongforhmacsha256")
@Testcontainers
class ApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testUnauthenticatedAccessReturns401() throws Exception {
        mockMvc.perform(get("/accounts/me"))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
               .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    @WithMockUser(username = "testUser1")
    void testNegativeAmountReturns400() throws Exception {
        TransferRequest req = new TransferRequest("testUser2", -500L, UUID.randomUUID().toString());
        mockMvc.perform(post("/transfers")
               .contentType(MediaType.APPLICATION_JSON)
               .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(username = "testUser1")
    void testSelfTransferReturns400() throws Exception {
        TransferRequest req = new TransferRequest("testUser1", 500L, UUID.randomUUID().toString());
        mockMvc.perform(post("/transfers")
               .contentType(MediaType.APPLICATION_JSON)
               .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error").value("SELF_TRANSFER"));
    }

    @Test
    @WithMockUser(username = "testUserA")
    void testInsufficientFundsReturns400() throws Exception {
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 100) ON CONFLICT DO NOTHING", "testUserA");
        TransferRequest req = new TransferRequest("testUserB", 500L, UUID.randomUUID().toString());
        mockMvc.perform(post("/transfers")
               .contentType(MediaType.APPLICATION_JSON)
               .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void testReadyzReturns200() throws Exception {
        // Postgres is running, so it should be READY
        mockMvc.perform(get("/readyz"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").value("READY"));
    }
}
