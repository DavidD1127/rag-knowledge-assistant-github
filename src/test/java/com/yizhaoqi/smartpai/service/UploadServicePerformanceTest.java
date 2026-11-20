package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证批量读取位图的调用次数，避免用模拟耗时冒充性能测量。 */
class UploadServicePerformanceTest {
    @Test
    @SuppressWarnings("unchecked")
    void readsBitmapOnceAndDecodesRedisBitOrder() {
        UploadService service = spy(new UploadService());
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        doReturn(10).when(service).getTotalChunks("file", "42");
        when(redis.execute(any(RedisCallback.class))).thenReturn(new byte[]{(byte) 0x81, (byte) 0x40});
        assertEquals(List.of(0, 7, 9), service.getUploadedChunks("file", "42"));
        verify(redis, times(1)).execute(any(RedisCallback.class));
    }
}
