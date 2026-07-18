package com.bosco.mine.server.modules.mine.dto;

import java.util.List;

/**
 * 当前用户可访问的组织和矿区上下文。
 *
 * @param organizations 可访问组织及其矿区
 */
public record MineContextResponse(List<OrganizationContext> organizations) {

    /** 创建不可变矿区上下文。 */
    public MineContextResponse {
        organizations = List.copyOf(organizations);
    }

    /**
     * 单个组织及其可访问矿区。
     *
     * @param organization 组织档案
     * @param organizationWide 是否拥有组织级数据授权
     * @param sites 可访问矿区
     */
    public record OrganizationContext(
            MineOrganizationResponse organization,
            boolean organizationWide,
            List<MineSiteResponse> sites
    ) {
        /** 创建不可变组织上下文。 */
        public OrganizationContext {
            sites = List.copyOf(sites);
        }
    }
}
