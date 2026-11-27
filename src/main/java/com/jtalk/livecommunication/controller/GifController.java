package com.jtalk.livecommunication.controller;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Controller("/api/v1/voice-rooms/gifs")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@Slf4j
class GifController {

    private static final List<GifItem> CURATED_FALLBACK = List.of(
            new GifItem("clap", "https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExaXJpY2x6dGJ2c3p4NWp5Y3h6N3k5Z2t6eGJ0NWR5d2w1eW5lYyZlcD12MV9naWZzX3NlYXJjaCZjdD1n/l3q2XhfQ8oCkm1Ts4/200w.gif",
                    "https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExaXJpY2x6dGJ2c3p4NWp5Y3h6N3k5Z2t6eGJ0NWR5d2w1eW5lYyZlcD12MV9naWZzX3NlYXJjaCZjdD1n/l3q2XhfQ8oCkm1Ts4/giphy.gif", "Applause"),
            new GifItem("thumbs-up", "https://media.giphy.com/media/111ebonMs90YLu/200w.gif",
                    "https://media.giphy.com/media/111ebonMs90YLu/giphy.gif", "Thumbs up"),
            new GifItem("hello", "https://media.giphy.com/media/ASd0Ukj0y3qMM/200w.gif",
                    "https://media.giphy.com/media/ASd0Ukj0y3qMM/giphy.gif", "Hello wave"),
            new GifItem("celebrate", "https://media.giphy.com/media/g9582DNuQppxC/200w.gif",
                    "https://media.giphy.com/media/g9582DNuQppxC/giphy.gif", "Celebrate"),
            new GifItem("laugh", "https://media.giphy.com/media/10JhviFuU2gWD6/200w.gif",
                    "https://media.giphy.com/media/10JhviFuU2gWD6/giphy.gif", "Laughing"),
            new GifItem("mind-blown", "https://media.giphy.com/media/xT0xeJpnrWC4XWblEk/200w.gif",
                    "https://media.giphy.com/media/xT0xeJpnrWC4XWblEk/giphy.gif", "Mind blown"),
            new GifItem("thank-you", "https://media.giphy.com/media/3oEdva9BUHPIs2SkGk/200w.gif",
                    "https://media.giphy.com/media/3oEdva9BUHPIs2SkGk/giphy.gif", "Thank you"),
            new GifItem("agree", "https://media.giphy.com/media/RrVzUOXldFe8M/200w.gif",
                    "https://media.giphy.com/media/RrVzUOXldFe8M/giphy.gif", "Agree")
    );

    private static final String REDIS_KEY_PREFIX = "gif:search:";
    private static final long CACHE_TTL_SECONDS = 5 * 60;

    private final ObjectMapper mapper;
    private final String tenorApiKey;
    private final String giphyApiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final StatefulRedisConnection<String, String> redis;

    GifController(ObjectMapper mapper,
                  StatefulRedisConnection<String, String> redis,
                  @Value("${jtalk.gifs.tenor-api-key:}") String tenorApiKey,
                  @Value("${jtalk.gifs.giphy-api-key:}") String giphyApiKey) {
        this.mapper = mapper;
        this.redis = redis;
        this.tenorApiKey = tenorApiKey == null ? "" : tenorApiKey.trim();
        this.giphyApiKey = giphyApiKey == null ? "" : giphyApiKey.trim();
    }

    @Serdeable.Serializable
    record GifItem(String id, String previewUrl, String url, String title) {
    }

    @Get
    List<GifItem> search(@Nullable @QueryValue String q) {
        String query = q == null ? "" : q.trim();
        String cacheKey = REDIS_KEY_PREFIX + query;

        RedisCommands<String, String> sync = redis.sync();

        String cached = sync.get(cacheKey);
        if (cached != null) {
            try {
                GifItem[] items = mapper.readValue(cached, GifItem[].class);
                return Arrays.asList(items);
            } catch (Exception e) {
                log.warn("Failed to deserialize cached GIFs for query: {}", query, e);
            }
        }

        List<GifItem> results = performSearch(query);

        try {
            String serialized = mapper.writeValueAsString(results);
            sync.setex(cacheKey, CACHE_TTL_SECONDS, serialized);
        } catch (Exception e) {
            log.warn("Failed to cache GIF search results for query: {}", query, e);
        }

        return results;
    }

    private List<GifItem> performSearch(String query) {
        if (!tenorApiKey.isEmpty()) {
            List<GifItem> tenor = fetchTenor(query);
            if (!tenor.isEmpty()) {
                return tenor;
            }
        }
        if (!giphyApiKey.isEmpty()) {
            List<GifItem> giphy = fetchGiphy(query);
            if (!giphy.isEmpty()) {
                return giphy;
            }
        }
        if (query.isEmpty()) {
            return CURATED_FALLBACK;
        }
        String lower = query.toLowerCase();
        List<GifItem> filtered = CURATED_FALLBACK.stream()
                .filter(g -> g.title().toLowerCase().contains(lower))
                .toList();
        return filtered.isEmpty() ? CURATED_FALLBACK : filtered;
    }

    private List<GifItem> fetchTenor(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = String.format(
                    "https://api.tenor.com/v1/search?q=%s&key=%s&limit=8&media_filter=preview",
                    encoded, tenorApiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(response.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
            if (results == null) {
                return List.of();
            }

            List<GifItem> items = new ArrayList<>();
            for (Map<String, Object> result : results) {
                String id = (String) result.get("id");
                @SuppressWarnings("unchecked")
                Map<String, Object> media = (Map<String, Object>) result.get("media_formats");
                if (media != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> preview = (Map<String, String>) media.get("preview");
                    @SuppressWarnings("unchecked")
                    Map<String, String> gif = (Map<String, String>) media.get("gif");
                    if (preview != null && gif != null) {
                        String previewUrl = preview.get("url");
                        String gifUrl = gif.get("url");
                        String title = (String) result.getOrDefault("title", "");
                        if (previewUrl != null && gifUrl != null) {
                            items.add(new GifItem(id, previewUrl, gifUrl, title));
                        }
                    }
                }
            }
            return items;
        } catch (Exception e) {
            log.warn("Error fetching from Tenor: {}", e.getMessage());
            return List.of();
        }
    }

    private List<GifItem> fetchGiphy(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = String.format(
                    "https://api.giphy.com/v1/gifs/search?q=%s&api_key=%s&limit=8",
                    encoded, giphyApiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(response.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("data");
            if (results == null) {
                return List.of();
            }

            List<GifItem> items = new ArrayList<>();
            for (Map<String, Object> result : results) {
                String id = (String) result.get("id");
                String title = (String) result.get("title");
                @SuppressWarnings("unchecked")
                Map<String, Object> images = (Map<String, Object>) result.get("images");
                if (images != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> preview = (Map<String, String>) images.get("preview_gif");
                    @SuppressWarnings("unchecked")
                    Map<String, String> original = (Map<String, String>) images.get("original");
                    if (preview != null && original != null) {
                        String previewUrl = preview.get("url");
                        String originalUrl = original.get("url");
                        if (previewUrl != null && originalUrl != null) {
                            items.add(new GifItem(id, previewUrl, originalUrl, title));
                        }
                    }
                }
            }
            return items;
        } catch (Exception e) {
            log.warn("Error fetching from Giphy: {}", e.getMessage());
            return List.of();
        }
    }
}
