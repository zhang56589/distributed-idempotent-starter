--[[
  verify_token.lua
  ============================================================
  原子性地校验并删除 Redis Token —— "判断 + 删除" 合二为一。

  用法（通过 StringRedisTemplate + DefaultRedisScript<Long> 调用）：

    redisTemplate.execute(
        redisScript,
        Collections.singletonList(KEYS[1]),   -- KEYS: [tokenKey]
        ARGV[1]                                -- ARGV: [clientSuppliedValue]
    )

  逻辑：
    1. GET KEYS[1] 取出服务端 token 的实际值
    2. 若不存在（redis 返回 false / nil）→ 返回 0（无效 / 提前过期 / 已消费）
    3. 若存在但与 ARGV[1] 不匹配     → 返回 -1（伪造 / 串用 token）
    4. 匹配成功则 DEL KEYS[1]        → 返回 1（放行 + 消费 token，保证一次性）

  返回值语义（Java 端按 Number 读取后 toInt）：
     1  → 校验通过，token 已消费，业务放行
     0  → token 不存在（过期 / 未申请 / 已被消费）
    -1  → token 存在但不匹配（非法串用）
  ============================================================
--]]

local serverValue = redis.call('GET', KEYS[1])

-- token 不存在 → 直接返回 0
if serverValue == false or serverValue == nil then
    return 0
end

-- token 值与客户端传入不匹配 → 返回 -1
if serverValue ~= ARGV[1] then
    return -1
end

-- 匹配成功 → 原子删除（DELETE 与 GET 在同一个 Lua 脚本内，天然原子）
redis.call('DEL', KEYS[1])
return 1
