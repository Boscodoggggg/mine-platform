package com.bosco.mine.server.modules.configuration.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 校验内部配置快照接口仅接受外部 secret 文件中的服务令牌。
 */
class InternalServiceTokenFilterTest {

    @TempDir
    Path tempDir;

    @Test
    void 正确令牌应放行且错误令牌应拒绝() throws Exception {
        Path tokenFile = tempDir.resolve("configuration-token");
        String token = "configuration-token-with-at-least-32-bytes";
        Files.writeString(tokenFile, token);
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(
                new ConfigurationDistributionProperties(true, tokenFile));

        MockHttpServletRequest acceptedRequest = request();
        acceptedRequest.addHeader(InternalServiceTokenFilter.TOKEN_HEADER, token);
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        FilterChain acceptedChain = mock(FilterChain.class);
        filter.doFilter(acceptedRequest, acceptedResponse, acceptedChain);
        verify(acceptedChain).doFilter(acceptedRequest, acceptedResponse);

        MockHttpServletRequest rejectedRequest = request();
        rejectedRequest.addHeader(InternalServiceTokenFilter.TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        FilterChain rejectedChain = mock(FilterChain.class);
        filter.doFilter(rejectedRequest, rejectedResponse, rejectedChain);
        assertThat(rejectedResponse.getStatus()).isEqualTo(401);
        verify(rejectedChain, never()).doFilter(rejectedRequest, rejectedResponse);
    }

    @Test
    void 未启用分发能力时内部接口应隐藏() throws Exception {
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(
                new ConfigurationDistributionProperties(false, tempDir.resolve("missing")));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void 同一服务令牌必须保护策略运行快照接口() throws Exception {
        Path tokenFile = tempDir.resolve("configuration-token");
        String token = "configuration-token-with-at-least-32-bytes";
        Files.writeString(tokenFile, token);
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(
                new ConfigurationDistributionProperties(true, tokenFile));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/v1/strategy-runtime-snapshots/snapshot-id");
        request.addHeader(InternalServiceTokenFilter.TOKEN_HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void 定位运行快照接口缺少服务令牌时必须拒绝() throws Exception {
        Path tokenFile = tempDir.resolve("configuration-token");
        String token = "configuration-token-with-at-least-32-bytes";
        Files.writeString(tokenFile, token);
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(
                new ConfigurationDistributionProperties(true, tokenFile));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/internal/location-snapshots/latest");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void 同一服务令牌必须保护聚合重算认领接口() throws Exception {
        Path tokenFile = tempDir.resolve("configuration-token");
        String token = "configuration-token-with-at-least-32-bytes";
        Files.writeString(tokenFile, token);
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(
                new ConfigurationDistributionProperties(true, tokenFile));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/v1/aggregation-recomputations/claim");
        request.addHeader(InternalServiceTokenFilter.TOKEN_HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/internal/v1/config-releases/release-id");
    }
}
