package dev.veedo.llmwear.mobile;

import java.net.URI;

public final class WebSearchProbe {
    public static void main(String[] args) throws Exception {
        String proxy = System.getenv("HTTPS_PROXY");
        if (proxy == null) proxy = System.getenv("https_proxy");
        if (proxy != null && !proxy.isEmpty() && System.getProperty("https.proxyHost") == null) {
            URI address = new URI(proxy);
            if (address.getHost() == null || address.getRawUserInfo() != null) {
                throw new IllegalArgumentException("The JVM probe needs a proxy host without embedded credentials");
            }
            System.setProperty("https.proxyHost", address.getHost());
            System.setProperty("https.proxyPort", Integer.toString(address.getPort() < 0 ? 80 : address.getPort()));
        }
        String query = args.length == 0 ? "LiteRT-LM official Android documentation" : args[0];
        WebSearchResult result = McpWebSearchClient.searchExa(query);
        System.out.println(result.withSources("MCP search succeeded"));
        System.out.println(result.prompt(query));
    }
}
