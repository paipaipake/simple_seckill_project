package com.jesper.seckill.controller;

import com.google.common.util.concurrent.RateLimiter;
import com.jesper.seckill.bean.SeckillOrder;
import com.jesper.seckill.bean.User;
import com.jesper.seckill.exception.SecKillException;
import com.jesper.seckill.rabbitmq.MQSender;
import com.jesper.seckill.rabbitmq.SeckillMessage;
import com.jesper.seckill.redis.GoodsKey;
import com.jesper.seckill.redis.RedisService;
import com.jesper.seckill.result.CodeMsg;
import com.jesper.seckill.result.Result;
import com.jesper.seckill.service.GoodsService;
import com.jesper.seckill.service.OrderService;
import com.jesper.seckill.service.SeckillService;
import com.jesper.seckill.vo.GoodsVo;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by jiangyunxiong on 2018/5/22.
 */
@Controller
@RequestMapping("/seckill")
public class SeckillController implements InitializingBean {

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    SeckillService seckillService;

    @Autowired
    RedisService redisService;

    @Autowired
    MQSender sender;

    //基于令牌桶算法的限流实现类
    RateLimiter rateLimiter = RateLimiter.create(10);

    // 基于货物ID的锁,减少并发对mysql的访问
    private static Map<Long, ReentrantLock> goodStockQueryLock = new ConcurrentHashMap();

    //做标记，判断该商品是否被处理过了
    private HashMap<Long, Boolean> localOverMap = new HashMap<Long, Boolean>();

    /**
     * GET POST
     * 1、GET幂等,服务端获取数据，无论调用多少次结果都一样
     * 2、POST，向服务端提交数据，不是幂等
     * <p>
     * 将同步下单改为异步下单
     *
     * @param model
     * @param user
     * @param goodsId
     * @return
     */
    @RequestMapping(value = "/do_seckill", method = RequestMethod.POST)
    @ResponseBody
    public Result<Integer> list(Model model, User user, @RequestParam("goodsId") long goodsId) {

        if (!rateLimiter.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
            return  Result.error(CodeMsg.ACCESS_LIMIT_REACHED);
        }

        if (user == null) {
            return Result.error(CodeMsg.SESSION_ERROR);
        }
        model.addAttribute("user", user);
        //内存标记，减少redis访问
        boolean over = localOverMap.get(goodsId);
        if (over) {
            return Result.error(CodeMsg.SECKILL_OVER);
        }

        // 预减库存
        long stock = redisService.decr(GoodsKey.getGoodsStock, "" + goodsId);//10
        if (stock < 0) {
            // 全更新不合理，1 因为有些货物已经标记卖完了，没必要重新处理 2 其他货物也许都在等待队列中|还没秒杀完，结果又恢复到真实库存（真实库存肯定>=缓存库存的，增大并发访问度）
            // 将mysql库存数量同步到redis中
            // afterPropertiesSet();
            // long stock2 = redisService.decr(GoodsKey.getGoodsStock, "" + goodsId);//10
            // if(stock2 < 0){
            //     localOverMap.put(goodsId, true);
            //     return Result.error(CodeMsg.SECKILL_OVER);
            // }
            preUpdateProductInRedis(goodsId);
        }

        //判断重复秒杀
        SeckillOrder order = orderService.getOrderByUserIdGoodsId(user.getId(), goodsId);
        if (order != null) {
            return Result.error(CodeMsg.REPEATE_SECKILL);
        }

        //生成订单消息入队
        SeckillMessage message = new SeckillMessage();
        message.setUser(user);
        message.setGoodsId(goodsId);
        sender.sendSeckillMessage(message);
        return Result.success(0);//排队中
    }

    /**
     * 将mysql总库存值更新到redis
     * 对同一类商品，加锁限制访问库表，一个更新缓存，其余接着用，减少库表压力
     * @param goodsId 货物I D
     * @return
     */
    private void preUpdateProductInRedis(Long goodsId) {
        goodStockQueryLock.putIfAbsent(goodsId, new ReentrantLock());
        ReentrantLock reentrantLock = goodStockQueryLock.get(goodsId);
        reentrantLock.lock();
        updateProductCacheStock(goodsId);
        reentrantLock.unlock();
    }


    /**
     * 用于查询实际货存量
     * 如果卖完了，则标志为结束，直接返回，如果没卖完，则更新缓存为实际货物量
     * 增加双缓存判断：如果前面缓存中货物量<0，但是查询mysql表是加锁了，因此可能多个并发中，有一个已经更新过缓存了，因此没必要再查询库表，减少库表压力
     * @param goodsId
     * @return
     */
    private void updateProductCacheStock(Long goodsId) {
        // 双缓存判断
        if (!localOverMap.get(goodsId)) { // 货物已经没了标志结束了
            throw new SecKillException(CodeMsg.SECKILL_OVER);
        }
        Integer cacheStockCount = redisService.get(GoodsKey.getGoodsStock, "" + goodsId, Integer.class);
        if (cacheStockCount > 0) {
            return;
        }
        GoodsVo goodsVo = goodsService.getGoodsVoByGoodsId(goodsId);

        // 商品卖光检查
        Integer realStockCount = goodsVo.getStockCount();
        if (realStockCount <= 0) {
            localOverMap.put(goodsId, true);
            throw new SecKillException(CodeMsg.SECKILL_OVER);
        }

        // 更新指定货物的缓存
        redisService.set(GoodsKey.getGoodsStock, "" + goodsVo.getId(), goodsVo.getStockCount());
    }

    /**
     * 系统初始化,将商品信息加载到redis和本地内存
     */
    @Override
    public void afterPropertiesSet() {
        List<GoodsVo> goodsVoList = goodsService.listGoodsVo();
        if (goodsVoList == null) {
            return;
        }
        for (GoodsVo goods : goodsVoList) {
            redisService.set(GoodsKey.getGoodsStock, "" + goods.getId(), goods.getStockCount());
            //初始化商品都是没有处理过的
            localOverMap.put(goods.getId(), false);
        }
    }

    /**
     * orderId：成功
     * -1：秒杀失败
     * 0： 排队中
     */
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    @ResponseBody
    public Result<Long> seckillResult(Model model, User user,
                                      @RequestParam("goodsId") long goodsId) {
        model.addAttribute("user", user);
        if (user == null) {
            return Result.error(CodeMsg.SESSION_ERROR);
        }
        long orderId = seckillService.getSeckillResult(user.getId(), goodsId);
        return Result.success(orderId);
    }
}
