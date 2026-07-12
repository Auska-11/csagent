package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.security.DemoIdentityToken;
import com.smartcs.smart_cs_agent.security.DemoJwtTokenService;
import com.smartcs.smart_cs_agent.security.SecurityProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 只供本地调试页面取得短期 JWT；生产环境通过配置关闭。 */
@RestController
@RequestMapping("/api/auth/demo")
public class DemoAuthController {

    private final DemoJwtTokenService tokenService;
    private final SecurityProperties properties;

    public DemoAuthController(DemoJwtTokenService tokenService, SecurityProperties properties) {
        this.tokenService = tokenService;
        this.properties = properties;
    }

    @PostMapping("/tokens")
    public ResponseEntity<List<DemoIdentityToken>> issueDemoTokens() {
        if (!properties.isDemoIssuerEnabled()) {
            throw new ApiNotFoundException("Demo token issuer not found");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(tokenService.issueDemoUserTokens());
    }
}
