package com.atguigu.daijia.driver.service;


import com.atguigu.daijia.model.vo.order.TextAuditingVo;

/**
 * 图片审核
 */
public interface CiService {

    Boolean imageAuditing(String path);

    TextAuditingVo textAuditing(String content);
}
