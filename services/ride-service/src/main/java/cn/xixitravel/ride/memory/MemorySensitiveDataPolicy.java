package cn.xixitravel.ride.memory;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class MemorySensitiveDataPolicy {
    private static final Pattern CHINESE_ID = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?!\\d)"
    );
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ORDER_OR_QUOTE = Pattern.compile(
            "(?i)(?:\\bXIXI-[A-Z0-9]{4,32}\\b|\\bQ-[A-Z0-9]{4,32}\\b)"
    );
    private static final Pattern API_SECRET = Pattern.compile(
            "(?i)(?:sk-[a-z0-9_-]{16,}|bearer\\s+[a-z0-9._-]{16,}|api[_ -]?key\\s*[:=])"
    );
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");

    public void validate(String value) {
        String normalized = value == null ? "" : value.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "密码", "口令", "验证码", "支付密码", "password", "passcode")) {
            reject("credential");
        }
        if (CHINESE_ID.matcher(normalized).find()) {
            reject("government identity number");
        }
        if (PHONE.matcher(normalized).find()) {
            reject("phone number");
        }
        if (ORDER_OR_QUOTE.matcher(normalized).find()) {
            reject("temporary order or quote identifier");
        }
        if (API_SECRET.matcher(normalized).find()) {
            reject("API credential");
        }
        var cardMatcher = CARD_CANDIDATE.matcher(normalized);
        while (cardMatcher.find()) {
            String digits = cardMatcher.group().replaceAll("\\D", "");
            if (passesLuhn(digits)) {
                reject("payment card number");
            }
        }
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private void reject(String category) {
        throw new IllegalArgumentException(
                "long-term memory rejected because it contains " + category
        );
    }
}
