package cn.xixitravel.ride.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KnowledgeQueryRequest(
        @NotBlank
        @Size(min = 2, max = 500)
        String query,

        @Min(1)
        @Max(5)
        Integer limit,

        @Pattern(regexp = "place_alias|vehicle|policy|safety|invoice")
        String category
) {
    public int resolvedLimit() {
        return limit == null ? 3 : limit;
    }

    public String resolvedCategory() {
        return category == null || category.isBlank() ? null : category;
    }
}
