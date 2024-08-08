package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.driver.config.TencentCloudProperties;
import com.atguigu.daijia.driver.service.CiService;
import com.atguigu.daijia.model.vo.order.TextAuditingVo;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ciModel.auditing.*;
import com.qcloud.cos.region.Region;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class CiServiceImpl implements CiService {

    @Autowired
    private TencentCloudProperties tencentCloudProperties;

    private COSClient getPrivateCOSClient() {
        COSCredentials cred = new BasicCOSCredentials(tencentCloudProperties.getSecretId(), tencentCloudProperties.getSecretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(tencentCloudProperties.getRegion()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        COSClient cosClient = new COSClient(cred, clientConfig);
        return cosClient;
    }

    @Override
    public Boolean imageAuditing(String path) {
        COSClient cosClient = getPrivateCOSClient();

        //审核图片内容
        //1.创建任务请求对象
        ImageAuditingRequest request = new ImageAuditingRequest();
        //2.添加请求参数 参数详情请见 API 接口文档
        //2.1设置请求 bucket
        request.setBucketName(tencentCloudProperties.getBucketPrivate());
        //2.2设置审核策略 不传则为默认策略（预设）
        //request.setBizType("");
        //2.3设置 bucket 中的图片位置
        request.setObjectKey(path);


        //3.调用接口,获取任务响应对象
        ImageAuditingResponse response = cosClient.imageAuditing(request);
        cosClient.shutdown();

        //用于返回该审核场景的审核结果，返回值：0：正常。1：确认为当前场景的违规内容。2：疑似为当前场景的违规内容。
        if (!response.getPornInfo().getHitFlag().equals("0")
                || !response.getAdsInfo().getHitFlag().equals("0")
                || !response.getTerroristInfo().getHitFlag().equals("0")
                || !response.getPoliticsInfo().getHitFlag().equals("0")
        ) {
            return false;
        }
        return true;
    }

    @Override
    public TextAuditingVo textAuditing(String content) {
        if(!StringUtils.hasText(content)) {
            TextAuditingVo textAuditingVo = new TextAuditingVo();
            textAuditingVo.setResult("0");
            return textAuditingVo;
        }
        COSClient cosClient = getPrivateCOSClient();

        TextAuditingRequest request = new TextAuditingRequest();
        request.setBucketName(tencentCloudProperties.getBucketPrivate());

        //将图片转换为base64字符串
        byte[] encoder = org.apache.commons.codec.binary.Base64.encodeBase64(content.getBytes());
        String contentBase64 = new String(encoder);
        request.getInput().setContent(contentBase64);
        request.getConf().setDetectType("all");

        TextAuditingResponse response = cosClient.createAuditingTextJobs(request);
        AuditingJobsDetail detail = response.getJobsDetail();
        TextAuditingVo textAuditingVo = new TextAuditingVo();

        if ("Success".equals(detail.getState())) {
            //检测结果: 0（审核正常），1 （判定为违规敏感文件），2（疑似敏感，建议人工复核）。
            String result = detail.getResult();

            //违规关键词
            StringBuffer keywords = new StringBuffer();
            List<SectionInfo> sectionInfoList = detail.getSectionList();
            for (SectionInfo info : sectionInfoList) {
                String pornInfoKeyword = info.getPornInfo().getKeywords();
                String illegalInfoKeyword = info.getIllegalInfo().getKeywords();
                String abuseInfoKeyword = info.getAbuseInfo().getKeywords();

                if (pornInfoKeyword.length() > 0) {
                    keywords.append(pornInfoKeyword).append(",");
                }
                if (illegalInfoKeyword.length() > 0) {
                    keywords.append(illegalInfoKeyword).append(",");
                }
                if (abuseInfoKeyword.length() > 0) {
                    keywords.append(abuseInfoKeyword).append(",");
                }
            }
            textAuditingVo.setResult(result);
            textAuditingVo.setKeywords(keywords.toString());
        }
        return textAuditingVo;
    }

}
