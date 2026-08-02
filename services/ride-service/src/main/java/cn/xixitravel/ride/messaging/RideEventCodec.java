package cn.xixitravel.ride.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class RideEventCodec {
    private final ObjectMapper objectMapper;

    public RideEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(RideEventMessage event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化行程事件", exception);
        }
    }

    public RideEventMessage decode(String payload) {
        try {
            return objectMapper.readValue(payload, RideEventMessage.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法解析行程事件", exception);
        }
    }
}
