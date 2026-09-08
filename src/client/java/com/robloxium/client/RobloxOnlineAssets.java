package com.robloxium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client-side cache for legacy Roblox assets referenced by HTTP URLs. */
final class RobloxOnlineAssets {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService IO =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "Robloxium-AssetDownload");
                t.setDaemon(true);
                return t;
            });

    private static final Map<String, CompletableFuture<Identifier>> TEXTURES =
            new ConcurrentHashMap<>();

    private static final Map<String, CompletableFuture<RobloxPartRenderer.OnlineMesh>> MESHES =
            new ConcurrentHashMap<>();

    private RobloxOnlineAssets() {
    }

    static String resolveUrl(String id) {
        if (id == null) {
            return null;
        }

        String s = id.trim();

        if (s.isEmpty()) {
            return null;
        }

        if (s.regionMatches(true, 0, "http://", 0, 7)
                || s.regionMatches(true, 0, "https://", 0, 8)) {
            return s;
        }

        if (s.regionMatches(true, 0, "rbxassetid://", 0, 13)) {
            String n = s.substring(13).replaceAll("[^0-9]", "");

            if (!n.isEmpty()) {
                return "http://www.nostro.lol/asset/catalog/" + n + ".png";
            }
        }

        return null;
    }

    static Identifier texture(String id) {
        String url = resolveUrl(id);

        if (url == null) {
            return null;
        }

        CompletableFuture<Identifier> f =
                TEXTURES.computeIfAbsent(
                        url,
                        RobloxOnlineAssets::downloadTexture
                );

        return f.getNow(null);
    }

    private static Identifier registerTexture(
            String prefix,
            DynamicTexture texture
    ) {
        Identifier id = Identifier.fromNamespaceAndPath(
                "robloxium",
                prefix + "_" + Integer.toHexString(
                        System.identityHashCode(texture)
                )
        );

        /*
         * Upload the texture once.
         * The identifier is cached afterward, so this isn't repeated
         * every frame.
         */
        texture.upload();

        Minecraft.getInstance()
                .getTextureManager()
                .register(id, texture);

        return id;
    }

    /**
     * Flips a NativeImage vertically.
     *
     * NativeImage in this Minecraft version does not provide flipY(),
     * so perform the operation manually.
     */
    private static void flipImageVertically(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height / 2; y++) {
            int oppositeY = height - 1 - y;

            for (int x = 0; x < width; x++) {
                int top = image.getPixel(x, y);
                int bottom = image.getPixel(x, oppositeY);

                image.setPixel(x, y, bottom);
                image.setPixel(x, oppositeY, top);
            }
        }
    }

    private static CompletableFuture<Identifier> downloadTexture(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(
                        URI.create(url)
                )
                        .timeout(Duration.ofSeconds(15))
                        .header(
                                "User-Agent",
                                "Robloxium/3.0 (2010 compatibility renderer)"
                        )
                        .GET()
                        .build();

                HttpResponse<InputStream> response =
                        HTTP.send(
                                req,
                                HttpResponse.BodyHandlers.ofInputStream()
                        );

                if (response.statusCode() < 200
                        || response.statusCode() >= 300) {
                    return null;
                }

                NativeImage image;

                try (InputStream in = response.body()) {
                    image = NativeImage.read(in);
                }

                /*
                 * Legacy Roblox textures need to be vertically flipped
                 * for the way Robloxium maps them onto Minecraft geometry.
                 */
                flipImageVertically(image);

                DynamicTexture texture =
                        new DynamicTexture(() -> url, image);

                CompletableFuture<Identifier> out =
                        new CompletableFuture<>();

                Minecraft.getInstance().execute(() -> {
                    try {
                        out.complete(
                                registerTexture(
                                        "robloxium_online",
                                        texture
                                )
                        );
                    } catch (Throwable t) {
                        try {
                            texture.close();
                        } catch (Throwable ignored) {
                        }

                        out.complete(null);
                    }
                });

                return out.join();

            } catch (Throwable ignored) {
                return null;
            }
        }, IO).thenCompose(v ->
                v == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.completedFuture(v)
        );
    }

    static RobloxPartRenderer.OnlineMesh mesh(String id) {
        String url = resolveMeshUrl(id);

        if (url == null) {
            return null;
        }

        return MESHES
                .computeIfAbsent(
                        url,
                        RobloxOnlineAssets::downloadMesh
                )
                .getNow(null);
    }

    private static String resolveMeshUrl(String id) {
        if (id == null) {
            return null;
        }

        String s = id.trim();

        if (s.isEmpty()) {
            return null;
        }

        if (s.regionMatches(true, 0, "http://", 0, 7)
                || s.regionMatches(true, 0, "https://", 0, 8)) {
            return s;
        }

        if (s.regionMatches(true, 0, "rbxassetid://", 0, 13)) {
            String n = s.substring(13).replaceAll("[^0-9]", "");

            if (!n.isEmpty()) {
                return "http://www.nostro.lol/asset/catalog/" + n + ".obj";
            }
        }

        return null;
    }

    private static CompletableFuture<RobloxPartRenderer.OnlineMesh> downloadMesh(
            String url
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(
                        URI.create(url)
                )
                        .timeout(Duration.ofSeconds(20))
                        .header(
                                "User-Agent",
                                "Robloxium/3.0 (2010 compatibility renderer)"
                        )
                        .GET()
                        .build();

                HttpResponse<String> response =
                        HTTP.send(
                                req,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() < 200
                        || response.statusCode() >= 300) {
                    return null;
                }

                return RobloxPartRenderer.parseOnlineObj(
                        response.body()
                );

            } catch (Throwable ignored) {
                return null;
            }
        }, IO);
    }
}