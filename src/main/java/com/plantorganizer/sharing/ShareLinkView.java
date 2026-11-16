package com.plantorganizer.sharing;

import java.time.Instant;
import java.util.List;

/** A share link as shown to its owner. */
public record ShareLinkView(String token, String path, String label, List<String> plantNames, Instant createdAt,
                            Instant expiresAt) {
}
