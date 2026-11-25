package com.plantorganizer.websearch;

/**
 * One web search hit: a page title, a short text extract, where it came from,
 * and the page's main photo (null when it has none).
 */
public record WebResult(String title, String extract, String url, String imageUrl) {

    public WebResult(String title, String extract, String url) {
        this(title, extract, url, null);
    }
}
