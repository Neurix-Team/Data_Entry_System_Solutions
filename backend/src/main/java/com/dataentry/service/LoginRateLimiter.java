package com.dataentry.service;

 
public interface LoginRateLimiter {

    boolean tryAcquire(String key);

     void reset(String key);
}
