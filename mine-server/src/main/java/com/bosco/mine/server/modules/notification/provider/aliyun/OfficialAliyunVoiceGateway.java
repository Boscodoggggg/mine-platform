package com.bosco.mine.server.modules.notification.provider.aliyun;

import com.aliyun.dyvmsapi20170525.models.SingleCallByTtsRequest;
import com.aliyun.dyvmsapi20170525.models.SingleCallByTtsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import org.springframework.stereotype.Component;

/**
 * 使用阿里云官方 Java SDK 调用语音 SingleCallByTts 接口。
 */
@Component
public class OfficialAliyunVoiceGateway implements AliyunVoiceGateway {

    private final AliyunClientCache<com.aliyun.dyvmsapi20170525.Client> cache =
            new AliyunClientCache<>();

    /** {@inheritDoc} */
    @Override
    public Response call(Command command) {
        try {
            com.aliyun.dyvmsapi20170525.Client client = client(command);
            SingleCallByTtsRequest request = new SingleCallByTtsRequest()
                    .setCalledNumber(command.phoneNumber())
                    .setCalledShowNumber(command.calledShowNumber())
                    .setTtsCode(command.ttsCode())
                    .setTtsParam(command.ttsParamJson())
                    .setPlayTimes(command.playTimes())
                    .setVolume(command.volume())
                    .setSpeed(command.speed())
                    .setOutId(command.outId());
            RuntimeOptions runtime = new RuntimeOptions()
                    .setAutoretry(false)
                    .setMaxAttempts(1)
                    .setConnectTimeout(command.connectTimeoutMs())
                    .setReadTimeout(command.readTimeoutMs());
            SingleCallByTtsResponse response = client.singleCallByTtsWithOptions(request, runtime);
            if (response == null || response.getBody() == null
                    || response.getBody().getCode() == null) {
                throw new AliyunGatewayException(null);
            }
            return new Response(response.getBody().getCode(), response.getBody().getCallId());
        } catch (AliyunGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AliyunGatewayException(exception);
        }
    }

    private com.aliyun.dyvmsapi20170525.Client client(Command command) throws Exception {
        String fingerprint = AliyunClientCache.fingerprint(
                command.endpoint(), command.regionId(), command.accessKeyId(),
                command.accessKeySecret(), String.valueOf(command.connectTimeoutMs()),
                String.valueOf(command.readTimeoutMs()));
        return cache.get(fingerprint, () -> new com.aliyun.dyvmsapi20170525.Client(
                new Config()
                        .setAccessKeyId(command.accessKeyId())
                        .setAccessKeySecret(command.accessKeySecret())
                        .setEndpoint(command.endpoint())
                        .setRegionId(command.regionId())
                        .setConnectTimeout(command.connectTimeoutMs())
                        .setReadTimeout(command.readTimeoutMs())));
    }
}
