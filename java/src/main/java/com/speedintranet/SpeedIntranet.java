package com.speedintranet;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SpeedIntranet {

    private static final String VERSION = "1.05";
    private static final int DEFAULT_PORT = 5201;
    private static final int BUFFER_SIZE = 65_536;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private static final int SMALL_FILE_SIZE = 1 * 1024;
    private static final int SMALL_FILE_COUNT = 200;
    private static final int MEDIUM_FILE_SIZE = 20 * 1_048_576;
    private static final int LARGE_FILE_SIZE = 300 * 1_048_576;

    private static final String TEST_SMALL = "small";
    private static final String TEST_MEDIUM = "medium";
    private static final String TEST_LARGE = "large";

    private static final byte[] CMD_PING = ascii("PING");
    private static final byte[] CMD_PONG = ascii("PONG");
    private static final byte[] CMD_UPLOAD = ascii("UPLD");
    private static final byte[] CMD_DOWNLOAD = ascii("DNLD");
    private static final byte[] CMD_FILE = ascii("FILE");
    private static final byte[] CMD_DONE = ascii("DONE");
    private static final byte[] CMD_RESULT = ascii("RSLT");
    private static final byte[] CMD_ERROR = ascii("ERRR");
    private static final byte[] CMD_BYE = ascii("BYE_");

    private SpeedIntranet() {
    }

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            if (options.version) {
                System.out.println("speed-intranet-java8 v" + VERSION);
                return;
            }
            if (options.help || options.mode == null) {
                printHelp();
                return;
            }

            if ("server".equals(options.mode)) {
                new Server(options.port, options.timeoutSeconds).run();
                return;
            }

            if ("client".equals(options.mode)) {
                runClientMode(options);
                return;
            }

            if ("auto".equals(options.mode)) {
                runAutoMode(options);
                return;
            }

            throw new IllegalArgumentException("Unknown mode: " + options.mode);
        } catch (Exception ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void runClientMode(CliOptions options) throws IOException {
        if (options.serverIp == null || options.serverIp.isEmpty()) {
            throw new IllegalArgumentException("--server is required in client mode.");
        }
        List<String> tests = parseTestTypes(options.tests);
        Client client = new Client(options.serverIp, options.port, options.timeoutSeconds);
        System.out.println("Target: " + options.serverIp + ":" + options.port);
        System.out.println("Tests: " + join(tests));
        System.out.println("Direction: " + options.direction);
        System.out.println("Repeat: " + options.repeat);
        System.out.println("Timeout: " + options.timeoutSeconds + " s");

        double ping = client.measurePing();
        if (ping >= 0) {
            System.out.println("TCP latency: " + fmt1(ping) + " ms");
        } else {
            System.out.println("TCP latency: unavailable");
        }

        List<TestResult> results = runTests(client, tests, options.direction, options.repeat);
        printSummary(results, options.serverIp);
        if (options.outputFile != null && !options.outputFile.isEmpty()) {
            writeResults(options.outputFile, results, options.serverIp, "client", tests, options.direction, options.repeat, options.timeoutSeconds);
            System.out.println("Results exported to: " + options.outputFile);
        }
    }

    private static void runAutoMode(CliOptions options) throws IOException {
        IniConfig ini = IniConfig.load(options.configFile);
        int port = ini.getInt("network", "port", options.port);
        int timeout = ini.getInt("network", "timeout", options.timeoutSeconds);
        String terminalsRaw = ini.get("network", "terminals", "");
        String testsSpec = ini.get("tests", "test_types", options.tests);
        String direction = ini.get("tests", "direction", options.direction);
        int repeat = ini.getInt("tests", "repeat", options.repeat);

        if (repeat <= 0) {
            throw new IllegalArgumentException("repeat must be > 0");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be > 0");
        }
        if (!isDirectionValid(direction)) {
            throw new IllegalArgumentException("direction must be upload, download or both");
        }

        List<String> terminals = parseTerminals(terminalsRaw);
        if (terminals.isEmpty()) {
            throw new IllegalArgumentException("No terminal IP configured in [network] terminals");
        }

        List<String> tests = parseTestTypes(testsSpec);
        List<TestResult> all = new ArrayList<TestResult>();

        System.out.println("Config: " + options.configFile);
        System.out.println("Terminals: " + join(terminals));
        System.out.println("Port: " + port);
        System.out.println("Tests: " + join(tests));
        System.out.println("Direction: " + direction);
        System.out.println("Repeat: " + repeat);
        System.out.println("Timeout: " + timeout + " s");

        for (String ip : terminals) {
            separator();
            System.out.println("Testing terminal: " + ip);
            Client client = new Client(ip, port, timeout);
            double ping = client.measurePing();
            if (ping < 0) {
                System.out.println("[WARN] Cannot reach " + ip + ":" + port);
                continue;
            }
            System.out.println("TCP latency: " + fmt1(ping) + " ms");
            List<TestResult> results = runTests(client, tests, direction, repeat);
            printSummary(results, ip);
            all.addAll(results);
        }

        if (options.outputFile != null && !options.outputFile.isEmpty()) {
            writeResults(options.outputFile, all, "auto", "auto", tests, direction, repeat, timeout);
            System.out.println("Results exported to: " + options.outputFile);
        }
    }

    private static final class CliOptions {
        String mode;
        String serverIp;
        int port = DEFAULT_PORT;
        String configFile = "config.ini";
        String tests = "all";
        String direction = "both";
        int repeat = 1;
        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        String outputFile;
        boolean version;
        boolean help;

        static CliOptions parse(String[] args) {
            CliOptions o = new CliOptions();
            int i = 0;
            while (i < args.length) {
                String a = args[i];
                if ("--version".equals(a)) {
                    o.version = true;
                    i++;
                    continue;
                }
                if ("-h".equals(a) || "--help".equals(a)) {
                    o.help = true;
                    i++;
                    continue;
                }
                if (!a.startsWith("--") && o.mode == null) {
                    o.mode = a.toLowerCase(Locale.ROOT);
                    i++;
                    continue;
                }
                if ("--server".equals(a)) {
                    o.serverIp = requireValue(args, i);
                    i += 2;
                    continue;
                }
                if ("--port".equals(a)) {
                    o.port = Integer.parseInt(requireValue(args, i));
                    i += 2;
                    continue;
                }
                if ("--config".equals(a)) {
                    o.configFile = requireValue(args, i);
                    i += 2;
                    continue;
                }
                if ("--tests".equals(a)) {
                    o.tests = requireValue(args, i);
                    i += 2;
                    continue;
                }
                if ("--direction".equals(a)) {
                    o.direction = requireValue(args, i).toLowerCase(Locale.ROOT);
                    i += 2;
                    continue;
                }
                if ("--repeat".equals(a)) {
                    o.repeat = Integer.parseInt(requireValue(args, i));
                    i += 2;
                    continue;
                }
                if ("--timeout".equals(a)) {
                    o.timeoutSeconds = Integer.parseInt(requireValue(args, i));
                    i += 2;
                    continue;
                }
                if ("--output".equals(a)) {
                    o.outputFile = requireValue(args, i);
                    i += 2;
                    continue;
                }
                throw new IllegalArgumentException("Unknown argument: " + a);
            }
            if (o.repeat <= 0) {
                throw new IllegalArgumentException("--repeat must be > 0");
            }
            if (o.timeoutSeconds <= 0) {
                throw new IllegalArgumentException("--timeout must be > 0");
            }
            if (!isDirectionValid(o.direction)) {
                throw new IllegalArgumentException("--direction must be upload, download or both");
            }
            return o;
        }

        private static String requireValue(String[] args, int i) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + args[i]);
            }
            return args[i + 1];
        }
    }

    private static final class IniConfig {
        private final Map<String, Map<String, String>> sections = new HashMap<String, Map<String, String>>();

        static IniConfig load(String path) throws IOException {
            File f = new File(path);
            if (!f.exists()) {
                throw new IOException("Config file not found: " + path);
            }
            IniConfig cfg = new IniConfig();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String currentSection = "";
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) {
                        continue;
                    }
                    if (t.startsWith("[") && t.endsWith("]") && t.length() >= 3) {
                        currentSection = t.substring(1, t.length() - 1).trim().toLowerCase(Locale.ROOT);
                        if (!cfg.sections.containsKey(currentSection)) {
                            cfg.sections.put(currentSection, new HashMap<String, String>());
                        }
                        continue;
                    }
                    int eq = t.indexOf('=');
                    if (eq > 0 && !currentSection.isEmpty()) {
                        String key = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                        String value = t.substring(eq + 1).trim();
                        cfg.sections.get(currentSection).put(key, value);
                    }
                }
            } finally {
                br.close();
            }
            return cfg;
        }

        String get(String section, String key, String fallback) {
            Map<String, String> map = sections.get(section.toLowerCase(Locale.ROOT));
            if (map == null) {
                return fallback;
            }
            String v = map.get(key.toLowerCase(Locale.ROOT));
            return v == null ? fallback : v;
        }

        int getInt(String section, String key, int fallback) {
            String v = get(section, key, null);
            if (v == null || v.isEmpty()) {
                return fallback;
            }
            return Integer.parseInt(v);
        }
    }

    private static final class CommandFrame {
        final byte[] cmd;
        final byte[] payload;

        CommandFrame(byte[] cmd, byte[] payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    private static final class TestResult {
        final String target;
        final String direction;
        final String fileType;
        final int repeatIndex;
        final int fileCount;
        final long totalBytes;
        final double elapsedSeconds;
        final double throughputMbps;
        final double throughputMibS;
        final double pingMs;

        TestResult(String target, String direction, String fileType, int repeatIndex, int fileCount, long totalBytes, double elapsedSeconds, double pingMs) {
            this.target = target;
            this.direction = direction;
            this.fileType = fileType;
            this.repeatIndex = repeatIndex;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.elapsedSeconds = elapsedSeconds;
            this.pingMs = pingMs;
            if (elapsedSeconds <= 0) {
                this.throughputMbps = 0;
                this.throughputMibS = 0;
            } else {
                this.throughputMbps = (totalBytes * 8.0) / (elapsedSeconds * 1_000_000.0);
                this.throughputMibS = totalBytes / (elapsedSeconds * 1_048_576.0);
            }
        }
    }

    private static final class Server {
        private final int port;
        private final int timeoutSeconds;

        Server(int port, int timeoutSeconds) {
            this.port = port;
            this.timeoutSeconds = timeoutSeconds;
        }

        void run() throws IOException {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("[SERVER] Listening on 0.0.0.0:" + port + " (Ctrl+C to stop)");
            while (true) {
                final Socket socket = serverSocket.accept();
                socket.setSoTimeout(timeoutSeconds * 1000);
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(socket);
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
        }

        private void handleClient(Socket socket) {
            String who = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            System.out.println("[SERVER] Incoming connection from " + who);
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                while (true) {
                    CommandFrame frame = recvCmd(in);
                    if (equalsCmd(frame.cmd, CMD_BYE)) {
                        break;
                    }
                    if (equalsCmd(frame.cmd, CMD_PING)) {
                        sendCmd(out, CMD_PONG, new byte[0]);
                        continue;
                    }
                    if (equalsCmd(frame.cmd, CMD_UPLOAD)) {
                        handleUpload(in, out);
                        continue;
                    }
                    if (equalsCmd(frame.cmd, CMD_DOWNLOAD)) {
                        handleDownload(in, out, frame.payload);
                        continue;
                    }
                    sendCmd(out, CMD_ERROR, utf8("Unknown command"));
                }
            } catch (SocketTimeoutException ex) {
                System.out.println("[SERVER] Timeout with " + who);
            } catch (Exception ex) {
                System.out.println("[SERVER] Error with " + who + ": " + ex.getMessage());
            } finally {
                closeQuietly(socket);
                System.out.println("[SERVER] Disconnected " + who);
            }
        }

        private void handleUpload(DataInputStream in, DataOutputStream out) throws IOException {
            CommandFrame start = recvCmd(in);
            if (!equalsCmd(start.cmd, CMD_PING)) {
                return;
            }
            sendCmd(out, CMD_PONG, new byte[0]);

            while (true) {
                CommandFrame frame = recvCmd(in);
                if (equalsCmd(frame.cmd, CMD_DONE)) {
                    break;
                }
                if (equalsCmd(frame.cmd, CMD_FILE)) {
                    long size = bytesToLong(frame.payload);
                    recvFileData(in, size);
                }
            }

            CommandFrame end = recvCmd(in);
            if (equalsCmd(end.cmd, CMD_PING)) {
                sendCmd(out, CMD_PONG, new byte[0]);
            }
            sendCmd(out, CMD_RESULT, utf8("{}"));
        }

        private void handleDownload(DataInputStream in, DataOutputStream out, byte[] payload) throws IOException {
            String testType = new String(payload, StandardCharsets.UTF_8);

            CommandFrame start = recvCmd(in);
            if (!equalsCmd(start.cmd, CMD_PING)) {
                return;
            }
            sendCmd(out, CMD_PONG, new byte[0]);

            if (TEST_SMALL.equals(testType)) {
                for (int i = 0; i < SMALL_FILE_COUNT; i++) {
                    sendSyntheticFile(out, SMALL_FILE_SIZE);
                }
            } else if (TEST_MEDIUM.equals(testType)) {
                sendSyntheticFile(out, MEDIUM_FILE_SIZE);
            } else if (TEST_LARGE.equals(testType)) {
                sendSyntheticFile(out, LARGE_FILE_SIZE);
            }

            sendCmd(out, CMD_DONE, new byte[0]);

            CommandFrame end = recvCmd(in);
            if (equalsCmd(end.cmd, CMD_PING)) {
                sendCmd(out, CMD_PONG, new byte[0]);
            }
        }
    }

    private static final class Client {
        private final String serverIp;
        private final int port;
        private final int timeoutSeconds;

        Client(String serverIp, int port, int timeoutSeconds) {
            this.serverIp = serverIp;
            this.port = port;
            this.timeoutSeconds = timeoutSeconds;
        }

        double measurePing() {
            Socket socket = null;
            try {
                socket = new Socket(serverIp, port);
                socket.setSoTimeout(timeoutSeconds * 1000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                long t0 = System.nanoTime();
                sendCmd(out, CMD_PING, new byte[0]);
                CommandFrame frame = recvCmd(in);
                long t1 = System.nanoTime();
                sendCmd(out, CMD_BYE, new byte[0]);
                if (equalsCmd(frame.cmd, CMD_PONG)) {
                    return nanosToMillis(t1 - t0);
                }
                return -1;
            } catch (Exception ex) {
                return -1;
            } finally {
                closeQuietly(socket);
            }
        }

        TestResult runUpload(String testType, int repeatIndex) {
            Socket socket = null;
            try {
                socket = new Socket(serverIp, port);
                socket.setSoTimeout(timeoutSeconds * 1000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                sendCmd(out, CMD_UPLOAD, utf8(testType));
                double pingMs = tcpPing(in, out);
                long tStart = System.nanoTime();

                long totalBytes;
                int fileCount;
                if (TEST_SMALL.equals(testType)) {
                    for (int i = 0; i < SMALL_FILE_COUNT; i++) {
                        sendSyntheticFile(out, SMALL_FILE_SIZE);
                    }
                    totalBytes = SMALL_FILE_SIZE * (long) SMALL_FILE_COUNT;
                    fileCount = SMALL_FILE_COUNT;
                } else if (TEST_MEDIUM.equals(testType)) {
                    sendSyntheticFile(out, MEDIUM_FILE_SIZE);
                    totalBytes = MEDIUM_FILE_SIZE;
                    fileCount = 1;
                } else if (TEST_LARGE.equals(testType)) {
                    sendSyntheticFile(out, LARGE_FILE_SIZE);
                    totalBytes = LARGE_FILE_SIZE;
                    fileCount = 1;
                } else {
                    throw new IllegalArgumentException("Unsupported test type: " + testType);
                }

                sendCmd(out, CMD_DONE, new byte[0]);
                tcpPing(in, out);
                long tEnd = System.nanoTime();

                recvCmd(in);
                sendCmd(out, CMD_BYE, new byte[0]);

                return new TestResult(serverIp, "upload", testType, repeatIndex, fileCount, totalBytes, nanosToSeconds(tEnd - tStart), pingMs);
            } catch (Exception ex) {
                System.out.println("[CLIENT] upload error (" + testType + "): " + ex.getMessage());
                return null;
            } finally {
                closeQuietly(socket);
            }
        }

        TestResult runDownload(String testType, int repeatIndex) {
            Socket socket = null;
            try {
                socket = new Socket(serverIp, port);
                socket.setSoTimeout(timeoutSeconds * 1000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                sendCmd(out, CMD_DOWNLOAD, utf8(testType));
                double pingMs = tcpPing(in, out);
                long tStart = System.nanoTime();

                long totalBytes = 0;
                int fileCount = 0;
                while (true) {
                    CommandFrame frame = recvCmd(in);
                    if (equalsCmd(frame.cmd, CMD_DONE)) {
                        break;
                    }
                    if (equalsCmd(frame.cmd, CMD_FILE)) {
                        long size = bytesToLong(frame.payload);
                        recvFileData(in, size);
                        totalBytes += size;
                        fileCount++;
                    }
                }

                tcpPing(in, out);
                long tEnd = System.nanoTime();

                sendCmd(out, CMD_BYE, new byte[0]);

                return new TestResult(serverIp, "download", testType, repeatIndex, fileCount, totalBytes, nanosToSeconds(tEnd - tStart), pingMs);
            } catch (Exception ex) {
                System.out.println("[CLIENT] download error (" + testType + "): " + ex.getMessage());
                return null;
            } finally {
                closeQuietly(socket);
            }
        }

        private double tcpPing(DataInputStream in, DataOutputStream out) throws IOException {
            long t0 = System.nanoTime();
            sendCmd(out, CMD_PING, new byte[0]);
            CommandFrame frame = recvCmd(in);
            if (!equalsCmd(frame.cmd, CMD_PONG)) {
                throw new IOException("Expected PONG");
            }
            return nanosToMillis(System.nanoTime() - t0);
        }
    }

    private static List<TestResult> runTests(Client client, List<String> tests, String direction, int repeat) {
        List<TestResult> results = new ArrayList<TestResult>();
        for (int pass = 1; pass <= repeat; pass++) {
            if (repeat > 1) {
                System.out.println("--- Pass " + pass + "/" + repeat + " ---");
            }
            for (String test : tests) {
                if ("upload".equals(direction) || "both".equals(direction)) {
                    System.out.println("-> Upload   (" + test + ")");
                    TestResult r = client.runUpload(test, pass);
                    if (r != null) {
                        results.add(r);
                        System.out.println(formatResult(r));
                    }
                }
                if ("download".equals(direction) || "both".equals(direction)) {
                    System.out.println("<- Download (" + test + ")");
                    TestResult r = client.runDownload(test, pass);
                    if (r != null) {
                        results.add(r);
                        System.out.println(formatResult(r));
                    }
                }
            }
        }
        return results;
    }

    private static void printSummary(List<TestResult> results, String target) {
        if (results.isEmpty()) {
            System.out.println("No results.");
            return;
        }
        separator();
        System.out.println("SUMMARY - " + target);
        separator();
        for (TestResult r : results) {
            System.out.println(formatResult(r));
        }

        List<TestResult> uploads = new ArrayList<TestResult>();
        List<TestResult> downloads = new ArrayList<TestResult>();
        for (TestResult r : results) {
            if ("upload".equals(r.direction)) {
                uploads.add(r);
            }
            if ("download".equals(r.direction)) {
                downloads.add(r);
            }
        }
        if (!uploads.isEmpty()) {
            System.out.println("Average upload:   " + fmt1(avgMbps(uploads)) + " Mbps");
        }
        if (!downloads.isEmpty()) {
            System.out.println("Average download: " + fmt1(avgMbps(downloads)) + " Mbps");
        }

        boolean repeated = false;
        for (TestResult r : results) {
            if (r.repeatIndex > 1) {
                repeated = true;
                break;
            }
        }
        if (repeated) {
            separator();
            System.out.println("Aggregates by direction/test (min / avg / max Mbps)");
            Map<String, List<Double>> map = new HashMap<String, List<Double>>();
            for (TestResult r : results) {
                String key = r.direction + "|" + r.fileType;
                List<Double> values = map.get(key);
                if (values == null) {
                    values = new ArrayList<Double>();
                    map.put(key, values);
                }
                values.add(r.throughputMbps);
            }
            List<String> keys = new ArrayList<String>(map.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                List<Double> values = map.get(key);
                double min = Collections.min(values);
                double max = Collections.max(values);
                double avg = 0;
                for (Double v : values) {
                    avg += v.doubleValue();
                }
                avg = avg / values.size();
                String[] parts = key.split("\\|");
                System.out.println(parts[0] + " " + parts[1] + ": " + fmt1(min) + " / " + fmt1(avg) + " / " + fmt1(max));
            }
        }

        separator();
    }

    private static void writeResults(String path,
                                     List<TestResult> results,
                                     String target,
                                     String mode,
                                     List<String> tests,
                                     String direction,
                                     int repeat,
                                     int timeout) throws IOException {
        if (results == null || results.isEmpty()) {
            return;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            writeCsv(path, results);
        } else if (lower.endsWith(".json")) {
            writeJson(path, results, target, mode, tests, direction, repeat, timeout);
        } else {
            throw new IllegalArgumentException("Unsupported output format. Use .json or .csv");
        }
    }

    private static void writeCsv(String path, List<TestResult> results) throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter(path));
        try {
            bw.write("target,direction,file_type,repeat_index,file_count,total_bytes,elapsed_seconds,throughput_mbps,throughput_mib_s,ping_ms");
            bw.newLine();
            for (TestResult r : results) {
                bw.write(csv(r.target)); bw.write(',');
                bw.write(csv(r.direction)); bw.write(',');
                bw.write(csv(r.fileType)); bw.write(',');
                bw.write(Integer.toString(r.repeatIndex)); bw.write(',');
                bw.write(Integer.toString(r.fileCount)); bw.write(',');
                bw.write(Long.toString(r.totalBytes)); bw.write(',');
                bw.write(fmt3(r.elapsedSeconds)); bw.write(',');
                bw.write(fmt3(r.throughputMbps)); bw.write(',');
                bw.write(fmt3(r.throughputMibS)); bw.write(',');
                bw.write(fmt3(r.pingMs));
                bw.newLine();
            }
        } finally {
            bw.close();
        }
    }

    private static void writeJson(String path,
                                  List<TestResult> results,
                                  String target,
                                  String mode,
                                  List<String> tests,
                                  String direction,
                                  int repeat,
                                  int timeout) throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter(path));
        try {
            bw.write("{\n");
            bw.write("  \"metadata\": {\n");
            bw.write("    \"version\": \"" + jsonEscape(VERSION) + "\",\n");
            bw.write("    \"mode\": \"" + jsonEscape(mode) + "\",\n");
            bw.write("    \"target\": \"" + jsonEscape(target) + "\",\n");
            bw.write("    \"tests\": \"" + jsonEscape(join(tests)) + "\",\n");
            bw.write("    \"direction\": \"" + jsonEscape(direction) + "\",\n");
            bw.write("    \"repeat\": " + repeat + ",\n");
            bw.write("    \"timeout_seconds\": " + timeout + "\n");
            bw.write("  },\n");
            bw.write("  \"results\": [\n");
            for (int i = 0; i < results.size(); i++) {
                TestResult r = results.get(i);
                bw.write("    {\n");
                bw.write("      \"target\": \"" + jsonEscape(r.target) + "\",\n");
                bw.write("      \"direction\": \"" + jsonEscape(r.direction) + "\",\n");
                bw.write("      \"file_type\": \"" + jsonEscape(r.fileType) + "\",\n");
                bw.write("      \"repeat_index\": " + r.repeatIndex + ",\n");
                bw.write("      \"file_count\": " + r.fileCount + ",\n");
                bw.write("      \"total_bytes\": " + r.totalBytes + ",\n");
                bw.write("      \"elapsed_seconds\": " + fmt3(r.elapsedSeconds) + ",\n");
                bw.write("      \"throughput_mbps\": " + fmt3(r.throughputMbps) + ",\n");
                bw.write("      \"throughput_mib_s\": " + fmt3(r.throughputMibS) + ",\n");
                bw.write("      \"ping_ms\": " + fmt3(r.pingMs) + "\n");
                bw.write("    }");
                if (i < results.size() - 1) {
                    bw.write(",");
                }
                bw.write("\n");
            }
            bw.write("  ]\n");
            bw.write("}\n");
        } finally {
            bw.close();
        }
    }

    private static void sendCmd(DataOutputStream out, byte[] cmd, byte[] payload) throws IOException {
        byte[] p = payload == null ? new byte[0] : payload;
        out.write(cmd);
        out.writeLong(p.length);
        if (p.length > 0) {
            out.write(p);
        }
        out.flush();
    }

    private static CommandFrame recvCmd(DataInputStream in) throws IOException {
        byte[] cmd = new byte[4];
        in.readFully(cmd);
        long len = in.readLong();
        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new IOException("Invalid payload length: " + len);
        }
        byte[] payload = new byte[(int) len];
        if (len > 0) {
            in.readFully(payload);
        }
        return new CommandFrame(cmd, payload);
    }

    private static void sendSyntheticFile(DataOutputStream out, int size) throws IOException {
        sendCmd(out, CMD_FILE, longToBytes(size));
        byte[] blank = new byte[BUFFER_SIZE];
        int remaining = size;
        while (remaining > 0) {
            int toSend = Math.min(BUFFER_SIZE, remaining);
            out.write(blank, 0, toSend);
            remaining -= toSend;
        }
        out.flush();
    }

    private static void recvFileData(DataInputStream in, long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IOException("Unsupported file size: " + size);
        }
        int remaining = (int) size;
        byte[] chunk = new byte[BUFFER_SIZE];
        while (remaining > 0) {
            int toRead = Math.min(BUFFER_SIZE, remaining);
            int read = in.read(chunk, 0, toRead);
            if (read < 0) {
                throw new IOException("Connection closed during file reception");
            }
            remaining -= read;
        }
    }

    private static byte[] longToBytes(long value) {
        byte[] b = new byte[8];
        b[0] = (byte) ((value >>> 56) & 0xFF);
        b[1] = (byte) ((value >>> 48) & 0xFF);
        b[2] = (byte) ((value >>> 40) & 0xFF);
        b[3] = (byte) ((value >>> 32) & 0xFF);
        b[4] = (byte) ((value >>> 24) & 0xFF);
        b[5] = (byte) ((value >>> 16) & 0xFF);
        b[6] = (byte) ((value >>> 8) & 0xFF);
        b[7] = (byte) (value & 0xFF);
        return b;
    }

    private static long bytesToLong(byte[] payload) {
        if (payload == null || payload.length != 8) {
            throw new IllegalArgumentException("FILE payload must contain 8-byte size");
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (((long) payload[i]) & 0xFFL);
        }
        return value;
    }

    private static List<String> parseTestTypes(String value) {
        if (value == null) {
            return Arrays.asList(TEST_SMALL, TEST_MEDIUM, TEST_LARGE);
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "all".equals(v)) {
            return Arrays.asList(TEST_SMALL, TEST_MEDIUM, TEST_LARGE);
        }
        List<String> out = new ArrayList<String>();
        String[] items = v.split(",");
        for (String item : items) {
            String t = item.trim();
            if (TEST_SMALL.equals(t) || TEST_MEDIUM.equals(t) || TEST_LARGE.equals(t)) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            return Arrays.asList(TEST_SMALL, TEST_MEDIUM, TEST_LARGE);
        }
        return out;
    }

    private static List<String> parseTerminals(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        String[] items = raw.split(",");
        for (String item : items) {
            String ip = item.trim();
            if (!ip.isEmpty()) {
                out.add(ip);
            }
        }
        return out;
    }

    private static String formatResult(TestResult r) {
        String arrow = "upload".equals(r.direction) ? "->" : "<-";
        return String.format(Locale.US,
            "%s [%s] %s : %d file(s), %s in %.3f s -> %.2f MiB/s (%.1f Mbps) [latency: %.1f ms]",
            arrow,
            padRight(r.direction.toUpperCase(Locale.ROOT), 8),
            padRight(r.fileType, 6),
            r.fileCount,
            humanSize(r.totalBytes),
            r.elapsedSeconds,
            r.throughputMibS,
            r.throughputMbps,
            r.pingMs
        );
    }

    private static double avgMbps(List<TestResult> list) {
        double sum = 0;
        for (TestResult r : list) {
            sum += r.throughputMbps;
        }
        return sum / list.size();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1_048_576L) {
            return fmt1(bytes / 1_048_576.0) + " MiB";
        }
        return fmt1(bytes / 1024.0) + " KiB";
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static boolean isDirectionValid(String direction) {
        return "upload".equals(direction) || "download".equals(direction) || "both".equals(direction);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean equalsCmd(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String fmt1(double v) {
        DecimalFormat df = new DecimalFormat("0.0");
        return df.format(v);
    }

    private static String fmt3(double v) {
        DecimalFormat df = new DecimalFormat("0.000");
        return df.format(v);
    }

    private static String csv(String s) {
        String value = s == null ? "" : s;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String jsonEscape(String s) {
        String value = s == null ? "" : s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int j = hex.length(); j < 4; j++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static void separator() {
        System.out.println("------------------------------------------------------------------------");
    }

    private static void printHelp() {
        System.out.println("speed-intranet-java8 v" + VERSION);
        System.out.println("Usage:");
        System.out.println("  server [--port 5201] [--timeout 10]");
        System.out.println("  client --server <ip> [--port 5201] [--tests all] [--direction both] [--repeat 1] [--timeout 10] [--output file]");
        System.out.println("  auto [--config config.ini] [--output file]");
        System.out.println("  --version");
    }
}




