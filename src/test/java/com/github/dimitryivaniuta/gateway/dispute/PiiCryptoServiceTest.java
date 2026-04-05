package com.github.dimitryivaniuta.gateway.dispute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.dispute.api.Dtos;
import com.github.dimitryivaniuta.gateway.dispute.pii.MasterKeyProvider;
import com.github.dimitryivaniuta.gateway.dispute.pii.PiiCryptoService;
import com.github.dimitryivaniuta.gateway.dispute.pii.PiiProperties;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for envelope encryption service.
 */
public class PiiCryptoServiceTest {

  @Test
  void roundtrip_encrypt_decrypt() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
    String masterKeyB64 = Base64.getEncoder().encodeToString(key);

    PiiProperties props = new PiiProperties(masterKeyB64, java.time.Duration.ofDays(90));
    MasterKeyProvider mkp = new MasterKeyProvider(props);
    PiiCryptoService svc = new PiiCryptoService(new ObjectMapper(), mkp);

    UUID caseId = UUID.randomUUID();
    Dtos.CustomerPii pii = new Dtos.CustomerPii("a@b.com", "Alice", "+48123");

    String env = svc.encrypt(caseId, pii);
    assertNotNull(env);

    Dtos.CustomerPii out = svc.decrypt(caseId, env);
    assertEquals("a@b.com", out.email());
    assertEquals("Alice", out.fullName());
    assertEquals("+48123", out.phone());
  }

  @Test
  void wrong_case_id_fails_decryption() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
    String masterKeyB64 = Base64.getEncoder().encodeToString(key);

    PiiCryptoService svc = new PiiCryptoService(new ObjectMapper(), new MasterKeyProvider(new PiiProperties(masterKeyB64, java.time.Duration.ofDays(90))));

    UUID caseId = UUID.randomUUID();
    String env = svc.encrypt(caseId, new Dtos.CustomerPii("x@y.com", "X", null));

    assertThrows(IllegalStateException.class, () -> svc.decrypt(UUID.randomUUID(), env));
  }
}
