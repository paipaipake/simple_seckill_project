
## 简单秒杀场景模拟

项目参考： [springboot-seckill](https://github.com/zaiyunduan123/springboot-seckill)
     

## 对参考项目的学习和优化

### 异常处理

全局异常处理器+异常定义+异常枚举类： 代码比较优雅
类：CodeMsg，Result， GlobalException

#### 优化：
##### 缺点：
项目中都是通过CodeMsg.erroCode返回，不太方便，因为函数封装后，调用不太方便。

##### 解决：
定义模块类SecKillException， 然后出现对应异常直接抛，然后在全局异常处理器中统一获取后返回。
```
if (!localOverMap.get(goodsId)) { // 货物已经没了标志结束了  
    throw new SecKillException(CodeMsg.SECKILL_OVER);  
}
```

### 秒杀思路

1. redis预减： mysql库存和redis缓存不一定要数据完全一致！ redis只是用做预减，当货物卖完了（mysql表库存没了），则标志结束，避免每次访问mysql表才知道秒杀没了。
2. 本地缓存，即把1中的商品秒杀结束标注位，存在本地，能更快一点
3. 减库存SQL写法，避免超卖问题：`update table set stock = stock - 1  where good_id = XXX and stock > 0  and Version = XXX`

```
超卖问题如何导致：
- SQL语法导致
	- update table set stock = ?  where good_id = 1。stock计算在内存中，然后修改减少后的stock赋值进去。
		- 库存在内存中计算，要考虑线程安全，否则计算剩余库存错误
		- 即使考虑了库存计算的并发安全，但是可以出现更新被覆盖问题，例如两个线程，一个update 10， 一个update 9，理论上先10再9，但是可以正好CPU切换等特殊情况，导致执行顺序有问题，从而库存计算错误
		- 如何解决？
			- sql语句中 update stock = stock - 1
	- 库存出现负数，update table set stock = stock - 1 where good_id = 1。加上限制 and stock > 0即可。
	- 即使上述两个问题都解决了，通过`update sk_goods_seckill set stock_count = stock_count - 1 where goods_id = #{goodsId} and stock_count > 0`来执行库存减少，还是可能出现超卖问题
		- 因为Update XX set stock_count = stock_count - 1这个语句，mysql底层执行顺序是：先过滤where条件得到记录行，然后读取每条记录行来获取stock_count，再才加上锁，再进行更新。
		- 因此如果两个线程并发执行这个SQL，这个stock可能只减少一个，也就是更新被覆盖了，导致更新丢失了。
		- 如何解决？
			- 借鉴CAS，加上给表加上列Version，每次先读version，然后带着读取的Version去更高，如果version不一样则循环
			- 加上读锁，in share mode
```

#### 优化：

##### 缺陷点：
项目中，下面代码不合理，且当缓存库存<0后，可能大量对mysql进行访问，库表压力大，代码如下：
```
long stock = redisService.decr(GoodsKey.getGoodsStock, "" + goodsId);//10  
if (stock < 0) {  
    // 全更新不合理，1 因为有些货物已经标记卖完了，没必要重新处理 2 其他货物也许都在等待队列中|还没秒杀完，结果又恢复到真实库存（真实库存肯定>=缓存库存的，增大并发访问度）  
    // 将mysql所有商品库存数量同步到redis中  
    afterPropertiesSet();  
    long stock2 = redisService.decr(GoodsKey.getGoodsStock, "" + goodsId);//10    // if(stock2 < 0){    //     localOverMap.put(goodsId, true);    //     return Result.error(CodeMsg.SECKILL_OVER);    // }
```

##### 优化思路：
1. 废弃afterPropertiesSet() 方法，一次性更新所有商品缓存库存。 而是只更新对应商品库存缓存
2. 增加分段锁和二次缓存检校， 当缓存库存<0，查询Mysql表的时候，加锁查询，并发访问中只要存在一个线程读取库表更新缓存后，后面的并发再次检校缓存后发现>0，则跳过对mysql表的读取，入消息队列


## redis+mysql实现秒杀业务逻辑流程

1. mysql将商品和对应库存，预热在redis中
2. 用户请求进来，
    1. 先判断用户是否已经存在订单，如果是则返回，避免重复下单
    2. redis中检查商品真实库存是否没了，如果没了则直接返回
    3. redis中检查库存是否还有剩余
        1. 如果剩余则预减
        2. 如果不剩余，
            1. 则去mysql表中查找真实库存是否存在，如果真实库存也没有，则设置库存没了则设置库存没了标识位
            2. 如果剩余则更新redis商品库存为实际库存(会和库存预见存在并发问题吗)
    4. 执行业务：
        1. 生成订单
        2. 支付
        3. 完成订单
    5. 完成订单后，减少实际库存(SQL需要避免超卖问题)

## 思考笔记

### 超卖问题思考和整理

什么是超卖？
卖出去的货物，比实际的库存多

超卖出现几种情况：
- 库存充足的情况下，多个用户并发购买N件，但是库存减少值 < N件
- 库存不充足的情况下，库存为M，多个用户并发购买N件，N > M，然后最好库存出现了负数。

超卖问题如何导致：
- SQL语法导致
    - update table set stock = ?  where good_id = 1。stock计算在内存中，然后修改减少后的stock赋值进去。
        - 库存在内存中计算，要考虑线程安全，否则计算剩余库存错误
        - 即使考虑了库存计算的并发安全，但是可以出现更新被覆盖问题，例如两个线程，一个update 10， 一个update 9，理论上先10再9，但是可以正好CPU切换等特殊情况，导致执行顺序有问题，从而库存计算错误
        - 如何解决？
            - sql语句中 update stock = stock - 1
    - 库存出现负数，update table set stock = stock - 1 where good_id = 1。加上限制 and stock > 0即可。
    - 即使上述两个问题都解决了，通过`update sk_goods_seckill set stock_count = stock_count - 1 where goods_id = #{goodsId} and stock_count > 0`来执行库存减少，还是可能出现超卖问题
        - 因为Update XX set stock_count = stock_count - 1这个语句，mysql底层执行顺序是：先过滤where条件得到记录行，然后读取每条记录行来获取stock_count，再才加上锁，再进行更新。
        - 因此如果两个线程并发执行这个SQL，这个stock可能只减少一个，也就是更新被覆盖了，导致更新丢失了。
        - 如何解决？
            - 借鉴CAS，加上给表加上列Version，每次先读version，然后带着读取的Version去更高，如果version不一样则循环
            - 加上读锁，in share mode

### redis 如何帮助超卖问题

引入redis目的： 减少对mysql底层数据库的请求。
如何操作呢?
1. 先将mysql中商品库存，存放在redis中预热
2. 秒杀请求进来后，先在redis中预减库存，生成订单，用户支付，完成订单，减少mysql库存

#### redis在秒杀问题中作用的思考

总：
1. redis定位是什么？和mysql数据完全同步？数据一致？
2. redis库存预减 和 实际库存减去，两个动作状态不一致，即预减，不一定订单就能完成

这存在一些问题，redis预减和订单实际完成存在差距：
redis减去库存后，用户在生成订单、支付的时候出错|超时，则不会减少mysql库中实际库存，从而redis和mysql不一致
如何解决？
1. 补库存： 如果失败，则redis中库存+1. 消息队列分离业务后，通过补可能麻烦，业务上也许不太合理？？
2. 同步库存： 将mysql中实际库存量同步到redis中（推荐）

## 不足

项目比较简单，有效订单生成，支付，地址等业务代码不涉及，太浅。后续我需要找项目学习整体商城流程： 购买-订单-支付-物流更新等功能
