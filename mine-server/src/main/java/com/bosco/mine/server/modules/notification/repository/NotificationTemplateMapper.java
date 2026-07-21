package com.bosco.mine.server.modules.notification.repository;

import com.bosco.mine.server.modules.notification.model.NotificationTemplate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知模板配置数据访问接口。
 */
@Mapper
public interface NotificationTemplateMapper {

    /**
     * 查询全部通知模板。
     *
     * @return 稳定排序的通知模板
     */
    @Select("""
            SELECT id, template_code AS templateCode, name,
                   channel_type AS channelType, event_type AS eventType, locale,
                   subject_template AS subjectTemplate, body_template AS bodyTemplate,
                   provider_template_code AS providerTemplateCode,
                   allowed_variables::text AS allowedVariablesJson, status,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_template
            ORDER BY channel_type, event_type, locale, name, id
            """)
    List<NotificationTemplate> findAll();

    /**
     * 按标识查询通知模板。
     *
     * @param id 模板标识
     * @return 通知模板
     */
    @Select("""
            SELECT id, template_code AS templateCode, name,
                   channel_type AS channelType, event_type AS eventType, locale,
                   subject_template AS subjectTemplate, body_template AS bodyTemplate,
                   provider_template_code AS providerTemplateCode,
                   allowed_variables::text AS allowedVariablesJson, status,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_template
            WHERE id = #{id}
            """)
    Optional<NotificationTemplate> findById(@Param("id") UUID id);

    /**
     * 按稳定编码查询通知模板。
     *
     * @param templateCode 模板编码
     * @return 通知模板
     */
    @Select("""
            SELECT id, template_code AS templateCode, name,
                   channel_type AS channelType, event_type AS eventType, locale,
                   subject_template AS subjectTemplate, body_template AS bodyTemplate,
                   provider_template_code AS providerTemplateCode,
                   allowed_variables::text AS allowedVariablesJson, status,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_template
            WHERE template_code = #{templateCode}
            """)
    Optional<NotificationTemplate> findByCode(@Param("templateCode") String templateCode);

    /**
     * 新增通知模板。
     *
     * @param template 模板配置
     * @param createdBy 创建用户标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO notification_template (
                id, template_code, name, channel_type, event_type, locale,
                subject_template, body_template, provider_template_code,
                allowed_variables, status, created_by, updated_by
            ) VALUES (
                #{template.id}, #{template.templateCode}, #{template.name},
                #{template.channelType}, #{template.eventType}, #{template.locale},
                #{template.subjectTemplate}, #{template.bodyTemplate},
                #{template.providerTemplateCode},
                CAST(#{template.allowedVariablesJson} AS JSONB), #{template.status},
                #{createdBy}, #{createdBy}
            )
            """)
    int insert(
            @Param("template") NotificationTemplate template,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 使用乐观锁修改通知模板。
     *
     * @param template 新模板配置
     * @param expectedVersion 期望版本号
     * @param updatedBy 修改用户标识
     * @return 影响行数
     */
    @Update("""
            UPDATE notification_template
            SET name = #{template.name}, channel_type = #{template.channelType},
                event_type = #{template.eventType}, locale = #{template.locale},
                subject_template = #{template.subjectTemplate},
                body_template = #{template.bodyTemplate},
                provider_template_code = #{template.providerTemplateCode},
                allowed_variables = CAST(#{template.allowedVariablesJson} AS JSONB),
                status = #{template.status}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{template.id} AND template_code = #{template.templateCode}
              AND version = #{expectedVersion}
            """)
    int update(
            @Param("template") NotificationTemplate template,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );
}
