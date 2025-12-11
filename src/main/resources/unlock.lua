-- unlock.lua 用于redis分布式锁解锁时保证 检查锁 和 删除锁 的原子性
---@diagnostic disable: undefined-global
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
    return redis.call('del',KEYS[1])
end--if语句结束

--不是自己的锁 返回0
return 0
