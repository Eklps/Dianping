package com.hmdp.utils;

public interface ILock {
    /**
     * 这个接口是为了解决分布式锁提出的接口
     * @param timeSec : 锁持有的实践，超过时间自动释放
     * @return  是否成功上锁
     */
    boolean tryLock(long timeSec);

    void unLock();
}
