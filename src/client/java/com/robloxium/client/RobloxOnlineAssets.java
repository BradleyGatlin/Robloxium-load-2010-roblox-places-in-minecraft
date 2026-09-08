private static CompletableFuture<Identifier> downloadTexture(String url){
    return CompletableFuture.supplyAsync(()->{
        try{
            HttpRequest req=HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent","Robloxium/3.0 (2010 compatibility renderer)")
                    .GET()
                    .build();

            HttpResponse<InputStream> response=
                    HTTP.send(req,HttpResponse.BodyHandlers.ofInputStream());

            if(response.statusCode()<200||response.statusCode()>=300)return null;

            NativeImage image;
            try(InputStream in=response.body()){
                image=NativeImage.read(in);
            }

            // Roblox legacy textures are vertically oriented differently
            // from Minecraft's texture coordinate convention.
            // I think so because they render wrong when its not flipped idk.
            image.flipY();

            DynamicTexture texture=new DynamicTexture(()->url,image);

            CompletableFuture<Identifier> out=new CompletableFuture<>();

            Minecraft.getInstance().execute(()->{
                try{
                    out.complete(registerTexture("robloxium_online",texture));
                }catch(Throwable t){
                    try{texture.close();}catch(Throwable ignored){}
                    out.complete(null);
                }
            });

            return out.join();

        }catch(Throwable ignored){
            return null;
        }
    },IO).thenCompose(v->
            v==null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.completedFuture(v)
    );
}