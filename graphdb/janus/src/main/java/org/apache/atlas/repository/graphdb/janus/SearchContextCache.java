package org.apache.atlas.repository.graphdb.janus;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.atlas.RequestContext;
import org.apache.atlas.service.redis.AbstractRedisService;
import org.apache.atlas.service.redis.RedisService;
import org.apache.atlas.utils.AtlasPerfMetrics;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class SearchContextCache {
    private static RedisService redisService = null;

    public static final String INVALID_SEQUENCE = "invalid_sequence";


    public SearchContextCache(@Qualifier("redisServiceImpl") RedisService redisService) {
        SearchContextCache.redisService = redisService;
    }


    public static void put(String key, Integer sequence, String esAsyncId) {
        AtlasPerfMetrics.MetricRecorder metric = RequestContext.get().startMetricRecord("putInCache");
       try {
           // Build the string in format `sequence/esAsyncId` and store it in redis
           String val = sequence + "/" + esAsyncId;
           redisService.putValue(key, val);
       } finally {
           RequestContext.get().endMetricRecord(metric);
       }
    }
    public static String get(String key){
        return redisService.getValue(key);
    }

    public static String getESAsyncSearchIdFromContextCache(String key, Integer sequence){
        AtlasPerfMetrics.MetricRecorder metric = RequestContext.get().startMetricRecord("getESAsyncSearchIdFromContextCache");
        try {
            //Get the context cache for the given key
            String contextCache = get(key);
            if(contextCache == null || sequence == null){
                return null;
            }
            // Split the context cache to get the sequence and ESAsyncId
            String[] contextCacheSplit = contextCache.split("/");
            if(contextCacheSplit.length != 2){
                return null;
            }
            int seq = Integer.parseInt(contextCacheSplit[0]);
            if(sequence > seq){
                return contextCacheSplit[1];
            } else if (sequence < seq) {
                return INVALID_SEQUENCE;
            }
            return null;
        } finally {
            RequestContext.get().endMetricRecord(metric);
        }

    }
    public static void remove(String key) {
        AtlasPerfMetrics.MetricRecorder metric = RequestContext.get().startMetricRecord("removeFromCache");
        try {
            redisService.removeValue(key);
        } finally {
            RequestContext.get().endMetricRecord(metric);
        }

    }
}

