-- 1.参数列表
-- 1.1优惠券ID
local voucherId = ARGV[1]
-- 1.2用户ID
local userId = ARGV[2]
local orderId = ARGV[3]

-- 2.定义key
-- 2.1库存key
local  stockKey = "seckill:stock:" .. voucherId
-- 2.2订单key
local orderKey = "seckill:order:" .. voucherId

-- 脚本业务
-- 1.判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足,返回1
    return 1
end
-- 2.判断用户是否下单
if (redis.call('sismember',orderKey,userId) == 1) then
    -- 用户已下单,返回2
    return 2
end
-- 3.扣减库存
redis.call('incrby', stockKey, -1)
-- 4.记录用户
redis.call('sadd', orderKey, userId)
redis.call('xadd','stream.orders',"*","userId",userId,"voucherId",voucherId,'id',orderId)
return 0



