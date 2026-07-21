package com.bosco.mine.server.modules.notification.provider.aliyun;

import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import org.springframework.stereotype.Component;

/**
 * 使用阿里云官方 Java SDK 调用短信 SendSms 接口。
 */
@Component
public class OfficialAliyunSmsGateway implements AliyunSmsGateway {

    private final AliyunClientCache<com.aliyun.dysmsapi20170525.Client> cache =
            new AliyunClientCache<>();

    /** {@inheritDoc} */
    @Override
    public Response send(Command command) {
        try {
            com.aliyun.dysmsapi20170525.Client client = client(command);
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(command.phoneNumbers())
                    .setSignName(command.signName())
                    .setTemplateCode(command.templateCode())
                    .setTemplateParam(command.templateParamJson())
                    .setOutId(command.outId());
            RuntimeOptions runtime = new RuntimeOptions()
                    .setAutoretry(false)
                    .setMaxAttempts(1)
                    .setConnectTimeout(command.connectTimeoutMs())
                    .setReadTimeout(command.readTimeoutMs());
            SendSmsResponse response = client.sendSmsWithOptions(request, runtime);
            if (response == null || response.getBody() == null
                    || response.getBody().getCode() == null) {
                throw new AliyunGatewayException(null);
            }
            return new Response(response.getBody().getCode(), response.getBody().getBizId());
        } catch (AliyunGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AliyunGatewayException(exception);
        }
    }

    private com.aliyun.dysmsapi20170525.Client client(Command command) throws Exception {
        String fingerprint = AliyunClientCache.fingerprint(
                command.endpoint(), command.regionId(), command.accessKeyId(),
                command.accessKeySecret(), String.valueOf(command.connectTimeoutMs()),
                String.valueOf(command.readTimeoutMs()));
        return cache.get(fingerprint, () -> new com.aliyun.dysmsapi20170525.Client(
                new Config()
                        .setAccessKeyId(command.accessKeyId())
                        .setAccessKeySecret(command.accessKeySecret())
                        .setEndpoint(command.endpoint())
                        .setRegionId(command.regionId())
                        .setConnectTimeout(command.connectTimeoutMs())
                        .setReadTimeout(command.readTimeoutMs())));
    }
}
