package cn.xixitravel.ride.intent;

import cn.xixitravel.ride.context.SessionPendingAction;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TravelIntentRecognizer {
    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "从(.{1,80}?)(?:到|去)(.{1,80}?)(?:打车|叫车|怎么走|多少钱|的车|[，,。！？!?]|$)"
    );
    private static final Pattern ORDER_PATTERN = Pattern.compile(
            "(?i)\\bXIXI-[A-Z0-9]{4,32}\\b"
    );

    public IntentRecognitionResult recognize(
            String utterance,
            SessionPendingAction pendingAction,
            String suppliedOrigin,
            String suppliedDestination,
            String suppliedOrderId
    ) {
        String normalized = required(utterance, "utterance", 500);
        SessionPendingAction normalizedPending = pendingAction == null
                ? SessionPendingAction.NONE
                : pendingAction;

        IntentRecognitionResult confirmation = recognizeConfirmation(normalized, normalizedPending);
        if (confirmation != null) {
            return confirmation;
        }

        Map<TravelIntent, Integer> scores = new EnumMap<>(TravelIntent.class);
        score(scores, normalized, TravelIntent.FORGET_PREFERENCE, 4,
                "忘记", "别记", "删除偏好", "清除偏好", "forget");
        score(scores, normalized, TravelIntent.REMEMBER_PREFERENCE, 4,
                "记住", "以后都", "保存偏好", "记下", "remember");
        score(scores, normalized, TravelIntent.RIDE_CANCEL, 4,
                "取消订单", "取消行程", "不要这辆车", "退单", "cancel ride");
        score(scores, normalized, TravelIntent.QUERY_INVOICE, 4,
                "发票", "开票", "报销凭证", "invoice");
        score(scores, normalized, TravelIntent.QUERY_NOTIFICATIONS, 3,
                "通知", "消息中心", "派单消息", "notifications");
        score(scores, normalized, TravelIntent.RIDE_STATUS, 3,
                "订单状态", "司机到哪", "车到哪", "行程状态", "查订单", "ride status");
        score(scores, normalized, TravelIntent.RIDE_CREATE, 3,
                "叫车", "打车", "下单", "预订一辆", "帮我叫", "book ride");
        score(scores, normalized, TravelIntent.RIDE_QUOTE, 2,
                "询价", "多少钱", "价格", "费用", "车型报价", "quote");
        score(scores, normalized, TravelIntent.LIST_MEMORY, 3,
                "记住了什么", "我的偏好列表", "所有偏好", "memory list");
        score(scores, normalized, TravelIntent.SEARCH_MEMORY, 2,
                "我的偏好", "按我的习惯", "我平时", "我的常用", "memory search");
        score(scores, normalized, TravelIntent.SEARCH_KNOWLEDGE, 1,
                "怎么", "是否", "能不能", "规则", "政策", "安全", "车型区别", "为什么", "what", "how");

        List<Map.Entry<TravelIntent, Integer>> ranked = scores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<TravelIntent, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().ordinal()))
                .toList();
        if (ranked.isEmpty()) {
            return result(
                    TravelIntent.UNKNOWN,
                    0.0,
                    false,
                    List.of(),
                    slots(normalized, suppliedOrigin, suppliedDestination, suppliedOrderId),
                    List.of(),
                    null,
                    "没有命中可确定的出行业务意图，需要追问用户"
            );
        }

        int topScore = ranked.getFirst().getValue();
        List<TravelIntent> alternatives = ranked.stream()
                .filter(entry -> entry.getValue() == topScore)
                .map(Map.Entry::getKey)
                .toList();
        TravelIntent intent = alternatives.getFirst();
        boolean ambiguous = alternatives.size() > 1;
        Map<String, String> extracted = slots(
                normalized,
                suppliedOrigin,
                suppliedDestination,
                suppliedOrderId
        );
        return result(
                intent,
                confidence(topScore, ambiguous),
                ambiguous,
                alternatives,
                extracted,
                missingSlots(intent, extracted),
                recommendedTool(intent),
                ambiguous
                        ? "多个意图得分相同，执行有副作用的操作前必须先消歧"
                        : "命中确定性领域关键词；该结果用于后端路由，不替代模型理解"
        );
    }

    private IntentRecognitionResult recognizeConfirmation(
            String utterance,
            SessionPendingAction pendingAction
    ) {
        if (pendingAction == SessionPendingAction.NONE) {
            return null;
        }
        if (containsAny(utterance, "确认", "同意", "可以下单", "就这样", "继续", "yes", "confirm")) {
            return result(
                    TravelIntent.CONFIRM_PENDING_ACTION,
                    0.99,
                    false,
                    List.of(TravelIntent.CONFIRM_PENDING_ACTION),
                    Map.of(),
                    List.of(),
                    null,
                    "当前任务存在待确认操作，且用户给出明确肯定表达"
            );
        }
        if (containsAny(utterance, "不确认", "不同意", "算了", "不要了", "停止", "no", "reject")) {
            return result(
                    TravelIntent.REJECT_PENDING_ACTION,
                    0.99,
                    false,
                    List.of(TravelIntent.REJECT_PENDING_ACTION),
                    Map.of(),
                    List.of(),
                    null,
                    "当前任务存在待确认操作，且用户给出明确否定表达"
            );
        }
        return null;
    }

    private Map<String, String> slots(
            String utterance,
            String suppliedOrigin,
            String suppliedDestination,
            String suppliedOrderId
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "origin", suppliedOrigin);
        putIfPresent(result, "destination", suppliedDestination);
        putIfPresent(result, "orderId", suppliedOrderId);

        if (!result.containsKey("origin") || !result.containsKey("destination")) {
            Matcher route = ROUTE_PATTERN.matcher(utterance);
            if (route.find()) {
                result.putIfAbsent("origin", route.group(1).trim());
                result.putIfAbsent("destination", route.group(2).trim());
            }
        }
        if (!result.containsKey("orderId")) {
            Matcher order = ORDER_PATTERN.matcher(utterance);
            if (order.find()) {
                result.put("orderId", order.group().toUpperCase(Locale.ROOT));
            }
        }
        return Map.copyOf(result);
    }

    private List<String> missingSlots(TravelIntent intent, Map<String, String> slots) {
        List<String> missing = new ArrayList<>();
        if (intent == TravelIntent.RIDE_QUOTE || intent == TravelIntent.RIDE_CREATE) {
            if (!slots.containsKey("origin")) {
                missing.add("origin");
            }
            if (!slots.containsKey("destination")) {
                missing.add("destination");
            }
        }
        if (intent == TravelIntent.RIDE_STATUS
                || intent == TravelIntent.RIDE_CANCEL
                || intent == TravelIntent.QUERY_NOTIFICATIONS
                || intent == TravelIntent.QUERY_INVOICE) {
            if (!slots.containsKey("orderId")) {
                missing.add("orderId");
            }
        }
        return List.copyOf(missing);
    }

    private String recommendedTool(TravelIntent intent) {
        return switch (intent) {
            case SEARCH_KNOWLEDGE -> "travelKnowledgeSearch";
            case RIDE_QUOTE, RIDE_CREATE -> "rideQuote";
            case RIDE_STATUS -> "rideStatus";
            case RIDE_CANCEL -> "ridePrepareCancel";
            case QUERY_NOTIFICATIONS -> "rideNotifications";
            case QUERY_INVOICE -> "rideInvoiceEligibility";
            case LIST_MEMORY -> "travelMemoryList";
            case SEARCH_MEMORY -> "travelMemorySearch";
            case REMEMBER_PREFERENCE -> "travelMemoryRemember";
            case FORGET_PREFERENCE -> "travelMemoryForget";
            case CONFIRM_PENDING_ACTION, REJECT_PENDING_ACTION, UNKNOWN -> null;
        };
    }

    private void score(
            Map<TravelIntent, Integer> scores,
            String utterance,
            TravelIntent intent,
            int weight,
            String... keywords
    ) {
        int matches = 0;
        for (String keyword : keywords) {
            if (utterance.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                matches++;
            }
        }
        if (matches > 0) {
            scores.merge(intent, weight + matches - 1, Integer::sum);
        }
    }

    private boolean containsAny(String text, String... candidates) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private double confidence(int score, boolean ambiguous) {
        double confidence = score >= 5 ? 0.95 : score >= 3 ? 0.85 : 0.70;
        return ambiguous ? Math.max(0.5, confidence - 0.25) : confidence;
    }

    private IntentRecognitionResult result(
            TravelIntent intent,
            double confidence,
            boolean ambiguous,
            List<TravelIntent> alternatives,
            Map<String, String> slots,
            List<String> missing,
            String tool,
            String reason
    ) {
        return new IntentRecognitionResult(
                intent,
                confidence,
                ambiguous,
                alternatives,
                slots,
                missing,
                tool,
                reason
        );
    }

    private void putIfPresent(Map<String, String> slots, String name, String value) {
        if (value != null && !value.isBlank()) {
            slots.put(name, value.trim());
        }
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
