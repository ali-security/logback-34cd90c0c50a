// Seal build helper: summarise surefire results and name every skipped test with its reason.
// Run from the repository root after `mvn install`:  java .github/seal/SkipReport.java
// Reads */target/surefire-reports/TEST-*.xml; for skips whose surefire message is generic
// (bare @Disabled / unnamed assumption) the reason is quoted from the test source.
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class SkipReport {
    static final Map<String, int[]> modules = new TreeMap<>(); // tests, failures, errors, skipped
    static final List<String> skipLines = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        List<Path> reports;
        try (Stream<Path> s = Files.walk(root)) {
            reports = s.filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml")
                            && p.getParent() != null
                            && p.getParent().getFileName().toString().equals("surefire-reports"))
                    .sorted().collect(Collectors.toList());
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        for (Path r : reports) {
            Path moduleDir = r.getParent().getParent().getParent();
            String module = root.relativize(moduleDir).toString().replace('\\', '/');
            Document d = f.newDocumentBuilder().parse(r.toFile());
            Element suite = d.getDocumentElement();
            int[] c = modules.computeIfAbsent(module, k -> new int[4]);
            c[0] += intAttr(suite, "tests");
            c[1] += intAttr(suite, "failures");
            c[2] += intAttr(suite, "errors");
            c[3] += intAttr(suite, "skipped");
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                NodeList sk = tc.getElementsByTagName("skipped");
                if (sk.getLength() == 0) continue;
                String cls = tc.getAttribute("classname");
                String method = tc.getAttribute("name").replaceAll("\\(.*$", "");
                String msg = ((Element) sk.item(0)).getAttribute("message");
                skipLines.add("SKIPPED [" + module + "] " + cls + "#" + method + " -- "
                        + reason(moduleDir, cls, method, msg));
            }
        }
        System.out.println("==================== SEAL TEST SUMMARY (surefire XML) ====================");
        int[] tot = new int[4];
        for (Map.Entry<String, int[]> e : modules.entrySet()) {
            int[] c = e.getValue();
            for (int i = 0; i < 4; i++) tot[i] += c[i];
            System.out.printf("MODULE %-26s tests=%d passed=%d failures=%d errors=%d skipped=%d%n",
                    e.getKey(), c[0], c[0] - c[1] - c[2] - c[3], c[1], c[2], c[3]);
        }
        System.out.println("-------------------- skipped tests (one line each, with reason) --------------------");
        for (String l : skipLines) System.out.println(l);
        System.out.printf("TOTAL tests=%d passed=%d failures=%d errors=%d skipped=%d (report files=%d)%n",
                tot[0], tot[0] - tot[1] - tot[2] - tot[3], tot[1], tot[2], tot[3], reports.size());
        System.out.println("Note: logback-classic-blackbox runs some classes in two surefire executions, "
                + "so a class may be listed twice.");
    }

    static int intAttr(Element e, String a) {
        String v = e.getAttribute(a);
        return v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    static boolean generic(String msg) {
        return msg == null || msg.isBlank() || msg.endsWith("is @Disabled")
                || msg.startsWith("Assumption failed: assumption is not true")
                || msg.equals("got: <false>, expected: is <true>");
    }

    static String reason(Path moduleDir, String cls, String method, String msg) {
        if (!generic(msg)) return msg.replaceAll("\\s+", " ").trim();
        String rel = cls.replaceAll("\\$.*$", "").replace('.', '/') + ".java";
        Path src = moduleDir.resolve("src/test/java").resolve(rel);
        if (!Files.isRegularFile(src)) return "surefire: " + (msg == null || msg.isBlank() ? "skipped" : msg);
        List<String> lines;
        try {
            lines = Files.readAllLines(src);
        } catch (IOException e) {
            return "surefire: " + msg;
        }
        int decl = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches(".*\\b(void|public|protected)\\b.*\\b" + java.util.regex.Pattern.quote(method) + "\\s*\\(.*")) {
                decl = i;
                break;
            }
        }
        String file = rel.substring(rel.lastIndexOf('/') + 1);
        if (decl >= 0) {
            // annotations and comments directly above the method
            for (int i = decl - 1; i >= 0 && i >= decl - 12; i--) {
                String l = lines.get(i).trim();
                if (l.startsWith("@Disabled")) {
                    String comment = commentAbove(lines, i);
                    return "upstream " + l + " at " + file + ":" + (i + 1)
                            + (!comment.isEmpty() ? " -- source comment: " + comment
                               : l.contains("//") ? "" : " (no reason text in source)");
                }
                if (l.equals("}") || l.endsWith(";")) break;
            }
            // assumption inside the method body
            for (int i = decl; i < lines.size() && i < decl + 40; i++) {
                String l = lines.get(i).trim();
                if (l.matches(".*\\bassume(True|False|That)?\\s*\\(.*")) {
                    return "assumption not met on this JVM/OS: `" + l + "` at " + file + ":" + (i + 1);
                }
                if (i > decl && lines.get(i).matches("^    }\\s*$")) break;
            }
        }
        // class-level @Disabled
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.startsWith("@Disabled") && i + 1 < lines.size()
                    && lines.get(i + 1).matches(".*\\bclass\\b.*")) {
                return "upstream class-level " + l + " at " + file + ":" + (i + 1);
            }
        }
        return "surefire: " + (msg == null || msg.isBlank() ? "skipped" : msg);
    }

    static String commentAbove(List<String> lines, int idx) {
        StringBuilder sb = new StringBuilder();
        for (int i = idx - 1; i >= 0 && i >= idx - 6; i--) {
            String l = lines.get(i).trim();
            if (l.startsWith("//")) sb.insert(0, l.substring(2).trim() + " ");
            else if (l.startsWith("@")) continue;
            else break;
        }
        return sb.toString().trim();
    }
}
