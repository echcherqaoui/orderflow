package com.echcherqaoui.orderflow.security.autoconfigure;

import com.echcherqaoui.orderflow.security.service.SignatureService;
import com.echcherqaoui.orderflow.security.service.impl.HmacSignatureService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class SignatureAutoConfiguration {

    /**
     * Registers HmacSignatureService as the default ISignatureService.
     */
    @Bean
    @ConditionalOnProperty(name = "app.security.hmac.enabled", havingValue = "true", matchIfMissing = true)
    public SignatureService signatureService(@Value("${app.security.hmac.secret}") String secret) {
        return new HmacSignatureService(secret);
    }

}