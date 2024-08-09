package com.atguigu.daijia.order.mapper;

import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.vo.order.OrderListVo;
import com.atguigu.daijia.model.vo.order.OrderPayVo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderInfoMapper extends BaseMapper<OrderInfo> {

    ////获取乘客订单分页列表
    IPage<OrderListVo> selectCustomerOrderPage(@Param("pageParam") Page<OrderInfo> pageParam, @Param("customerId") Long customerId);

    IPage<OrderListVo> selectDriverOrderPage(@Param("pageParam") Page<OrderInfo> pageParam, @Param("customerId") Long customerId);

    OrderPayVo selectOrderPayVo(@Param("orderNo") String orderNo, @Param("customerId") Long customerId);
}
