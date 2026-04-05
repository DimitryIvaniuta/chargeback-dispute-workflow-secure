package com.github.dimitryivaniuta.gateway.dispute;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseState;
import com.github.dimitryivaniuta.gateway.dispute.domain.CaseTeam;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * End-to-end controller tests: RBAC, state transitions, audit.
 */
@AutoConfigureMockMvc
public class DisputeCaseControllerIT extends BaseIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper om;

  @MockBean KafkaTemplate<String, Object> kafkaTemplate;

  @Test
  void open_case_and_audit_flow() throws Exception {
    var createReq = om.createObjectNode()
        .put("externalRef", "cb-123")
        .put("customerRef", "customer@example.com")
        .put("amountCents", 1999)
        .put("currency", "USD")
        .put("assignedTeam", "CHARGEBACK")
        .put("description", "dispute");

    String body = mvc.perform(post("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(createReq))
            .with(jwt().jwt(j -> j.subject("alice").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_VIEW","DISPUTE_EDIT")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_VIEW"), new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.externalRef").value("cb-123"))
        .andExpect(jsonPath("$.state").value("OPEN"))
        .andReturn().getResponse().getContentAsString();

    JsonNode created = om.readTree(body);
    String caseId = created.get("id").asText();

    // Change state
    var stateReq = om.createObjectNode()
        .put("targetState", "UNDER_REVIEW")
        .put("note", "ok");

    mvc.perform(put("/api/cases/{id}/state", caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(stateReq))
            .with(jwt().jwt(j -> j.subject("alice").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_EDIT")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("UNDER_REVIEW"));

    // Audit contains STATE_CHANGED
    mvc.perform(get("/api/cases/{id}/audit", caseId)
            .with(jwt().jwt(j -> j.subject("alice").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_VIEW")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_VIEW"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].action").exists());
  }

  @Test
  void opening_case_for_other_team_is_forbidden_for_non_admin() throws Exception {
    var createReq = om.createObjectNode()
        .put("externalRef", "cb-999")
        .put("customerRef", "x@y.com")
        .put("amountCents", 100)
        .put("currency", "USD")
        .put("assignedTeam", "FRAUD");

    mvc.perform(post("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(createReq))
            .with(jwt().jwt(j -> j.subject("bob").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_EDIT")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isForbidden());
  }

  @Test
  void cannot_edit_case_from_other_team() throws Exception {
    // create as admin on FRAUD team
    var createReq = om.createObjectNode()
        .put("externalRef", "cb-777")
        .put("customerRef", "c@c.com")
        .put("amountCents", 100)
        .put("currency", "USD")
        .put("assignedTeam", "FRAUD");

    String body = mvc.perform(post("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(createReq))
            .with(jwt().jwt(j -> j.subject("admin").claim("team", "SUPPORT").claim("roles", List.of("DISPUTE_ADMIN","DISPUTE_EDIT","DISPUTE_VIEW")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_ADMIN"), new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("ROLE_DISPUTE_VIEW"))
            ))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    String caseId = om.readTree(body).get("id").asText();

    // edit attempt by CHARGEBACK editor should be denied (team mismatch)
    var stateReq = om.createObjectNode().put("targetState", "UNDER_REVIEW");

    mvc.perform(put("/api/cases/{id}/state", caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(stateReq))
            .with(jwt().jwt(j -> j.subject("alice").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_EDIT")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isForbidden());
  }


  @Test
  void pii_is_only_returned_to_pii_viewers() throws Exception {
    var createReq = om.createObjectNode()
        .put("externalRef", "cb-pii-1")
        .put("customerRef", "cust-1")
        .put("amountCents", 100)
        .put("currency", "USD")
        .put("assignedTeam", "CHARGEBACK");

    var pii = om.createObjectNode()
        .put("email", "john@example.com")
        .put("fullName", "John Doe")
        .put("phone", "+48123456789");
    createReq.set("pii", pii);

    String body = mvc.perform(post("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(createReq))
            .with(jwt().jwt(j -> j.subject("alice").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_EDIT")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.externalRef").value("cb-pii-1"))
        .andExpect(jsonPath("$.pii").doesNotExist())
        .andReturn().getResponse().getContentAsString();

    String id = om.readTree(body).get("id").asText();

    // Regular viewer: no PII
    mvc.perform(get("/api/cases/" + id)
            .with(jwt().jwt(j -> j.subject("bob").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_VIEW")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_VIEW"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pii").doesNotExist());

    // PII viewer: PII is present
    mvc.perform(get("/api/cases/" + id)
            .with(jwt().jwt(j -> j.subject("pii").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_VIEW","DISPUTE_PII_VIEW")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_VIEW"), new SimpleGrantedAuthority("ROLE_DISPUTE_PII_VIEW"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pii.email").value("john@example.com"))
        .andExpect(jsonPath("$.pii.fullName").value("John Doe"));
  }

  @Test
  void presign_upload_and_download_endpoints_work() throws Exception {
    var createReq = om.createObjectNode()
        .put("externalRef", "cb-att-1")
        .put("customerRef", "cust-2")
        .put("amountCents", 200)
        .put("currency", "USD")
        .put("assignedTeam", "CHARGEBACK");

    String body = mvc.perform(post("/api/cases")
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(createReq))
            .with(jwt().jwt(j -> j.subject("alice").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_EDIT")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String caseId = om.readTree(body).get("id").asText();

    var req = om.createObjectNode()
        .put("filename", "evidence.pdf")
        .put("contentType", "application/pdf")
        .put("sizeBytes", 1234)
        .put("sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    String presign = mvc.perform(post("/api/cases/" + caseId + "/attachments/presign-upload")
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(req))
            .with(jwt().jwt(j -> j.subject("alice").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_EDIT")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_EDIT"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
        .andReturn().getResponse().getContentAsString();

    String attachmentId = om.readTree(presign).get("attachmentId").asText();

    mvc.perform(get("/api/cases/" + caseId + "/attachments/" + attachmentId + "/presign-download")
            .with(jwt().jwt(j -> j.subject("bob").claim("team", "CHARGEBACK").claim("roles", List.of("DISPUTE_VIEW")))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPUTE_VIEW"), new SimpleGrantedAuthority("TEAM_CHARGEBACK"))
            ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
  }

}
