package com.bosco.mine.server.modules.notification.provider.aliyun;

import java.util.Objects;

/**
 * 隔离阿里云语音 Provider 与官方 Java SDK。
 */
@FunctionalInterface
public interface AliyunVoiceGateway {

    /** 调用阿里云 SingleCallByTts。 */
    Response call(Command command);

    /**
     * 阿里云 TTS 单呼命令。
     *
     * @param endpoint 官方服务端点
     * @param regionId 地域编码
     * @param accessKeyId 访问密钥标识
     * @param accessKeySecret 访问密钥秘密
     * @param phoneNumber 被叫号码
     * @param calledShowNumber 被叫侧显示号码
     * @param ttsCode TTS 模板编码
     * @param ttsParamJson TTS 模板变量 JSON
     * @param playTimes 播放次数
     * @param volume 音量
     * @param speed 语速
     * @param outId 外部流水号
     * @param connectTimeoutMs 连接超时
     * @param readTimeoutMs 读取超时
     */
    record Command(
            String endpoint,
            String regionId,
            String accessKeyId,
            String accessKeySecret,
            String phoneNumber,
            String calledShowNumber,
            String ttsCode,
            String ttsParamJson,
            int playTimes,
            int volume,
            int speed,
            String outId,
            int connectTimeoutMs,
            int readTimeoutMs
    ) {
        /** 校验语音命令。 */
        public Command {
            Objects.requireNonNull(endpoint, "阿里云语音端点不能为空");
            Objects.requireNonNull(regionId, "阿里云语音地域不能为空");
            Objects.requireNonNull(accessKeyId, "阿里云语音 AccessKeyId 不能为空");
            Objects.requireNonNull(accessKeySecret, "阿里云语音 AccessKeySecret 不能为空");
            Objects.requireNonNull(phoneNumber, "阿里云语音被叫号码不能为空");
            Objects.requireNonNull(calledShowNumber, "阿里云语音显号不能为空");
            Objects.requireNonNull(ttsCode, "阿里云语音 TTS 模板不能为空");
            Objects.requireNonNull(ttsParamJson, "阿里云语音 TTS 参数不能为空");
            Objects.requireNonNull(outId, "阿里云语音外部流水不能为空");
        }
    }

    /** 阿里云语音业务响应。 */
    record Response(String code, String callId) {
        /** 校验语音响应。 */
        public Response {
            Objects.requireNonNull(code, "阿里云语音响应码不能为空");
        }
    }
}
