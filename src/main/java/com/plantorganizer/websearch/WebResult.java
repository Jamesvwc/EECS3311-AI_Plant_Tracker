package com.plantorganizer.websearch;

/** One web search hit: a page title, a short text extract and where it came from. */
public record WebResult(String title, String extract, String url) {
}
