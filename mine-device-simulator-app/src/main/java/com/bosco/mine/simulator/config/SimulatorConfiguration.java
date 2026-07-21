package com.bosco.mine.simulator.config;

import com.bosco.mine.simulator.payload.PayloadRenderer;
import com.bosco.mine.simulator.profile.ProfileStore;
import com.bosco.mine.simulator.profile.YamlProfileStore;
import com.bosco.mine.simulator.runtime.SimulatorRuntimeService;
import com.bosco.mine.simulator.transport.TransportFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 组装独立模拟器应用所需的核心组件。
 */
@Configuration(proxyBeanMethods = false)
public class SimulatorConfiguration {

    /**
     * 校验管理页面只向本机开放。
     *
     * @param bindAddress 管理页面监听地址
     * @param allowNonLoopback 是否由容器部署层明确限制并允许非回环监听
     * @return 本地绑定安全护栏
     */
    @Bean
    public LocalBindingGuard localBindingGuard(@Value("${server.address}") String bindAddress,
            @Value("${mine.simulator.allow-non-loopback:false}") boolean allowNonLoopback) {
        return new LocalBindingGuard(bindAddress, allowNonLoopback);
    }

    /**
     * 创建 YAML 配置仓库。
     *
     * @param profileFile 配置文件路径
     * @return 配置仓库
     */
    @Bean
    public ProfileStore profileStore(@Value("${mine.simulator.profile-file}") String profileFile) {
        return new YamlProfileStore(Path.of(profileFile));
    }

    /**
     * 创建报文模板渲染器。
     *
     * @return 报文渲染器
     */
    @Bean
    public PayloadRenderer payloadRenderer() {
        return new PayloadRenderer();
    }

    /**
     * 创建传输通道工厂。
     *
     * @return 传输工厂
     */
    @Bean
    public TransportFactory transportFactory() {
        return new TransportFactory();
    }

    /**
     * 创建模拟任务运行服务。
     *
     * @param profileStore 配置仓库
     * @param payloadRenderer 报文渲染器
     * @param transportFactory 传输工厂
     * @return 运行服务
     */
    @Bean
    public SimulatorRuntimeService simulatorRuntimeService(ProfileStore profileStore,
            PayloadRenderer payloadRenderer, TransportFactory transportFactory) {
        return new SimulatorRuntimeService(profileStore, payloadRenderer, transportFactory);
    }
}
