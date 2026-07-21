package com.bosco.mine.server.modules.notification.provider.aliyun;

import java.util.Objects;

/**
 * 隔离阿里云短信 Provider 与官方 Java SDK。
 */
@FunctionalInterface
public interface AliyunSmsGateway {

    /** 调用阿里云 SendSms。 */
    Response send(Command command);

    /**
     * 阿里云短信发送命令。
     *
     * @param endpoint 官方服务端点
     * @param regionId 地域编码
     * @param accessKeyId 访问密钥标识
     * @param accessKeySecret 访问密钥秘密
     * @param phoneNumbers 接收号码
     * @param signName 短信签名
     * @param templateCode 模板编码
     * @param templateParamJson 模板变量 JSON
     * @param outId 外部流水号
     * @param connectTimeoutMs 连接超时
     * @param readTimeoutMs 读取超时
     */
    record Command(
            String endpoint,
            String regionId,
            String accessKeyId,
            String accessKeySecret,
            String phoneNumbers,
            String signName,
            String templateCode,
            String templateParamJson,
            String outId,
            int connectTimeoutMs,
            int readTimeoutMs
    ) {
        /** 校验短信发送命令。 */
        public Command {
            Objects.requireNonNull(endpoint, "阿里云短信端点不能为空");
            Objects.requireNonNull(regionId, "阿里云短信地域不能为空");
            Objects.requireNonNull(accessKeyId, "阿里云短信 AccessKeyId 不能为空");
            Objects.requireNonNull(accessKeySecret, "阿里云短信 AccessKeySecret 不能为空");
            Objects.requireNonNull(phoneNumbers, "阿里云短信号码不能为空");
            Objects.requireNonNull(signName, "阿里云短信签名不能为空");
            Objects.requireNonNull(templateCode, "阿里云短信模板不能为空");
            Objects.requireNonNull(templateParamJson, "阿里云短信模板参数不能为空");
            Objects.requireNonNull(outId, "阿里云短信外部流水不能为空");
        }

        /** 返回不包含密钥、手机号和模板参数的安全日志摘要。 */
        @Override
        public String toString() {
            return "Command[endpoint=" + endpoint
                    + ", regionId=" + regionId
                    + ", outId=" + outId
                    + ", connectTimeoutMs=" + connectTimeoutMs
                    + ", readTimeoutMs=" + readTimeoutMs + "]";
        }
    }

    /** 阿里云短信业务响应。 */
    record Response(String code, String bizId) {
        /** 校验短信响应。 */
        public Response {
            Objects.requireNonNull(code, "阿里云短信响应码不能为空");
        }
    }
}
