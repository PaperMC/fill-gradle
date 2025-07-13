package io.papermc.fill.model.response.v3;

import org.jspecify.annotations.NullMarked;
import java.util.List;

@NullMarked
public record VersionsResponse(List<VersionResponse> versions) {
}
