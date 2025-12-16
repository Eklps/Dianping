--1.确认参数
--1.1优惠券id
local voucherId=ARGV[1]
--1.2用户id
local userId=ARGV[2]
--1.3订单id
local orderId=ARGV[3]

--2.数据key
-- .. 是lua的字符拼接
--2.1库存key
local stockKey = 'sekill:stock'.. voucherId
--2.2订单key
local orderKey = 'sekill:order'.. voucherId

--3.脚本业务
--3.1判断库存是否充足
if(tonumber(redis.call('get',stockKey))<0) then
 --不充足返回1
 return 1
end

--3.2判断用户是否下单
if(redis.call('sismember',orderKey,userId)==1) then
 --是，也就是已经下过一单了，不允许再下，返回2
 return 2
end

--3.3用户之前没有下单，扣减库存
redis.call('incryby',stockKey,-1)
--存入用户id到当前优惠券的set集合
redis.call('sadd',orderKey,userId)
--3.4发送消息到队列中, XADD stream.orders * k1 v1 k2 v2
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
--返回0
return 0