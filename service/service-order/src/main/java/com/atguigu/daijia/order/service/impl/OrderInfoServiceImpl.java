package com.atguigu.daijia.order.service.impl;

import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.model.entity.order.OrderInfo;
import com.atguigu.daijia.model.entity.order.OrderStatusLog;
import com.atguigu.daijia.model.enums.OrderStatus;
import com.atguigu.daijia.model.form.order.OrderInfoForm;
import com.atguigu.daijia.model.form.order.UpdateOrderCartForm;
import com.atguigu.daijia.model.vo.order.CurrentOrderInfoVo;
import com.atguigu.daijia.order.mapper.OrderInfoMapper;
import com.atguigu.daijia.order.mapper.OrderStatusLogMapper;
import com.atguigu.daijia.order.service.OrderInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderStatusLogMapper orderStatusLogMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    //乘客下单
    @Override
    public Long saveOrderInfo(OrderInfoForm orderInfoForm) {
        //order_info添加订单数据
        OrderInfo orderInfo = new OrderInfo();
        BeanUtils.copyProperties(orderInfoForm,orderInfo);
        //订单号
        String orderNo = UUID.randomUUID().toString().replaceAll("-","");
        orderInfo.setOrderNo(orderNo);
        //订单状态
        orderInfo.setStatus(OrderStatus.WAITING_ACCEPT.getStatus());
        orderInfoMapper.insert(orderInfo);

        //记录日志
        log(orderInfo.getId(),orderInfo.getStatus());

        //向redis添加标识，减少对数据库访问压力
        //接单标识，标识不存在了说明不在等待接单状态了
        redisTemplate.opsForValue().set(RedisConstant.ORDER_ACCEPT_MARK,
                "0", RedisConstant.ORDER_ACCEPT_MARK_EXPIRES_TIME, TimeUnit.MINUTES);

        return orderInfo.getId();
    }

    public void log(Long orderId, Integer status) {
        OrderStatusLog orderStatusLog = new OrderStatusLog();
        orderStatusLog.setOrderId(orderId);
        orderStatusLog.setOrderStatus(status);
        orderStatusLog.setOperateTime(new Date());
        orderStatusLogMapper.insert(orderStatusLog);
    }

    //根据订单id获取订单状态
    @Override
    public Integer getOrderStatus(Long orderId) {
        //sql语句： select status from order_info where id=?
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId,orderId);
        wrapper.select(OrderInfo::getStatus);
        //调用mapper方法
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
        //订单不存在
        if(orderInfo == null) {
            return OrderStatus.NULL_ORDER.getStatus();
        }
        return orderInfo.getStatus();
    }

    //司机抢单
    @Override
    public Boolean robNewOrder(Long driverId, Long orderId) {
        return robNewOrderV2(driverId,orderId);
//        //判断订单是否存在，通过Redis，减少数据库压力
//        if(!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
//            //抢单失败
//            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
//        }
//
//        //司机抢单
//        //修改order_info表订单状态值2：已经接单 + 司机id + 司机接单时间
//        //修改条件：根据订单id
//        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
//        wrapper.eq(OrderInfo::getId,orderId);
//        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
//        //设置
//        orderInfo.setStatus(OrderStatus.ACCEPTED.getStatus());
//        orderInfo.setDriverId(driverId);
//        orderInfo.setAcceptTime(new Date());
//        //调用方法修改
//        int rows = orderInfoMapper.updateById(orderInfo);
//        if(rows != 1) {
//            //抢单失败
//            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
//        }
//
//        //删除抢单标识
//        redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK);
//        return true;
    }

    //Redisson分布式锁
    //司机抢单
    private Boolean robNewOrderV2(Long driverId, Long orderId) {
        //判断订单是否存在，通过Redis，减少数据库压力
        if(!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }

        //创建锁
        RLock lock = redissonClient.getLock(RedisConstant.ROB_NEW_ORDER_LOCK + orderId);

        try {
            //获取锁
            // 第一个参数获取锁等待时间
            // 第二个参数获取到锁，锁过期时间
            boolean flag = lock.tryLock(RedisConstant.ROB_NEW_ORDER_LOCK_WAIT_TIME,RedisConstant.ROB_NEW_ORDER_LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if(flag) {
                if(!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
                    //抢单失败
                    throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
                }
                //司机抢单
                //修改order_info表订单状态值2：已经接单 + 司机id + 司机接单时间
                //修改条件：根据订单id
                LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(OrderInfo::getId,orderId);
                OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
                //设置
                orderInfo.setStatus(OrderStatus.ACCEPTED.getStatus());
                orderInfo.setDriverId(driverId);
                orderInfo.setAcceptTime(new Date());
                //调用方法修改
                int rows = orderInfoMapper.updateById(orderInfo);
                if(rows != 1) {
                    //抢单失败
                    throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
                }

                //删除抢单标识
                redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK);
            }
        }catch (Exception e) {
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }finally {
            //释放
            if(lock.isLocked()) {
                lock.unlock();
            }
        }
        return true;
    }

    //司机抢单：乐观锁方案解决并发问题
    public Boolean robNewOrderV1(Long driverId, Long orderId) {
        //判断订单是否存在，通过Redis，减少数据库压力
        if(!redisTemplate.hasKey(RedisConstant.ORDER_ACCEPT_MARK)) {
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }

        //司机抢单
        //update order_info set status =2 ,driver_id = ?,accept_time = ?
        // where id=? and status = 1
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId,orderId);
        wrapper.eq(OrderInfo::getStatus,OrderStatus.WAITING_ACCEPT.getStatus());

        //修改值
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setStatus(OrderStatus.ACCEPTED.getStatus());
        orderInfo.setDriverId(driverId);
        orderInfo.setAcceptTime(new Date());

        //调用方法修改
        int rows = orderInfoMapper.update(orderInfo,wrapper);
        if(rows != 1) {
            //抢单失败
            throw new GuiguException(ResultCodeEnum.COB_NEW_ORDER_FAIL);
        }

        //删除抢单标识
        redisTemplate.delete(RedisConstant.ORDER_ACCEPT_MARK);
        return true;
    }

    //乘客端查找当前订单
    @Override
    public CurrentOrderInfoVo searchCustomerCurrentOrder(Long customerId) {
        //封装条件
        //乘客id
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getCustomerId,customerId);

        //各种状态
        Integer[] statusArray = {
                OrderStatus.ACCEPTED.getStatus(),
                OrderStatus.DRIVER_ARRIVED.getStatus(),
                OrderStatus.UPDATE_CART_INFO.getStatus(),
                OrderStatus.START_SERVICE.getStatus(),
                OrderStatus.END_SERVICE.getStatus(),
                OrderStatus.UNPAID.getStatus()
        };
        wrapper.in(OrderInfo::getStatus,statusArray);

        //获取最新一条记录
        wrapper.orderByDesc(OrderInfo::getId);
        wrapper.last(" limit 1");

        //调用方法
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);

        //封装到CurrentOrderInfoVo
        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();
        if(orderInfo != null) {
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setIsHasCurrentOrder(true);
        } else {
            currentOrderInfoVo.setIsHasCurrentOrder(false);
        }
        return currentOrderInfoVo;
    }

    //司机端查找当前订单
    @Override
    public CurrentOrderInfoVo searchDriverCurrentOrder(Long driverId) {
        //封装条件
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getDriverId,driverId);
        Integer[] statusArray = {
                OrderStatus.ACCEPTED.getStatus(),
                OrderStatus.DRIVER_ARRIVED.getStatus(),
                OrderStatus.UPDATE_CART_INFO.getStatus(),
                OrderStatus.START_SERVICE.getStatus(),
                OrderStatus.END_SERVICE.getStatus()
        };
        wrapper.in(OrderInfo::getStatus,statusArray);
        wrapper.orderByDesc(OrderInfo::getId);
        wrapper.last(" limit 1");
        OrderInfo orderInfo = orderInfoMapper.selectOne(wrapper);
        //封装到vo
        CurrentOrderInfoVo currentOrderInfoVo = new CurrentOrderInfoVo();
        if(null != orderInfo) {
            currentOrderInfoVo.setStatus(orderInfo.getStatus());
            currentOrderInfoVo.setOrderId(orderInfo.getId());
            currentOrderInfoVo.setIsHasCurrentOrder(true);
        } else {
            currentOrderInfoVo.setIsHasCurrentOrder(false);
        }
        return currentOrderInfoVo;
    }

    //司机到达起始点
    @Override
    public Boolean driverArriveStartLocation(Long orderId, Long driverId) {
        // 更新订单状态和到达时间，条件：orderId + driverId
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId,orderId);
        wrapper.eq(OrderInfo::getDriverId,driverId);

        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setStatus(OrderStatus.DRIVER_ARRIVED.getStatus());
        orderInfo.setArriveTime(new Date());

        int rows = orderInfoMapper.update(orderInfo, wrapper);

        if(rows == 1) {
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
    }

    @Override
    public Boolean updateOrderCart(UpdateOrderCartForm updateOrderCartForm) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderInfo::getId,updateOrderCartForm.getOrderId());
        wrapper.eq(OrderInfo::getDriverId,updateOrderCartForm.getDriverId());

        OrderInfo orderInfo = new OrderInfo();
        BeanUtils.copyProperties(updateOrderCartForm,orderInfo);
        orderInfo.setStatus(OrderStatus.UPDATE_CART_INFO.getStatus());

        int rows = orderInfoMapper.update(orderInfo, wrapper);
        if(rows == 1) {
            return true;
        } else {
            throw new GuiguException(ResultCodeEnum.UPDATE_ERROR);
        }
    }
}
