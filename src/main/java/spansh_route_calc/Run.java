package spansh_route_calc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;

public class Run {

    static final Path CONFIG_FILE = Path.of("spansh_last.properties");

    static final String API_URL = "https://www.spansh.co.uk/api/route";
    static final String RESULTS_URL = "https://www.spansh.co.uk/api/results/";
    static final String PLOTTER_URL = "https://www.spansh.co.uk/plotter/results/";

    static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static final Object bestLock = new Object();
    static int bestJumps = Integer.MAX_VALUE;
    static Result bestResult = null;

    static boolean OVERCHARGE = false;
    static int SUPERCHARGE;

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String encodeUrl(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @SneakyThrows
    static void main(String[] args) {
        if (System.console() == null && !Arrays.asList(args).contains("--console")) {
            String jarPath = new java.io.File(Run.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
            new ProcessBuilder("cmd.exe", "/c", "start", "cmd.exe", "/k", "java -jar \"" + jarPath + "\" --console").start();
            return;
        }

        Properties config = new Properties();

        if (Files.exists(CONFIG_FILE)) {
            try (var reader = Files.newBufferedReader(CONFIG_FILE)) {
                config.load(reader);
            }
        }

        Scanner scanner = new Scanner(System.in);

        String from = prompt(scanner, "From system", config.getProperty("from", ""));
        String to = prompt(scanner, "To system", config.getProperty("to", ""));
        String range = prompt(scanner, "Jump range", config.getProperty("range", ""));
        String overcharge = prompt(scanner, "Overcharge (true/false)", config.getProperty("overcharge", "false"));

        double rangeParsed;
        while (true) {
            try {
                rangeParsed = Double.parseDouble(range);
                break;
            } catch (NumberFormatException e) {
                System.out.print("Invalid range, enter a number: ");
                range = scanner.nextLine().trim();
            }
        }

        OVERCHARGE = Boolean.parseBoolean(overcharge);
        SUPERCHARGE = OVERCHARGE ? 6 : 4;

        config.setProperty("from", from);
        config.setProperty("to", to);
        config.setProperty("range", range);
        config.setProperty("overcharge", Boolean.toString(OVERCHARGE));

        try (var writer = Files.newBufferedWriter(CONFIG_FILE)) {
            config.store(writer, null);
        }

        String fromForm = encode(from);
        String toForm = encode(to);
        String fromUrl = encodeUrl(from);
        String toUrl = encodeUrl(to);

        System.out.println("\nRunning optimised efficiency search...");
        System.out.println("From:  " + from);
        System.out.println("To:    " + to);
        System.out.println("Range: " + rangeParsed + " ly | Supercharge: " + SUPERCHARGE + "x");

        long start = System.currentTimeMillis();

        int low = 1;
        int high = 99;

        while (high - low > 6) {

            int a = low;
            int b = low + (high - low) / 4;
            int c = low + 2 * (high - low) / 4;
            int d = low + 3 * (high - low) / 4;
            int e = high;

            int[] candidates = {a, b, c, d, e};

            System.out.println("\nSearching efficiency range [" + low + " - " + high + "]");

            int[] results = evaluateAll(candidates, rangeParsed, fromForm, toForm, fromUrl, toUrl);

            int bestIdx = 0;
            for (int i = 1; i < results.length; i++) {
                if (results[i] < results[bestIdx]) bestIdx = i;
            }
            int bestEff = candidates[bestIdx];

            if (bestEff <= b) {
                high = c;
            } else if (bestEff <= c) {
                low = b;
                high = d;
            } else {
                low = c;
            }
        }

        int[] finalRange = new int[high - low + 1];
        for (int i = 0; i < finalRange.length; i++) finalRange[i] = low + i;

        System.out.println("\nFinal sweep [" + low + " - " + high + "]");
        evaluateAll(finalRange, rangeParsed, fromForm, toForm, fromUrl, toUrl);

        long elapsed = (System.currentTimeMillis() - start) / 1000;

        System.out.println("\n========== DONE in " + elapsed + "s ==========");

        synchronized (bestLock) {
            if (bestResult != null) {
                System.out.println("Best efficiency: " + bestResult.efficiency + "%");
                System.out.println("Total jumps: " + bestResult.totalJumps);
                System.out.println("URL: " + bestResult.url);
            } else {
                System.out.println("No result found.");
            }
        }
    }

    @SneakyThrows
    static int[] evaluateAll(int[] efficiencies, double range, String fromForm, String toForm, String fromUrl, String toUrl) {

        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] futures = Arrays.stream(efficiencies).mapToObj(eff -> CompletableFuture.supplyAsync(() -> evaluateEff(eff, range, fromForm, toForm, fromUrl, toUrl))).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();

        int[] results = new int[efficiencies.length];
        for (int i = 0; i < futures.length; i++) {
            results[i] = futures[i].join();
        }
        return results;
    }

    static int evaluateEff(int eff, double range, String fromForm, String toForm, String fromUrl, String toUrl) {
        try {
            String payload = "efficiency=" + eff + "&range=" + range + "&from=" + fromForm + "&to=" + toForm + "&supercharge_multiplier=" + SUPERCHARGE;

            HttpRequest post = HttpRequest.newBuilder().uri(URI.create(API_URL)).header("Content-Type", "application/x-www-form-urlencoded").POST(BodyPublishers.ofString(payload)).timeout(Duration.ofSeconds(15)).build();

            HttpResponse<String> postResp = client.send(post, BodyHandlers.ofString());

            if (postResp.statusCode() != 200 && postResp.statusCode() != 202) {
                return Integer.MAX_VALUE;
            }

            JsonObject json = JsonParser.parseString(postResp.body()).getAsJsonObject();
            String id = json.get("job").getAsString();
            int totalJumps = pollForTotalJumps(id);

            if (totalJumps == Integer.MAX_VALUE) return totalJumps;

            String url = PLOTTER_URL + id + "?efficiency=" + eff + "&from=" + fromUrl + "&range=" + range + "&supercharge_multiplier=" + SUPERCHARGE + "&to=" + toUrl + "&via=%5B%5D";

            synchronized (bestLock) {
                if (totalJumps < bestJumps) {
                    bestJumps = totalJumps;
                    bestResult = new Result(eff, totalJumps, url);
                }
                System.out.printf("eff %3d -> %4d jumps (best %d @ %d%%)%n", eff, totalJumps, bestResult.totalJumps, bestResult.efficiency);
            }

            return totalJumps;

        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    static int pollForTotalJumps(String id) {
        String url = RESULTS_URL + id;
        long delay = 500;

        for (int i = 0; i < 60; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build();

                HttpResponse<String> res = client.send(req, BodyHandlers.ofString());
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();

                if ("ok".equalsIgnoreCase(json.get("status").getAsString())) {
                    JsonArray systems = json.getAsJsonObject("result").getAsJsonArray("system_jumps");

                    int total = 0;
                    for (var entry : systems) {
                        total += entry.getAsJsonObject().get("jumps").getAsInt();
                    }
                    return total;
                }

                Thread.sleep(delay);
                delay = Math.min(delay * 2, 5000);

            } catch (Exception ignored) {
            }
        }

        return Integer.MAX_VALUE;
    }

    static String prompt(Scanner sc, String label, String last) {
        if (last.isEmpty()) {
            System.out.print(label + ": ");
        } else {
            System.out.print(label + " [" + last + "]: ");
        }
        String in = sc.nextLine().trim();
        return in.isEmpty() ? last : in;
    }

    @Data
    @AllArgsConstructor
    static class Result {
        int efficiency;
        int totalJumps;
        String url;
    }
}