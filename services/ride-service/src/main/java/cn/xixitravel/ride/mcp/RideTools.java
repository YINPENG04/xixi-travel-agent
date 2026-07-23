package cn.xixitravel.ride.mcp;

import cn.xixitravel.ride.api.CreateRideRequest;
import cn.xixitravel.ride.api.QuoteRequest;
import cn.xixitravel.ride.domain.RideOrder;
import cn.xixitravel.ride.domain.RideQuote;
import cn.xixitravel.ride.service.RideService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public class RideTools {
    private final RideService rideService;

    public RideTools(RideService rideService) {
        this.rideService = rideService;
    }

    @Tool(description = "查询嘻嘻出行的实时车型、接驾时间和预估价格。创建订单前必须先调用。")
    public List<RideQuote> rideQuote(
            @ToolParam(description = "出发地名称") String origin,
            @ToolParam(description = "目的地名称") String destination,
            @ToolParam(description = "路线里程，单位公里") double distanceKilometers,
            @ToolParam(description = "预计行驶时间，单位分钟") int durationMinutes
    ) {
        return rideService.quote(
                new QuoteRequest(origin, destination, distanceKilometers, durationMinutes)
        );
    }

    @Tool(description = "使用有效报价创建嘻嘻出行订单。必须取得用户确认后调用。")
    public RideOrder rideCreate(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "本次创建操作唯一的幂等键") String idempotencyKey,
            @ToolParam(description = "ride_quote 返回的报价 ID") String quoteId,
            @ToolParam(description = "出发地名称") String origin,
            @ToolParam(description = "目的地名称") String destination
    ) {
        return rideService.createRide(
                userId,
                idempotencyKey,
                new CreateRideRequest(quoteId, origin, destination)
        );
    }

    @Tool(description = "查询嘻嘻出行订单的当前状态。")
    public RideOrder rideStatus(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "订单 ID") String orderId
    ) {
        return rideService.getRide(userId, orderId);
    }

    @Tool(description = "取消尚未开始的嘻嘻出行订单。必须取得用户确认后调用。")
    public RideOrder rideCancel(
            @ToolParam(description = "用户 ID") String userId,
            @ToolParam(description = "订单 ID") String orderId
    ) {
        return rideService.cancel(userId, orderId);
    }
}
