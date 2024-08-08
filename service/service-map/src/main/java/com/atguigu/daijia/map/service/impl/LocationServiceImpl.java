package com.atguigu.daijia.map.service.impl;

import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.atguigu.daijia.common.constant.RedisConstant;
import com.atguigu.daijia.common.constant.SystemConstant;
import com.atguigu.daijia.driver.client.DriverInfoFeignClient;
import com.atguigu.daijia.map.service.LocationService;
import com.atguigu.daijia.model.entity.driver.DriverSet;
import com.atguigu.daijia.model.form.map.SearchNearByDriverForm;
import com.atguigu.daijia.model.form.map.UpdateDriverLocationForm;
import com.atguigu.daijia.model.form.map.UpdateOrderLocationForm;
import com.atguigu.daijia.model.vo.map.NearByDriverVo;
import com.atguigu.daijia.model.vo.map.OrderLocationVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
@Slf4j
@SuppressWarnings({"unchecked", "rawtypes"})
public class LocationServiceImpl implements LocationService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private DriverInfoFeignClient driverInfoFeignClient;

    //更新司机位置信息
    @Override
    public Boolean updateDriverLocation(UpdateDriverLocationForm updateDriverLocationForm) {
        //把司机位置信息添加redis里面geo
        Point point = new Point(updateDriverLocationForm.getLongitude().doubleValue(),
                updateDriverLocationForm.getLatitude().doubleValue());
        //添加到redis里面
        redisTemplate.opsForGeo().add(RedisConstant.DRIVER_GEO_LOCATION,
                point,
                updateDriverLocationForm.getDriverId().toString());
        return true;
    }

    //删除司机位置信息
    @Override
    public Boolean removeDriverLocation(Long driverId) {
        redisTemplate.opsForGeo().remove(RedisConstant.DRIVER_GEO_LOCATION,driverId.toString());
        return true;
    }


    @Override
    public List<NearByDriverVo> searchNearByDriver(SearchNearByDriverForm searchNearByDriverForm) {
        // 搜索经纬度位置5公里以内的司机
        //定义经纬度点
        Point point = new Point(searchNearByDriverForm.getLongitude().doubleValue(), searchNearByDriverForm.getLatitude().doubleValue());
        //定义距离：5公里(系统配置)
        Distance distance = new Distance(SystemConstant.NEARBY_DRIVER_RADIUS, RedisGeoCommands.DistanceUnit.KILOMETERS);

        //定义以point点为中心，distance为距离这么一个范围
        Circle circle = new Circle(point, distance);

        //定义GEO参数
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance() //包含距离
                .includeCoordinates() //包含坐标
                .sortAscending(); //排序：升序

        // 1.GEORADIUS获取附近范围内的信息
        GeoResults<RedisGeoCommands.GeoLocation<String>> result = redisTemplate.opsForGeo().radius(RedisConstant.DRIVER_GEO_LOCATION, circle, args);

        //2.收集信息，存入list
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = result.getContent();

        //3.返回计算后的信息
        List<NearByDriverVo> list = new ArrayList();
        if(!CollectionUtils.isEmpty(content)) {

            Iterator<GeoResult<RedisGeoCommands.GeoLocation<String>>> iterator = content.iterator();

            while (iterator.hasNext()) {
                GeoResult<RedisGeoCommands.GeoLocation<String>> item = iterator.next();

                //司机id
                Long driverId = Long.parseLong(item.getContent().getName());

                //当前距离
                BigDecimal currentDistance = new BigDecimal(item.getDistance().getValue()).setScale(2, RoundingMode.HALF_UP);
                log.info("司机：{}，距离：{}",driverId, item.getDistance().getValue());

                //获取司机接单设置参数
                DriverSet driverSet = driverInfoFeignClient.getDriverSet(driverId).getData();

                //订单里程判断，orderDistance==0：不限制 接单距离-总里程 < 0就不满足
                if(driverSet.getOrderDistance().doubleValue() != 0 && driverSet.getOrderDistance().subtract(searchNearByDriverForm.getMileageDistance()).doubleValue() < 0) {
                    continue;
                }

                //接单里程判断，acceptDistance==0：不限制，预期接单距离-当前距离 < 0就不满足
                if(driverSet.getAcceptDistance().doubleValue() != 0 && driverSet.getAcceptDistance().subtract(currentDistance).doubleValue() < 0) {
                    continue;
                }

                //满足条件的附近司机信息
                NearByDriverVo nearByDriverVo = new NearByDriverVo();
                nearByDriverVo.setDriverId(driverId);
                nearByDriverVo.setDistance(currentDistance);
                list.add(nearByDriverVo);
            }
        }
        return list;
    }


    //司机赶往代驾起始点：更新订单地址到缓存
    @Override
    public Boolean updateOrderLocationToCache(UpdateOrderLocationForm updateOrderLocationForm) {

        OrderLocationVo orderLocationVo = new OrderLocationVo();
        orderLocationVo.setLongitude(updateOrderLocationForm.getLongitude());
        orderLocationVo.setLatitude(updateOrderLocationForm.getLatitude());

        String key = RedisConstant.UPDATE_ORDER_LOCATION + updateOrderLocationForm.getOrderId();
        redisTemplate.opsForValue().set(key,orderLocationVo);
        return true;
    }

    @Override
    public OrderLocationVo getCacheOrderLocation(Long orderId) {
        String key = RedisConstant.UPDATE_ORDER_LOCATION + orderId;
        OrderLocationVo orderLocationVo = (OrderLocationVo)redisTemplate.opsForValue().get(key);
        return orderLocationVo;
    }
}
