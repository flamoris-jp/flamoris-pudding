package net.flamoris.pudding;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mock notification signing. This protocol is not an adapter for any real provider. */
public final class SandboxPayments {
    private final byte[] key;
    public SandboxPayments(byte[] key){if(key==null||key.length<32||key.length>128)throw new IllegalArgumentException("mock signing key");this.key=key.clone();}
    private byte[] mac(Map<String,Object> notice) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(Json.write(notice).getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public Map<String,Object> issue(UUID order,int revision,Instant now) {
        var notice=Map.<String,Object>of("provider","pudding-mock-v1","event_id",UUID.randomUUID().toString(),"order_id",order.toString(),"source_revision",revision,"issued_at",now.toString());
        return Map.of("notice",notice,"signature",HexFormat.of().formatHex(mac(notice)));
    }
    public UUID verify(Map<String,Object> envelope,Instant now) {
        Input.fields(envelope,"notice signature","");var notice=Input.object(envelope.get("notice"));
        Input.fields(notice,"provider event_id order_id source_revision issued_at","");
        if(!"pudding-mock-v1".equals(notice.get("provider")))throw new IllegalArgumentException("provider");
        Input.uuid(notice,"event_id");Input.integer(notice,"source_revision",0,Integer.MAX_VALUE);
        String signature=Input.text(envelope,"signature",64);if(!signature.matches("[a-f0-9]{64}")||!MessageDigest.isEqual(mac(notice),HexFormat.of().parseHex(signature)))throw new IllegalArgumentException("signature");
        Instant issued=Instant.parse(Input.text(notice,"issued_at",40));
        if(issued.isBefore(now.minusSeconds(300))||issued.isAfter(now.plusSeconds(30)))throw new IllegalArgumentException("notification freshness");
        return Input.uuid(notice,"order_id");
    }
    @Override public String toString(){return "SandboxPayments[redacted]";}
}
